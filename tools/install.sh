#!/bin/bash
# install.sh : the release APK onto the tablet, when no USB dialog is up and nobody plays
# (the note counter stands still; no "midi pkt" line at all = no key played since the app started).
ADB=/c/Users/michi/AppData/Local/Android/Sdk/platform-tools/adb.exe
notes() { $ADB shell "logcat -d -t 400" | grep 'midi pkt' | tail -1 | sed -E 's/.* on=([0-9]+) off.*->.*/\1/' | tr -d '\r'; }
a=$(notes); sleep 10; b=$(notes)
d=$($ADB shell "dumpsys activity activities | grep -c UsbPermissionActivity" | tr -d '\r')
echo "notes $a -> $b, dialog $d"
[ "$a" = "$b" ] && [ "$d" = "0" ] || { echo "NOT installing"; exit 1; }
$ADB install -r /c/Users/michi/Documents/mdwsynth/rigalong/app/build/outputs/apk/release/app-release.apk 2>&1 | tail -1
$ADB shell am start -n com.rigalong/.MainActivity >/dev/null
sleep 14
$ADB shell "dumpsys activity activities | grep -c UsbPermissionActivity; dumpsys power | grep -m1 mWakefulness=; logcat -d -t 1500 | grep -E 'rigalong-audio: health' | tail -1 | cut -c1-260"
