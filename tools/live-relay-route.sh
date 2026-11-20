#!/bin/sh
# Live capture-route proof over the real chatmail relay
# (workflow live-relay-route.yml). A = bot account on the emulator, with
# B paired as its Bidik peer; B runs on the runner and plays the Bidik
# device. B's DM doubles as the seeded requester chat: a real capture
# command needs a third identity (peer and requester must differ by
# design), which the approved two-login scope does not include.
# Synthetic content only; credentials via env + stdin/file handoff,
# deleted by the app after use; never argv/logs/artifacts.
set -e
JIN=com.vanipy.jin

pip install -q deltachat-rpc-client==2.69.0 deltachat-rpc-server==2.69.0

test -n "$JIN_RELAY_A_ADDR" && test -n "$JIN_RELAY_A_PASSWORD" \
  && test -n "$JIN_RELAY_B_ADDR" && test -n "$JIN_RELAY_B_PASSWORD" \
  || { echo "secret env missing" >&2; exit 1; }

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Owner-only pairing request (peer = B) and account-A credential handoff:
# stdin pipes, never command-line arguments.
python3 -c 'import os,sys; sys.stdout.write(os.environ["JIN_RELAY_B_ADDR"])' \
  | adb shell "run-as $JIN sh -c 'cat > files/PAIR_BIDIK_REQ'"
python3 -c 'import json,os; print(json.dumps({"addr": os.environ["JIN_RELAY_A_ADDR"], "mail_pw": os.environ["JIN_RELAY_A_PASSWORD"]}))' \
  | adb shell "run-as $JIN sh -c 'cat > files/live_login.json'"

adb shell am force-stop $JIN
adb shell am start -W -n $JIN/.MainActivity >/dev/null
sleep 2
adb shell am startservice -n $JIN/.BotService --es mode pairbidik
sleep 4
adb shell run-as $JIN cat files/bot_state.json | grep -q bidik_peer_addr \
  || { echo "live route: pairing not stored" >&2; exit 1; }
adb shell am startservice -n $JIN/.BotService --es mode liveroute

i=0
until adb shell run-as $JIN cat files/bot.log 2>/dev/null | grep -q "live bot running as"; do
  sleep 2; i=$((i+1))
  if [ $i -ge 90 ]; then
    echo "live route: bot did not reach the live loop" >&2
    adb shell run-as $JIN tail -20 files/bot.log >&2 || true
    exit 1
  fi
done
# The app must have deleted the credential file before entering the loop.
if adb shell run-as $JIN cat files/live_login.json 2>/dev/null | grep -q addr; then
  echo "live route: credential file was not deleted by the app" >&2; exit 1
fi
echo "live route: bot signed in, paired, live loop running"

python3 tools/live-relay-route-b.py
echo "live relay route: PASS"
