# Source paths, in NIR, TASTy and diagnostics, are those scalac records for the
# same command line in the same directory: relative to -sourceroot (by default
# the working directory) when the source is under it, else as given.
set -e
fail() { echo "  $*"; exit 1; }
printf 'package p\nobject Q:\n  def main(args: Array[String]): Unit = println(args.length)\n' > src/Q.scala
abs=$PWD/src/Q.scala
jvm() { "$JAVA" -cp "$JVM_CP" dotty.tools.dotc.Main -Xplugin:"$NSCPLUGIN" "$@"; }
n=0
for args in "src/Q.scala" "$abs" "-sourceroot / $abs" "-sourceroot src $abs" "-sourcepath src src/Q.scala"; do
  n=$((n + 1))
  mkdir -p snc$n jvm$n
  # shellcheck disable=SC2086
  "$SNC" -d snc$n -classpath "$NATIVE_CP" $args
  # shellcheck disable=SC2086
  jvm -d jvm$n -classpath "$NATIVE_CP" $args
  rm -f jvm$n/p/*.class
  diff -r jvm$n snc$n > /dev/null || fail "output differs from scalac's for: $args"
done
# Diagnostics and events name files as the command line does
"$SNC" --events-text -d snc1 -classpath "$NATIVE_CP" src/P.scala > rel.events || true
grep -q '"file":"src/P.scala"' rel.events || fail "relative path not kept in events: $(cat rel.events)"
"$SNC" --events-text -d snc1 -classpath "$NATIVE_CP" "$PWD/src/P.scala" > abs.events || true
grep -q "\"file\":\"$PWD/src/P.scala\"" abs.events || fail "absolute path not kept in events: $(cat abs.events)"
