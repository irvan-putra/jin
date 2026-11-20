#!/bin/sh
# Live-relay sign-in + round-trip proof (workflow live-relay-smoke.yml).
# The app signs the vendored core into an EXISTING throwaway relay account
# (credentials via runner env, stdin handoff, never argv/logs/artifacts)
# and sends a token message to the account's own address. A second core
# instance of the SAME account on the runner (multi-device sync, no new
# account) witnesses the token arriving through the relay: that is the
# round trip, since a device never sees its own echo as fresh.
set -e
JIN=com.vanipy.jin
export LIVE_ECHO_TOKEN="live-echo-$(date +%s)"

pip install -q deltachat-rpc-client==2.69.0 deltachat-rpc-server==2.69.0

# Witness first, so it is listening before the app sends.
rm -f WITNESS_RESULT
python3 tools/live-relay-witness.py &
WITNESS_PID=$!

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Credential handoff: stdin pipe, never command-line arguments.
python3 -c 'import json,os; print(json.dumps({"addr": os.environ["JIN_RELAY_C_ADDR"], "mail_pw": os.environ["JIN_RELAY_C_PASSWORD"], "token": os.environ["LIVE_ECHO_TOKEN"]}))' \
  | adb shell "run-as $JIN sh -c 'mkdir -p files && cat > files/live_login.json'"
test -z "$JIN_RELAY_C_ADDR" && { echo "secret env missing" >&2; exit 1; }

adb shell am force-stop $JIN
adb shell am start -W -n $JIN/.MainActivity >/dev/null
sleep 2
adb shell am startservice -n $JIN/.BotService --es mode selfecho

i=0
while [ $i -lt 120 ]; do
  if adb shell run-as $JIN cat files/LIVE_ECHO_RESULT 2>/dev/null | grep -q status; then break; fi
  sleep 2; i=$((i+1))
done
RESULT=$(adb shell run-as $JIN cat files/LIVE_ECHO_RESULT 2>/dev/null || true)
echo "LIVE_ECHO_RESULT: $RESULT"

# The app must have deleted the credential file before writing the result.
if adb shell run-as $JIN cat files/live_login.json 2>/dev/null | grep -q addr; then
  echo "credential file was not deleted by the app" >&2; kill $WITNESS_PID 2>/dev/null; exit 1
fi

wait $WITNESS_PID || true
WITNESS=$(cat WITNESS_RESULT 2>/dev/null || echo '{"status":"failed","detail":"witness produced no result"}')
echo "WITNESS_RESULT: $WITNESS"

echo "$RESULT" | grep -q '"status": "ok"' || { echo "app send leg FAILED" >&2; exit 1; }
echo "$WITNESS" | grep -q '"status": "ok"' || { echo "relay round trip NOT witnessed" >&2; exit 1; }
echo "live relay sign-in + round trip OK"
