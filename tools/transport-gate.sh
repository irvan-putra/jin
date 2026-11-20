#!/bin/sh
# Joint transport gate for jin (see ui-smoke.yml, transport-gate job).
# Installs jin + the probe app on one emulator and drives the real cross-app
# boundary: binder auth (package + cert pin), default-off grants, real
# ParcelFileDescriptor pipe streaming with byte cap, bridge round trips
# against the offline fixture bot, and visible failure behavior. Synthetic
# fixture content only; no account, no network, no private data.
set -e
JIN=com.vanipy.jin
PROBE=com.vanipy.jinprobe
HANDLE=$(python3 -c "import base64;print(base64.urlsafe_b64encode(b'bot-fixture@fixture.invalid|11|g1').decode().rstrip('='))")

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r probe/build/outputs/apk/debug/probe-debug.apk

runprobe() { # $1 = scenario
  adb shell am force-stop $PROBE
  adb shell run-as $PROBE rm -f files/probe-$1.log 2>/dev/null || true
  adb shell am start -W -n $PROBE/.MainActivity --es scenario "$1" --es handle "$HANDLE" >/dev/null
  i=0
  while [ $i -lt 120 ]; do
    if adb shell run-as $PROBE cat files/probe-$1.log 2>/dev/null | grep -q "PROBE DONE"; then break; fi
    sleep 2; i=$((i+1))
  done
  adb shell run-as $PROBE cat files/probe-$1.log
}

# Phase 1: an ungranted caller is refused at every method (default-off).
runprobe ungranted

# Phase 2: seed owner grants (provider prefs + python mirror + bot state),
# as the Connected-apps UI would have written them.
if [ -z "$PROBE_CERT_SHA256" ]; then echo "PROBE_CERT_SHA256 not set" >&2; exit 1; fi
printf '%s' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name=\"grant.com.vanipy.jinprobe\">{\"cert\":\"$PROBE_CERT_SHA256\",\"enabled\":true,\"prefix\":\"/probe\",\"chats\":{\"$HANDLE\":{\"send\":true,\"recv\":true}},\"updated\":1}</string></map>" \
  | adb shell "run-as $JIN sh -c 'mkdir -p shared_prefs && cat > shared_prefs/transport_grants.xml'"
printf '%s' "{\"com.vanipy.jinprobe\":{\"enabled\":true,\"prefix\":\"/probe\",\"chats_send\":[\"$HANDLE\"],\"chats_recv\":[\"$HANDLE\"]}}" \
  | adb shell "run-as $JIN sh -c 'cat > files/SYNC_GRANTS'"
printf '%s' '{"provisioned":true,"bot_addr":"bot-fixture@fixture.invalid","acct_gen":1,"chats":{"11":{}}}' \
  | adb shell "run-as $JIN sh -c 'cat > files/bot_state.json'"
adb shell am force-stop $JIN

# Phase 3: granted caller while the bot is down must see BOT_NOT_RUNNING,
# never a silent queue.
runprobe granted

# Phase 4: the offline fixture bot serves the bridge with the synthetic rpc
# boundary DOWN: real pipes, real drain/EOF ordering, visible failures.
adb shell run-as $JIN touch files/FIXTURE_RPC_DOWN
adb shell am start -W -n $JIN/.MainActivity >/dev/null
sleep 2
adb shell am startservice -n $JIN/.BotService --es mode syncfixture
i=0
while [ $i -lt 120 ]; do
  if adb shell run-as $JIN cat files/SYNC_ALIVE 2>/dev/null | grep -q .; then break; fi
  sleep 2; i=$((i+1))
done
runprobe live

# Phase 5: synthetic rpc boundary UP: success, dedup replay, refusal of
# different-body reuse, status by ref, file retry via retained bytes.
# Synthetic only - this is NOT relay delivery.
adb shell run-as $JIN rm -f files/FIXTURE_RPC_DOWN
runprobe live2
FILEREF=$(adb shell run-as $PROBE cat files/probe-live2.log | grep "file-commit-ok" | grep -o "ref=m[0-9]*" | head -1 | cut -d= -f2)

# Phase 6: simulate the stage-TTL sweep, then the documented recovery route:
# bare retry -> EXPIRED, same-token re-stage of identical bytes -> original
# ref, different bytes -> TOKEN_REUSED. Never a fresh token.
adb shell run-as $JIN rm -f files/stage/probeV02_file.bin
adb shell am force-stop $PROBE
adb shell run-as $PROBE rm -f files/probe-swept.log 2>/dev/null || true
adb shell am start -W -n $PROBE/.MainActivity --es scenario swept --es handle "$HANDLE" --es fileref "$FILEREF" >/dev/null
i=0
while [ $i -lt 120 ]; do
  if adb shell run-as $PROBE cat files/probe-swept.log 2>/dev/null | grep -q "PROBE DONE"; then break; fi
  sleep 2; i=$((i+1))
done
adb shell run-as $PROBE cat files/probe-swept.log

# Phase 7: inbound capture through the REAL hook, driven by injected
# synthetic messages: prefix isolation for this caller.
printf '%s' '{"chat_id":11,"text":"/probe hello","msg_id":1001}' | adb shell "run-as $JIN sh -c 'cat > files/FIXTURE_INJECT_a'"
printf '%s' '{"chat_id":11,"text":"/other x","msg_id":1002}' | adb shell "run-as $JIN sh -c 'cat > files/FIXTURE_INJECT_b'"
printf '%s' '{"chat_id":11,"text":"no prefix at all","msg_id":1003}' | adb shell "run-as $JIN sh -c 'cat > files/FIXTURE_INJECT_c'"
sleep 4
runprobe inbound

# Phase 8: retention overflow: an old cursor must see gap+first_available.
printf '%s' '{"chat_id":11,"text":"/probe bulk","msg_id":2000,"fill":510}' | adb shell "run-as $JIN sh -c 'cat > files/FIXTURE_INJECT_d'"
# wait for the bulk inject to finish: capture is prefix-filtered, so only
# the /probe messages land - 1 earlier + 510 bulk = seq 511, not 513.
# (translation is slow; a fixed sleep raced the capture loop)
i=0
while [ $i -lt 150 ]; do
  SEQ=$(adb shell run-as $JIN cat files/SYNC_INBOX_SEQ 2>/dev/null || echo 0)
  case "$SEQ" in ''|*[!0-9]*) SEQ=0 ;; esac
  [ "$SEQ" -ge 511 ] && break
  sleep 2; i=$((i+1))
done
echo "inbox seq reached $SEQ"
runprobe gap

# Phase 9: capture route through the REAL live message path (ChatControl
# .on_new_message), offline: owner pairing via the app-private request file,
# a capture command in the served chat, then the peer's result + file and a
# stale queue's file. Synthetic only; no account, no relay.
adb shell am force-stop $JIN
printf '%s' 'bidik-fixture@fixture.invalid' | adb shell "run-as $JIN sh -c 'cat > files/PAIR_BIDIK_REQ'"
# A force-stopped app starts in the background on newer Android, where
# startservice is refused; bring it forward (wake + relaunch) and retry.
startsvc() { # $1 = service mode
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell am start -W -n $JIN/.MainActivity >/dev/null
  j=0
  while [ $j -lt 15 ]; do
    if adb shell dumpsys activity activities 2>/dev/null | grep "ResumedActivity" | grep -q "$JIN/.MainActivity"; then break; fi
    sleep 1; j=$((j+1))
  done
  adb shell am startservice -n $JIN/.BotService --es mode "$1"
}
i=0
until startsvc pairbidik; do
  i=$((i+1))
  [ $i -gt 5 ] && { echo "transport gate: cannot start pairbidik service" >&2; exit 1; }
  sleep 3
done
sleep 3
adb shell run-as $JIN cat files/bot_state.json | grep -q "bidik-fixture@fixture.invalid" || {
  echo "transport gate: pairbidik did not store the peer" >&2; exit 1; }
i=0
until startsvc routefixture; do
  i=$((i+1))
  [ $i -gt 5 ] && { echo "transport gate: cannot start routefixture service" >&2; exit 1; }
  sleep 3
done
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
adb shell run-as $JIN cat files/fixture-sends.jsonl | grep "capture-queue" | head -1 > /tmp/route_q.jsonl
QID=$(python3 -c "import json;print(json.loads(json.loads(open('/tmp/route_q.jsonl').read())['text'])['queue_id'])")
[ -n "$QID" ] || { echo "transport gate: no queue doc left for the peer" >&2; exit 1; }
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
  echo "transport gate: result status did not route to the requester" >&2; exit 1; }
[ "$(echo "$RSENDS" | grep -c 'capture-result-file')" = 1 ] || {
  echo "transport gate: expected exactly one forwarded file (stale one refused)" >&2; exit 1; }
echo "$RSENDS" | grep -q '/fixture/stale.png' && {
  echo "transport gate: stale queue file was forwarded" >&2; exit 1; }
echo "$RSENDS" | grep -q "queued $QID" || {
  echo "transport gate: capture ack missing in served chat" >&2; exit 1; }
echo "transport gate: capture route PASS"

# any probe FAIL line or nonzero failure count fails the job
for s in ungranted granted live live2 swept inbound gap; do
  adb shell run-as $PROBE cat files/probe-$s.log > /tmp/probe-$s.log 2>/dev/null || true
done
# the dedup machinery must have sent each successful logical send ONCE
SENDS=$(adb shell run-as $JIN cat files/fixture-sends.jsonl 2>/dev/null || true)
echo "$SENDS"
[ "$(echo "$SENDS" | grep -c '"text": "probe ok 1"')" = 1 ] || { echo "transport gate: duplicate send of probe ok 1" >&2; exit 1; }
[ "$(echo "$SENDS" | grep -c '"text": "probe file ok"')" = 1 ] || { echo "transport gate: duplicate send of probe file ok" >&2; exit 1; }
if grep -h " FAIL " /tmp/probe-*.log; then
  echo "transport gate: FAILURES above" >&2
  exit 1
fi
grep -h "PROBE DONE" /tmp/probe-*.log
echo "transport gate: PASS"
