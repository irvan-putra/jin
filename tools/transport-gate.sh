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
  | adb shell run-as $JIN sh -c 'mkdir -p shared_prefs && cat > shared_prefs/transport_grants.xml'
printf '%s' "{\"com.vanipy.jinprobe\":{\"enabled\":true,\"prefix\":\"/probe\",\"chats_send\":[\"$HANDLE\"],\"chats_recv\":[\"$HANDLE\"]}}" \
  | adb shell run-as $JIN sh -c 'cat > files/SYNC_GRANTS'
printf '%s' '{"provisioned":true,"bot_addr":"bot-fixture@fixture.invalid","acct_gen":1,"chats":{"11":{}}}' \
  | adb shell run-as $JIN sh -c 'cat > files/bot_state.json'
adb shell am force-stop $JIN

# Phase 3: granted caller while the bot is down must see BOT_NOT_RUNNING,
# never a silent queue.
runprobe granted

# Phase 4: the offline fixture bot serves the bridge: real pipes, real
# drain/EOF ordering, visible failures at the accountless rpc boundary.
adb shell am start -W -n $JIN/.MainActivity >/dev/null
sleep 2
adb shell am startservice -n $JIN/.BotService --es mode syncfixture
i=0
while [ $i -lt 120 ]; do
  if adb shell run-as $JIN cat files/SYNC_ALIVE 2>/dev/null | grep -q .; then break; fi
  sleep 2; i=$((i+1))
done
runprobe live

# any probe FAIL line or nonzero failure count fails the job
for s in ungranted granted live; do
  adb shell run-as $PROBE cat files/probe-$s.log > /tmp/probe-$s.log 2>/dev/null || true
done
if grep -h " FAIL " /tmp/probe-ungranted.log /tmp/probe-granted.log /tmp/probe-live.log; then
  echo "transport gate: FAILURES above" >&2
  exit 1
fi
grep -h "PROBE DONE" /tmp/probe-ungranted.log /tmp/probe-granted.log /tmp/probe-live.log
echo "transport gate: PASS"
