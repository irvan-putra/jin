#!/bin/sh
# Live-relay sign-in proof (workflow live-relay-smoke.yml).
# Signs the vendored core into an EXISTING throwaway relay account whose
# credentials arrive via runner env (GitHub secrets), then proves a real
# relay round trip by self-echo. Credentials never hit argv, logs or
# artifacts; the credential file is deleted by the app before the result
# file appears. See app/src/main/python/live_relay.py.
set -e
JIN=com.vanipy.jin

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Credential handoff: stdin pipe, never command-line arguments.
python3 -c 'import json,os; print(json.dumps({"addr": os.environ["JIN_RELAY_C_ADDR"], "mail_pw": os.environ["JIN_RELAY_C_PASSWORD"]}))' \
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
echo "$RESULT" | grep -q '"status": "ok"' || { echo "live relay selfecho FAILED" >&2; exit 1; }
# The app must have deleted the credential file before writing the result.
if adb shell run-as $JIN cat files/live_login.json 2>/dev/null | grep -q addr; then
  echo "credential file was not deleted by the app" >&2; exit 1
fi
echo "live relay selfecho OK"
