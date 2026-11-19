# The sbt phases of snc extract what scalac's do: the .inc files that
# -Ydump-sbt-inc writes (used names and class dependencies; Inlining writes
# them after sbt-api has appended the API, which is lost, with scalac too) are
# the same, up to the order of hash sets, which differs between runs of either
# compiler. Then the same sources with --analysis: the events are complete and
# consistent, and dependencies on the JDK's classes are marked as such, with
# the JDK's own classes and, given OTHER_JDK, with -release (from ct.sym).
python3 -I - "$WORK_DIR" <<'PY'
import difflib, re, sys
work = sys.argv[1]
enums = ["LocalDependencyByInheritance", "DependencyByInheritance", "DependencyByMemberRef",
         "DependencyByMacroExpansion", "PatMatTarget", "Implicit", "Default"]
def split_enums(s):
    found = []
    while s:
        e = next((e for e in enums if s.startswith(e)), None)
        if e is None: return [s]
        found.append(e); s = s[len(e):]
    return sorted(found)
entry = re.compile(r"(.+?) in \[([A-Za-z]*)\], ")
def entries(line):
    # "<class>: <name> in [<scopes>], <name> in [<scopes>], ...", from hash sets
    m = re.match(r"^(.+?): ((?:.+? in \[[A-Za-z]*\], )*)$", line)
    if not m: return None
    items = sorted(f"{n} in [{''.join(split_enums(c))}]" for n, c in entry.findall(m.group(2)))
    return m.group(1) + ": " + ", ".join(items)
def canonical(text):
    names, rest = text.split("\nDependencies:\n=============\n")
    head, names = names.split("===========\n")
    rest = rest.splitlines()
    deps = []
    while rest and entries(rest[0]) is not None:
        deps.append(entries(rest.pop(0)))
    names = sorted(entries(l) or l for l in names.splitlines())
    return [head] + names + ["Dependencies:"] + sorted(deps) + rest
ok = True
for name in ["A.inc", "B.inc"]:
    snc = canonical(open(f"{work}/snc/{name}").read())
    jvm = canonical(open(f"{work}/jvm/{name}").read())
    if snc != jvm:
        ok = False
        print(f"  {name} differs:")
        for l in list(difflib.unified_diff(jvm, snc, "scalac", "snc", lineterm=""))[:40]:
            print("    " + l)
    elif snc.index("Dependencies:") < 4 or len(snc) - snc.index("Dependencies:") < 4:
        ok = False
        print(f"  {name} has no names or dependencies")
sys.exit(0 if ok else 1)
PY
[ $? = 0 ] || exit 1

mkdir -p analysis
"$SNC" --events-text --analysis -color:never -d analysis -classpath "$NATIVE_CP" A.scala B.scala > analysis.out 2> analysis.err || {
  echo "  snc --analysis failed:"; cat analysis.err; exit 1; }
python3 -I - analysis.out "$(pwd -P)" "$JAVA_HOME" <<'PY'
import json, os, sys
events = [json.loads(l) for l in open(sys.argv[1])]
cwd = sys.argv[2]
java_home = os.path.realpath(sys.argv[3])
kinds = [e["event"] for e in events]
assert events[0] == {"event": "start", "protocol": "1.1", "compilerVersion": events[0]["compilerVersion"]}, events[0]
assert events[-1] == {"event": "done", "exitCode": 0, "errors": 0, "warnings": 0}, events[-1]
# Only with -Yearly-tasty-output (pipelining), as with scalac
assert "apiPhaseCompleted" not in kinds and "dependencyPhaseCompleted" not in kinds, kinds
assert [e["source"] for e in events if e["event"] == "startSource"] == [0, 1]
def of(kind): return [e for e in events if e["event"] == kind]
for e in of("generatedNonLocalClass") + of("generatedLocalClass"):
    assert e["file"].startswith(cwd + "/analysis/") and e["file"].endswith(".nir"), e
    assert os.path.isfile(e["file"]), e
products = {e["binaryName"]: e for e in of("generatedNonLocalClass")}
for name in ["a.Shape", "a.Lit", "a.Lit$", "a.Zero", "a.Zero$", "a.Geometry", "a.Geometry$", "a.Outer",
             "a.Outer$Inner", "a.hello", "a.Main", "a.Main$", "b.Square", "b.Eval", "b.Eval$", "b.Visitor"]:
    assert name in products, (name, sorted(products))
assert products["b.Square"]["source"] == 1 and products["a.Shape"]["source"] == 0
apis = {e["classLike"][1]: e for e in of("api")}
for name in ["a.Shape", "a.Expr", "a.Lit", "a.Geometry", "a.Outer", "a.Outer.Inner", "b.Square", "b.Eval"]:
    assert name in apis, (name, sorted(apis))
def tags(node, found):
    if isinstance(node, list):
        if node and isinstance(node[0], str): found.add(node[0])
        for x in node: tags(x, found)
    return found
found = tags([e["classLike"] for e in of("api")], set())
for t in ["CL", "S", "Def", "Val", "Var", "TA", "TD", "CLD", "P", "Z", "R", "Poly", "C", "Y", "A", "E", "pro", "pri"]:
    assert t in found, (t, sorted(found))
shape = apis["a.Shape"]["classLike"]
assert shape[5] == "trait" and shape[10] is True and shape[11][0][3] == "co", shape
assert sorted(e["name"] for e in of("mainClass")) == ["a.Main", "a.hello"], of("mainClass")
deps = {(e["from"], e["on"], e["context"]) for e in of("classDependency")}
assert ("b.Square", "a.Shape", "DependencyByInheritance") in deps, deps
assert ("b.Eval", "a.Lit", "DependencyByMemberRef") in deps, deps
names = {(e["className"], e["name"]): e["scopes"] for e in of("usedName")}
assert names[("b.Eval", "Expr")] == ["Default", "PatMatTarget"], names[("b.Eval", "Expr")]
lib = [e for e in of("binaryDependency") if e["binaryName"] == "scala.collection.immutable.List"]
assert lib and all(e["entry"].endswith(".jar") and "jdk" not in e for e in lib), lib
jdk = [e for e in of("binaryDependency") if e["binaryName"] == "java.lang.Object"]
assert jdk and all(e.get("jdk") is True for e in jdk), jdk
# The JDK's own classes come from its jmods/, or from ct.sym when it has none.
jmod = os.path.join(java_home, "jmods", "java.base.jmod")
archive = jmod if os.path.isfile(jmod) else os.path.join(java_home, "lib", "ct.sym")
assert all(os.path.realpath(e["entry"]) == archive for e in jdk), (archive, jdk)
assert all(e.get("jdk") is True for e in of("binaryDependency") if e["binaryName"].startswith("java.")), of("binaryDependency")
assert not any("jdk" in e for e in of("binaryDependency") if not e["binaryName"].startswith("java.")), of("binaryDependency")
PY
[ $? = 0 ] || exit 1

# With a newer JDK and -release 17, the JDK's classes come from its ct.sym.
if [ -n "${OTHER_JDK:-}" ]; then
  mkdir -p analysis-release
  "$SNC" --events-text --analysis -color:never --java-home "$OTHER_JDK" -release 17 -d analysis-release \
    -classpath "$NATIVE_CP" A.scala B.scala > analysis-release.out 2> analysis-release.err || {
    echo "  snc --analysis -release 17 failed:"; cat analysis-release.out analysis-release.err; exit 1; }
  python3 -I - analysis-release.out "$OTHER_JDK" <<'PY'
import json, os, sys
events = [json.loads(l) for l in open(sys.argv[1])]
java_home = os.path.realpath(sys.argv[2])
assert events[-1]["event"] == "done" and events[-1]["exitCode"] == 0, events[-1]
jdk = [e for e in events if e["event"] == "binaryDependency" and e["binaryName"] == "java.lang.Object"]
assert jdk and all(e.get("jdk") is True for e in jdk), jdk
assert all(os.path.realpath(e["entry"]) == os.path.join(java_home, "lib", "ct.sym") for e in jdk), jdk
PY
fi
