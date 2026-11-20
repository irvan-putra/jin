#!/bin/sh
# Capture-route gate on a FRESH emulator (see ui-smoke.yml, route-gate job).
# The route must start its fixture services immediately after boot, while
# the freshly launched app is foreground: mid-run service starts after a
# force-stop are denied as background foreground-service starts on API 34
# ("app is in background uid null"), which is why this is its own job and
# not a late phase of the joint transport gate. Synthetic fixture content
# only; no account, no network, no relay, no private data.
set -e
JIN=com.vanipy.jin

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Served-chat state as an accepted chat would have produced, plus the
# owner-only pairing request the drawer dialog writes.
printf '%s' '{"provisioned":true,"bot_addr":"bot-fixture@fixture.invalid","acct_gen":1,"chats":{"11":{}}}' \
  | adb shell "run-as $JIN sh -c 'cat > files/bot_state.json'"
printf '%s' 'bidik-fixture@fixture.invalid' | adb shell "run-as $JIN sh -c 'cat > files/PAIR_BIDIK_REQ'"

adb shell am start -W -n $JIN/.MainActivity >/dev/null
sleep 2
adb shell am startservice -n $JIN/.BotService --es mode pairbidik
sleep 4
adb shell run-as $JIN cat files/bot_state.json | grep -q "bidik-fixture@fixture.invalid" || {
  echo "route gate: pairbidik did not store the peer" >&2; exit 1; }
echo "route gate: pairing stored"
adb shell am startservice -n $JIN/.BotService --es mode routefixture
sleep 2

python3 - <<'GATEPY'
import json
spec = {"chat_id": 11, "text": "capture https://example.com/",
        "from": "user@fixture.invalid", "from_id": 2, "msg_id": 3001}
open("/tmp/route_a.json", "w").write(json.dumps(spec))
GATEPY
adb shell "run-as $JIN sh -c 'cat > files/FIXTURE_ROUTE_a'" < /tmp/route_a.json
i=0
while [ $i -lt 60 ]; do
  if adb shell run-as $JIN cat files/fixture-sends.jsonl 2>/dev/null | grep -q "capture-queue"; then break; fi
  sleep 2; i=$((i+1))
done
adb shell run-as $JIN cat files/fixture-sends.jsonl 2>/dev/null | grep "capture-queue" | head -1 > /tmp/route_q.jsonl
QID=$(python3 -c "import json;print(json.loads(json.loads(open('/tmp/route_q.jsonl').read())['text'])['queue_id'])")
[ -n "$QID" ] || { echo "route gate: no queue doc left for the peer" >&2; exit 1; }
QID="$QID" python3 - <<'GATEPY'
import json, os
qid = os.environ["QID"]
result = {"type": "capture-result", "queue_id": qid, "page_id": "p1",
          "captures": ["a.png"], "status": "ok", "load_ms": 500}
specs = {
    "b": {"chat_id": 77, "text": json.dumps(result),
          "from": "bidik-fixture@fixture.invalid", "from_id": 3, "msg_id": 3002},
    "c": {"chat_id": 77, "text": "capture-result-file:%s:p1:a.png" % qid,
          "from": "bidik-fixture@fixture.invalid", "from_id": 3,
          "msg_id": 3003, "file": "/fixture/a.png"},
    "d": {"chat_id": 77, "text": "capture-result-file:oldqueue:p1:a.png",
          "from": "bidik-fixture@fixture.invalid", "from_id": 3,
          "msg_id": 3004, "file": "/fixture/stale.png"},
}
for k, v in specs.items():
    open("/tmp/route_%s.json" % k, "w").write(json.dumps(v))
GATEPY
for k in b c d; do
  adb shell "run-as $JIN sh -c 'cat > files/FIXTURE_ROUTE_$k'" < /tmp/route_$k.json
done
i=0
while [ $i -lt 60 ]; do
  if adb shell run-as $JIN cat files/fixture-sends.jsonl 2>/dev/null | grep -q "p1: ok"; then break; fi
  sleep 2; i=$((i+1))
done
RSENDS=$(adb shell run-as $JIN cat files/fixture-sends.jsonl)
echo "$RSENDS"
[ "$(echo "$RSENDS" | grep -c 'p1: ok')" = 1 ] || {
  echo "route gate: result status did not route to the requester" >&2; exit 1; }
[ "$(echo "$RSENDS" | grep -c 'capture-result-file')" = 1 ] || {
  echo "route gate: expected exactly one forwarded file (stale one refused)" >&2; exit 1; }
echo "$RSENDS" | grep -q '/fixture/stale.png' && {
  echo "route gate: stale queue file was forwarded" >&2; exit 1; }
echo "$RSENDS" | grep -q "queued $QID" || {
  echo "route gate: capture ack missing in served chat" >&2; exit 1; }
echo "route gate: PASS"
