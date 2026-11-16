#!/bin/bash
# install.sh : the release APK onto the tablet, when it is unlocked, no USB dialog is up and nobody plays.
# Nobody plays = the note counter of the app's own "midi pkt" log line stands still for 10 s
# (no such line since the app started = no key played since then).
ADB=/c/Users/michi/AppData/Local/Android/Sdk/platform-tools/adb.exe
notes() {
  $ADB shell 'logcat -d --pid=$(pidof com.rigalong)' | grep 'midi pkt' | tail -1 |
    sed -E 's/.* on=([0-9]+) off.*->.*/\1/' | tr -d '\r'
}
a=$(notes); sleep 10; b=$(notes)
d=$($ADB shell "dumpsys activity activities | grep -c UsbPermissionActivity" | tr -d '\r')
# Locked: the app would start behind the lock screen, where its routing does
# not run (2026-11-16: it came up on the tablet's speaker). Wait for an unlock.
k=$($ADB shell "dumpsys window | grep -c 'isKeyguardShowing=true'" | tr -d '\r')
echo "notes $a -> $b, dialog $d, locked $k"
[ "$a" = "$b" ] && [ "$d" = "0" ] && [ "$k" = "0" ] || { echo "NOT installing"; exit 1; }
$ADB install -r /c/Users/michi/Documents/mdwsynth/rigalong/app/build/outputs/apk/release/app-release.apk 2>&1 | tail -1
$ADB shell am start -n com.rigalong/.MainActivity >/dev/null
sleep 14
$ADB shell "dumpsys activity activities | grep -c UsbPermissionActivity; dumpsys power | grep -m1 mWakefulness=; logcat -d -t 1500 | grep -E 'rigalong-audio: health' | tail -1 | cut -c1-260"
