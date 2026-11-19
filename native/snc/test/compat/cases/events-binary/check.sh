# The binary stream carries the same events as the text one
python3 -I -c '
import json, struct, sys
data = open(sys.argv[1], "rb").read()
assert data[:4] == b"SNCE", data[:4]
major, minor = struct.unpack(">HH", data[4:8])
assert (major, minor) == (1, 1), (major, minor)
i, events = 8, []
while i < len(data):
    (n,) = struct.unpack(">I", data[i:i + 4])
    kind, payload = data[i + 4], data[i + 5:i + 4 + n]
    assert kind == 1, kind
    events.append(json.loads(payload))
    i += 4 + n
text = [json.loads(l) for l in open(sys.argv[2])]
assert events == text, (events, text)
assert events[0]["event"] == "start" and events[-1] == {"event": "done", "exitCode": 1, "errors": 3, "warnings": 0}
' "$WORK_DIR/snc.1.out" "$WORK_DIR/snc.2.out"
