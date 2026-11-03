#!/usr/bin/env bash
# Builds the desktop app for the current OS: a trimmed Java runtime (jlink) plus a native launcher
# (jpackage), zipped. The result runs without an installed Java; double-clicking it starts the
# local web UI ("ui" is the default argument) and opens the browser.
#
# Usage: packaging/package.sh <shaded-jar> <version> <output-dir>
#   e.g. packaging/package.sh target/sensor-data-collector-2.0.0.jar 2.0.0 dist
#
# Output: SensorDataCollector-<version>-windows-x64.zip  (folder with SensorDataCollector.exe)
#         SensorDataCollector-<version>-macos-arm64.zip  (SensorDataCollector.app)
#         SensorDataCollector-<version>-linux-x64.tar.gz (folder with bin/SensorDataCollector)
set -euo pipefail

jar=$1
version=$2
out=$3
name=SensorDataCollector
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# jpackage wants a purely numeric version (macOS: first number at least 1)
app_version=$(printf '%s' "$version" | sed -E 's/^v//; s/[^0-9.].*$//; s/\.$//')
[ -n "$app_version" ] || { echo "cannot derive a numeric version from '$version'" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) os=macos; icon="$here/icon.icns" ;;
  MINGW*|MSYS*|CYGWIN*) os=windows; icon="$here/icon.ico" ;;
  *) os=linux; icon="$here/icon.png" ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  *) arch=x64 ;;
esac

mkdir -p "$work/input" "$out"
cp "$jar" "$work/input/app.jar"
cp "$here/../LICENSE" "$here/../THIRD_PARTY_NOTICES.md" "$work/input/"

# Modules the app needs, plus ones jdeps cannot see: EC cryptography for TLS to the vendor API.
# H2's optional tools (web console, Java-source functions, scripting) pull in the AWT/Swing stack,
# a compiler and a script engine; the app uses none of them, and leaving them out saves ~30 MB.
optional='java.desktop|java.compiler|java.scripting|java.instrument'
modules=$(jdeps --ignore-missing-deps --multi-release 21 --print-module-deps "$jar" \
  | tr ',' '\n' | grep -vxE "$optional" | tr '\n' ',' | sed 's/,$//')
modules="$modules,jdk.crypto.ec"
echo "runtime modules: $modules"

# No class-data-sharing archive: it measured ~40 ms faster start-up (of ~0.5 s) for 26 MB.
jlink --add-modules "$modules" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output "$work/runtime"

args=(
  --type app-image
  --name "$name"
  --app-version "$app_version"
  --vendor "Efe Cagin Yildirim"
  --description "Collects condition-monitoring data and exports it as CSV"
  --copyright "Copyright (c) Efe Cagin Yildirim"
  --input "$work/input"
  --main-jar app.jar
  --main-class io.github.efecaginyildirim.collector.app.Main
  --arguments ui
  --java-options -XX:+UseSerialGC
  --java-options -Xmx512m
  --runtime-image "$work/runtime"
  --icon "$icon"
  --dest "$work/image"
)
[ "$os" = macos ] && args+=(--mac-package-identifier io.github.efecaginyildirim.sensordatacollector)
jpackage "${args[@]}"

base="$name-$version-$os-$arch"
case "$os" in
  macos)
    app="$work/image/$name.app"
    # The app has no window of its own (it lives in the browser), so it should not sit in the Dock
    # as an unresponsive icon. LSUIElement makes it a background agent; re-sign after the edit.
    plutil -insert LSUIElement -bool YES "$app/Contents/Info.plist"
    codesign --force --deep --sign - "$app"
    ditto -c -k --sequesterRsrc --keepParent "$app" "$out/$base.zip"
    ;;
  windows)
    powershell -NoProfile -Command \
      "Compress-Archive -Path '$(cygpath -w "$work/image/$name")' -DestinationPath '$(cygpath -w "$out")\\$base.zip' -Force"
    ;;
  linux)
    tar -C "$work/image" -czf "$out/$base.tar.gz" "$name"
    ;;
esac

du -sh "$work/image" | sed "s|$work/image|unpacked size|"
ls -l "$out"
