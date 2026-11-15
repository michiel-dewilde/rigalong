#!/bin/bash
# lineout_test.sh NAME : what leaves the CP-88's line out, for a native change (real-time code
# is tried, not read). An organ chord held 8 s on song 2's setBfree, then a run on its sfizz,
# recorded by the Scarlett 2i2 on the ZBook (hw:Gen, 4 channels, inputs on 1-2) and analysed
# there by lineout.py (copy it to the ZBook's home first: scp tools/lineout.py bk@zbookg5:).
# The app must be on the Stage (off it, it is silent), on the seeded setlist.
ADB=/c/Users/michi/AppData/Local/Android/Sdk/platform-tools/adb.exe
name=${1:-take}
B="am broadcast -a com.rigalong"
$ADB shell "$B.STAGE --ei song 2 --ei liveset 1 >/dev/null; $B.RESETPEAKS >/dev/null"
ssh bk@zbookg5 "arecord -q -D hw:Gen -f S32_LE -r 48000 -c 4 -d 19 -t raw $name.raw" &
sleep 1.5
$ADB shell "
for n in 48 55 60 64; do $B.NOTE --ei note \$n --ei vel 100 >/dev/null; done
sleep 8
for n in 48 55 60 64; do $B.NOTE --ei note \$n --ei vel 0 >/dev/null; done
sleep 1
$B.STAGE --ei liveset 2 >/dev/null
sleep 0.5
for n in 48 52 55 60 64 67 72 76 79 84 79 76 72 67 64 60; do
  $B.NOTE --ei note \$n --ei vel 100 >/dev/null; sleep 0.12; $B.NOTE --ei note \$n --ei vel 0 >/dev/null
done
sleep 1
$B.STAGE --ei liveset 1 >/dev/null
"
wait
ssh bk@zbookg5 "python3 lineout.py $name.raw"
$ADB shell "logcat -d -t 400 | grep -E 'rigalong-audio: (health|usb hold)' | tail -2"
