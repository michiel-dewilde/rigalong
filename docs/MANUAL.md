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
**Setup**. On the Stage screen the other three open only on a **long
press**, so a stray touch in the middle of a song never takes you off the
stage. The red **PANIC** button stops every sound, at once, everywhere.

### Stage

What you play from.

* **Song**: its name, what comes next, and ◀ ▶ to move through the setlist.
* **Live sets**: the large buttons. A live set is one state of the song, such
  as "Verse", "Chorus" or "Solo". Switching never cuts a note: a key you are
  holding keeps sounding until you release it, even if the new live set no
  longer uses that sound, and tails ring out naturally.
* **Levels**: one fader per sound the song uses. Faders move only when you
  drag them, from where they are; touching one does nothing. "Store levels in
  this live set" (hold) makes the current levels the live set's own.
* **Organ**: when the song uses the organ, its nine drawbars, Leslie (stop /
  slow / fast) and percussion are here.
* **Tempo**: the song's tempo with a blinking beat. TAP sets a tempo by
  tapping, four times or more; "save" keeps it in the song.
* **Lead sheet**: when the song has one, it takes the left part of the screen
  (the top in portrait), one or two pages at a time.

**Footswitches and page turners** (Bluetooth page turners such as the
PageFlip Butterfly work as keyboards): Page Down, right arrow or space goes
forward, Page Up or left arrow goes back. When the song has a lead sheet they
turn pages; otherwise they move between live sets (Setup can change this).

**The CP88/CP73's own live set buttons** select the current song's live
sets: set 1 selects live set 1, and so on.

### Songs

Where songs are made. On the left is the setlist that is on stage: change
the order with ▲ ▼, remove a song with ✕, or add one. On the right is the
song being edited:

* **Engines**: which of the six sounds the song uses: Piano (SFZ sampler),
  Organ (tonewheel), Synth (Surge XT), FM (DX7), Texture (Plaits) and SF2
  (SoundFonts). For each, a patch from what is installed, and a level.
* **Tempo**, if the song has one.
* **Lead sheet…**: attach a PDF.
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
    sounds are written into the keyboard's reserved page (see Setup) and
    selected live set by live set.

**Save** stores the song. Earlier versions are kept in the library.

### Library

Every setlist and song, with its history. Open a setlist on the stage, make
a new one, or add any song to the setlist on stage. **Export this setlist…**
writes the setlist and all of its songs, CP-88 sounds and lead sheets
included, into one file. **Import…** reads such a file back, on this tablet
or another one. Importing never overwrites: a song that was changed on both
sides is kept in both versions.

### Setup

* **Audio out** and **MIDI in and out**: the device and how it is used.
  * **ALSA** goes through Android.
  * **USB interface** and **USB device** let the app drive the USB device
    directly, for the lowest latency. These are needed for sending anything
    to the CP88/CP73, and "USB device" is the one to prefer.
* **USB buffer**: 6 or 8 ms of audio queued on the wire, in the direct modes.
* **CP-88**:
  * the **reserved page** that songs may write their own sounds into
    (page 40 by default). Its eight sounds are overwritten.
  * the **MIDI channel** it listens on.
* **Do Not Disturb** while the app is in front: notifications and calls are
  silenced; alarms and media are not. Android asks you once to allow it.
* **Lead sheets**:
  * two pages side by side, in landscape;
  * whether a turn moves one page or two;
  * whether the page turner turns pages or live sets.
* **Status**: every figure about the audio and MIDI path, for when something
  sounds wrong.

---

## Connecting a CP88/CP73

1. Connect the keyboard's USB TO HOST port to the tablet with a USB A-B cable,
   through a USB-C adapter or a hub if needed.
2. Start Bag-O-Synths and allow it to use the USB device ("always allow").
3. In Setup, set audio and MIDI to **USB device**. Both sound and notes then
   travel over that single cable, and the tablet's sound comes out of the
   keyboard's own outputs.
4. Mind the volume: the direct modes bypass Android's volume.
