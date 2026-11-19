#!/usr/bin/env bash
# Packs snc-host, as `sbt snc-host/nativeLink` links it, into a local
# coursier channel, from which coursier installs it on the PATH as `snc`:
#
#   native/snc/coursier-channel.sh [<out dir>]
#   cs install --channel <out dir>/channel snc
#
# The default <out dir> is native/snc-host/target/coursier. The archive holds
# bin/snc, its manifest (lib/snc.snm) and copies of the NIR it was linked from
# (lib/snc-host/, listed in lib/snc.classpath), so the installed snc does not
# depend on this checkout or on the Ivy home it was built with.
#
# It also holds what `snc repl` needs: the REPL's runtime
# (lib/repl-runtime.jar), the Scala Native fork's native snlink (bin/snlink),
# and the libraries that lines compile and link with (lib/repl/), which
# lib/snc-distribution.properties makes the REPL's class path when none is
# given, with an evaluator host linked for them (lib/repl-host/). snlink is
# $SNLINK, else the one `sbt snlink3/nativeLink` builds in ../scala-native.
# The libraries are resolved with `cs` from the Ivy home the Scala Native fork
# was published to ($SCALANATIVE_IVY_HOME, by default ~/.ivy2), Maven Central
# and the Scala nightlies, for the versions that `snc --about` reports.
#
# The version is the Scala Native version and a digest of snc-host, snlink,
# the runtime and the libraries, so a new link gives a new version, which
# `cs update snc` installs.
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
out=${1:-$repo/native/snc-host/target/coursier}
mkdir -p "$out"
out=$(cd "$out" && pwd)

hosts=("$repo"/native/snc-host/target/scala-*/snc-host)
if [ ${#hosts[@]} -ne 1 ] || [ ! -f "${hosts[0]}" ]; then
  echo "coursier-channel.sh: no snc-host; run sbt snc-host/nativeLink first" >&2
  exit 1
fi
host=${hosts[0]}
runtime=$(dirname "$host")/repl-runtime.jar
for f in "$host.snm" "$host.classpath" "$runtime"; do
  [ -f "$f" ] || { echo "coursier-channel.sh: $f is missing; run sbt snc-host/nativeLink again" >&2; exit 1; }
done

if [ -n "${SNLINK:-}" ]; then
  snlink=$SNLINK
else
  snlinks=("$repo"/../scala-native/target/out/native0.5/scala-*/snlink/snlink)
  snlink=${snlinks[0]}
fi
if [ ! -f "$snlink" ]; then
  echo "coursier-channel.sh: no snlink at $snlink; build it in the Scala Native fork with" >&2
  echo "  sbt 'set LocalProject(\"snlink3\") / nativeConfig ~= (_.withMode(scala.scalanative.build.Mode.releaseFast))' snlink3/nativeLink" >&2
  echo "or name it with SNLINK=<path>" >&2
  exit 1
fi
if ! "$snlink" --help 2>&1 | grep -q -- --interrupt-handler; then
  echo "coursier-channel.sh: $snlink has no --interrupt-handler, which the REPL's lines are linked with;" >&2
  echo "use the snlink of the Scala Native fork this snc goes with" >&2
  exit 1
fi

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) platform=aarch64-apple-darwin ;;
  Darwin-x86_64) platform=x86_64-apple-darwin ;;
  Linux-aarch64 | Linux-arm64) platform=aarch64-pc-linux ;;
  Linux-x86_64) platform=x86_64-pc-linux ;;
  *) echo "coursier-channel.sh: unsupported platform $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

sha() { # <algorithm bits> <file>
  if command -v "sha$1sum" > /dev/null; then "sha$1sum" "$2"; else shasum -a "$1" "$2"; fi | cut -d' ' -f1
}

about=$("$host" --about)
sn=$(sed -nE 's/.*"scalaNativeVersion":"([^"]+)".*/\1/p' <<< "$about")
scala=$(sed -nE 's/.*"scalaVersion":"([^"]+)".*/\1/p' <<< "$about")

# The REPL's libraries: Scala Native's, the Scala library of snc's version and
# the Scala library's NIR
ivy=${SCALANATIVE_IVY_HOME:-$HOME/.ivy2}
libs=()
while IFS= read -r jar; do
  case $jar in *-sources.jar | *-javadoc.jar) ;; *) libs+=("$jar") ;; esac
done < <(cs fetch --no-default \
  -r "ivy:file://$ivy/local/[defaultPattern]" -r central \
  -r https://repo.scala-lang.org/artifactory/maven-nightlies \
  $(for m in nativelib clib posixlib windowslib javalib auxlib; do echo "org.scala-native:${m}_native0.5_3:$sn"; done) \
  "org.scala-native:scalalib_native0.5_3:$scala+$sn" "org.scala-native:scala3lib_native0.5_3:$scala+$sn" \
  "org.scala-lang:scala-library:$scala")
if [ ${#libs[@]} -eq 0 ]; then
  echo "coursier-channel.sh: could not resolve the REPL's libraries from $ivy/local" >&2
  exit 1
fi

digest=$(for f in "$host" "$snlink" "$runtime" "${libs[@]}"; do sha 256 "$f"; done |
  if command -v sha256sum > /dev/null; then sha256sum; else shasum -a 256; fi | cut -c1-12)
version="$sn-$digest"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
bundle=$work/snc-$version
mkdir -p "$bundle/bin" "$bundle/lib/snc-host" "$bundle/lib/repl"
cp "$host" "$bundle/bin/snc"
cp "$snlink" "$bundle/bin/snlink"
cp "$runtime" "$bundle/lib/repl-runtime.jar"
cp "$host.snm" "$bundle/lib/snc.snm"
: > "$bundle/lib/snc.classpath"
i=0
while IFS= read -r entry || [ -n "$entry" ]; do
  [ -n "$entry" ] || continue
  case $entry in /*) ;; *) entry=$(dirname "$host")/$entry ;; esac
  i=$((i + 1))
  name=$(printf '%02d-%s' "$i" "$(basename "$entry")")
  cp -R "$entry" "$bundle/lib/snc-host/$name"
  echo "snc-host/$name" >> "$bundle/lib/snc.classpath"
done < "$host.classpath"

classpath=()
for jar in "${libs[@]}"; do
  cp "$jar" "$bundle/lib/repl/"
  classpath+=("repl/$(basename "$jar")")
done
cat > "$bundle/lib/snc-distribution.properties" << EOF
version=$version
repl.classpath=$(IFS=:; echo "${classpath[*]}")
EOF
# The evaluator host for those libraries, so that the first session does not
# link it
echo "Linking the REPL's evaluator host (a minute or two)"
"$bundle/bin/snc" repl --link-evaluator-host "$bundle/lib/repl-host" > "$work/repl-host.log" 2>&1 || {
  cat "$work/repl-host.log" >&2
  echo "coursier-channel.sh: linking the evaluator host failed" >&2
  exit 1
}
echo "repl.evaluator-host=repl-host/snrepl-host" >> "$bundle/lib/snc-distribution.properties"
# One line through the packed REPL: snc-host links it against the evaluator
# host that this snlink linked, so a snlink of another toolchain fails here
printf '20 + 22\n:quit\n' | "$bundle/bin/snc" repl -color:never --repl-work-dir "$work/repl" > "$work/repl-check.log" 2>&1 || true
if ! grep -q 'val res0: Int = 42' "$work/repl-check.log"; then
  cat "$work/repl-check.log" >&2
  echo "coursier-channel.sh: the packed REPL did not run a line" >&2
  exit 1
fi
rm -rf "$work/repl"

maven=$out/maven
dir=$maven/org/scala-native/snc
rm -rf "$dir"
mkdir -p "$dir/$version"
tar -C "$work" -czf "$dir/$version/snc-$version-$platform.tar.gz" "snc-$version"
pom=$dir/$version/snc-$version.pom
cat > "$pom" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.scala-native</groupId>
  <artifactId>snc</artifactId>
  <version>$version</version>
  <packaging>pom</packaging>
</project>
EOF
sha 1 "$pom" > "$pom.sha1"

mkdir -p "$out/channel"
cat > "$out/channel/snc.json" << EOF
{
  "repositories": ["file://$maven"],
  "dependencies": ["org.scala-native:snc:$version"],
  "launcherType": "prebuilt",
  "prebuiltBinaries": {
    "$platform": "tgz+file://$maven/org/scala-native/snc/\${version}/snc-\${version}-$platform.tar.gz!snc-\${version}/bin/snc"
  }
}
EOF

echo "snc $version for $platform. Install it with"
echo "  cs install --channel $out/channel snc"
