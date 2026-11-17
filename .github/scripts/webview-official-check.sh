#!/usr/bin/env bash
# Run webviewpatch's pure-Kotlin locator against the current *official*, Google-signed Android System
# WebView of the Canary, Dev and Beta channels, as an early warning that a new WebView may stop patching.
# No device, no Google account, no secrets.
#
# For each channel it resolves the published version from chromiumdash (the official version source), then
# fetches the Google-signed APK from the apkpure CDN *by HTTP range only*: the ZIP end-of-central-directory
# and central directory, the APK Signing Block (for signer/source-stamp verification), the deflated
# AndroidManifest.xml (for the versionName), and each stored-uncompressed lib/<abi>/libwebviewchromium.so.
# The rest of the ~250 MB APK is never transferred.
#
# apkpure is an untrusted third-party mirror; we trust only Google's signature. Verification (ranged, not a
# full apksigner run over the whole APK — that would defeat the ranged fetch) is:
#   * the APK signer certificate SHA-256 == the channel's pinned digest,
#   * the Play source-stamp certificate SHA-256 == the pinned digest,
#   * the resolved versionName appears (UTF-16LE) in the inflated AndroidManifest.xml.
# A build that fails any of these is treated as "skipped", never a failure.
#
# ABIs checked, per the operator decision: arm64-v8a and the armeabi-v7a half of the 64+32 APK, plus the
# armeabi-v7a-only APK where the channel publishes one.
#
# Skip vs. fail: a 403/404/timeout/not-hosted, or any verification mismatch, is a *skip* for that channel
# (loud ::warning:: annotation), not a failure. The job fails only when a verified official build is fetched
# and the locator does NOT find the X-Requested-With adder in it. If every channel is skipped, the job passes
# with a loud warning.
#
# Usage:
#   webview-official-check.sh                         all channels, both ABIs (+ arm32-only), fetch and run
#   webview-official-check.sh --channels "canary"     restrict channels
#   webview-official-check.sh --abis "arm64-v8a"      restrict ABIs taken from the 64+32 APK
#   webview-official-check.sh --no-arm32only          skip the armeabi-v7a-only APK
#   webview-official-check.sh --version-canary 160.0.8166.0   pin a channel's version (skip chromiumdash)
#   webview-official-check.sh --no-run                fetch, verify and lay out; do not run gradle
#   webview-official-check.sh --no-fetch              run gradle over an already-populated OUTDIR
#   webview-official-check.sh --local-apk-dir DIR     lay out pre-extracted libwebviewchromium.so files as
#                                                     channels (no fetch, no verify) and gate on them
#
# Env: CURL (default curl; a drop-in that writes the body to stdout, e.g. tachi's site-fetch, also works),
#      GRADLE (default ./gradlew), OUTDIR (default <repo>/.official-libs), PACE seconds between requests
#      (default 4), APKPURE_BASE, CHROMIUMDASH.
set -euo pipefail

CURL=${CURL:-curl}
GRADLE=${GRADLE:-./gradlew}
PACE=${PACE:-4}
APKPURE_BASE=${APKPURE_BASE:-https://d.apkpure.com/b/APK}
CHROMIUMDASH=${CHROMIUMDASH:-https://chromiumdash.appspot.com/fetch_releases}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUTDIR=${OUTDIR:-$REPO/.official-libs}
META="$OUTDIR/meta.tsv"
PY=${PYTHON:-python3}

# channel -> package and chromiumdash name
pkg_of() { case "$1" in canary) echo com.google.android.webview.canary;; dev) echo com.google.android.webview.dev;; beta) echo com.google.android.webview.beta;; *) return 1;; esac; }
dash_of() { case "$1" in canary) echo Canary;; dev) echo Dev;; beta) echo Beta;; *) return 1;; esac; }
# Pinned Google signer certificate SHA-256 per channel (apksigner --print-certs). Each channel has its own
# signing key; the Play source stamp is shared across all of them.
signer_pin() { case "$1" in
  canary) echo da2e1b4ab9926bc7b081d4e9e57e3c9ec77ee51f5f0f767ee84c131c574e20ee;;
  dev) echo a7e383ab8a6f4f0c6ad609503c17e40d9588d2fdcb45112f59f65273135f1d9b;;
  beta) echo 639fc7f1c168cb8a0df3f66f604f282967f696e91ed28612f8d8511a3748d2aa;;
  *) return 1;; esac; }
STAMP_PIN=3257d599a49d2c961a471ca9843f59d341a405884583fc087df4237b733bbd6d

channels="canary dev beta"
abis="arm64-v8a armeabi-v7a"
want_arm32only=1
run_gradle=1
do_fetch=1
local_apk_dir=""
declare -A version_override=()

while [ $# -gt 0 ]; do
  case "$1" in
    --channels) channels=$2; shift 2;;
    --abis) abis=$2; shift 2;;
    --no-arm32only) want_arm32only=0; shift;;
    --no-run) run_gradle=0; shift;;
    --no-fetch) do_fetch=0; shift;;
    --local-apk-dir) local_apk_dir=$2; do_fetch=0; shift 2;;
    --version-canary) version_override[canary]=$2; shift 2;;
    --version-dev) version_override[dev]=$2; shift 2;;
    --version-beta) version_override[beta]=$2; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

warn() { echo "::warning::$*"; echo "SKIP: $*" >&2; }
pace() { sleep "$PACE"; }

# Resolve the apkpure download for a versionCode to a direct winudf URL and the full size. Prints
# "<url>\t<total>" on success; non-zero when the code is not hosted (apkpure redirects to its /versions
# page) or the mirror refused us (403/empty). Reads only one byte (Range 0-0).
resolve() {
  local pkg=$1 code=$2 hdr url total
  hdr=$($CURL -sS -L --fail --retry 2 --retry-delay "$PACE" -r 0-0 -o /dev/null \
        -D - -w 'EFFECTIVE %{url_effective}\n' "$APKPURE_BASE/$pkg?versionCode=$code" 2>/dev/null) || return 1
  url=$(printf '%s\n' "$hdr" | awk '$1=="EFFECTIVE"{print $2}' | tail -1 | tr -d '\r')
  total=$(printf '%s\n' "$hdr" | awk 'tolower($1)=="content-range:"{print $NF}' | tail -1 | sed 's#.*/##' | tr -d '\r')
  case "$url" in *winudf.com*) : ;; *) return 1;; esac
  case "$total" in ''|*[!0-9]*) return 1;; esac
  printf '%s\t%s\n' "$url" "$total"
}

# Fetch the trailing <bytes> of a URL into a file (suffix Range). Writes the body to stdout, so a stdout-only
# CURL drop-in works too.
fetch_tail() { local url=$1 n=$2 out=$3; $CURL -sS -L --fail --retry 2 --retry-delay "$PACE" -r "-$n" "$url" > "$out"; }
# Fetch an absolute byte range [start,end] into a file.
fetch_range() { local url=$1 start=$2 end=$3 out=$4; $CURL -sS -L --fail --retry 2 --retry-delay "$PACE" -r "$start-$end" "$url" > "$out"; }

# Parse the metadata JSON of a resolved (url,total) into $WORK/meta-<code>.json. Returns non-zero on failure.
parse_meta() {
  local url=$1 total=$2 code=$3
  local tail="$WORK/tail-$code" json="$WORK/meta-$code.json" n=2000000 start
  [ "$total" -lt "$n" ] && n=$total
  start=$((total - n))
  fetch_tail "$url" "$n" "$tail" || return 1
  "$PY" -I "$HERE/apk_meta.py" "$tail" "$start" "$total" > "$json" || return 1
}

# jq-free one-liners over a parsed meta JSON (python, stdlib only).
meta_libs() { "$PY" -I -c 'import json,sys;print(" ".join(sorted(e["name"].split("/")[1] for e in json.load(open(sys.argv[1]))["entries"] if e["name"].endswith("libwebviewchromium.so"))))' "$1"; }
meta_cert() { "$PY" -I -c 'import json,sys;print(json.load(open(sys.argv[1]))["certs"][sys.argv[2]] or "")' "$1" "$2"; }
meta_entry() { "$PY" -I -c 'import json,sys
d=json.load(open(sys.argv[1]))
for e in d["entries"]:
    if e["name"]==sys.argv[2]:
        print(e["lho"],e["csize"],e["usize"],e["method"]); break' "$1" "$2"; }

# Return a fresh winudf URL for a versionCode (the download token ages), or non-zero.
fresh_url() { local r; r=$(resolve "$1" "$2") || return 1; printf '%s' "${r%%$'\t'*}"; }

# Extract one stored (uncompressed) entry by range into <out>, resumably. A single Range can truncate
# mid-stream on a slow link (or the token can expire on a long transfer), so append the still-missing tail
# range until <out> reaches <size>, re-resolving the URL whenever an attempt makes no progress.
extract_stored() {
  local pkg=$1 code=$2 lho=$3 size=$4 out=$5
  local url lh="$WORK/lh" nlen elen datastart have before start end attempt=0 stalls=0
  url=$(fresh_url "$pkg" "$code") || return 1
  fetch_range "$url" "$lho" "$((lho + 29))" "$lh" || return 1
  nlen=$("$PY" -I -c 'import struct,sys;print(struct.unpack_from("<H",open(sys.argv[1],"rb").read(),26)[0])' "$lh")
  elen=$("$PY" -I -c 'import struct,sys;print(struct.unpack_from("<H",open(sys.argv[1],"rb").read(),28)[0])' "$lh")
  datastart=$((lho + 30 + nlen + elen))
  : > "$out"
  while :; do
    have=$(stat -c%s "$out" 2>/dev/null || echo 0)
    [ "$have" -ge "$size" ] && break
    attempt=$((attempt + 1)); [ "$attempt" -gt 16 ] && return 1
    before=$have
    start=$((datastart + have)); end=$((datastart + size - 1))
    $CURL -sS -L --fail --retry 3 --retry-delay "$PACE" -r "$start-$end" "$url" >> "$out" || true
    have=$(stat -c%s "$out" 2>/dev/null || echo 0)
    if [ "$have" -le "$before" ]; then
      stalls=$((stalls + 1)); [ "$stalls" -gt 5 ] && return 1
      pace; url=$(fresh_url "$pkg" "$code") || return 1
    fi
    pace
  done
  [ "$(stat -c%s "$out")" -eq "$size" ]
}

# The resolved versionName must appear (UTF-16LE) in the inflated AndroidManifest.xml of this APK.
verify_version() {
  local url=$1 json=$2 version=$3 lho csize _usize _method lh nlen elen datastart mf
  read -r lho csize _usize _method < <(meta_entry "$json" AndroidManifest.xml)
  [ -n "$lho" ] || return 1
  lh="$WORK/mf.lh"; mf="$WORK/mf.deflate"
  fetch_range "$url" "$lho" "$((lho + 29))" "$lh" || return 1
  nlen=$("$PY" -I -c 'import struct,sys;print(struct.unpack_from("<H",open(sys.argv[1],"rb").read(),26)[0])' "$lh")
  elen=$("$PY" -I -c 'import struct,sys;print(struct.unpack_from("<H",open(sys.argv[1],"rb").read(),28)[0])' "$lh")
  datastart=$((lho + 30 + nlen + elen))
  pace
  fetch_range "$url" "$datastart" "$((datastart + csize - 1))" "$mf" || return 1
  "$PY" -I -c 'import zlib,sys
raw=zlib.decompress(open(sys.argv[1],"rb").read(),-15)
sys.exit(0 if sys.argv[2].encode("utf-16-le") in raw else 1)' "$mf" "$version"
}

# Append a row the Kotlin test reads: label, channel, version, versionCode, abi, variant.
add_lib() {
  local label=$1 channel=$2 version=$3 code=$4 abi=$5 variant=$6
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$channel" "$version" "$code" "$abi" "$variant" >> "$META"
}

resolve_version() {
  local channel=$1 name body v
  [ -n "${version_override[$channel]:-}" ] && { echo "${version_override[$channel]}"; return 0; }
  name=$(dash_of "$channel")
  body=$($CURL -sS -L --fail --retry 2 --retry-delay "$PACE" "$CHROMIUMDASH?platform=Webview&channel=$name&num=1" 2>/dev/null) || return 1
  v=$(printf '%s' "$body" | "$PY" -I -c 'import json,sys;print(json.load(sys.stdin)[0]["version"])' 2>/dev/null) || return 1
  case "$v" in [0-9]*.[0-9]*.[0-9]*.[0-9]*) echo "$v";; *) return 1;; esac
}

# Fetch, verify and lay out one channel. Never exits the script; a failure is a warn()ed skip.
do_channel() {
  local channel=$1 pkg version build patch base
  pkg=$(pkg_of "$channel")
  version=$(resolve_version "$channel") || { warn "$channel: could not resolve the version (chromiumdash)"; return 0; }
  build=$(echo "$version" | cut -d. -f3); patch=$(echo "$version" | cut -d. -f4)
  base=$((build * 100000 + patch * 100))
  echo "== $channel $version (build $build, patch $patch, base versionCode $base) =="

  # Probe candidate arch suffixes and classify each by the ABIs its APK actually contains (the suffix->ABI
  # map differs by channel, so never trust the digit — read the central directory). 00 arm32-only, 01/03
  # the 64+32 universal, 06 x86 (skipped).
  local suf code libs universal_code="" arm32_code=""
  for suf in 03 01 00; do
    code=$((base + 10#$suf))
    local r; r=$(resolve "$pkg" "$code") || { pace; continue; }
    local url total; url=${r%%$'\t'*}; total=${r##*$'\t'}
    pace
    parse_meta "$url" "$total" "$code" || { warn "$channel: metadata parse failed for $code"; pace; continue; }
    libs=$(meta_libs "$WORK/meta-$code.json")
    echo "   $code: [$libs]"
    local has64=0 has32=0
    case " $libs " in *" arm64-v8a "*) has64=1;; esac
    case " $libs " in *" armeabi-v7a "*) has32=1;; esac
    if [ "$has64" -eq 1 ] && [ "$has32" -eq 1 ]; then
      [ -z "$universal_code" ] && universal_code=$code
    elif [ "$has32" -eq 1 ]; then
      [ -z "$arm32_code" ] && arm32_code=$code
    fi
    # Stop probing once we have every variant this run wants.
    [ -n "$universal_code" ] && { [ "$want_arm32only" -eq 0 ] || [ -n "$arm32_code" ]; } && break
    pace
  done

  if [ -z "$universal_code" ]; then
    warn "$channel: apkpure has no 64+32 (arm64-v8a + armeabi-v7a) APK for $version (blocked or not hosted)"
  else
    lay_out_code "$channel" "$version" "$universal_code" u "$abis"
  fi
  if [ "$want_arm32only" -eq 1 ] && [ -n "$arm32_code" ] && case " $abis " in *" armeabi-v7a "*) true;; *) false;; esac; then
    lay_out_code "$channel" "$version" "$arm32_code" a32 armeabi-v7a
  fi
}

# Verify one APK (signer + stamp + versionName) and extract the requested ABIs' .so into the layout.
lay_out_code() {
  local channel=$1 version=$2 code=$3 variant=$4 want=$5
  local json="$WORK/meta-$code.json"
  local signer stamp
  signer=$(meta_cert "$json" signer); stamp=$(meta_cert "$json" stamp)
  if [ "$signer" != "$(signer_pin "$channel")" ]; then warn "$channel $code: signer cert $signer != pin"; return 0; fi
  if [ "$stamp" != "$STAMP_PIN" ]; then warn "$channel $code: source-stamp cert $stamp != pin"; return 0; fi
  # Re-resolve a fresh winudf URL (the probe's token may have aged) for the AndroidManifest read.
  local url; url=$(fresh_url "$(pkg_of "$channel")" "$code") || { warn "$channel $code: re-resolve failed"; return 0; }
  pace
  if ! verify_version "$url" "$json" "$version"; then warn "$channel $code: versionName $version not in AndroidManifest"; return 0; fi
  pace
  local pkg abi lho _csize usize method dir
  pkg=$(pkg_of "$channel")
  for abi in $want; do
    case " $(meta_libs "$json") " in *" $abi "*) : ;; *) continue;; esac
    read -r lho _csize usize method < <(meta_entry "$json" "lib/$abi/libwebviewchromium.so")
    if [ "$method" != "0" ]; then warn "$channel $code $abi: not stored uncompressed (method $method)"; continue; fi
    dir="$OUTDIR/$channel-$code-$abi-$variant"
    mkdir -p "$dir"
    if [ -s "$dir/libwebviewchromium.so" ] && [ "$(stat -c%s "$dir/libwebviewchromium.so")" -eq "$usize" ]; then
      echo "   $channel $code $abi ($variant): cached"
    else
      echo "::group::$channel $code $abi ($variant) — ranged .so fetch ($usize bytes)"
      if ! extract_stored "$pkg" "$code" "$lho" "$usize" "$dir/libwebviewchromium.so"; then
        warn "$channel $code $abi: .so fetch failed"; echo "::endgroup::"; rm -rf "$dir"; continue
      fi
      echo "::endgroup::"
      pace
    fi
    add_lib "$channel-$code-$abi-$variant" "$channel" "$version" "$code" "$abi" "$variant"
    echo "   verified $channel $version $code $abi ($variant)"
  done
}

# Lay out pre-extracted .so files (local mode): any libwebviewchromium.so under DIR becomes a gating row,
# labelled by its parent directory. No fetch, no signature check — for local lib/snapshot/negative tests.
do_local() {
  local so label abi n=0
  while IFS= read -r so; do
    abi=$(basename "$(dirname "$so")")
    label="local-$((n++))-$abi"
    mkdir -p "$OUTDIR/$label"
    cp "$so" "$OUTDIR/$label/libwebviewchromium.so"
    add_lib "$label" local - - "$abi" local
    echo "   local: $label <- $so"
  done < <(find "$local_apk_dir" -type f -name libwebviewchromium.so | sort)
  [ "$n" -gt 0 ] || { echo "no libwebviewchromium.so under $local_apk_dir" >&2; return 1; }
}

main() {
  if [ "$do_fetch" -eq 1 ] || [ -n "$local_apk_dir" ]; then
    mkdir -p "$OUTDIR"; : > "$META"
    WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
    if [ -n "$local_apk_dir" ]; then
      do_local
    else
      for channel in $channels; do do_channel "$channel"; pace; done
    fi
  fi

  local count=0
  [ -f "$META" ] && count=$(grep -c . "$META" || true)
  echo "== laid out $count verified lib(s) =="
  [ "$count" -gt 0 ] && cat "$META"

  if [ "$count" -eq 0 ]; then
    echo "::warning::WebView official check: every channel was skipped (mirror unreachable, not hosted, or verification failed) — nothing was gated this run."
    exit 0
  fi
  if [ "$run_gradle" -eq 0 ]; then echo "--no-run: layout only"; exit 0; fi

  exec "$GRADLE" :modules:webviewpatch:testDebugUnitTest --rerun-tasks \
    --tests keiyoushi.runtime.webviewpatch.OfficialCheckTest \
    -Dxrw.official-libs="$OUTDIR" --console=plain
}

main
