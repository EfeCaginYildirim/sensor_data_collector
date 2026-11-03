#!/usr/bin/env bash
# Starts a packaged app the way a double-click does (no arguments, default data folder), then drives
# it over HTTP: sign in, collect the demo data, query a filtered dataset, quit.
#
# Usage: packaging/smoke-test.sh <unpacked-app-dir> <expected-version>
set -euo pipefail

dir=$1
expected=$2
name=SensorDataCollector

case "$(uname -s)" in
  Darwin)
    data="$HOME/Library/Application Support/$name"
    launch() { open "$dir/$name.app"; }   # Launch Services, as Finder does
    ;;
  MINGW*|MSYS*|CYGWIN*)
    data="$APPDATA/$name"
    launch() { "$dir/$name/$name.exe" & }
    ;;
  *)
    data="${XDG_DATA_HOME:-$HOME/.local/share}/sensor-data-collector"
    launch() { "$dir/$name/bin/$name" > /dev/null 2>&1 & }
    ;;
esac

fail() {
  echo "FAILED: $*" >&2
  echo "--- log ---" >&2
  cat "$data"/logs/collector-0.log >&2 2>/dev/null || true
  exit 1
}

rm -rf "$data"
launch
for _ in $(seq 1 60); do [ -s "$data/instance.url" ] && break; sleep 1; done
[ -s "$data/instance.url" ] || fail "the app did not start within 60 s"
url=$(cat "$data/instance.url")
base=${url%%\?*}
jar=$(mktemp)
echo "started: $base"

curl -sf -c "$jar" -o /dev/null "$url" || fail "sign-in"
api() { curl -sf -b "$jar" -H 'Content-Type: application/json' "$@"; }

status=$(api "${base}api/status") || fail "status"
echo "status: $status"
case "$status" in *"\"version\":\"$expected\""*) ;; *) fail "expected version $expected" ;; esac

# a second start must reuse the running instance (Windows and Linux; on macOS, Launch Services
# already brings the running app forward instead of starting another)
if [ "$(uname -s)" != Darwin ]; then
  launch
  sleep 5
  [ "$(cat "$data/instance.url")" = "$url" ] || fail "a second start replaced the running instance"
  api "${base}api/status" > /dev/null || fail "the first instance stopped answering"
fi

api -X POST -d '{}' "${base}api/runs" > /dev/null || fail "start a collection"
for _ in $(seq 1 120); do
  progress=$(api "${base}api/progress")
  case "$progress" in *'"running":false'*'"lastStatus":"'*) break ;; esac
  sleep 1
done
case "$progress" in *'"lastStatus":"SUCCESS"'*) echo "collection: SUCCESS" ;; *) fail "collection: $progress" ;; esac

preview=$(api -X POST -d '{"spec":{"monitorIds":[1001],"features":["VRMSX","BPFO"],"sampling":"daily"},"limit":5}' \
  "${base}api/dataset/preview") || fail "dataset preview"
written=$(printf '%s' "$preview" | sed -E 's/.*"written":([0-9]+).*/\1/')
[ "$written" -gt 0 ] || fail "dataset preview is empty: $preview"
echo "dataset preview: $written daily rows"

api -X POST "${base}api/shutdown" > /dev/null || fail "quit"
for _ in $(seq 1 30); do
  curl -s -o /dev/null --max-time 2 "${base}api/status" || { echo "quit: stopped"; exit 0; }
  sleep 1
done
fail "the app did not stop after quit"
