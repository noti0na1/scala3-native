#!/usr/bin/env python3
"""Runs the separately compiled tests of tests/run-macros with the macro test
rig: snc linked with the tests' macro implementations (see README.md).

For each test directory whose files fall into several groups (`_1`, `_2`, ...
suffixes, as in vulpix), every group but the last defines macros: the JVM
compiler compiles it (with nscplugin, against the JDK it runs on), and the rig
links its NIR. The rig then compiles the last group, `snlink` links all
groups, and the program's output (stdout and stderr) must match the check
file, as in vulpix's run tests.

  run-macros.py prepare [tests...]   compile the macro groups with the JVM compiler
  run-macros.py rig                  generate the rig's glue and link the rig
  run-macros.py run [tests...]       compile the last groups with the rig, link, run
  run-macros.py exec [tests...]      the same with snc-host ($SNC), which links and
                                     loads the macro groups' code itself
  run-macros.py baseline [tests...]  the same with the JVM compiler instead of the rig
  run-macros.py joint [tests...]     compile the joint tests (same-run macros) with the rig
  run-macros.py joint-exec [tests...]  compile the joint tests with snc-host, link, run
  run-macros.py report               summarize results

Configuration, from the environment:
  WORK          work directory
  TESTS_DIR     tests/run-macros of the Scala 3 repository
  JAVA_HOME     a JDK 17: the JVM compiler runs on it, and snc reads its Java API
  JAVA          java for the JVM compiler (default: $JAVA_HOME/bin/java)
  JVM_CP_FILE   class path of the JVM compiler (scala3-compiler-nonbootstrapped)
  NSCPLUGIN     nscplugin jar for the compiler's Scala version
  USER_CP_FILE  class path for compiling: Scala Native's libraries and scala-library
  LINK_CP_FILE  USER_CP plus the NIR of the Scala library, for linking
  SNC_CP_FILE   snc's link class path
  SNLINK, RIG_GEN, LINK_RIG (link-rig.sh)
  SNC           snc-host, for exec and joint-exec
  RIG_MODE      snlink mode for the rig (default release-fast)
  RIG_EXTRA_MANIFEST, RIG_STUBS, RIG_EXTRA_CP
                more rig-gen scopes (macro libraries), stubs, and the class path
                they link with
  JOBS          parallel jobs (default 4)
"""

import concurrent.futures
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ENV = dict(os.environ)
ENV.setdefault("JAVA", os.path.join(ENV["JAVA_HOME"], "bin", "java"))
WORK = Path(ENV.get("WORK", "work")).resolve()
TESTS_DIR = Path(ENV["TESTS_DIR"]).resolve() if "TESTS_DIR" in ENV else None
JOBS = int(ENV.get("JOBS", "4"))

DEFAULT_OPTIONS = [
    "-indent", "-Yno-deep-subtypes", "-Yno-double-bindings", "-Xcheck-macros",
    "-Wconf:id=E222:s", "-pagewidth", "120", "-color:never",
]


def read(name):
    return Path(ENV[name]).read_text().strip()


def scope_name(test):
    return re.sub(r"[^A-Za-z0-9]", "_", test)


DIRECTIVE = re.compile(r"^\s*//>\s*using\s+options?\s+(.*)$")


def directive_options(files):
    opts = []
    for f in files:
        for line in f.read_text(errors="replace").splitlines():
            m = DIRECTIVE.match(line)
            if m:
                opts += [o.strip('"') for o in m.group(1).split()]
    return opts


def groups(test_dir):
    """The compilation groups of a test directory, in order (as vulpix)."""
    by_group = {}
    for f in sorted(test_dir.iterdir()):
        if f.suffix not in (".scala", ".java"):
            continue
        parts = f.stem.split("_")[1:] if "_" in f.stem else []
        ordinal = next((int(p) for p in parts if p.isdigit()), -(2 ** 31))
        compiler = next((p[1:] for p in parts if re.fullmatch(r"c[\d.]+", p)), "")
        by_group.setdefault((ordinal, compiler), []).append(f)
    return [by_group[k] for k in sorted(by_group)]


def discover(only=None):
    """Tests as (name, kind, groups): kind is 'separate', 'joint' or 'java'."""
    tests = []
    for entry in sorted(TESTS_DIR.iterdir()):
        name = entry.stem if entry.is_file() else entry.name
        if only and not any(re.search(o, name) for o in only):
            continue
        if entry.is_file():
            if entry.suffix == ".scala":
                tests.append((name, "joint", [[entry]]))
            continue
        gs = groups(entry)
        if not gs:
            continue
        files = [f for g in gs for f in g]
        if any(f.suffix == ".java" for f in files):
            kind = "java"
        elif len(gs) < 2:
            kind = "joint"
        else:
            kind = "separate"
        tests.append((name, kind, gs))
    return tests


def rig_excluded():
    """Tests left out of the rig (rig-exclude.txt), with the reason."""
    f = Path(__file__).resolve().parent / "rig-exclude.txt"
    result = {}
    for line in f.read_text().splitlines() if f.exists() else []:
        if line.strip() and not line.startswith("#"):
            name, _, reason = line.partition("\t")
            result[name.strip()] = reason.strip()
    return result


def check_file(test):
    f = TESTS_DIR / (test + ".check")
    return f if f.exists() else None


def run(cmd, cwd=None, env=None, timeout=600):
    start = time.time()
    try:
        p = subprocess.run(cmd, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=timeout)
        return p.returncode, p.stdout.decode("utf-8", "replace"), time.time() - start
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or b"").decode("utf-8", "replace")
        return "timeout", out, time.time() - start


def jvm_compile(files, out, classpath):
    out.mkdir(parents=True, exist_ok=True)
    cmd = [ENV["JAVA"], "-Xss8m", "-Xmx2g", "-cp", read("JVM_CP_FILE"), "dotty.tools.dotc.Main",
           "-Xplugin:" + ENV["NSCPLUGIN"],
           "-classpath", classpath, "-d", str(out)] + DEFAULT_OPTIONS + directive_options(files) + \
          [str(f) for f in files]
    return run(cmd)


def test_work(test):
    return WORK / "tests" / test


def macro_dirs(test, gs):
    return [test_work(test) / f"g{i}" for i in range(len(gs) - 1)]


def save(test, key, result):
    d = test_work(test)
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{key}.json").write_text(json.dumps(result, indent=1))


def load(test, key):
    f = test_work(test) / f"{key}.json"
    return json.loads(f.read_text()) if f.exists() else None


def prepare_one(test, gs):
    user_cp = read("USER_CP_FILE")
    cp = user_cp
    for i, files in enumerate(gs[:-1]):
        out = test_work(test) / f"g{i}"
        shutil.rmtree(out, ignore_errors=True)
        code, log, secs = jvm_compile(files, out, cp)
        (test_work(test) / f"g{i}.log").write_text(log)
        if code != 0:
            result = {"status": "prepare-failed", "group": i, "log": log[-4000:]}
            save(test, "prepare", result)
            return test, result
        cp = cp + ":" + str(out)
    result = {"status": "ok"}
    save(test, "prepare", result)
    return test, result


def cmd_prepare(only):
    tests = [t for t in discover(only) if t[1] == "separate"]
    with concurrent.futures.ThreadPoolExecutor(JOBS) as pool:
        for test, result in pool.map(lambda t: prepare_one(t[0], t[2]), tests):
            print(f"{result['status']:15} {test}", flush=True)


def cmd_rig():
    tests = [t for t in discover() if t[1] == "separate"]
    rig = WORK / "rig"
    rig.mkdir(parents=True, exist_ok=True)
    lines = []
    excluded = rig_excluded()
    for name, _, gs in tests:
        p = load(name, "prepare")
        if not p or p["status"] != "ok" or name in excluded:
            continue
        dirs = [str(d) for d in macro_dirs(name, gs)]
        scope = scope_name(name)
        lines.append(f"{scope}\tsnc_rig.{scope}\t{':'.join(dirs)}")
    # More scopes (macro libraries), stubs and link class path, if given
    if ENV.get("RIG_EXTRA_MANIFEST"):
        lines += [l for l in Path(ENV["RIG_EXTRA_MANIFEST"]).read_text().splitlines() if l.strip()]
    (rig / "manifest.tsv").write_text("\n".join(lines) + "\n")
    shutil.rmtree(rig / "gen", ignore_errors=True)
    stubs = [ENV["RIG_STUBS"]] if ENV.get("RIG_STUBS") else []
    code, log, secs = run([ENV["RIG_GEN"], str(rig / "gen"), str(rig / "manifest.tsv")] + stubs, timeout=3600)
    print(log)
    if code != 0:
        sys.exit(f"rig-gen failed: {code}")
    print(f"rig-gen: {secs:.1f} s, {len(lines)} scopes")
    mode = ENV.get("RIG_MODE", "release-fast")
    extra = [ENV["RIG_EXTRA_CP"]] if ENV.get("RIG_EXTRA_CP") else []
    code, log, secs = run([ENV["LINK_RIG"], str(rig / "gen"), str(rig / "link"), mode] + extra, timeout=7200)
    (rig / "link.log").write_text(log)
    print(log[-3000:])
    print(f"link: {code} in {secs:.1f} s")


def link_and_run(test, dirs, base):
    link_cp = read("LINK_CP_FILE")
    shutil.rmtree(base, ignore_errors=True)
    code, log, secs = run([ENV["SNLINK"], "--main", "Test", "--base-dir", str(base), "--mode", "debug"] +
                          [str(d) for d in dirs] + link_cp.split(":"), timeout=1200)
    binary = base / "out"
    if code != 0 or not binary.exists():
        return {"status": "link-failed", "log": log[-4000:], "link_secs": secs}
    code, out, rsecs = run([str(binary)], cwd=str(test_work(test)), timeout=120)
    shutil.rmtree(base / "native", ignore_errors=True)
    result = {"link_secs": secs, "run_secs": rsecs, "exit": code}
    if code != 0:
        result.update(status="run-failed", output=out[-4000:])
        return result
    cf = check_file(test)
    if cf:
        expected = [l.rstrip("\r\n") for l in cf.read_text(errors="replace").splitlines()]
        actual = [l.rstrip("\r\n") for l in out.splitlines()]
        if expected != actual:
            (test_work(test) / "actual.check").write_text(out)
            result.update(status="wrong-output", output=out[-4000:])
            return result
    result["status"] = "pass"
    return result


def last_group_one(test, gs, mode, worker):
    p = load(test, "prepare")
    if not p or p["status"] != "ok":
        return test, {"status": "prepare-failed"}
    if mode == "rig" and test in rig_excluded():
        result = {"status": "rig-excluded", "reason": rig_excluded()[test]}
        save(test, mode, result)
        return test, result
    dirs = macro_dirs(test, gs)
    cp = read("USER_CP_FILE") + "".join(":" + str(d) for d in dirs)
    out = test_work(test) / f"{mode}-out"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    files = gs[-1]
    if mode == "rig":
        env = dict(ENV, SNC_MACRO_SCOPES=scope_name(test))
        cmd = [str(WORK / "rig" / "link" / "snc-rig"), "-scalanative", "-classpath", cp, "-d", str(out)] + \
              DEFAULT_OPTIONS + directive_options(files) + [str(f) for f in files]
        code, log, secs = run(cmd, env=env)
    elif mode == "exec":
        env = dict(ENV, SNC_MACRO_LOG=str(test_work(test) / "exec-macros.log"))
        (test_work(test) / "exec-macros.log").unlink(missing_ok=True)
        cmd = [ENV["SNC"], "-scalanative", "-classpath", cp, "-d", str(out)] + \
              DEFAULT_OPTIONS + directive_options(files) + [str(f) for f in files]
        code, log, secs = run(cmd, env=env)
    else:
        code, log, secs = jvm_compile(files, out, cp)
    (test_work(test) / f"{mode}-compile.log").write_text(log)
    if code != 0:
        result = {"status": "compile-failed", "compile_secs": secs, "log": log[-4000:]}
    else:
        result = link_and_run(test, dirs + [out], WORK / "link" / f"w{worker}")
        result["compile_secs"] = secs
        result["compile_log"] = log[-2000:]
    save(test, mode, result)
    return test, result


def cmd_last(mode, only):
    tests = [t for t in discover(only) if t[1] == "separate"]
    import threading
    local = threading.local()
    counter = iter(range(10 ** 6))

    def work(t):
        if not hasattr(local, "id"):
            local.id = next(counter)
        return last_group_one(t[0], t[2], mode, local.id)

    with concurrent.futures.ThreadPoolExecutor(JOBS) as pool:
        for test, result in pool.map(work, tests):
            print(f"{result['status']:15} {test}", flush=True)


def joint_one(test, gs):
    """Compiles a joint test (macros defined in the same run) with the rig."""
    files = [f for g in gs for f in g]
    out = test_work(test) / "joint-out"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    env = dict(ENV)
    cmd = [str(WORK / "rig" / "link" / "snc-rig"), "-scalanative", "-classpath", read("USER_CP_FILE"),
           "-d", str(out)] + DEFAULT_OPTIONS + directive_options(files) + [str(f) for f in files]
    code, log, secs = run(cmd, env=env)
    if code == 0:
        status = "compiled"
    elif "which this compilation run compiles" in log:
        status = "same-run-error"
    elif "defined in the same source file" in log:
        status = "same-file-error"
    else:
        status = "other-error"
    result = {"status": status, "exit": code, "log": log[-4000:]}
    save(test, "joint", result)
    return test, result


def cmd_joint(only):
    tests = [t for t in discover(only) if t[1] == "joint"]
    for t in tests:
        test, result = joint_one(t[0], t[2])
        print(f"{result['status']:15} {test}", flush=True)


def joint_exec_one(test, gs, worker):
    """Compiles a joint test with snc-host, which runs the macros it defines
    after their run, then links and runs it."""
    files = [f for g in gs for f in g]
    out = test_work(test) / "joint-exec-out"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    env = dict(ENV, SNC_MACRO_LOG=str(test_work(test) / "joint-exec-macros.log"))
    (test_work(test) / "joint-exec-macros.log").unlink(missing_ok=True)
    cmd = [ENV["SNC"], "-scalanative", "-classpath", read("USER_CP_FILE"),
           "-d", str(out)] + DEFAULT_OPTIONS + directive_options(files) + [str(f) for f in files]
    code, log, secs = run(cmd, env=env)
    (test_work(test) / "joint-exec-compile.log").write_text(log)
    if code != 0:
        result = {"status": "compile-failed", "compile_secs": secs, "log": log[-4000:]}
    else:
        result = link_and_run(test, [out], WORK / "link" / f"j{worker}")
        result["compile_secs"] = secs
    save(test, "joint-exec", result)
    return test, result


def cmd_joint_exec(only):
    tests = [t for t in discover(only) if t[1] == "joint"]
    import threading
    local = threading.local()
    counter = iter(range(10 ** 6))

    def work(t):
        if not hasattr(local, "id"):
            local.id = next(counter)
        return joint_exec_one(t[0], t[2], local.id)

    with concurrent.futures.ThreadPoolExecutor(JOBS) as pool:
        for test, result in pool.map(work, tests):
            print(f"{result['status']:15} {test}", flush=True)


def cmd_report():
    tests = discover()
    kinds = {}
    for name, kind, _ in tests:
        kinds.setdefault(kind, []).append(name)
    print("tests: " + ", ".join(f"{k} {len(v)}" for k, v in sorted(kinds.items())))
    for mode in ("rig", "exec", "baseline"):
        counts = {}
        for name in kinds.get("separate", []):
            r = load(name, mode)
            status = r["status"] if r else "not-run"
            counts.setdefault(status, []).append(name)
        total = len(kinds.get("separate", []))
        print(f"\n{mode}: " + ", ".join(f"{k} {len(v)}" for k, v in sorted(counts.items())) +
              f" (pass rate {100.0 * len(counts.get('pass', [])) / max(total, 1):.1f}% of {total})")
    # rig against baseline
    both = rig_only = base_only = 0
    for name in kinds.get("separate", []):
        r, b = load(name, "rig"), load(name, "baseline")
        rp = r is not None and r["status"] == "pass"
        bp = b is not None and b["status"] == "pass"
        both += rp and bp
        rig_only += rp and not bp
        base_only += bp and not rp
    print(f"\npass in both: {both}; rig only: {rig_only}; baseline only: {base_only}")
    # The executor against the rig
    both = exec_only = rig_only = 0
    for name in kinds.get("separate", []):
        e, r = load(name, "exec"), load(name, "rig")
        ep = e is not None and e["status"] == "pass"
        rp = r is not None and r["status"] == "pass"
        both += ep and rp
        exec_only += ep and not rp
        rig_only += rp and not ep
    print(f"exec and rig: {both}; exec only: {exec_only}; rig only: {rig_only}")
    counts = {}
    for name in kinds.get("joint", []):
        r = load(name, "joint-exec")
        counts.setdefault(r["status"] if r else "not-run", []).append(name)
    print("\njoint-exec: " + ", ".join(f"{k} {len(v)}" for k, v in sorted(counts.items())))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "prepare":
        cmd_prepare(args)
    elif cmd == "rig":
        cmd_rig()
    elif cmd == "run":
        cmd_last("rig", args)
    elif cmd == "exec":
        cmd_last("exec", args)
    elif cmd == "joint-exec":
        cmd_joint_exec(args)
    elif cmd == "baseline":
        cmd_last("baseline", args)
    elif cmd == "joint":
        cmd_joint(args)
    elif cmd == "report":
        cmd_report()
    elif cmd == "list":
        for name, kind, gs in discover(args):
            print(f"{kind:9} {name} {[len(g) for g in gs]}")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
