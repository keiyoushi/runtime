#!/usr/bin/env bash
# Run webviewpatch's pure-Kotlin locator against the current *official*, Google-signed Android System
# WebView of the Stable, Beta and Canary channels, as an early warning that a new WebView may stop patching.
# No device, no Google account.
#
# The builds come from the private repo hazptr/webview-builds, which a maintainer's fetcher keeps current:
# it pulls each channel's WebView from Google Play, verifies it in full (apksigner: one signer, v2/v3 and the
# Play source stamp, signer/stamp certificates pinned per channel), extracts every lib/<abi>/
# libwebviewchromium.so and publishes them under the release tags `stable`, `beta`, `canary`, each with a
# manifest.json listing versionName, versionCode, sha256 and build profile per asset and a fetchedAt stamp.
# Up to five libs per channel: the 64-bit-only Pixel-profile APK's arm64-v8a and armeabi-v7a, the 32-bit-only
# APK's armeabi-v7a-only, and the 64+32 APK's arm64-v8a-32bit-capable and armeabi-v7a-32bit-capable.
#
# This script, per channel, reads the release by tag via the GitHub REST API (token: WEBVIEW_BUILDS_TOKEN,
# fine-grained, Contents: Read-only on that repo), downloads manifest.json and every listed asset, verifies
# each asset's SHA-256 against the manifest, and lays the verified .so files out under -Dxrw.official-libs for
# OfficialCheckTest. The builds are already signature-verified by the fetcher and SHA-256-verified here, so
# there is no signer-pin or mirror-trust logic: this is our own repo, so a download error or a SHA-256
# mismatch is a hard failure, not a skip.
#
# Fail vs. skip vs. warn:
#   * No token (fork PRs, or not configured yet)      -> skip every channel, loud ::notice::, job PASSES.
#   * API error, download error, or SHA-256 mismatch  -> FAIL (our own repo; a real problem).
#   * manifest.json fetchedAt older than 3 days       -> ::warning:: annotation, NOT a failure.
#   * a verified lib the locator finds no adder in     -> FAIL (a new WebView may not patch). Done by gradle.
#
# Usage:
#   webview-official-check.sh                       all channels, fetch + verify + run
#   webview-official-check.sh --channels "beta"     restrict channels
#   webview-official-check.sh --no-fetch            re-verify an already-populated OUTDIR and run (no download)
#   webview-official-check.sh --no-run              fetch, verify and lay out; do not run gradle
#   webview-official-check.sh --local-dir DIR       lay out every libwebviewchromium.so under DIR as gating
#                                                   rows (no token, no verify) and gate on them (local tests)
#
# Env: WEBVIEW_BUILDS_TOKEN (or GH_TOKEN) the read-only token; WEBVIEW_BUILDS_REPO (default hazptr/webview-builds),
#      GH_API (default https://api.github.com), GRADLE (default ./gradlew), OUTDIR (default <repo>/.official-libs),
#      STALE_DAYS (default 3), PACE seconds between API calls (default 1).
set -euo pipefail

TOKEN=${WEBVIEW_BUILDS_TOKEN:-${GH_TOKEN:-}}
BUILDS_REPO=${WEBVIEW_BUILDS_REPO:-hazptr/webview-builds}
API=${GH_API:-https://api.github.com}
GRADLE=${GRADLE:-./gradlew}
PACE=${PACE:-1}
STALE_DAYS=${STALE_DAYS:-3}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUTDIR=${OUTDIR:-$REPO/.official-libs}
META="$OUTDIR/meta.tsv"
MANIFESTS="$OUTDIR/manifests"

channels="stable beta canary"
run_gradle=1
do_fetch=1
local_dir=""

while [ $# -gt 0 ]; do
  case "$1" in
    --channels) channels=$2; shift 2;;
    --no-run) run_gradle=0; shift;;
    --no-fetch) do_fetch=0; shift;;
    --local-dir) local_dir=$2; do_fetch=0; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

warn() { echo "::warning::$*"; echo "WARN: $*" >&2; }
pace() { sleep "$PACE"; }

# GitHub REST API GET. $1 path (absolute, after $API), $2 accept header, $3 output file.
api_get() {
  local path=$1 accept=$2 out=$3
  curl -fsSL --retry 3 --retry-delay "$PACE" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: $accept" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$API$path" -o "$out"
}

# Append a row the Kotlin test reads: label, channel, version, versionCode, abi, variant.
add_lib() {
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" "$6" >> "$META"
}

# Fetch (or reuse, under --no-fetch), verify and lay out one channel. A download/API error or SHA-256
# mismatch exits the whole script non-zero (our own repo). Returns 0 on success.
do_channel() {
  local channel=$1
  local mf="$MANIFESTS/$channel.json" rel="$WORK/release-$channel.json"

  if [ "$do_fetch" -eq 1 ]; then
    echo "== $channel: reading release tag from $BUILDS_REPO =="
    api_get "/repos/$BUILDS_REPO/releases/tags/$channel" "application/vnd.github+json" "$rel" \
      || { echo "ERROR: $channel: cannot read release tag (API error / repo or tag missing)" >&2; exit 1; }
    local mf_id
    mf_id=$(jq -r '.assets[] | select(.name=="manifest.json") | .id' "$rel")
    [ -n "$mf_id" ] && [ "$mf_id" != "null" ] || { echo "ERROR: $channel: no manifest.json asset in the release" >&2; exit 1; }
    pace
    api_get "/repos/$BUILDS_REPO/releases/assets/$mf_id" "application/octet-stream" "$mf" \
      || { echo "ERROR: $channel: manifest.json download failed" >&2; exit 1; }
  elif [ ! -f "$mf" ]; then
    echo "ERROR: $channel: --no-fetch but no manifest at $mf" >&2; exit 1
  fi

  local version fetched_at
  version=$(jq -r '.versionName' "$mf")
  fetched_at=$(jq -r '.fetchedAt // empty' "$mf")
  echo "   $channel $version (fetchedAt ${fetched_at:-unknown})"

  # Stale-manifest warning (not a failure): the fetcher hasn't refreshed this channel in > STALE_DAYS.
  if [ -n "$fetched_at" ]; then
    local age_days
    age_days=$(python3 -I -c 'import sys,datetime as d
t=d.datetime.fromisoformat(sys.argv[1].replace("Z","+00:00"))
print(int((d.datetime.now(d.timezone.utc)-t).total_seconds()//86400))' "$fetched_at" 2>/dev/null || echo "")
    if [ -n "$age_days" ] && [ "$age_days" -gt "$STALE_DAYS" ]; then
      warn "$channel: manifest is ${age_days}d old (> ${STALE_DAYS}d) — the fetcher may be stuck; builds may be stale"
    fi
  fi

  # Every libwebviewchromium*.so asset the manifest lists.
  local name
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    local sha abi profile code pver variant label dir target
    sha=$(jq -r --arg n "$name" '.assets[$n].sha256' "$mf")
    abi=$(jq -r --arg n "$name" '.assets[$n].abi' "$mf")
    profile=$(jq -r --arg n "$name" '.assets[$n].profile' "$mf")
    code=$(jq -r --arg n "$name" '.assets[$n].versionCode' "$mf")
    # Per-asset version: its profile's versionName (profiles can differ mid-rollout), else the top-level one.
    pver=$(jq -r --arg p "$profile" '.profiles[$p].versionName // empty' "$mf")
    [ -n "$pver" ] || pver=$version
    variant=$profile
    label="$channel-$name"
    dir="$OUTDIR/$label"; mkdir -p "$dir"
    target="$dir/libwebviewchromium.so"

    if [ "$do_fetch" -eq 1 ]; then
      local aid
      aid=$(jq -r --arg n "$name" '.assets[] | select(.name==$n) | .id' "$rel")
      [ -n "$aid" ] && [ "$aid" != "null" ] || { echo "ERROR: $channel: asset $name listed in the manifest but not in the release" >&2; exit 1; }
      pace
      echo "::group::$channel $name — download + verify"
      api_get "/repos/$BUILDS_REPO/releases/assets/$aid" "application/octet-stream" "$target" \
        || { echo "ERROR: $channel $name: download failed" >&2; echo "::endgroup::"; exit 1; }
      echo "::endgroup::"
    elif [ ! -f "$target" ]; then
      echo "ERROR: $channel $name: --no-fetch but $target is missing" >&2; exit 1
    fi

    local got
    got=$(sha256sum "$target" | cut -d' ' -f1)
    if [ "$got" != "$sha" ]; then
      echo "ERROR: $channel $name: SHA-256 mismatch (manifest $sha, got $got)" >&2; exit 1
    fi
    add_lib "$label" "$channel" "$pver" "$code" "$abi" "$variant"
    echo "   verified $channel $pver $code $abi ($variant) <- $name"
  done < <(jq -r '.assets | keys[] | select(startswith("libwebviewchromium"))' "$mf")
}

# Lay out pre-extracted .so files (local mode): any libwebviewchromium.so under DIR becomes a gating row,
# labelled by its parent directory. No token, no verification — for local lib / negative tests.
do_local() {
  local so abi n=0
  while IFS= read -r so; do
    abi=$(basename "$(dirname "$so")")
    local label="local-$((n++))-$abi"
    mkdir -p "$OUTDIR/$label"
    cp "$so" "$OUTDIR/$label/libwebviewchromium.so"
    add_lib "$label" local - - "$abi" local
    echo "   local: $label <- $so"
  done < <(find "$local_dir" -type f -name libwebviewchromium.so | sort)
  [ "$n" -gt 0 ] || { echo "no libwebviewchromium.so under $local_dir" >&2; exit 1; }
}

main() {
  if [ "$do_fetch" -eq 1 ] && [ -z "$TOKEN" ] && [ -z "$local_dir" ]; then
    echo "::notice::WebView official check skipped: no WEBVIEW_BUILDS_TOKEN (fork PR, or the secret is not configured yet). Nothing was gated."
    exit 0
  fi

  mkdir -p "$OUTDIR" "$MANIFESTS"
  : > "$META"  # rebuilt from the manifests / layout every pass (including --no-fetch re-verify)
  WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

  if [ -n "$local_dir" ]; then
    do_local
  else
    for channel in $channels; do do_channel "$channel"; pace; done
  fi

  local count=0
  [ -f "$META" ] && count=$(grep -c . "$META" || true)
  echo "== laid out $count verified lib(s) =="
  [ "$count" -gt 0 ] && cat "$META"
  [ "$count" -gt 0 ] || { echo "ERROR: no libs laid out" >&2; exit 1; }

  if [ "$run_gradle" -eq 0 ]; then echo "--no-run: layout only"; exit 0; fi

  exec "$GRADLE" :modules:webviewpatch:testDebugUnitTest --rerun-tasks \
    --tests keiyoushi.runtime.webviewpatch.OfficialCheckTest \
    -Dxrw.official-libs="$OUTDIR" --console=plain
}

main
