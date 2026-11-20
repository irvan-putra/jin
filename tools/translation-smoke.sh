#!/bin/sh
# ARM-translation runtime smoke for jin (see ui-smoke.yml, smoke-translated).
# Proves jin's real arm64-only APK - Chaquopy CPython, the bundled arm64
# librpcsrv.so DeltaChat core, sqlite - starts and completes its offline
# validation probe on a hosted x86_64 emulator through the google_apis
# API34 image's ARM->x86 translation. No account, no network, no secrets;
# the marker lives in the app's own files/validation.log.
set -e
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.vanipy.jin

adb wait-for-device
adb install -r "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c || true
# API 34 refuses a background FGS launch ("app is in background"), so
# foreground the app first, then start the bot service.
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 3
adb shell am startservice -n $PKG/.BotService

# Translation is slow: allow up to 8 minutes for the validation probe.
i=0
while [ $i -lt 96 ]; do
  LOG=$(adb shell run-as $PKG cat files/validation.log 2>/dev/null || true)
  case "$LOG" in
    *"VALIDATION PASS"*)
      echo "translation smoke: VALIDATION PASS"
      echo "$LOG"
      # Real menu-entry proof through UiAutomator's touch injection (the
      # shell cannot synthesize a reliable long-press/edge-drag on this
      # emulator). Runs before ui-smoke.sh writes its fixtures.
      ./gradlew connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.vanipy.jin.MenuEntryTest
      # The macos runner route never runs on this account, so capture the
      # same bot-state and menu screenshots right here on the translating
      # emulator (ui-smoke.sh is plain adb, arch-independent).
      sh tools/ui-smoke.sh
      exit 0 ;;
    *"VALIDATION FAIL"*)
      echo "translation smoke: VALIDATION FAIL" >&2
      echo "$LOG" >&2
      exit 1 ;;
  esac
  sleep 5
  i=$((i+1))
done
echo "translation smoke: timed out waiting for validation marker" >&2
adb shell run-as $PKG cat files/validation.log 2>/dev/null >&2 || true
adb logcat -d | tail -80 >&2 || true
exit 1
