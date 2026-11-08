#!/usr/bin/env bash
# Build the release APK and install it on an Android TV over network ADB.
# Usage: ./install.sh <tv ip>[:port]
# Needs: JDK 17+, the Android SDK (ANDROID_HOME or local.properties) and adb on PATH.
# Turn on Developer options > USB debugging on the TV first, and accept the prompt it shows.
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:?usage: ./install.sh <tv ip>[:port]}"
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
APK=app/build/outputs/apk/release/app-release.apk

./gradlew --no-daemon -q assembleRelease
adb connect "$TV"
# adb install can wait for an on-screen confirmation on some TVs (Android 14); push + pm install avoids it
adb -s "$TV" push "$APK" /data/local/tmp/peekwell.apk
adb -s "$TV" shell pm install -r /data/local/tmp/peekwell.apk
adb -s "$TV" shell rm /data/local/tmp/peekwell.apk
# Motion alerts pop up over other apps. Most TVs have no Settings screen for that permission, so grant it here.
adb -s "$TV" shell appops set com.bytefoss.peekwell SYSTEM_ALERT_WINDOW allow
echo "Installed. Open Peekwell on the TV, then switch USB debugging off again."
