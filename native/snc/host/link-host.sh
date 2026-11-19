#!/bin/bash
# Links snc with snlink as `sbt snc/nativeLink` and `snc-host/nativeLink` do:
# single-threaded, with the embedded properties files, and as a live-execution
# host with the boundary of boundary.txt unless HOST=0.
#
#   SNLINK=… SNC_CP_FILE=… link-host.sh <base dir> <mode> [<main class>] [snlink options…]
#
# SNC_CP_FILE holds the link class path, colon-separated
# (`sbt "export snc-host/Compile/fullClasspath"`). The main class is
# dotty.tools.dotc.SncHostMain by default, the host's main; with
# dotty.tools.dotc.SncMain it is the plain compiler.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
base=$1; mode=$2; main=${3:-dotty.tools.dotc.SncHostMain}
shift $(( $# < 3 ? $# : 3 ))
boundary=()
if [ "${HOST:-1}" != 0 ]; then
  while read -r kind pattern; do
    case "$kind" in
      open) boundary+=(--open "$pattern") ;;
      callable) boundary+=(--callable "$pattern") ;;
    esac
  done < <(grep -v '^[[:space:]]*#' "$here/boundary.txt" | grep -v '^[[:space:]]*$')
  boundary+=(--stub-unlinkable)
fi
cp=$(tr ':' ' ' < "$SNC_CP_FILE")
# shellcheck disable=SC2086
exec $SNLINK --main "$main" --base-dir "$base" --module snc --mode "$mode" \
  --multithreading false --embed-resources \
  --resource-include compiler.properties --resource-include library.properties --resource-include /library.properties \
  ${boundary[@]+"${boundary[@]}"} "$@" $cp
