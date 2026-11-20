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

# 3: open the left menu. Proof of the REAL documented entries first:
# (a) slow edge drag from x=0, (b) long-press in the scroll area. The
# MENU_PROBE seam is only an isolated-render fallback and says so.
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $3}')
W=${SIZE%x*}; H=${SIZE#*x}
menu_open() {
  adb shell uiautomator dump /sdcard/window.xml || echo "uiautomator dump failed: $?"
  adb shell cat /sdcard/window.xml 2>/dev/null | grep -q "View logs"
}
ENTRY=none
adb shell input swipe 0 $((H / 2)) $((W * 2 / 5)) $((H / 2)) 1200
sleep 3
if menu_open; then ENTRY=edge-drag; fi
if [ "$ENTRY" = none ]; then
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H * 3 / 4)) 1200
  sleep 3
  if menu_open; then ENTRY=long-press; fi
fi
if [ "$ENTRY" = none ]; then
  echo "real menu entries did not open the drawer; MENU_PROBE seam render only"
  adb shell "run-as $PKG touch files/MENU_PROBE"
  sleep 4
  menu_open || { shot 3-menu-failed; echo "menu did not open even via seam"; exit 1; }
  ENTRY=menu-probe-seam-isolated-render-only
  adb shell "run-as $PKG rm -f files/MENU_PROBE"
fi
echo "menu entry used: $ENTRY"
shot 3-menu

# Back closes the menu instead of leaving; fail loudly if the app lost focus
adb shell input keyevent 4
sleep 2
adb shell "dumpsys window | grep mCurrentFocus" | grep -q "$PKG" || { shot 4-after-back-failed; echo "Back left the app instead of closing the menu"; exit 1; }
shot 4-after-back
adb shell "run-as $PKG rm -f files/MENU_PROBE"

ls -l shots/
