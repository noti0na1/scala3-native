# Which JDK snc compiles against (README.md, "The JDK"): --java-home, then
# JAVA_HOME, then the others. A directory named by either that is not a JDK
# is a usage error; -version needs no JDK.
set -e
cp_=$NATIVE_CP
jdk=$JAVA_HOME
fail() { echo "  $*"; exit 1; }
mkdir -p out out1 out2

# --java-home and JAVA_HOME must name a JDK: exit code 2 and a clear message
set +e
"$SNC" --java-home "$PWD" -d out -classpath "$cp_" Hello.scala > a.out 2> a.err
code=$?
set -e
[ $code = 2 ] || fail "--java-home <not a JDK>: exit code $code, expected 2"
grep -q "is not a JDK" a.err || fail "--java-home <not a JDK>: $(cat a.err)"
set +e
JAVA_HOME=$PWD "$SNC" -d out -classpath "$cp_" Hello.scala > b.out 2> b.err
code=$?
set -e
[ $code = 2 ] || fail "JAVA_HOME=<not a JDK>: exit code $code, expected 2"
grep -q "JAVA_HOME" b.err || fail "JAVA_HOME=<not a JDK>: $(cat b.err)"
JAVA_HOME=$PWD "$SNC" -version > /dev/null || fail "-version failed without a JDK"

# --java-home wins over JAVA_HOME, and gives the same output
JAVA_HOME=$PWD "$SNC" --java-home "$jdk" -d out1 -classpath "$cp_" Hello.scala \
  || fail "--java-home did not win over JAVA_HOME"
"$SNC" -d out2 -classpath "$cp_" Hello.scala
diff -r out1 out2 || fail "different output with --java-home"
"$SNC" -verbose -d out2 -classpath "$cp_" Hello.scala 2>&1 | grep -q "compiling against the JDK at" \
  || fail "-verbose does not name the JDK"

# The JDK's API, not javalib's: java.text compiles (and fails only when linked)
"$SNC" -d out2 -classpath "$cp_" Text.scala || fail "java.text did not compile"

# -release N below the JDK's version reads its ct.sym, as scalac -release N does
if [ -n "${OTHER_JDK:-}" ]; then
  mkdir -p rel-snc rel-jvm
  JAVA_HOME=$OTHER_JDK "$SNC" -release 17 -d rel-snc -classpath "$cp_" Hello.scala
  "$OTHER_JDK/bin/java" -cp "$JVM_CP" dotty.tools.dotc.Main -Xplugin:"$NSCPLUGIN" -release 17 \
    -d rel-jvm -classpath "$cp_" Hello.scala
  rm -f rel-jvm/*.class
  diff -r rel-jvm rel-snc || fail "different output from scalac -release 17"
  set +e
  JAVA_HOME=$OTHER_JDK "$SNC" -release 17 -color:never -d rel-snc -classpath "$cp_" Sequenced.scala > rel.out 2>&1
  code=$?
  set -e
  [ $code = 1 ] || fail "-release 17: an API of Java 21 compiled (exit code $code)"
  JAVA_HOME=$OTHER_JDK "$SNC" -d rel-snc -classpath "$cp_" Sequenced.scala || fail "an API of Java 21 did not compile"
fi
