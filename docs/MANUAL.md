# RigAlong — user manual (draft)

RigAlong turns an Android tablet into a sound module for a stage piano:
six synthesis engines that you play from your keyboard, organised into songs
and setlists, switched from the screen, a footswitch, or the keyboard's own
buttons. It works with any class-compliant USB MIDI keyboard. It has
particular support for the Yamaha CP88/CP73, which can carry both the notes
and the sound over a single USB cable.

*RigAlong is an independent project, not affiliated with or endorsed by
Yamaha. "CP88" and "CP73" are named only as compatible hardware.*

---

## The four screens

The bar at the top switches between **Stage**, **Songs**, **Library** and
**Setup**.

* On the Stage screen, the other three open only on a **long press**, so a
  stray touch in the middle of a song never takes you off the stage.
* Android's **Back** gesture returns to the stage, and on the stage it does
  nothing, so it cannot close the instrument mid-song.
* The red **PANIC** button stops every sound, at once, everywhere.

The line beside the tabs says where the sound goes and how late it is
("CP-88 · direct USB · 9,7 ms"), and the tablet's heat when it is not cool.
It turns amber, and says what is wrong instead, when something needs
attention: no sound path (the stream down, the wrong device, the tablet's own
speaker), dropouts in the last minute (a gap or a click in the sound, with
their count and cause), or the tablet running hot. The first ten seconds
after the sound starts do not count: a start settles. On a phone held
upright, the tabs take a row of their own and PANIC stays beside this line.

### Stage

What you play from.

* **Song**: its name, what comes next, and ◀ ▶ to move through the setlist.
  Tap the name for the whole setlist, and a song in it to go straight there.
  "Loading…" under the name means the song's sounds are still coming in:
  the keys may not play them yet.
* **Live sets**: the large buttons. A live set is one state of the song, such
  as "Verse", "Chorus" or "Solo". **Hold** a live set button to edit it: the
  song opens in Songs at that live set. **Hold** a sound's name above its
  fader to open its sound for the live set that is playing: what you change
  there is that live set's own (see Songs, "a live set's own sound"), unless
  you pick "Whole song" at the top. Switching never cuts a note: a key you are
  holding keeps sounding until you release it, even if the new live set no
  longer uses that sound, and tails ring out naturally.
* **Levels**: one fader per sound the live set plays. The song's other
  sounds are a tap away: "+3 silent" beside "Levels" shows them all,
  "playing only" folds them again. A drag moves a fader from where it is (it
  never jumps when you start to slide); a tap sets it where you tap.
  Drawbars go to the nearest of their nine steps. "Store in this live set"
  (hold it) makes what you hear the live set's own: the levels, and whatever
  you moved on the stage or with a knob since you selected it (the drawbars,
  a filter). A value moved back to the song's stops being its own. The stage
  then says "Stored in" and the live set's name for a few seconds, with
  **Undo** beside it.
* **Master**: the overall volume, at the right. It belongs to the room, not
  to the song, so no live set changes it. Hold its label to return to 0 dB.
* **setBfree**: when the song uses setBfree, its nine drawbars, Leslie (stop /
  slow / fast) and percussion are here. With several setBfree instances, the
  panel is the one playing in this live set, and says which.
* **Knobs, wheels and pedals**: a control you have taught to a sound's
  setting (see Songs) moves it while that sound plays in the live set, so one
  knob can be a setBfree drawbar in one live set and a Surge XT filter in
  the next. After a switch it **picks up**: it does nothing until you turn it
  past the setting's value, so nothing jumps.
* **Tempo**: the tempo in force (the live set's own, or the song's) and its
  metre, with a blinking beat. TAP sets a tempo by tapping, four times or
  more. **Tap the tempo itself** for the tempo dialog: type a tempo, choose
  **No tempo**, go **Back to the stored tempo**, or **Save** it into the song
  (or into the live set, when it has its own). A tapped or typed tempo is
  amber and holds until another song opens.
* **Metronome**, framed together with the tempo and TAP: **START** plays it
  from beat one, **COUNT** counts in, **STOP** stops it. Running, the beat blinks; what is heard and shown is set in
  Setup (Metronome): the click and the bar count (the beat's number, beat
  one accented), each always, during a count-in only, or never, and whether
  it keeps going after a count-in. Another song stops it.
* **On-screen keys**: when no MIDI controller is attached, a keyboard at the
  bottom of every screen plays what the current live set plays.
* **Lead sheet**: when the song has one, it takes the left part of the screen
  (the top in portrait), one or two pages at a time.

**Footswitches and page turners**: Bluetooth page turners such as the
PageFlip Butterfly work as keyboards.

* Page Down, right arrow or space goes forward; Page Up or left arrow goes
  back.
* When the song has a lead sheet they turn pages; otherwise they move between
  live sets. Setup can change this.
* MIDI footswitches, including the CP88/CP73's foot switch jacks, work too
  once you have taught them in Setup.

**A device that follows the app** (see Setup, Devices) selects the current
song's live sets with its own buttons: on a CP88/CP73, set 1 selects live
set 1, and so on.

### Songs

Where songs are made. On the left, two tabs:

* the **setlist** on stage: change the order with ▲ ▼, remove a song with ✕,
  make a **New song** in it, or **Add songs…** from all the songs there are
  (search, tap to add);
* **All songs**: every song, whether or not a setlist has it -- search, tap
  one to open it, **+** to add it to the setlist, or make a **New song (in
  no setlist)**.

On the right is the song being edited:

* **Sounds**: what the song plays. Each sound is one of six engines -- sfizz
  (SFZ sampler), setBfree (tonewheel organ), Surge XT, Dexed (DX7), Plaits
  and FluidSynth (SoundFonts) -- with its own patch, level and effect. A song
  can have several of one engine: **Add a sound** "+ sfizz" twice gives two
  sfizz instances, each with its own instrument, split, transposition and
  fader. Tap a sound's name to name it ("Bright"); ✕ removes it.
  * The wide button beside the name is **what the sound plays**, chosen by
    ear. Tap it for the list (search narrows it); tap an entry and it is on
    the keys at once, the list staying open; **◀ ▶** step to the one before
    and after; **Play a note** sounds middle C when no hand is on the
    keyboard. **Keep** (or a second tap on the entry) takes it into the
    song, **Cancel** puts back what the sound had. While the list is open
    the song being edited is what the keys play, on a live set where this
    sound plays. For FluidSynth every preset of every installed SoundFont is
    an entry ("0:012 Marimba", its font beside it).
  * **Patch…** shows every setting of that sound (drawbars, models, sends,
    transposition) as sliders over their real ranges. What you change is
    heard at once and kept in the song.
  * "Take current" keeps what the engine has now; "Clear" goes back to the
    plain patch.
  * **Library patches** share a sound between songs. "Save as library
    patch…" in the patch dialog shares it, and the patch list shows library
    patches first. The dialog says which library patch a sound uses and how
    many songs use it.
  * When you save a song whose library patch you changed, it asks: **update
    it everywhere** (every song using it moves, and their setlists with
    them), **only in this song** (this song keeps its own copy), or **in the
    songs you choose**. A song left on an older version shows it, with "Use
    the latest version".
  * **FX…** puts an effect after that sound: Delay, Phaser, Flanger, Rotary
    or Hall, from Surge XT. Times and rates can **sync** to the song's tempo,
    in note values (1/4, 1/8 dotted…).
* **Tempo** (typed, stepped or tapped, or none) and the **metre** -- beats per
  bar and the beat unit, so 6/8 counts eighths -- and the count-in's bars;
  and a **Lead sheet…** (a PDF). Synced effects and MIDI clock follow the
  tempo in quarter notes.
  * **Groups** splits the bar for odd metres: "3+2" or "2+3" for 5/4, "3+3"
    for 6/8, "3+3+2+2+2" for a bar of twelve eighths that swings between
    6/8 and 3/4. They set the beats per bar too. The click then sounds three
    tones: high on the first beat, a middle tone at each group's start, low
    on the other beats. **Group starts only** sounds just the first beat
    (high) and the group starts (low), the other beats silent.
  * **First bar "1 . 2 ."**, beside the count-in's bars: the count-in starts
    with a bar on the half notes, "1" and "2" on the middle tone, then
    counts "1 2 3 4" high and low. On by default in 4/4; in a grouped metre
    it sounds the group starts.
* **Reverb…**: the room all of the song's sounds meet in (size, damping,
  width, mix), heard at once while you move it. A song that sets nothing gets
  the default room.
* **Master FX…**: one more effect on the whole mix, after the room, such as
  a Hall or a delay over everything.
* **Live sets**: each can be **heard** (Hear): the song as you have it now,
  saved or not, plays at that live set, on the keys and the on-screen keys,
  and follows your edits; leaving Songs brings the stage's song back. A live
  set may have its **own tempo** and metre, in place of the song's. For each
  live set and each sound:
  * whether it **plays**;
  * a **key range**, from low to high, which makes a split. Tap the note
    (it reads "play…") and play the key where the split should be; − and +
    move a semitone, or an octave when held;
  * **transposition** in semitones (an octave when − or + is held);
  * the MIDI **channel** it listens to (0 means any);
  * optionally its **own level**;
  * when the sound has an effect, whether the **effect** is on in this live
    set. Turned off, it stops taking new sound and lets its echoes and tail
    ring out;
  * **Sound…**: its sound in this live set (see below). It reads
    "Sound (2)" when the live set has two values of its own.

  Copy a live set to make the next one quickly.

  **A live set's own sound.** The song holds the sounds; a live set switches
  them on and off and adjusts them. Its sound dialog has two choices at the
  top:
  * **Whole song**: you change the sound's patch, so every live set hears it.
  * **Only in "Solo"** (the live set): you change it for this live set
    alone, such as pulling setBfree's drawbars or opening a filter. These
    values sit on top of the song's: other live sets keep the song's, and
    leaving this live set puts the song's values back. They are plain values,
    so switching stays instant. A value of its own is marked ●; ↺ takes it
    back to the song's value, and **Use in song** makes it the song's, so
    every live set has it. A library patch is not changed by a live set's own
    values.

  **MIDI** beside a setting teaches it a knob, wheel or pedal: tap it, then
  move the control ("CC 21 ✕" afterwards; tap that to forget it). It works
  in every live set where the sound plays.

  To give one live set a different preset, use **New sound for this live
  set**. It adds a sound that starts as this one sounds here, plays only in
  this live set (where the original stops), and opens the preset list for
  it. Both sounds are loaded with the song, so switching to it is still
  instant; a live set that loaded a preset itself would cut the sound.
* **Devices**, per live set: one row for each keyboard or sound module in
  Setup's Devices, offering what that device can do. Every device:
  * **Leave**: it plays whatever it has;
  * **Select**: switching to this live set selects one of its stored sounds,
    by the device's own address -- a program (and, if it needs one, a bank)
    for a plain MIDI device, a page and set for a CP88/CP73. Direct USB modes.

  A device whose own sounds the app can hold (a CP88/CP73) also has:
  * **Own sound**: the song carries a sound of its own for the device. Set the
    sound up on the device, then press "Import current sound" (unsaved edits
    and the panel's knobs are included). That sound belongs to this live set
    only; **Save as library sound** puts it in the library's device sounds,
    under its name, to use in other songs. **From library…** takes one from
    there. A live set with a library sound **follows** it, marked "library":
    rename or edit it in the Library and every song that uses it plays and
    shows the new version. **YAML…** (the device's text form) edits it as
    text for this live set: the edited sound is the live set's own again,
    and the library keeps its version. When the song is opened, its sounds are written into the
    device's reserved area (see Setup): live set 1 in its first slot, and so
    on -- for a CP88/CP73, set 1 of the reserved page.

  On such a device **Select** works the same way: the stored sound is
  copied into the live set's slot of the reserved area when the song opens.
  The device stays on the reserved page, so its own buttons keep choosing
  the song's live sets.

  **The device shows the live set's name.** Every sound the app writes is
  named after its live set, so the keyboard's display says which live set of
  the song is playing, whatever its own part in it is. A CP88/CP73 shows 15
  characters, without accents. Live sets nine and up are on the next
  reserved page, in the same order; the keyboard's page buttons reach them.

  **CP-88 page .X9P** (at the top, when a live set owns a sound; one per
  device that has a file for it) saves the area the app writes into the
  device for this song as the device's own file, to load on a CP88/CP73
  from a USB stick.

  When the keyboard's own live set buttons select the app's live sets (the
  default), every set of the reserved page that a live set doesn't give a
  sound of its own holds a **silent** sound, named after the live set too,
  and so does every reserved set the song has no live set for. Pressing a
  button on the keyboard then never brings in its own instruments by
  accident.

**Save** stores the song (it reads "Save •" while there is something to
save). Earlier versions are kept in the library. Unsaved edits are not lost
when you leave Songs, close the app or it stops: they are kept on the tablet
and come back, and the tab reads **Songs · unsaved** meanwhile. **Back to
saved** lets them go and opens the song as it was saved. **Undo** above the song
takes back the last action whole: a save that moved a patch, three songs and
two setlists is one Undo. The keys
at the bottom play the current live set without a keyboard attached.

### Library

Every setlist and song, with its history. Tabs at the top hold one kind
each, with its count: **All songs** (and the setlists), **Engine sounds**
(what the engines have installed: Surge XT patches, Dexed voices, SoundFont
presets, sfizz instruments, with a chip per engine), **Patches** (library
patches) and **Device sounds**. **Search** narrows the tab you are on.

* Open a setlist on the stage, make a new one, or add any song to the
  setlist on stage.
* **Export this setlist…** writes the setlist and all of its songs, CP-88
  sounds and lead sheets included, into one file.
* **Import…** takes any file the app knows, recognised by what it is,
  not by its name:
  * an exported library file, on this tablet or another one. Importing never
    overwrites: a song changed on both sides is kept in both versions;
  * a device's own files, recognised by its driver. For a CP88/CP73, as its
    File menu saves them on a USB stick: **.X9S** (one sound), **.X9P** (a
    page), **.X9L** (all live sets) and **.X9A** (a backup), and
    cp-liveset's YAML (.yaml) and JSON (.json) documents of them. Their
    sounds go into
    **Device sounds**; a sound the library already has is not added twice;
  * an SFZ sample library as a **.zip**. It then appears in every sfizz
    patch list;
  * an engine's own file: a **SoundFont** (.sf2 or .sf3) for FluidSynth, a
    **DX7 bank** of 32 voices (.syx) for Dexed, a **Surge XT patch** (.fxp).
    It is installed where the engine looks and appears in its patch lists;
    whatever plays now keeps playing. A single DX7 voice is refused: Dexed
    loads banks.
  One Import is one Undo.
* **Details** (beside a setlist) and the **versions** count (beside a song)
  open an entry:
  * rename it, or delete it (hold). Setlists that hold a deleted song keep
    playing it;
  * its **history**, newest first. **Restore** makes an earlier version the
    latest again, as a new version, so nothing is lost; a song's setlists
    move with it;
  * for a song, the setlists that **use** it. A setlist holding an older
    version keeps it until you press **Update to the latest version**;
  * versions **changed elsewhere too**, after an import: use one of them, or
    keep this tablet's.
* **Device sounds**: the devices' own sounds the library holds, by kind of
  device, imported from their files or read from a device ("Import current
  sound"). For the CP88/CP73's Live Set Sounds: **Play on CP-88** puts one
  on the keyboard as its current sound, without touching a stored set, so
  you can hear it. **.X9S**, **.yaml** and **.json** save it as a file the
  keyboard or cp-liveset loads (the JSON holds every byte, the YAML only
  what differs from the standard sound). **YAML…** opens it as text, in cp-liveset's YAML
  form: only what differs from the standard sound is listed, by name
  (`category: Organ`, `instrument: Click Organ`, `on: true`); add a line to
  change anything else, with a name or a number. The line under the text says
  what it reads as, or where it goes wrong, as you type. **Play on CP-88**
  plays the edited sound before you keep it; **Save** keeps it as the
  sound's new version; **Save as .yaml** writes a file cp-liveset reads.
  The name keeps its padding: trailing NULs show as `\0`, trailing spaces
  are dropped and put back.
* **Patches**: the library patches, with Details: the songs using each one
  (and "Update to the latest version" for a song left behind), where it was
  forked from, its history. A Surge XT, Dexed or FluidSynth patch saves as
  its engine's own file, for that engine on a computer or on another
  tablet's Import:
  * **.fxp** for Surge XT: its patch with the macros as the library patch
    has them;
  * **.syx** for Dexed: a bank of 32 with the patch's voice first and INIT
    VOICE in the other slots, the transpose in the voice;
  * **.sf2** (or **.sf3**, from an SF3) for FluidSynth: a SoundFont of the
    patch's one preset and the samples it plays, with the source's credits
    and licence, transposed as it plays here: a key sounds the note its
    Transpose makes of it, samples and all.

  Each is called what the patch is. Level, bend range, engine mode and
  effect sends stay behind: they are how this rig plays the sound, and the
  files have no place for them.
* **Forget history (hold)** in an entry's Details keeps what it is now and
  stops carrying its earlier versions along in exports. It cannot be undone.
* **Undo** at the top takes back the last action.

### Setup

* **Environment**: your rigs, such as "Stage" and "Home". Each one remembers
  the devices and their modes, the external devices, the footswitches, the
  lead-sheet options, the click, Do Not Disturb and the master volume.
  * Tap one to switch to it.
  * "Suggest" marks the ones whose devices are all plugged in.
* **Audio out** and **MIDI in and out**: the device and how it is used.
  * **ALSA** goes through Android.
  * **USB interface** and **USB device** let the app drive the USB device
    directly, for the lowest latency. These are needed for sending anything
    to the CP88/CP73, and "USB device" is the one to prefer. Only these
    ask Android for permission to use the device; with ALSA the app never
    does, and plugging a USB device in raises no dialog.
  * A direct mode is **chosen per device**. It holds for a CP88/CP73 and
    for any other device you chose it for while that device was the one
    shown here. Another sound card that happens to be plugged in (a dock's,
    a headset's) plays through Android and is never asked for, until you
    choose a direct mode for it. For such a device Android asks again at
    every replug: "Always" exists only for keyboards RigAlong knows.
* **USB buffer**: 6, 7 (the default) or 8 ms of audio queued on the wire, in the direct modes.
  More is steadier: at rest 7 ms had about a third of the brief gaps 6 ms had
  (2026-10-14); each extra millisecond adds one to the latency.
* **Devices**: the keyboards and sound modules the app drives over MIDI.
  A device the app knows by name (a CP88/CP73) is added the first time it is
  plugged in; **+ MIDI device** adds any other, **Remove** takes one away.
  Songs refer to a device by its name here. For each:
  * the **MIDI channel** it listens on;
  * for a device whose own sounds the app holds, the **reserved areas**
    that the open song's sounds are written into -- for a CP88/CP73, pages:
    the **first reserved page** and **how many** from it on (39 and 40 by
    default, room for sixteen live sets; reserve more for a song with more).
    Whatever these pages hold is overwritten when a song opens (a page
    takes about a tenth of a second);
  * its **home**: the sound of its own it goes back to when RigAlong lets
    go of it (for a CP88/CP73 a Live Set, 1:1 by default);
  * whether its **own selections choose the app's live sets**; **Rewrite
    the reserved page** reads the stored sounds the song copies and writes
    it all again.

  Before the app writes into an area for the first time, it reads back what
  the area holds and keeps it in the library's **Device sounds**, so no
  sound of your own is lost to it. This is the app's own doing, so Undo
  does not take it back.
* **Footswitches and MIDI controllers**: a controller each for the next and
  the previous live set (or lead-sheet page), tap tempo, the metronome's
  play/stop, and the count-in. Press **Learn**, then the pedal. A controller does one thing: learning
  it for another takes it away from the first.
* **Stage**: Do Not Disturb while the app is in front. Notifications and
  calls are silenced; alarms and media are not. Android asks you once to
  allow it.
* **Metronome**:
  * **Click** and **Bar count** (the beat's number, beat one accented):
    always, during a count-in only, or never;
  * **After a count-in**: keep going (blinking, with whatever is "always") or
    stop; the count-in's bars where a song names none;
  * whether it is heard in the main out, and how loud. With the CP88/CP73 as
    the only output, the audience hears it too; otherwise the beat is on the
    screen only;
  * **MIDI clock** out to the keyboard: 24 ticks a beat at the song's tempo,
    with a Start at the click's first beat, for gear that follows MIDI clock.
    Direct USB modes.
* **Lead sheets**:
  * two pages side by side, in landscape;
  * whether a turn moves one page or two;
  * whether the page turner turns pages or live sets.
* **Status**: what the sound path is doing, a line each, only the lines that
  apply to the path in use:
  * **output**: the device, the path (direct USB, or AAudio with its mode)
    and whether it runs;
  * **latency**: the total, and what it is made of: the queue on the wire (or
    the stream) plus the render reserve, which grows after a dropout and
    shrinks again while all is calm. The first 10 s after the audio starts
    (the tablet busy launching the app) don't count: the reserve goes back
    to its floor when they are over;
  * **dropouts**: in the last minute, and since a time it names, by cause:
    *render* (the engines were late filling the reserve), *USB* (a hold: the
    direct path's short deliberate silence that keeps the CP-88 from
    latching), *USB error*, *xrun* (AAudio). **Reset counts** starts them,
    and the worst figures below, again from now;
  * **usb** (direct path): holds by reason, wire errors, and alt errors,
    which must stay 0;
  * **hold cost** (direct path, after a hold): how long the alt cycle took
    that empties the device's FIFO -- most of a hold's silence. With the
    CP-88 on the tablet's own USB port it takes about 7 ms; behind a dock's
    hubs about 50 ms, and the line says SLOW;
  * **reap loop** (direct path): the worst gap in the thread that feeds the
    wire (its own alt cycles left out), and the CP-88's clock as it asks for
    audio;
  * **presented** and **stream** (AAudio): whether the hardware position
    advances, and the stream's rate, burst and buffer;
  * **render**: the slowest 128-frame block against the time it plays, and
    sfizz's voices;
  * **level**, **midi**, **heat** and **probe** (Measure latency's result);
  * **Measure latency**: sends a click through your speakers and listens for
    it on the tablet's microphone;
  * the **MIDI monitor**: the last messages to and from the keyboard, the
    first place to look when a pedal or button seems to do nothing;
  * **Share diagnostics…**: the app keeps a log of the audio path, the CP-88
    and the heat on the tablet (about ten hours of it). This button hands it,
    with the app's own messages, to the share sheet: email it, or attach it
    to an issue. It never leaves the tablet otherwise.

---

## Where RigAlong sounds

RigAlong plays on the **Stage**, and in **Songs** while a live set is being
heard (Hear, or the list of what a sound plays). In Library and Setup, and
in Songs otherwise, it is silent, and a keyboard it gives sounds to is on
its own home sound: a CP88/CP73 is a piano again, and its Live Set buttons
choose its own sounds. Back on the Stage the song is on the keys as it was.
Closing RigAlong (the notification's Stop, or its task swiped away) gives
the keyboard back the same way. Putting RigAlong in the background is not
leaving the stage: it keeps playing.

## Connecting a CP88/CP73

1. Connect the keyboard's USB TO HOST port to the tablet with a USB A-B cable,
   through a USB-C adapter or a hub if needed.
2. Start RigAlong. Out of the box it plays through Android (ALSA). The
   first time, it asks to show a notification: RigAlong keeps playing when
   it is not in front, and its notification is what says so and has the
   **Stop** button, which closes the instrument and gives the keyboard back
   to Android.
3. In Setup, set audio and MIDI to **USB device**. RigAlong asks once for the
   microphone: Android offers "Always" for a USB audio device only to apps
   allowed to record (RigAlong records only when you measure latency). Then
   allow RigAlong to use the keyboard and choose **Always**, so that a
   replugged keyboard comes back without asking in the middle of a set. Both sound and notes
   then travel over that single cable, and the tablet's sound comes out of
   the keyboard's own outputs.

   Plugging the keyboard in never starts RigAlong. While RigAlong is not
   running, the keyboard is an ordinary sound card for the tablet's other
   apps; RigAlong takes it when you start it, and gives it back when you
   close it.
4. Mind the volume: the direct modes bypass Android's volume. Use the Master
   fader on the stage.
5. Save the setup as an environment, for example "Stage".
