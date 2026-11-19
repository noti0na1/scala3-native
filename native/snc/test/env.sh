# The environment of the tests under native/snc/test (README.md, "Testing"):
#
#   . native/snc/test/env.sh <work dir>
#   WORK=<dir> native/snc/test/compat/run.sh
#   SNC=$SNC_HOST WORK=<dir> native/snc/host/test/run.sh
#   cd native/snc/test/macro-rig && WORK=<dir> SNC=$SNC_HOST python3 -I run-macros.py prepare
#   cd native/snc/test/macro-rig && WORK=<dir> SNC=$SNC_HOST python3 -I run-macros.py exec
#
# It needs:
# - the companion Scala Native fork's artifacts in $SNC_IVY_HOME/local (by
#   default ~/.ivy2/local), and its snlink as $SNLINK (by default snlink on
#   the PATH);
# - snc and snc-host linked in this repository;
# - JAVA_HOME naming a JDK 17, and optionally OTHER_JDK, a newer JDK, for
#   the -release case of the compatibility suite;
# - coursier's cs, to fetch scala-library.
# The class path of the JVM compiler built from the same sources is
# exported from sbt into <work dir> once.

_work=${1:?usage: . native/snc/test/env.sh <work dir>}
_repo=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../../.." && pwd)
mkdir -p "$_work"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
export JAVA_HOME
export SNC_IVY_HOME=${SNC_IVY_HOME:-$HOME/.ivy2}
_sn=$(sed -nE 's/.*val scalaNativeVersion = "([^"]+)".*/\1/p' "$_repo/project/NativeBuild.scala")
_nightly=$(sed -nE 's/.*val nscpluginScalaVersion = "([^"]+)".*/\1/p' "$_repo/project/NativeBuild.scala")
_i=$SNC_IVY_HOME/local/org.scala-native
_lib() { echo "$_i/$1_native0.5_3/$_sn/jars/$1_native0.5_3.jar"; }

_scala_library=$(cs fetch --intransitive "org.scala-lang:scala-library:$_nightly" \
  -r https://repo.scala-lang.org/artifactory/maven-nightlies 2>/dev/null)
NATIVE_CP=
for _l in nativelib clib posixlib windowslib javalib auxlib; do NATIVE_CP="$NATIVE_CP$(_lib $_l):"; done
export NATIVE_CP="$NATIVE_CP$_scala_library"
export LINK_CP="$NATIVE_CP:$_i/scalalib_native0.5_3/$_nightly+$_sn/jars/scalalib_native0.5_3.jar:$_i/scala3lib_native0.5_3/$_nightly+$_sn/jars/scala3lib_native0.5_3.jar"
export NSCPLUGIN=$_i/nscplugin_$_nightly/$_sn/jars/nscplugin_$_nightly.jar
export JUNIT_PLUGIN=$(ls "$_i"/junit-plugin_3.*/"$_sn"/jars/junit-plugin_3.*.jar 2>/dev/null | head -1)
export JUNIT_CP="$(_lib junit-runtime):$(_lib test-interface):$(_lib test-interface-sbt-defs)"
export OTHER_SCALA_LIBRARY=$(cs fetch --intransitive org.scala-lang:scala-library:3.10.0 2>/dev/null)
export SNLINK=${SNLINK:-$(command -v snlink)}
export SNC=$_repo/native/snc/target/scala-3.10.2-RC1/snc
export SNC_HOST=$_repo/native/snc-host/target/scala-3.10.2-RC1/snc-host

# run-macros.py
echo "$NATIVE_CP" > "$_work/user-cp.txt"
echo "$LINK_CP" > "$_work/link-cp.txt"
export USER_CP_FILE=$_work/user-cp.txt LINK_CP_FILE=$_work/link-cp.txt
export TESTS_DIR=$_repo/tests/run-macros

# The JVM compiler built from the same sources
export JVM_CP_FILE=$_work/jvm-cp.txt
if [ ! -s "$JVM_CP_FILE" ]; then
  (cd "$_repo" && sbt -Dsbt.ivy.home="$SNC_IVY_HOME" --batch \
    'export scala3-compiler-nonbootstrapped/Runtime/fullClasspath') | grep -E '^/' | tail -1 > "$JVM_CP_FILE"
fi
unset _work _repo _sn _nightly _i _scala_library _l
