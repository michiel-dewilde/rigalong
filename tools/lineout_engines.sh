#!/bin/bash
# lineout_engines.sh TAG : middle C held 2 s on a live set of each engine (the seeded songs),
# recorded from the CP-88's line out; level and pitch of each, to compare two states of the app
# (before and after a re-route, two builds). Needs lineout_note.py in the ZBook's home.
ADB=/c/Users/michi/AppData/Local/Android/Sdk/platform-tools/adb.exe
tag=${1:-t}
B="am broadcast -a com.rigalong"
files=""
for pair in ${PAIRS:-1:1 2:1 3:1 4:1 4:2}; do
  song=${pair%:*}; ls=${pair#*:}
  $ADB shell "$B.STAGE --ei song $song --ei liveset $ls >/dev/null"; sleep 2.5
  f="$tag-s${song}l${ls}.raw"; files="$files $f"
  ssh bk@zbookg5 "arecord -q -D hw:Gen -f S32_LE -r 48000 -c 4 -d 4 -t raw $f" &
  sleep 1
  $ADB shell "$B.NOTE --ei note 60 --ei vel 100 >/dev/null; sleep 2; $B.NOTE --ei note 60 --ei vel 0 >/dev/null"
  wait
done
ssh bk@zbookg5 "python3 lineout_note.py $files"
