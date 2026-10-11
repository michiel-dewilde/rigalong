# Bag-O-Synths — user manual (draft)

Bag-O-Synths turns an Android tablet into a sound module for a stage piano:
six synthesis engines that you play from your keyboard, organised into songs
and setlists, switched from the screen, a footswitch, or the keyboard's own
buttons. It works with any class-compliant USB MIDI keyboard. It has
particular support for the Yamaha CP88/CP73, which can carry both the notes
and the sound over a single USB cable.

*Bag-O-Synths is an independent project, not affiliated with or endorsed by
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

The line beside the tabs reports the audio path and the tablet's
temperature. It turns amber when something needs attention.

### Stage

What you play from.

* **Song**: its name, what comes next, and ◀ ▶ to move through the setlist.
* **Live sets**: the large buttons. A live set is one state of the song, such
  as "Verse", "Chorus" or "Solo". Switching never cuts a note: a key you are
  holding keeps sounding until you release it, even if the new live set no
  longer uses that sound, and tails ring out naturally.
* **Levels**: one fader per sound the song uses. Faders move only when you
  drag them, from where they are; touching one does nothing. "Store levels in
  this live set" (hold it) makes the current levels the live set's own.
* **Master**: the overall volume, at the right. It belongs to the room, not
  to the song, so no live set changes it. Hold its label to return to 0 dB.
* **Organ**: when the song uses the organ, its nine drawbars, Leslie (stop /
  slow / fast) and percussion are here.
* **Tempo**: the song's tempo with a blinking beat. TAP sets a tempo by
  tapping, four times or more; "save" keeps it in the song.
* **Click**: COUNT plays a count-in of the song's count-in bars (one if it
  names none); with a continuous click (Setup) the button is CLICK and runs
  until STOP. While it runs, the beat shows its number in the bar, in step
  with what you hear. Another song stops it.
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

**The CP88/CP73's own live set buttons** select the current song's live
sets: set 1 selects live set 1, and so on.

### Songs

Where songs are made. On the left is the setlist that is on stage: change
the order with ▲ ▼, remove a song with ✕, or add one. On the right is the
song being edited:

* **Engines**: which of the six sounds the song uses: Piano (SFZ sampler),
  Organ (tonewheel), Synth (Surge XT), FM (DX7), Texture (Plaits) and SF2
  (SoundFonts). For each, a patch from what is installed, and a level.
  * **Patch…** shows every setting of that sound (drawbars, models, sends,
    transposition) as sliders over their real ranges. What you change is
    heard at once and kept in the song.
  * "Take current" keeps what the engine has now; "Clear" goes back to the
    plain patch.
* **Tempo**, if the song has one, with its beats per bar and count-in bars,
  and a **Lead sheet…** (a PDF).
* **Live sets**: for each live set and each engine:
  * whether it **plays**;
  * a **key range**, from low to high, which makes a split;
  * **transposition** in semitones;
  * the MIDI **channel** it listens to (0 means any);
  * optionally its **own level**.

  Copy a live set to make the next one quickly.
* **CP-88**, per live set:
  * **Leave**: the keyboard plays whatever it has;
  * **Select**: switching to this live set selects one of its stored sounds
    (page and set);
  * **Own sound**: the song carries a keyboard sound of its own. Set the
    sound up on the CP88/CP73, then press "Import current sound". Unsaved
    edits and the panel's knobs are included. When the song is opened, its
    sounds are written into the keyboard's reserved page (see Setup): live set
    1 in set 1 of that page, and so on.

  When the keyboard's own live set buttons select the app's live sets (the
  default), every set of the reserved page that a live set doesn't give a
  sound of its own holds a **silent** sound. Pressing a button on the
  keyboard then never brings in its own instruments by accident.

**Save** stores the song. Earlier versions are kept in the library. The keys
at the bottom play the current live set without a keyboard attached.

### Library

Every setlist and song, with its history.

* Open a setlist on the stage, make a new one, or add any song to the
  setlist on stage.
* **Export this setlist…** writes the setlist and all of its songs, CP-88
  sounds and lead sheets included, into one file.
* **Import…** reads such a file back, on this tablet or another one.
  Importing never overwrites: a song changed on both sides is kept in both
  versions.
* **Install instrument (zip)…** adds an SFZ sample library from a zip file.
  It then appears in every Piano patch list.

### Setup

* **Environment**: your rigs, such as "Stage" and "Home". Each one remembers
  the devices and their modes, the CP-88 settings, the footswitches, the
  lead-sheet options, the click, Do Not Disturb and the master volume.
  * Tap one to switch to it.
  * "Suggest" marks the ones whose devices are all plugged in.
* **Audio out** and **MIDI in and out**: the device and how it is used.
  * **ALSA** goes through Android.
  * **USB interface** and **USB device** let the app drive the USB device
    directly, for the lowest latency. These are needed for sending anything
    to the CP88/CP73, and "USB device" is the one to prefer.
* **USB buffer**: 6 or 8 ms of audio queued on the wire, in the direct modes.
* **CP-88**:
  * the **reserved page** that songs write their own sounds into (page 40 by
    default). Its eight sounds are overwritten.
  * the **MIDI channel** it listens on.
  * whether its **live set buttons select the app's live sets**.
* **Footswitches and MIDI controllers**: a controller for the next and one
  for the previous live set (or lead-sheet page). Press **Learn**, then the
  pedal.
* **Stage**: Do Not Disturb while the app is in front. Notifications and
  calls are silenced; alarms and media are not. Android asks you once to
  allow it.
* **Click**:
  * a count-in only, or a continuous click;
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
* **Status**:
  * every figure about the audio and MIDI path, for when something sounds
    wrong;
  * **Measure latency**: sends a click through your speakers and listens for
    it on the tablet's microphone;
  * the **MIDI monitor**: the last messages to and from the keyboard, the
    first place to look when a pedal or button seems to do nothing.

---

## Connecting a CP88/CP73

1. Connect the keyboard's USB TO HOST port to the tablet with a USB A-B cable,
   through a USB-C adapter or a hub if needed.
2. Start Bag-O-Synths and allow it to use the USB device ("always allow").
3. In Setup, set audio and MIDI to **USB device**. Both sound and notes then
   travel over that single cable, and the tablet's sound comes out of the
   keyboard's own outputs.
4. Mind the volume: the direct modes bypass Android's volume. Use the Master
   fader on the stage.
5. Save the setup as an environment, for example "Stage".
