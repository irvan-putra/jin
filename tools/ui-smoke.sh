#!/bin/sh
# Real-OS UI smoke for jin (see .github/workflows/ui-smoke.yml).
# Fixture state only: a synthetic bot address seeded via run-as; the bot
# service, python runtime, network and chatmail relay are never started.
set -e
mkdir -p shots
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.vanipy.jin

adb wait-for-device
adb install -r "$APK"
# API 33+ would otherwise show the notification-permission prompt over the UI.
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

shot() { adb exec-out screencap -p > "shots/$1.png"; }
relaunch() {
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity >/dev/null
  # generous settle: ARM translation makes every frame slower
  sleep 8
}

# 0: fresh install, setup state
relaunch
shot 0-setup

# adb shell re-parses a joined command line, so nested sh -c quoting is
# lossy (run 44679632064: the redirect escaped to the outer shell and
# failed at cwd /). Pipe base64 on stdin instead - no inner quoting at all.
write_state() {
  printf '%s' "$1" | base64 | adb shell "run-as $PKG sh -c 'base64 -d > files/bot_state.json'"
}

# 1: account created, stopped
write_state '{"provisioned":true,"bot_addr":"bot-fixture@chatmail.example","chats":{}}'
relaunch
shot 1-ready

# 2: marked running (fixture RUNNING flag; service itself not started)
write_state '{"provisioned":true,"bot_addr":"bot-fixture@chatmail.example","chats":{"11":{},"22":{}}}'
adb shell "run-as $PKG sh -c 'touch files/RUNNING'"
relaunch
shot 2-running

# 3: open the left menu with a real edge swipe
adb shell input swipe 2 1200 650 1200 250
sleep 3
shot 3-menu

# Back closes the menu instead of leaving; screenshot after Back
adb shell input keyevent 4
sleep 2
shot 4-after-back

ls -l shots/
