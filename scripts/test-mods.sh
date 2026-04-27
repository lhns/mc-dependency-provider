#!/usr/bin/env bash
# Run a Gradle command in every test-mod's own root build.
#
# Each test-mod under test-mods/ is a standalone Gradle build (Loom or
# ModDevGradle) that pulls mcdepprovider in via composite-build includeBuild,
# so they're not subprojects of the root build and `./gradlew :test-mods:...`
# doesn't reach them. This loop covers what CI does in separate
# `working-directory:` steps.
#
# Usage:
#   scripts/test-mods.sh <gradle-args...>
#   scripts/test-mods.sh build
#   scripts/test-mods.sh generateMcdpManifest jar --no-daemon
#   ONLY=kotlin-example scripts/test-mods.sh runServer
#
# ONLY: comma-separated list of test-mod directory names to restrict to.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLEW="$REPO_ROOT/gradlew"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <gradle-args...>" >&2
  echo "  e.g.   $0 build" >&2
  echo "         ONLY=kotlin-example $0 runServer" >&2
  exit 2
fi

if [ -n "${ONLY:-}" ]; then
  IFS=',' read -ra ALLOW <<< "$ONLY"
fi

allowed() {
  local name="$1"
  if [ -z "${ALLOW+x}" ]; then return 0; fi
  for a in "${ALLOW[@]}"; do
    [ "$a" = "$name" ] && return 0
  done
  return 1
}

shopt -s nullglob
fail=0
for d in "$REPO_ROOT"/test-mods/*/; do
  name="$(basename "$d")"
  if ! allowed "$name"; then
    echo ">>> skip $name"
    continue
  fi
  echo ">>> $name: gradlew $*"
  if ! ( cd "$d" && "$GRADLEW" "$@" ); then
    echo "!!! $name failed" >&2
    fail=1
  fi
done

exit $fail
