# --about prints one JSON object with the fields build tools check
out=$WORK_DIR/snc.1.out
python3 -I -c '
import json, sys
a = json.load(open(sys.argv[1]))
for k in ["name", "scalaVersion", "scalalibVersion", "compilerVersion", "versionMessage", "scalaNativeVersion", "nirVersion",
          "tastyVersion", "backends", "defaultBackend", "plugins", "eventsProtocol",
          "executable"]:
    assert k in a, k
assert a["name"] == "snc" and a["backends"] == ["native"] and a["eventsProtocol"] == "1.0"
assert sorted((p["name"], p["enabledByDefault"]) for p in a["plugins"]) == [("scalanative", True), ("scalanative-junit", False)], a["plugins"]
assert a["scalalibVersion"] == a["scalaVersion"] + "+" + a["scalaNativeVersion"]
assert a["scalaVersion"].endswith("-NIGHTLY"), a["scalaVersion"]
' "$out"
