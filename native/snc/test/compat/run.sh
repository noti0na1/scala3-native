#!/usr/bin/env bash
# The scalac-compatibility suite of snc: runs snc and the JVM scalac built from
# the same sources on the same inputs, and compares exit codes, standard output
# and error, and the files they write (TASTy, NIR, SemanticDB, rewritten
# sources; class files are ignored). See README.md, section
# "Compatibility suite".
#
#   run.sh [case...]           run all cases, or the named ones
#   UPDATE=1 run.sh [case...]  rewrite the expected output of snc-only cases
#
# Environment:
#   SNC           the snc binary (default: the one the build links)
#   JVM_CP        class path of the JVM compiler, or JVM_CP_FILE, a file with it
#   JAVA_HOME     the JDK that both compilers compile against: the JVM
#                 compiler runs on it, and snc reads its Java API (required)
#   JAVA          java for the JVM compiler (default: $JAVA_HOME/bin/java)
#   NATIVE_CP     Scala Native's libraries and the scala-library of snc's Scala
#                 version (snc --about), or NATIVE_CP_FILE
#   NSCPLUGIN     nscplugin jar for the JVM compiler, or NSCPLUGIN_FILE
#   OTHER_JDK     optional: a JDK newer than JAVA_HOME, for the -release case
#   OTHER_SCALA_LIBRARY  optional: a scala-library jar of another version
#                 than snc's, for the case that checks the warning about it
#   JUNIT_PLUGIN  optional: Scala Native's junit-plugin jar, and JUNIT_CP, the
#                 class path of junit-runtime and test-interface, for the
#                 JUnit case
#   SNLINK        optional: snlink, to link and run the multi-module case
#   LINK_CP       the class path to link with: NATIVE_CP with the NIR of the
#                 Scala library (scalalib_native, scala3lib_native)
#   WORK          working directory (default: a new temporary directory)

set -u
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../../.." && pwd)

SNC=${SNC:-$repo/native/snc/target/scala-3.10.2-RC1/snc}
[ -n "${JAVA_HOME:-}" ] || { echo "run.sh: set JAVA_HOME to a JDK" >&2; exit 2; }
JAVA=${JAVA:-$JAVA_HOME/bin/java}
from_file() { # variable name, file variable name
  local v=$1 f=$2
  if [ -z "${!v:-}" ] && [ -n "${!f:-}" ]; then eval "$v=\$(cat \"\${$f}\")"; fi
  if [ -z "${!v:-}" ]; then echo "run.sh: set $v or $f" >&2; exit 2; fi
}
from_file JVM_CP JVM_CP_FILE
from_file NATIVE_CP NATIVE_CP_FILE
from_file NSCPLUGIN NSCPLUGIN_FILE
OTHER_JDK=${OTHER_JDK:-}
SNLINK=${SNLINK:-}
LINK_CP=${LINK_CP:-$NATIVE_CP}
JUNIT_PLUGIN=${JUNIT_PLUGIN:-}
JUNIT_CP=${JUNIT_CP:-}
OTHER_SCALA_LIBRARY=${OTHER_SCALA_LIBRARY:-}
WORK=${WORK:-$(mktemp -d "${TMPDIR:-/tmp}/snc-compat.XXXXXX")}
UPDATE=${UPDATE:-}
export SNC JAVA JAVA_HOME JVM_CP NATIVE_CP NSCPLUGIN OTHER_JDK SNLINK LINK_CP OTHER_SCALA_LIBRARY JUNIT_PLUGIN JUNIT_CP

[ -x "$SNC" ] || { echo "run.sh: no snc at $SNC" >&2; exit 2; }

# The JVM compiler, set up as snc is: nscplugin, and the Java API of the JDK
# it runs on, which snc reads through JAVA_HOME
scalac_jvm() {
  "$JAVA" -cp "$JVM_CP" dotty.tools.dotc.Main -Xplugin:"$NSCPLUGIN" "$@"
}
export -f scalac_jvm

# The arguments of a step line, with @VAR@ placeholders replaced
expand() {
  local line=$1
  line=${line//@CP@/$NATIVE_CP}
  line=${line//@NSCPLUGIN@/$NSCPLUGIN}
  line=${line//@JUNIT_PLUGIN@/$JUNIT_PLUGIN}
  line=${line//@JUNIT_CP@/$JUNIT_CP}
  line=${line//@OTHER_SCALA_LIBRARY@/$OTHER_SCALA_LIBRARY}
  printf '%s' "$line"
}

# Text that differs between the two commands by design
normalize() { # file, case dir, work dir
  LC_ALL=C sed -e 's/Usage: snc /Usage: scalac /' -e 's/  snc -help  gives/  scalac -help  gives/' \
      -e "s#$3#@WORK@#g" "$1" > "$1.norm"
  if [ -f "$2/normalize.sed" ]; then sed -i.bak -f "$2/normalize.sed" "$1.norm"; rm -f "$1.norm.bak"; fi
}

# Files under a directory (class files and the case's own files left out), with
# hashes; a case's tree.ignore lists grep patterns of paths to leave out too
tree() { # dir, case dir
  local ignore=/dev/null
  [ -f "$2/tree.ignore" ] && ignore=$2/tree.ignore
  (cd "$1" && find . -type f ! -name '*.class' ! -name steps ! -name mode ! -name snc-exit \
     ! -name normalize.sed ! -name check.sh ! -name tree.ignore ! -name requires ! -path './expected/*' | \
     grep -v -f "$ignore" | LC_ALL=C sort | \
     while read -r f; do printf '%s %s\n' "$(shasum -a 256 < "$f" | cut -c1-16)" "$f"; done)
}

# Runs the steps of a case with one compiler ("snc" or "jvm") in $work/$compiler
run_steps() { # case dir, work dir, compiler
  local dir=$1 work=$2 compiler=$3 n=0 line
  rm -rf "$work/$compiler"; mkdir -p "$work/$compiler"
  (cd "$dir" && tar cf - --exclude expected .) | (cd "$work/$compiler" && tar xf -)
  mkdir -p "$work/$compiler/out" "$work/$compiler/a" "$work/$compiler/b"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    n=$((n + 1))
    local args; args=$(expand "$line")
    # shellcheck disable=SC2086
    (cd "$work/$compiler" && set -f && if [ "$compiler" = snc ]; then "$SNC" $args; else scalac_jvm $args; fi) \
      > "$work/$compiler.$n.out" 2> "$work/$compiler.$n.err" < /dev/null
    echo $? > "$work/$compiler.$n.exit"
  done < "$dir/steps"
  echo $n
}

pass=0; fail=0; skip=0; failed=""

# Whether the variables that a case's `requires` file lists are all set
requirements_met() { # case dir
  local v
  [ -f "$1/requires" ] || return 0
  while read -r v; do [ -n "$v" ] && [ -z "${!v:-}" ] && { echo "$v"; return 1; }; done < "$1/requires"
  return 0
}
report() { # case, ok?
  if [ "$2" = 0 ]; then pass=$((pass + 1)); echo "PASS $1"
  else fail=$((fail + 1)); failed="$failed $1"; echo "FAIL $1"; fi
}

# A compat case: snc and the JVM compiler must agree
run_compat() { # case
  local name=$1 dir=$here/cases/$1 work=$WORK/$1 ok=0 i
  mkdir -p "$work"
  local steps; steps=$(run_steps "$dir" "$work" snc)
  run_steps "$dir" "$work" jvm > /dev/null
  i=0
  while [ $i -lt "$steps" ]; do
    i=$((i + 1))
    local se je expect
    se=$(cat "$work/snc.$i.exit"); je=$(cat "$work/jvm.$i.exit")
    expect=$je
    if [ -f "$dir/snc-exit" ]; then expect=$(sed -n "${i}p" "$dir/snc-exit"); fi
    if [ "$se" != "$expect" ]; then
      echo "  step $i: snc exited with $se, expected $expect (scalac: $je)"; ok=1
    fi
    for s in out err; do
      normalize "$work/snc.$i.$s" "$dir" "$work/snc"
      normalize "$work/jvm.$i.$s" "$dir" "$work/jvm"
      if ! diff -u "$work/jvm.$i.$s.norm" "$work/snc.$i.$s.norm" > "$work/$i.$s.diff"; then
        echo "  step $i: std$s differs (- scalac, + snc):"; sed 's/^/    /' "$work/$i.$s.diff" | head -40; ok=1
      fi
    done
  done
  tree "$work/snc" "$dir" > "$work/snc.tree"; tree "$work/jvm" "$dir" > "$work/jvm.tree"
  if ! diff -u "$work/jvm.tree" "$work/snc.tree" > "$work/tree.diff"; then
    echo "  output files differ (- scalac, + snc):"; sed 's/^/    /' "$work/tree.diff" | head -40; ok=1
  fi
  if [ -f "$dir/check.sh" ]; then
    (cd "$work/snc" && WORK_DIR=$work bash "$dir/check.sh") || ok=1
  fi
  report "$name" $ok
}

# An snc-only case: snc's output must match expected/
run_snc() { # case
  local name=$1 dir=$here/cases/$1 work=$WORK/$1 ok=0 i
  mkdir -p "$work"
  local steps; steps=$(run_steps "$dir" "$work" snc)
  if [ -n "$UPDATE" ]; then mkdir -p "$dir/expected"; fi
  i=0
  while [ $i -lt "$steps" ]; do
    i=$((i + 1))
    for s in exit out err; do
      local f=$work/snc.$i.$s
      LC_ALL=C sed -e "s#$work/snc#@WORK@#g" -e "s#$(dirname "$SNC")#@SNCDIR@#g" \
        -e "s#${OTHER_SCALA_LIBRARY:-@OTHER_SCALA_LIBRARY@}#@OTHER_SCALA_LIBRARY@#g" \
        -e 's/-git-[0-9a-f]\{7\}/-git-@HASH@/g' "$f" > "$f.norm"
      if [ -n "$UPDATE" ]; then cp "$f.norm" "$dir/expected/$i.$s"
      elif ! diff -u "$dir/expected/$i.$s" "$f.norm" > "$work/$i.$s.diff"; then
        echo "  step $i: $s differs (- expected, + snc):"; sed 's/^/    /' "$work/$i.$s.diff" | head -40; ok=1
      fi
    done
  done
  if [ -f "$dir/check.sh" ]; then
    (cd "$work/snc" && WORK_DIR=$work bash "$dir/check.sh") || ok=1
  fi
  report "$name" $ok
}

cases=("$@")
if [ ${#cases[@]} -eq 0 ]; then
  cases=()
  for d in "$here"/cases/*/; do cases+=("$(basename "$d")"); done
fi
echo "snc: $SNC"
echo "work: $WORK"
for c in "${cases[@]}"; do
  if ! missing=$(requirements_met "$here/cases/$c"); then
    echo "SKIP $c (set $missing)"; skip=$((skip + 1)); continue
  fi
  mode=compat
  [ -f "$here/cases/$c/mode" ] && mode=$(cat "$here/cases/$c/mode")
  case "$mode" in
    compat) run_compat "$c" ;;
    snc) run_snc "$c" ;;
    *) echo "unknown mode $mode for $c"; fail=$((fail + 1)) ;;
  esac
done
skipped=; [ "$skip" -gt 0 ] && skipped=", $skip skipped"
echo "$pass passed, $fail failed${failed:+:$failed}$skipped"
[ "$fail" = 0 ]
