#!/usr/bin/env bash
# Run webviewpatch's pure-Kotlin locator against the latest published Chromium *snapshot* WebView, as an
# early warning that a future WebView may stop patching. No device.
#
# It resolves LAST_CHANGE for the two Android snapshot platforms on GCS, downloads each chrome-android.zip,
# extracts only SystemWebView.apk's lib/<abi>/libwebviewchromium.so, lays them out, and runs
# SnapshotCheckTest (which fails when the locator finds no adder).
#
#   Android_Arm64  -> the gate (arm64-v8a primary + armeabi-v7a secondary; the production 64+32 ABI layout)
#   Android        -> informational only (standalone 32-bit build; non-production codegen, see
#                     work/keiyoushi-runtime/snapshot-check.md / CONTRIBUTING.md). A miss there never fails.
#
# A gate failure means: this snapshot WebView would not patch. A gate pass is a smoke signal, not proof the
# Google-signed Play WebView of that version patches (the snapshot is a non-official trunk build).
#
# Usage:
#   webview-snapshot-check.sh                       resolve, download, extract, run
#   webview-snapshot-check.sh --build-arm64 N --build-android N   pin the builds (CI passes the cached numbers)
#   webview-snapshot-check.sh --local-dir DIR       skip download; take libs from a local chromium-snapshots
#                                                   tree (DIR/<platform>/<build>/.../lib/<abi>/libwebviewchromium.so)
#   webview-snapshot-check.sh --no-run              lay the libs out and print the plan, but do not run gradle
#
# Env: GRADLE (default ./gradlew), WORKDIR (default a mktemp dir), SNAPSHOT_BASE (GCS base URL).
set -euo pipefail

BASE=${SNAPSHOT_BASE:-https://storage.googleapis.com/chromium-browser-snapshots}
GRADLE=${GRADLE:-./gradlew}
GATE_PLATFORM=Android_Arm64
INFO_PLATFORM=Android
build_arm64=""
build_android=""
local_dir=""
run_gradle=1

while [ $# -gt 0 ]; do
  case "$1" in
    --build-arm64) build_arm64=$2; shift 2 ;;
    --build-android) build_android=$2; shift 2 ;;
    --local-dir) local_dir=$2; shift 2 ;;
    --no-run) run_gradle=0; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

WORKDIR=${WORKDIR:-$(mktemp -d)}
gate_dir="$WORKDIR/gate"
info_dir="$WORKDIR/info"
mkdir -p "$gate_dir" "$info_dir"

# Resolve LAST_CHANGE (a plain integer build number) for a snapshot platform.
resolve() {
  local platform=$1 n
  n=$(curl --fail --silent --show-error --retry 3 --retry-delay 5 "$BASE/$platform/LAST_CHANGE")
  case "$n" in (''|*[!0-9]*) echo "bad LAST_CHANGE for $platform: '$n'" >&2; return 1 ;; esac
  echo "$n"
}

# Download chrome-android.zip for <platform>/<build>, pull out SystemWebView.apk's libwebviewchromium.so
# per ABI into <destdir>/<abi>/libwebviewchromium.so. Peak disk = zip + apk; both deleted straight after.
fetch_libs() {
  local platform=$1 build=$2 dest=$3
  if [ -n "$(find "$dest" -name libwebviewchromium.so -print -quit 2>/dev/null)" ]; then
    echo "$platform: libs already present (cache hit), skipping download"
    return 0
  fi
  local tmp="$WORKDIR/dl-$platform"
  mkdir -p "$tmp"
  echo "::group::$platform build $build"
  curl --fail --location --retry 3 --retry-delay 5 --output "$tmp/chrome-android.zip" \
    "$BASE/$platform/$build/chrome-android.zip"
  # Extract only the one APK, then delete the zip; then only the .so entries, then delete the APK.
  unzip -o -j "$tmp/chrome-android.zip" 'chrome-android/apks/SystemWebView.apk' -d "$tmp" >/dev/null
  rm -f "$tmp/chrome-android.zip"
  extract_so "$tmp/SystemWebView.apk" "$dest"
  rm -f "$tmp/SystemWebView.apk"
  echo "::endgroup::"
}

# Copy each lib/<abi>/libwebviewchromium.so out of an APK into <destdir>/<abi>/.
extract_so() {
  local apk=$1 dest=$2 entry abi
  while IFS= read -r entry; do
    abi=$(basename "$(dirname "$entry")")
    mkdir -p "$dest/$abi"
    unzip -o -p "$apk" "$entry" > "$dest/$abi/libwebviewchromium.so"
    echo "  extracted $abi/libwebviewchromium.so ($(stat -c%s "$dest/$abi/libwebviewchromium.so") bytes)"
  done < <(unzip -Z1 "$apk" 'lib/*/libwebviewchromium.so')
}

# Local mode: copy already-extracted lib/<abi>/libwebviewchromium.so out of a chromium-snapshots tree,
# preserving the ABI dir. Takes the pre-extracted .so (not the APK), to mirror a normal run's inputs.
local_libs() {
  local platform=$1 dest=$2 so abi n=0
  while IFS= read -r so; do
    abi=$(basename "$(dirname "$so")")
    mkdir -p "$dest/$abi"
    cp "$so" "$dest/$abi/libwebviewchromium.so"
    echo "  local $platform: $abi/libwebviewchromium.so ($(stat -c%s "$so") bytes)"
    n=$((n + 1))
  done < <(find "$local_dir/$platform" -type f -name libwebviewchromium.so 2>/dev/null)
  [ "$n" -gt 0 ] || { echo "no libwebviewchromium.so under $local_dir/$platform" >&2; return 1; }
}

if [ -n "$local_dir" ]; then
  local_libs "$GATE_PLATFORM" "$gate_dir"
  local_libs "$INFO_PLATFORM" "$info_dir" || echo "(no $INFO_PLATFORM libs locally; skipping info)"
else
  [ -n "$build_arm64" ] || build_arm64=$(resolve "$GATE_PLATFORM")
  [ -n "$build_android" ] || build_android=$(resolve "$INFO_PLATFORM")
  echo "$GATE_PLATFORM build $build_arm64 (gate); $INFO_PLATFORM build $build_android (info)"
  fetch_libs "$GATE_PLATFORM" "$build_arm64" "$gate_dir"
  fetch_libs "$INFO_PLATFORM" "$build_android" "$info_dir" || echo "(info build fetch failed; skipping info)"
fi

echo "gate libs:"; find "$gate_dir" -name libwebviewchromium.so -printf '  %p (%s bytes)\n'
echo "info libs:"; find "$info_dir" -name libwebviewchromium.so -printf '  %p (%s bytes)\n'

if [ "$run_gradle" -eq 0 ]; then echo "--no-run: layout only"; exit 0; fi

info_prop=()
[ -n "$(find "$info_dir" -name libwebviewchromium.so -print -quit)" ] && info_prop=(-Dxrw.snapshot-info-libs="$info_dir")

exec "$GRADLE" :modules:webviewpatch:testDebugUnitTest --rerun-tasks \
  --tests keiyoushi.runtime.webviewpatch.SnapshotCheckTest \
  -Dxrw.snapshot-libs="$gate_dir" "${info_prop[@]}" --console=plain
