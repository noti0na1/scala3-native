#!/bin/bash
# The host test (README.md, "Testing"): a host snc compiles
# macros from Scala source, links them as an extension against its own
# manifest itself (--link-extension, no snlink), loads the extension
# (--load-extension) and expands the macros with the extension's code. Then
# its macro executor does the same by itself: from the class path, from its
# cache, and for macros defined in the same run.
#
#   SNC=<host snc> WORK=<dir> run.sh [<extension mode: debug|release-fast>]
#
# The compile class path is the NIR and TASTy the host was linked from, as
# <snc>.classpath lists it. Each compile runs with the default GC and under
# GC_INITIAL_HEAP_SIZE=1m, which makes the collector run often while the
# extension's objects are live.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
snc=${SNC:?set SNC to a host snc}
work=${WORK:?set WORK to an output directory}
mode=${1:-}
cp=$(paste -sd: "$snc.classpath")
rm -rf "$work"; mkdir -p "$work/macros" "$work/use" "$work/fail"
fails=0
check() { # check <log> <pattern>...
  local log=$1; shift
  for pattern in "$@"; do
    if grep -qF -- "$pattern" "$log"; then echo "  ok: $pattern"
    else echo "  MISSING: $pattern"; fails=$((fails + 1)); fi
  done
}

echo "== compile the macros"
"$snc" -color:never -d "$work/macros" -classpath "$cp" "$here"/macros/*.scala > "$work/macros.log" 2>&1 \
  || { cat "$work/macros.log"; exit 1; }

echo "== link them against this snc"
link=(--link-extension "$work/ext" --name hosttest)
[ "$mode" = debug ] && link+=(--debug)
bundle=$("$snc" "${link[@]}" "$work/macros" 2> "$work/link.log") || { cat "$work/link.log"; exit 1; }
cat "$work/link.log"
echo "  bundle: $bundle ($(wc -c < "$bundle" | tr -d ' ') bytes)"

for gc in default small-heap; do
  if [ $gc = small-heap ]; then export GC_INITIAL_HEAP_SIZE=1m
  else unset GC_INITIAL_HEAP_SIZE; fi
  echo "== expand with the extension loaded (GC: $gc)"
  SNC_LOAD_TIMINGS=1 "$snc" --load-extension "$bundle" 'snchost.Entry$' -color:never \
    -d "$work/use" -classpath "$cp:$work/macros" "$here/use/Use.scala" > "$work/use-$gc.log" 2>&1
  echo "  exit $?"
  check "$work/use-$gc.log" \
    "extension: registered 6 macro entries in snchost.Entry\$.apply" \
    'describe: tree: "ab".+(1) | Apply(Select(Literal(StringConstant("ab")), "+"), List(Literal(IntConstant(1))))' \
    "nodes | sorted: List(ccc, bb, a) | permutations: 123,132,213,231,312,321 | powers: 1023 | Macro | caught java.util.NoSuchElementException" \
    "times10: args.length.*(3).+(4) becomes args.length.*(30).+(40)"
  grep "snc: loaded" "$work/use-$gc.log" | sed 's/^/  /'

  echo "== expansions that fail in the extension (GC: $gc)"
  "$snc" --load-extension "$bundle" 'snchost.Entry$' -color:never \
    -d "$work/fail" -classpath "$cp:$work/macros" "$here/fail/Fail.scala" > "$work/fail-$gc.log" 2>&1
  status=$?
  echo "  exit $status (1 expected)"
  [ $status = 1 ] || fails=$((fails + 1))
  check "$work/fail-$gc.log" \
    "Exception occurred while executing macro expansion." \
    "snchost.MacroBoom: boom from the extension" \
    "aborted by the extension" \
    "snchost.MacroBoom: boom from +++" "3 errors found"
  # The extension is optimized, which may inline the implementations into
  # its entry table: the traces end before the compiler's frames
  if grep -q "at dotty.tools.dotc.typer" "$work/fail-$gc.log"; then
    echo "  UNEXPECTED: the compiler's frames in a macro's trace"; fails=$((fails + 1))
  else echo "  ok: no compiler frames in the traces"; fi
done
unset GC_INITIAL_HEAP_SIZE

# The macro executor: no --load-extension; the compiler links the macros'
# directory itself when an expansion first needs it, then takes it from its
# cache
export XDG_CACHE_HOME=$work/cache
mkdir -p "$work/use-exec" "$work/fail-exec"
for round in first cached; do
  echo "== expand through the macro executor ($round use)"
  SNC_MACRO_LOG="$work/exec-$round.timings" "$snc" -color:never -d "$work/use-exec" \
    -classpath "$cp:$work/macros" "$here/use/Use.scala" > "$work/use-exec-$round.log" 2>&1
  echo "  exit $?"
  check "$work/use-exec-$round.log" \
    'describe: tree: "ab".+(1) | Apply(Select(Literal(StringConstant("ab")), "+"), List(Literal(IntConstant(1))))' \
    "times10: args.length.*(3).+(4) becomes args.length.*(30).+(40)"
  sed 's/^/  /' "$work/exec-$round.timings"
done
check "$work/exec-first.timings" $'\tlinked\t'
check "$work/exec-cached.timings" $'\tcached\t'

# Without the extension's static storage as roots, this crashes or fails (a
# host built with that registration switched off was checked to)
echo "== state of the extension's module across expansions and collections (GC_MAXIMUM_HEAP_SIZE=512m)"
mkdir -p "$work/state"
GC_MAXIMUM_HEAP_SIZE=512m "$snc" -color:never -d "$work/state" -classpath "$cp:$work/macros" \
  "$here/state/State.scala" > "$work/state.log" 2>&1
echo "  exit $?"
check "$work/state.log" "count: 1: a" "count: 2: a,b" "count: 3: a,b,c" "count: 4: a,b,c,d"

echo "== expansions that fail, through the macro executor"
"$snc" -color:never -d "$work/fail-exec" -classpath "$cp:$work/macros" "$here/fail/Fail.scala" > "$work/fail-exec.log" 2>&1
status=$?
echo "  exit $status (1 expected)"
[ $status = 1 ] || fails=$((fails + 1))
check "$work/fail-exec.log" \
  "Exception occurred while executing macro expansion." \
  "snchost.MacroBoom: boom from the extension" \
  "aborted by the extension" \
  "snchost.MacroBoom: boom from +++" "at snchost.Macros\$.\$plus\$plus\$plus(" \
  "3 errors found"

echo "== a cached bundle is linked again when a class it lacked is there, or another entry's class comes first"
# Without helper1, Helper is missing and the macro's code is a stub; with it,
# the bundle must not be reused. The bundle takes Helper from helper1; with
# helper2, which has another Helper, before it on the class path, it must not
# be reused either
mkdir -p "$work/helper1" "$work/helper2" "$work/shadow" "$work/shadow-use"
sed 's/def value: Int = 1/def value: Int = 2/' "$here/shadow/Helper.scala" > "$work/Helper2.scala"
"$snc" -color:never -d "$work/helper1" -classpath "$cp" "$here/shadow/Helper.scala" > "$work/helpers.log" 2>&1 &&
  "$snc" -color:never -d "$work/helper2" -classpath "$cp" "$work/Helper2.scala" >> "$work/helpers.log" 2>&1 &&
  "$snc" -color:never -d "$work/shadow" -classpath "$cp:$work/helper1" "$here/shadow/ShadowMacro.scala" >> "$work/helpers.log" 2>&1 \
  || { cat "$work/helpers.log"; exit 1; }
for round in missing first cached shadowed; do
  shadow_cp="$cp:$work/shadow:$work/helper1"
  [ $round = missing ] && shadow_cp="$cp:$work/shadow"
  [ $round = shadowed ] && shadow_cp="$cp:$work/shadow:$work/helper2:$work/helper1"
  SNC_MACRO_LOG="$work/shadow-$round.timings" "$snc" -color:never -d "$work/shadow-use" \
    -classpath "$shadow_cp" "$here/shadow/ShadowUse.scala" > "$work/shadow-$round.log" 2>&1
  echo "  $round: exit $?, $(grep -o 'shadow: .*' "$work/shadow-$round.log"), $(cut -f2 "$work/shadow-$round.timings" 2>/dev/null | tr '\n' ' ')"
done
check "$work/shadow-missing.log" "it needs snchost.shadow.Helper"
check "$work/shadow-first.log" "shadow: Helper.value is 1"
check "$work/shadow-first.timings" $'\tlinked\t'
check "$work/shadow-cached.log" "shadow: Helper.value is 1"
check "$work/shadow-shadowed.log" "shadow: Helper.value is 2"
check "$work/shadow-cached.timings" $'\tcached\t'
check "$work/shadow-shadowed.timings" $'\tlinked\t'

echo "== macros defined in the same run (the use waits for the run that compiles them)"
mkdir -p "$work/same"
"$snc" -color:never -d "$work/same" -classpath "$cp" "$here"/macros/*.scala "$here/use/Use.scala" > "$work/same.log" 2>&1
status=$?
echo "  exit $status (0 expected)"
[ $status = 0 ] || fails=$((fails + 1))
[ -f "$work/same/Use.nir" ] && [ -f "$work/same/Use.tasty" ] || { echo "  MISSING: same/Use.nir, same/Use.tasty"; fails=$((fails + 1)); }
check "$work/same.log" \
  'describe: tree: "ab".+(1) | Apply(Select(Literal(StringConstant("ab")), "+"), List(Literal(IntConstant(1))))' \
  "times10: args.length.*(3).+(4) becomes args.length.*(30).+(40)"
unset XDG_CACHE_HOME

if [ -n "${SNLINK:-}" ]; then
  echo "== link the expanded program with snlink and run it"
  # shellcheck disable=SC2086
  $SNLINK --main Use --base-dir "$work/prog" "$work/use" $(paste -sd' ' "$snc.classpath") > "$work/prog.log" 2>&1 \
    || { tail -20 "$work/prog.log"; exit 1; }
  "$work/prog/out" > "$work/run.log" 2>&1
  check "$work/run.log" 'tree: "ab".+(1)' "40"
fi

if [ $fails = 0 ]; then echo "PASS"; else echo "FAIL: $fails checks"; exit 1; fi
