# Module B, compiled against module A's TASTy and NIR, links with snlink and runs
if [ -z "${SNLINK:-}" ]; then echo "  (SNLINK not set: not linked)"; exit 0; fi
set -e
"$SNLINK" --main b.Main --base-dir link --module main "$PWD/a" "$PWD/b" $(echo "${LINK_CP:-$NATIVE_CP}" | tr ":" " ") > link.log 2>&1 \
  || { tail -20 link.log; exit 1; }
./link/main > run.out
diff -u - run.out <<'EXPECTED'
Circle with area 3.141592653589793
Square with area 4.0
Red,Green,Blue
3.5
51
n=42
1.0 2.0 3.0
21
3
5.0
EXPECTED
