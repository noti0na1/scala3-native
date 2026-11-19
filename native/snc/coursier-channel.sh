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
# depend on this checkout or on the Ivy home it was built with. The version is
# the Scala Native version and a digest of the binary, so a new link gives a
# new version, which `cs update snc` installs.
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
for f in "$host.snm" "$host.classpath"; do
  [ -f "$f" ] || { echo "coursier-channel.sh: $f is missing" >&2; exit 1; }
done

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

sn=$("$host" --about | sed -nE 's/.*"scalaNativeVersion":"([^"]+)".*/\1/p')
version="$sn-$(sha 256 "$host" | cut -c1-12)"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
bundle=$work/snc-$version
mkdir -p "$bundle/bin" "$bundle/lib/snc-host"
cp "$host" "$bundle/bin/snc"
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
