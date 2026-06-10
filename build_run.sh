#!/usr/bin/env bash
# build_run.sh — Clean, build (debug), and deploy to connected Android device/emulator
set -euo pipefail

APP_ID="com.zebra.rfid.usb"
ACTIVITY=".MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "=== Checking for connected device ==="
if ! adb get-state 1>/dev/null 2>&1; then
    echo "ERROR: No device/emulator found. Connect a device or start an emulator first."
    exit 1
fi
adb devices

echo ""
echo "=== Clean ==="
./gradlew clean

echo ""
echo "=== Build (debug) ==="
./gradlew assembleDebug

echo ""
echo "=== Install ==="
adb install -r "$APK"

echo ""
echo "=== Launch ==="
adb shell am start -n "${APP_ID}/${APP_ID}${ACTIVITY}"

echo ""
echo "=== Done. Streaming logcat for MYUSB tag (Ctrl+C to stop) ==="
adb logcat -c
adb logcat -s MYUSB:D
