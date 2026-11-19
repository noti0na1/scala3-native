#!/bin/bash
# Links a macro test rig: snc with the NIR that rig-gen wrote, and the class
# paths of scopes without a prefix.
#
#   SNLINK=… SNC_CP_FILE=… link-rig.sh <gen dir> <base dir> <mode> [<class path>]
#
# SNC_CP_FILE holds snc's link class path (`sbt "export snc/Compile/fullClasspath"`).
set -euo pipefail
gen=$1; base=$2; mode=$3; extra=${4:-}
cp=$(cat "$SNC_CP_FILE")
[ -n "$extra" ] && cp="$cp:$extra"
exec "$SNLINK" --main snc.rig.Main --base-dir "$base" --module snc-rig --mode "$mode" \
  --multithreading false --embed-resources \
  --resource-include compiler.properties --resource-include library.properties --resource-include /library.properties \
  "$gen/nir" $(echo "$cp" | tr ':' ' ')
