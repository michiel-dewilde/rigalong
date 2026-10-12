# Requirements: RigAlong, from prototype to usable app

Collected from Michiel, one round at a time. **This is a requirements list, not
a plan.** No plan is made until Michiel asks for one explicitly.

How to read it:

* **Stated**: Michiel said so, with the date.
* **Interpreted**: my reading of what he said, or what follows from it. It
  becomes Stated once he confirms it, and is corrected or struck out otherwise.
* **Assumed**: inferred from the prototype or the project's history and not yet
  discussed.
* **Risk**: something in the requirement that may not work as stated, with the
  reason why.
* **Open**: asked and not yet answered.
* An answer that changes an earlier one replaces it, and the decision log at the
  bottom keeps the old version and why it changed.

---

## 0. Vocabulary

| term | meaning |
|---|---|
| **Environment** | The rig: the named ports and what each is bound to on this tablet, plus external synths and the CP-88 live set link. There are several, and one is active. |
| **Port** | An abstract, named endpoint: audio in/out (mono or stereo) or MIDI in/out. Live sets use ports only by name. |
| **Binding** | What a port is attached to concretely: an ALSA (AAudio/MidiManager) device and its channels, a USB interface, a whole USB device, or an external synth. |
| **External synth** | A sound source outside the tablet (the CP-88) that is controlled over MIDI and whose audio reaches the PA by its own path. |
| **Song** | The live sets one song needs, in order, plus the instances they share. (Called "bank" in round 1.) |
| **Live set** | One state of a song: which instances sound, their parameter values, the wiring and MIDI processing, the CP-88 sound, and the stage controls. Named, copyable, editable. |
| **Instance** | A named block in a song: a synth (engine plus patch) or an effect. |
| **Wire** | A connection carrying MIDI or audio, never both. |
| **Setlist** | An ordered list of songs for a gig. |
| **Library item** | Anything the app stores that can be managed, exchanged, imported and exported (section 8). |
| **CP-88 Live Set Sound** | Always written out in full, to keep it apart from the app's own live sets. Addressed as page (1-40) : set (1-8). |

## 1. Purpose and users

Stated (2026-10-10)

* S1.1 The app is for Michiel and for other people. The end goal is a Google Play
  release.
* S1.2 The whole current UI goes. It is replaced by a thorough configuration
  interface and a practical live-playing setup.

Interpreted

* I1.1 Other users have other keyboards, tablets and interfaces, so nothing may
  depend on the CP-88. The CP-88 is one external synth, with a profile and a
  live set link, that others may not have.
* I1.2 Live sets are portable between rigs and between users because they name
  ports, never devices. The same live set runs at home on a Scarlett and on
  stage on the CP-88 once each environment binds the ports.

## 2. Views (Stated 2026-10-10, refined)

1. **Environment**: define the ports and bind them; define external synths;
   define the CP-88 live set link; switch between environments.
2. **Live set configuration**: the graph editor; the stage controls; the MIDI
   mapping. Also where songs and setlists are put together.
3. **Stage / live**: switch songs and live sets; work the exposed controls.
4. **Patch editors**: one per engine that has editable patches; create and
   change patches.
5. **Library**: browse, import and export everything the app stores.

## 3. Environment

Stated (2026-10-10)

* S3.1 Abstract ports: mono or stereo audio in/out, MIDI in/out.
* S3.2 Bindings: an ALSA device, an individual USB interface, a whole USB
  device, or a CP-88 as an external synth.
* S3.3 ALSA bindings expose channel selection, sample rate and bit depth, plus a
  quick choice among the recommended combinations that the platform grants as
  exclusive and MMAP.
* S3.4 USB bindings keep working as now: the reap loop on the borrowed AAudio
  (SCHED_FIFO) thread.
* S3.5 USB supports class-compliant ("canonical") audio and MIDI, both generic,
  for any audio sync mode, and trained per device by profile, as fbx does for
  the CP-88. Every option is as selectable as in ALSA.
* S3.6 Selecting live sets from the CP-88's buttons, and selecting the CP-88's
  live set sound from the app, is configured in the environment.
* S3.7 **Only clean clock situations are supported.** No resampling between
  clock domains.
* S3.8 A few environments, with switching between them.
* S3.11 (2026-10-11, refines S3.7) **Clock islands.** A song's graph splits
  into islands, one per audio device, each rendered on that device's clock.
  No audio wire joins two islands: a synth's output cannot go to two
  devices. Independent sources (a click, a synth, an effect chain) may each
  sit on their own device. MIDI and tempo cross islands freely.
* S3.12 (2026-10-11) **The retimer** is the one explicit exception: a node whose
  inputs and outputs lie in disjoint islands, carrying audio from one clock
  to another. It can optionally **dictate its own clock** to its input side,
  so that an island with no audio device in it still has a clock to render
  on.
* S3.10 The app can **suggest** an environment from the devices plugged in, as an
  explicit action the user takes. It never switches environments by itself.
* S3.9 **USB device profiles have a documented format**, and users can supply
  their own. A guided in-app measurement is not a requirement: latch
  detection needs a bench setup (a second interface on the line out, or a
  wire monitor) that most users do not have.

Interpreted

* I3.1 Live set switching follows the CP-88 in both directions. Pressing a
  live set button on the CP-88 selects the matching in-app live set, and
  selecting an in-app live set selects its CP-88 sound. The environment holds
  that mapping and suppresses the echo so the two never ping-pong.
* I3.2 A live set that uses a port the current environment does not bind still
  loads, and shows which ports are unbound. Wires to an unbound port are
  inactive.
* ~~I3.3 All audio ports of an environment on one audio device.~~ Replaced by
  S3.11: any number of audio devices, one island each.
* I3.8 The graph editor colours islands and refuses an audio wire that would
  join two of them, offering a retimer instead.
* I3.9 A retimer between two devices is an asynchronous sample-rate converter
  that tracks the drift between the clocks. It adds a few milliseconds of
  latency, shown on the node.
* I3.10 A retimer that **dictates the clock** of its input island drives it at a
  fixed ratio to its output device's clock: no drift, only a fixed-ratio
  conversion. That is also how an engine runs at its own native rate (Plaits
  at 48 kHz into the CP-88's 44,1 kHz, as the prototype already does inside
  the Plaits node), made explicit. (Q-K1)
* I3.11 Each island with a device costs a render thread on that device's
  callback.
* I3.12 (2026-10-11) **An island is a clock, not a port.** A device's audio in
  and out share its clock, so CP-88 in -> effects -> CP-88 out is one island
  and needs no retimer. On the CP-88 this is literal: its OUT stream is paced
  by its IN stream (implicit feedback), and in the direct USB modes fbx
  already runs both on one loop. The environment derives the islands from
  the devices. A UAC2 device that declares separate clock sources for its
  inputs and outputs is two islands.
* I3.14 (2026-10-11) **How the CP-88 handles audio** (Owner's Manual p. 27, and
  LOG.md 2026-09 for the measured part):
  * USB audio **in** (from the tablet) goes to every output: OUTPUT XLR,
    OUTPUT jacks, PHONES. Its level is the "USB Audio Volume" setting.
  * USB audio **out** (to the tablet) is identical to the main outputs: the
    CP-88's own sections. It does **not** carry the USB input back (measured:
    exactly zero), so there is no feedback loop. No setting sends a section
    to USB only.
  * The **INPUT [L/MONO]/[R]** jacks (analog line, with a GAIN knob) are mixed
    into the outputs and **never sent over USB**. They are the CP-88's
    "through": anything plugged in there reaches the PA through the CP-88
    with no latency, without the tablet.
* I3.15 **A mic on a Focusrite, heard through the CP-88**, three ways:
  * **A (recommended): through the CP-88's INPUT jacks.** Focusrite in ->
    tablet effects -> Focusrite out -> cable -> CP-88 INPUT -> PA. One island
    (the Focusrite), no retimer; latency is the Focusrite's own round trip.
  * **B: retimed into the CP-88's USB.** Focusrite in -> tablet -> retimer ->
    CP-88 USB out. Works, at the retimer's few ms on top of both devices'
    latencies.
  * **C: no tablet.** Mic preamp straight into the CP-88's INPUT jacks.
  A and B both need the Focusrite and the CP-88 on the tablet's one USB-C
  port at once, so a hub. fbx has been measured behind hubs (0 deaths).
* I3.16 **Retimers: automatic, but visible** (proposal, Q-K3). Drawing an audio
  wire across islands inserts a retimer by itself instead of being refused.
  The wire shows a badge with the latency it adds, and the retimer can be
  opened and set (its buffer target). Not hidden: this project exists
  because of latency, and a retimer spends it.
* I3.17 With automatic retimers:
  * every node still renders in exactly **one** island, by default the island
    of its first audio output, changeable in the editor. A synth wired to two
    devices then sounds on the second through a retimer, a few ms later;
  * an island without a device no longer occurs, so the **dictated clock of
    S3.12 is dropped** unless a use for it turns up (the mic case does not
    need it);
  * **declaring devices locked (I3.13) is dropped**: a retimer between two
    genuinely locked devices measures a ratio of exactly 1 and costs only
    its small buffer.
* S3.13 (2026-10-11) I3.16 and I3.17 confirmed: retimers automatic and
  visible; the dictated clock and the locked-devices declaration dropped.
* S3.14 (2026-10-11) **Whether two ports share a device is decided by the
  environment**, not by the song. A song only names ports.

Interpreted

* I3.18 **Islands and retimers are derived at binding time.** When a song is
  loaded (preloaded) in an environment, or the environment is switched,
  the app maps its ports to devices, forms the islands, and inserts
  retimers where an audio wire crosses two. The song stores **no**
  retimers. The same song has none in an environment where everything is
  on one Scarlett, and one in an environment where the mic is on a
  Focusrite and the PA on the CP-88.
* I3.19 The song editor shows islands and retimer badges **as bound in the
  active environment**, and can preview against another environment ("how
  does this song come out on the stage rig?"). A song can be checked
  against every environment at once: which ones would need retimers, and
  where.
* I3.20 Retimer settings (buffer target) belong to the **environment**, per pair
  of devices, since that is where the pair exists.
* I3.21 Which island a node renders in follows the same rule at binding time
  (the island of its first audio output). A song can state a preference
  by **port** ("render with the port Main Out"), never by device.
* I3.13 Two devices locked to one external clock (word clock, S/PDIF) are still
  two islands unless the user declares them locked in the environment. (Q-K2)
* I3.4 The CP-88 external synth has a **reserved page range** set in the
  environment: the pages the app may write songs into (section 5).
* ~~I3.5 The app could suggest an environment from the devices plugged in.~~
  Settled by S3.10.
* I3.6 The profile format document also says how a profile is measured,
  building on `tools/fbx/PROFILING.md`. A profile is a library item (8.1).
* I3.7 A device without a profile runs on the generic class-compliant path
  (S3.5), not refused.

Risks

* R3.2 **Generic direct USB is new work.** Direct audio today drives
  implicit-feedback devices only; explicit-feedback and no-feedback support
  were removed (PROVISIONAL.md). Supporting them generically means rebuilding
  them and testing on hardware that has them.
* R3.3 **USB MIDI out does not exist yet.** The direct path reads MIDI only.
  Driving the CP-88 (Program Change, SysEx) and other external synths needs
  MIDI out on every binding type.
* ~~R3.4 Whether the CP-88 transmits its live set selection.~~ **Verified
  2026-10-11 (LOG.md):** at default settings it sends Bank Select MSB 63, LSB =
  page - 1, Program Change = set - 1, on channel 1, for every selection and
  for a page change alone. I3.1's CP-88-to-app direction works.
* R3.6 **In ALSA mode the app may not see the device's clock.** On this tablet
  the HAL converts the CP-88's 44,1 kHz to the MMAP port's 48 kHz (HANDOFF,
  section 47). In and out then pass through separate conversions, so whether
  they stay locked sample for sample, as one island requires, is to be
  measured. In the direct USB modes the question does not arise.
* R3.7 **Feeding the CP-88's own sound back through the tablet doubles it.**
  The CP-88 mixes its sections to its outputs directly, so its sound returned
  through the tablet arrives a second time, 15-27 ms later (the measured
  direct-USB round trip): a comb filter, not an effect. **Checked: the CP-88
  cannot avoid it** (I3.14). Processing the CP-88's own sound in the tablet
  only makes sense when the PA is fed from another device, not from the
  CP-88's outputs.
* R3.5 The borrowed-FIFO-thread trick depends on how a given Android build
  schedules AAudio callbacks. It is measured on this tablet only.

## 4. Songs, live sets and instances

Stated (2026-10-10)

* S4.1 A live set wires named audio and MIDI ports through synths (a specific
  engine with a specific, configurable patch) and/or effects (gain, reverb,
  ...). A wire carries MIDI or audio.
* S4.2 MIDI processing: all of filter and remap by channel, message-type filter,
  CC filter and remap (number and range), key range (splits), velocity range
  (layers), transpose and octave shift.
* S4.3 A live set is a named entity that can be copied and edited.
* S4.4 A different sound for held notes generally means a different instance.
  But a different live set sometimes only means a different parameter of the
  same synth, and that case must be handled too.
* S4.5 The CP-88 can be added to a live set as an external synth and configured
  like an internal synth. Its MIDI and audio are not wired, because it has its
  own built-in path.
* S4.6 With a CP-88, an in-app live set can add tablet sounds on top of the
  CP-88's live set sound, and/or control the CP-88's live set sound itself.

Interpreted: the answer to S4.4 (to confirm)

* I4.1 **Instances belong to the song; live sets are states of them.** A song
  holds a set of named instances, each an engine plus one patch. Each live set
  of the song says which instances sound, at what level, through which wiring
  and MIDI processing, and with which **parameter values** on top of the patch.
* I4.2 **Switching live sets inside a song:**
  * an instance active in both keeps playing; changed parameters move to their
    new values, ramped, so held notes follow, like turning a knob mid-note
    (drawbars, Leslie speed, filter, a layer's level);
  * an instance active only in the old live set fades out on note-off and its
    tails ring out;
  * an instance active only in the new live set is already loaded (the whole
    song is preloaded) and simply comes in.
* I4.3 So **a different patch means a different instance**, and "held notes keep
  the old sound" falls out of that. A different parameter value means the same
  instance. The editor makes the choice visible: changing the patch of an
  instance in one live set asks whether to change it for the whole song or to
  split off a new instance.
* I4.4 Per changed parameter, an optional transition time (default: the usual
  short ramp, e.g. 15 ms; longer for a deliberate morph).
* I4.5 Across songs, instances are not shared. A song switch may reuse an
  already loaded engine with the same engine and patch, as an invisible
  optimisation (no reload of the same 1,2 GB piano).
* I4.6 "Same name" means same engine type too.

Stated (2026-10-10)

* S4.7 The song model of I4.1-I4.5 is confirmed: instances belong to the song,
  live sets are states of them.

Stated (2026-10-10): one graph per song, as below

* I4.9 **One graph per song.** The song owns one graph: every instance, every
  MIDI processor, every wire. A live set never changes the topology. It
  changes values: which nodes are enabled, every node parameter (including
  the MIDI processors' split points, transposes and channel maps), and the
  **on/gain of every wire**. Routing differences between live sets are wires
  switched on and off, not wires added and removed.
* I4.10 Why not a graph per live set:
  * a switch would recompile the graph and rewire running instances; a voice
    whose output wire disappears is cut off with its tail, and keeping the
    old wiring alive until tails end is the hard part of per-live-set graphs;
  * with one graph, a switch is only parameter changes, so it is as instant
    and as click-free as a fader move, and tails ring out through the wiring
    they started in;
  * preloading the song (S5.3) and preloading its graph become the same thing.
* I4.11 The cost: a song's graph contains every variant at once, and can get
  crowded. The editor therefore shows the graph **as seen from one live set**:
  disabled nodes and switched-off wires dimmed, or hidden on request.
* I4.12 A disabled effect either bypasses at once or lets its tail ring out,
  chosen per node.
* S4.8 **A note-off reaches the instance that received its note-on**, whatever
  live set change happened in between.

Interpreted

* I4.14 Made concrete, the MIDI path tracks every sounding note from the input
  port to the instance(s) it reached:
  * the note-off follows the **route of its note-on**, even when the new live set
    has switched that wire off, disabled the node, moved the split point, or
    remapped the channel;
  * it carries the **transformation of its note-on**: a note played under
    transpose +12 is released as that note, even if the transpose changed in
    between;
  * the same holds for **sustain**: a pedal-up after a switch reaches every
    instance that holds notes sustained by that pedal (and, through I4.13,
    the newly active ones too);
  * the same holds for **polyphonic aftertouch** of a held note;
  * a layered note (one key into several instances) is released in all of them.
* S4.9 (2026-10-11) The CP-88 external synth's edit page can **import the
  CP-88's current active configuration** into a patch (a CP-88 Live Set
  Sound, in cp-liveset's YAML form).

Interpreted

* I4.16 **Verified possible in Yamaha's documentation.** The CP88/CP73
  Supplementary Manual v2.00 (MIDI Parameter Change Table, Bulk Control)
  lists bulk header/footer address `0E 7F 00` / `0F 7F 00` as the
  **Current Sound Buffer**, beside the 320 user slots at `0E pp 0n`. A Bulk
  Dump Request to it returns the sound as it is now, unsaved edits
  included. cp-liveset addresses only page:set today; the ported codec adds
  this address. One read takes about 0,4 s (cp-liveset's measured round
  trip).
* I4.17 The same edit page can also import from a given page:set on the
  device, from a `.X9S`/`.X9P`/`.X9L`/`.X9A` file, and from a cp-liveset
  YAML or JSON file, and export to all of these.
* I4.18 The **parameter changes** that live CP-88 stage controls need (I4.7)
  address the same current sound buffer, block by block (common, zones,
  sections), so the edit page can also audition edits on the CP-88 as they
  are made.

Stated (2026-10-11)

* S4.12 (2026-10-11) **When the CP-88's buttons select the app's live sets, the
  CP-88 must not mix in its own instruments.** Pressing set n also switches the
  CP-88 to that slot, so wherever a song does not give the CP-88 a sound of
  its own, the slot must hold a silent ("empty") sound.

Interpreted (built 2026-10-11)

* I4.19 When the CP-88 follows (a Setup switch, on by default), live set n owns
  slot n of the reserved page. Opening a song writes the whole page: a live
  set's own sound where it has one, a silent sound (all three sections off,
  volume 0) everywhere else -- except live sets that select a sound elsewhere.
  A live set that leaves the CP-88 alone selects its silent slot. Slots
  already holding the same sound are not rewritten, to spare the flash.
* I4.20 Consequence: a song has at most 8 live sets that the CP-88's buttons
  can reach (one page), and at most 8 own sounds.

* S4.10 A CP-88 patch is stored and hashed as the **byte-exact bulk dump**.
  cp-liveset's YAML is the view: what the editor shows and what import and
  export accept.
* S4.11 Getting a song's CP-88 sounds onto the CP-88 uses **reserved slots
  only** (S5.3). Writing the current sound buffer at a switch is not a
  switching method. The current buffer is used for import (S4.9) and for
  auditioning edits with parameter changes (I4.18).

Superseded

* Q-C3 The stored, hashed form of a CP-88 patch: the byte-exact bulk dump (my
  recommendation: stable for good), with cp-liveset's YAML as the editing
  and exchange view; or the YAML itself? Concise YAML leaves out values
  equal to cp-liveset's factory-default table, so a change to that table
  would change the text, and the hash, of an unchanged sound.
* Q-C4 The current sound buffer can also be **written**. That allows "own"
  (I4.7) without touching the user's slots: on a switch, send the live
  set's sound into the current buffer instead of selecting a preloaded
  slot. The cost is the send time (17 data blocks) at every switch, and
  possibly an audible change while it lands. Keep the reserved slots
  (S5.3) as the way, offer the buffer as an alternative where no slots may
  be used, or measure both first?

Risk

* R4.3 To measure: how long writing the current sound buffer takes, and what
  the CP-88 sounds like while it does.

* I4.15 A panic action (all notes off, all sound off, to every instance and
  every MIDI out port) is always reachable on the stage view.
* I4.13 **Controller state chase.** On a switch, an instance that becomes
  active receives the current state of the continuous controllers: sustain
  pedal, expression, mod wheel, pitch bend. Otherwise a pedal held through
  the switch does nothing to the new sound until it is pressed again.
* I4.7 The CP-88 node in a live set controls the CP-88 at one of three levels:
  * **leave it**: the CP-88 plays whatever it has;
  * **select**: switching to this live set selects a given, existing page:set;
  * **own**: the live set holds the full CP-88 Live Set Sound (via the
    cp-liveset data model), edited in the app like a synth patch.
  Its parameters can also be exposed as stage controls, sent as CP-88
  parameter changes.
* I4.8 cp-liveset's codec (Python, MIT) is ported into the app. MIT is
  compatible with GPL-3.0-or-later.

Risks

* R4.1 Parameter changes on a held note depend on the engine. Most parameters
  of every engine apply at once to sounding voices. A few act only at note-on
  (e.g. some sfizz opcodes, a DX7 algorithm change, an oscillator type in
  Surge). Those take effect on the next note. The editor should mark them.
* R4.2 **Memory.** Preloading a whole song bounds a song's size: the sum of
  its instances must fit in RAM next to everything else (the tablet has
  12 GB; a sampled piano is 1,2 GB).

## 5. Setlists, preloading and the CP-88

Stated (2026-10-10)

* S5.1 Setlists contain songs; songs contain live sets.
* S5.2 The stage view has buttons to select a live set and a button for the
  next song.
* S5.3 Choosing a song in the stage view **preloads all its live sets**,
  including writing its CP-88 Live Set Sounds into the CP-88, so switching
  between them is immediate. Any chosen song can be preloaded this way, not
  only the current one.

Interpreted

* I5.1 On the CP-88, a switch is then a Bank Select + Program Change to a slot
  that already holds the sound. The writing happens at song selection, never
  at a live set switch.
* I5.2 The writing goes to the reserved page range (I3.4), one page per song
  (8 sets), or more pages for a song with more than 8 CP-88 sounds.
* I5.3 **Preloading the next song while the current one plays** needs at least
  two reserved areas used in turn, so the slots in use are never overwritten.
  The app does the same for its own instances, within the memory limit (R4.2).
* I5.4 Before first writing into the reserved pages, the app reads their
  current contents and keeps them as a backup library item.
* I5.5 A song may also use "select" (I4.7) only, which writes nothing.

Risks

* R5.1 Writing SysEx to the CP-88 while it plays may glitch its sound or
  delay its keys. To be measured: how long a 19-message write takes, and
  whether the CP-88 keeps playing cleanly during it. If it does not,
  preloading the next song during the current one is not possible for the
  CP-88 part.
* R5.2 Does the CP-88 write its slots to flash on every bulk dump? If so,
  wear and write time are a concern. To be checked.

## 6. Controls

Stated (2026-10-10)

* S6.1 Users choose which controls appear on the stage display (e.g. the
  setBfree drawbars).
* S6.2 Users choose which controls and buttons are in the UI and/or set from
  MIDI.

Interpreted

* I6.1 Each exposed control is a parameter of an instance (internal or
  CP-88), with a label, a range, a widget type (fader, knob, drawbar, toggle,
  button, selector), and an optional MIDI binding (port, channel, CC or note,
  range, curve). There is a MIDI-learn function.
* ~~I6.2 Pickup per control.~~ Folded into I6.6.

Stated (2026-10-10)

* S6.3 It must be possible to specify values that are applied **every time** a
  live set is selected.
* S6.4 Open whether other knobs and sliders keep their value across a switch or
  return to defaults. Michiel asked for help designing this.

Stated (2026-10-10): the design below, as recommended, including Resume

* I6.3 **Values come in layers.** Every parameter of the song's graph has a
  **song value** (the graph as built). A live set can **override** it. The
  stored value of a parameter in a live set is the override if there is
  one, the song value otherwise.
* I6.4 **Every parameter has a recall mode**, set at song level and changeable
  per live set:
  * **Recall** (the default): selecting the live set sets the stored value,
    every time. This is S6.3.
  * **Keep**: selecting the live set leaves the current value alone, whatever
    the previous live set or the performer's hand left there. For things like
    a volume pedal, Leslie speed, or a filter the player rides through a song.
  * **Resume**: selecting the live set restores the value it had when this
    live set was last left, in this session. The first time, it is the
    stored value. For "my tweaks to the solo sound survive a trip to the
    verse and back".
* I6.5 Recalled values move with the transition time of I4.4, so a recall
  never clicks.
* I6.6 **Physical controls against recalled values.** A recall may put a value
  somewhere the physical knob is not. Per control, one of:
  * **pickup** (default): the knob acts only once it passes the value;
  * **jump**: the knob takes over at once;
  * **scale**: the knob moves the value proportionally from where it is
    toward the end it is turned to.
  The stage view shows the value and, while they differ, where the knob is.
* I6.7 **On-select actions.** Besides values, a live set can send MIDI messages
  to any MIDI port when selected (Program Change, CC, SysEx to other gear).
  The CP-88 selection of I4.7 is a built-in action of this kind.
* I6.8 **Global controls** live outside songs (in the environment or the stage
  setup) and are never recalled: master volume, a global FX send, the
  sustain pedal's meaning. A song switch therefore cannot jump the master
  volume.
* I6.9 Snapshot capture: "store current values into this live set" turns
  what is sounding now into overrides, for every parameter or a selection.

## 7. Patch editors

Stated (2026-10-10)

* S7.1 Every engine with patch editing gets an editor to create and change
  patches.
* S7.2 Simplest editors first; Surge XT's if feasible.

Interpreted

* I7.1 Order by size: FluidSynth (preset, a few channel settings), sfizz
  (instrument, volume, tuning, polyphony, the instrument's own CCs), Plaits
  (model plus about a dozen), setBfree (drawbars, percussion, vibrato,
  Leslie, preamp), Dexed (155 voice parameters, Dexed's layout as the
  reference), Surge XT.
* I7.2 **A generic editor driven by each engine's parameter metadata** (name,
  range, unit, group), with custom panels only where they matter (drawbars,
  the DX7's operators and algorithm). Surge XT carries full metadata for
  every parameter, so a grouped generic editor covers most of a patch
  cheaply. Its modulation routing is the part that needs its own UI, and
  decides whether "if feasible" holds.

Risk

* R7.1 The engines differ greatly in size. Surge XT has hundreds of
  parameters, Dexed 155, setBfree and Plaits a few dozen, and sfizz and
  FluidSynth have hardly any patch to edit. A full Surge editor costs more than
  everything else put together. (Q-P1)

## 8. Library, import and export

### 8.1 What the library holds

Stated (2026-10-10)

* S8.1 A good library and import/export interface.
* S8.2 Everything is a managed, exchangeable library item, including patches,
  live sets, songs, setlists, environments and USB audio profiles.

Interpreted

* I8.1 Item types: SFZ instruments, SoundFonts, DX7 `.syx` banks, Surge
  patches and wavetables, patches for every engine, live sets, songs,
  setlists, environments, USB device profiles, and CP-88 Live Set Sounds (from
  `.X9*` files or read from the device).
* I8.6 Environments exchange well at the port level. Their bindings name
  concrete devices (USB vendor/product id, ALSA names), so on another tablet a
  binding is either matched to the same device or left unbound.
* I8.7 USB device profiles are keyed by vendor and product id (and firmware
  version where it matters). Shipped profiles, user-measured profiles and
  imported ones live side by side. A profile measured on one unit keeps its
  margins for other units.

### 8.2 The representational core

Stated (2026-10-10)

* S8.3 **Items link to each other by the SHA-256 of their content.** That is the
  representational core. A name can never cause a conflict.
* S8.5 How the objects are stored (git, Git LFS, a plain directory, anything
  else) is an implementation choice **outside** the core (section 8.4).

Interpreted

* S8.8 (2026-10-11) I8.9-I8.15 and I8.26-I8.32 confirmed as described.
* I8.9 **Two layers.**
  * **Objects** are immutable and identified by the SHA-256 of their canonical
    content. Patches, live sets, songs, setlists, environments, profiles,
    CP-88 Live Set Sounds and instrument packs are all objects. They refer to
    each other only by hash, so a setlist's hash pins down its every song,
    patch and sample.
  * **Library entries** are the mutable layer: a name (and author,
    description, tags, history) pointing at the current hash. Editing makes a
    new object and moves the entry to it. The previous version stays
    reachable as history.
* I8.10 **Names live outside the hash.** Renaming changes nothing that refers to
  the item, and two identical patches saved under different names are one
  object. Names that matter to the content, such as an instance's name inside
  a song, are part of that song's content.
* I8.11 **Canonical form.** Every object type has one canonical serialisation
  (JSON per RFC 8785 for the app's own types, the exact bytes for foreign
  formats such as a `.syx`, a Surge patch, or a CP-88 bulk dump), so equal
  content always gives the same hash on any device and any app version. The
  core is specified as a format document, so it can be read and written
  without the app.
* I8.12 **Large content as a manifest.** An instrument pack is an object listing
  its files and their SHA-256s. Identical sample files are stored once, and an
  import verifies every file against its hash.
* I8.5 **References are immutable.** A song refers to a patch by hash, so
  editing that patch never changes a song by surprise. "Update from library"
  re-points a reference to a newer version, explicitly.
* I8.13 **An edit ripples upward.** Changing a patch in a song makes a new song
  object, and a new setlist object if the setlist refers to it. The library
  entries move with it. These objects are small JSON, so this is cheap.
* I8.15 Stage-time tweaks (moving a drawbar while playing) are session state,
  not new objects. They become objects only when saved.
* I8.22 **References are recognisable without knowing the type.** Every
  reference in every object has one syntactic form (e.g. a field whose value
  is `sha256:<hex>`), so generic code can find all references of any object,
  including types added later. Export closure, garbage collection and the
  reverse index (I8.24) are generic as a result.
* I8.23 **Entry history is itself objects.** Each version of a library entry is
  an object holding the name, the target hash, the previous version's hash,
  author and time. The only mutable state in the whole library is one
  pointer per entry to its latest version. Two devices that moved one entry
  apart show up as two versions with the same predecessor (I8.21).

Stated (2026-10-10)

* S8.6 Sync uses something canonical.

Interpreted

* I8.24 **Indexes are derived, local, and disposable.** Lookup by name, type,
  tag or author, search, and "which songs use this patch" (the reverse
  references) are a local database built from the objects and entries. It
  is never synced, never exported, and can be deleted and rebuilt at any
  time. Only objects and entry pointers are authoritative.
* I8.25 **Candidates for a canonical sync protocol** (there is no general
  standard for "a SHA-256 keyed database", but there are standards that are
  exactly that shape):
  * **OCI Distribution Spec**, the container-registry protocol, used for
    arbitrary artifacts through ORAS. Blobs addressed by `sha256:` digest,
    an existence check before upload, JSON manifests referring to blobs by
    digest, and mutable **tags**: the two layers of I8.9 exactly. The
    matching on-disk format is the **OCI Image Layout** (`blobs/sha256/...`
    plus `index.json`). Servers: any registry, including self-hosted ones on
    a NAS (zot, distribution, Gitea/Forgejo packages) and GHCR.
  * **Git LFS Batch API**: SHA-256-keyed blob transfer over HTTP with
    have/want negotiation. Blobs only: no mutable names.
  * **A plain folder**: objects at `objects/sha256/<hex>` and entry pointers
    as small files, synced by any file-sync tool. Immutable objects named by
    their hash never conflict, so only entry pointers can.

Stated (2026-10-10)

* S8.7 **OCI** is the canonical exchange and sync format. Not Git LFS.

Stated (2026-10-11): the design below

* I8.26 **Sync** is the OCI Distribution protocol, against a registry the user
  configures (a self-hosted one such as zot or Gitea/Forgejo on a NAS, or
  GHCR). The whole library is **one OCI repository**, so blobs are stored
  once across every entry.
* I8.27 **Export and import files** are an **OCI Image Layout**, as a single
  archive. The same format as sync, offline: standard tools (`oras`) can
  read and push it, and the app needs no second format.
* I8.28 **Each entry is a tag.** A tag names the entry's identity (the hash of
  its first version), not its display name, since tag syntax is restricted
  and names are free text. The display name is in the entry version object.
* I8.29 **Each entry version is an OCI manifest whose layers list its whole
  closure**: every object its hash reaches, down to each sample file. The
  registry then keeps all of them alive (R8.4), and pulling one tag pulls
  everything it needs. Listing the closure costs only digests, because the
  registry stores each blob once. Media types name the app's object types
  (e.g. `application/vnd.rigalong.patch.v1+json`).
* I8.30 Downloadable content that is not the user's to redistribute (I8.3) is
  left out of the closure by default and recorded as "fetch from source".
* I8.31 **Divergence** between devices (I8.21, I8.23) is found by comparing
  version chains after a pull. Both versions are kept and the user chooses,
  never resolved silently.
* I8.32 The plain-folder candidate is dropped. An OCI Image Layout is itself a
  folder, but its `index.json` is mutable, so a folder sync tool would
  conflict on it. Folders are for export, the registry for sync.

Risk

* R8.4 Registries garbage-collect blobs that no manifest reaches. An object
  that another object refers to only by a hash inside its JSON is invisible
  to the registry, so the dependency graph has to be mirrored into OCI
  manifests (an object's manifest listing its dependencies), or the registry
  deletes them.

Risk

* R8.1 Floating-point parameter values must serialise identically everywhere,
  or the same patch gets two hashes. RFC 8785 defines exactly that; it has to
  be used consistently, including for values read back from an engine.

### 8.3 Exchange

Interpreted

* I8.3 **Export bundles dependencies.** Exporting a setlist includes everything
  its hash reaches. Downloadable content (Salamander, a factory library) is
  referenced by hash plus where to get it, and bundled only on request: size,
  and other people's licences.
* I8.4 **Import has no conflicts.** An object whose hash exists already *is*
  that object, so it is skipped. A different object is a different hash, so
  it is added. Only names can coincide, and two items of the same name simply
  both exist, told apart by author, date and short hash.
* I8.8 Import uses Android's file picker and share sheet (no adb), and works
  with archives. Export can share a single item or produce a full backup.
* I8.20 Users never need to know how the library is stored.
* I8.21 **Sync never merges object content.** If two devices both moved one
  entry, both versions are kept and the user chooses. A merge of two patches
  could produce a patch nobody made.

### 8.4 Storage backend: an implementation choice, not a requirement

Notes for when the plan is made, not requirements:

* git for the entries and small objects (history, diff, revert, sync to a
  remote), Git LFS layout for large binaries. Git LFS's object id is the
  SHA-256 of the content, the same as the core's. Git's own object ids are
  not, so with git underneath the app keeps a SHA-256 index of its own.
* JGit (EDL, GPL-compatible) is the realistic git library on Android. Its LFS
  behaviour on Android is to be tried before relying on it.
* Garbage collection of objects that no entry and no history reaches:
  explicit or after a grace period, never during a gig.

## 9. Stage view and lead sheets

Stated (2026-10-10)

* S9.1 The stage view can show the song's **lead sheet as a PDF**, 1-up or
  2-up, turned by a **footswitch** (e.g. a Bluetooth page turner such as the
  PageFlip Butterfly).
* S9.2 That setup belongs to the **environment**: which footswitch, what its
  pedals do, 1-up or 2-up.
* S9.3 A song can store a lead sheet.

Interpreted

* I9.1 The lead sheet is a library object referred to by hash, like a patch.
  A song refers to one (or none).
* I9.2 Bluetooth page turners present themselves as keyboards (arrow keys,
  Page Up/Down). The environment maps those keys to actions: page forward,
  page back, and optionally next/previous live set or song.
* I9.3 2-up turns by one page (half turn: the right page moves to the left)
  or by two pages, chosen in the environment. A half turn lets the player
  read on while the page changes.
* I9.4 Optional: a live set can name the lead-sheet page to show when it is
  selected (the chorus page for the chorus).
* I9.5 The PDF is rendered with Android's own `PdfRenderer`; no dependency.
* I9.6 Besides the lead sheet, the stage view shows: the setlist position and
  the next song, the live set buttons, the exposed controls, the panic
  button. Its layout (sheet size against controls) is a setting of the
  environment, since it depends on the screen.

## 10. External MIDI gear and switching sources

Stated (2026-10-10)

* S10.1 Canonical MIDI device support where possible.
* S10.2 Selecting live sets from external hardware is defined **in the
  environment and in the song**.

Stated (2026-10-11): I10.3 (MIDNAM now, MIDI 2.0 Property Exchange later) and
I10.4 (the switching split) confirmed.

Interpreted

* I10.1 **A generic external MIDI synth node**, besides the CP-88: any device
  on any MIDI port, sent notes, controllers, pitch bend, aftertouch, Bank
  Select plus Program Change, and SysEx. Its audio is either outside the
  tablet (like the CP-88) or comes back on an audio input port of the same
  clock (S3.7).
* I10.2 Transports: USB MIDI (ALSA or direct), Bluetooth LE MIDI, and other
  apps' virtual MIDI ports, all through Android's MIDI service.
* I10.3 **Device descriptions in a canonical format: MIDNAM** (the MMA's MIDI
  Name Document, XML), imported as library objects. It names a device's
  banks, programs and controllers, so an external synth node offers patch
  names instead of numbers. MIDI 2.0 Property Exchange, which lets a device
  report this itself, is a later option.
* I10.4 **Switching sources, in two halves:**
  * the **environment** says *which* inputs may switch, and how: the CP-88's
    live set buttons, a MIDI footswitch, the page turner, Program Change from
    some port, a CC as next/previous;
  * the **song** says *what* each incoming selection means in this song:
    which of its live sets answers to which incoming number or action.
  So one rig can be switched the same way through every song, while each
  song decides its own order.
* I10.5 The external synth instances of a song also receive the switch: a
  live set's on-select actions (I6.7) send them their Program Change, as the
  CP-88 gets its own.

## 11. Effects

Stated (2026-10-10)

* S11.1 The proposed set (EQ, compressor, delay, chorus, rotary, amp
  simulation, reverb, gain) is good. Borrow them from an existing source
  where possible.

Interpreted

* I11.1 **Surge XT's effect units as standalone effect nodes.** They are already
  in the build (GPL-3): reverbs (two algorithms and a spring), delay,
  chorus, ensemble, flanger, phaser, rotary speaker, distortion, waveshaper,
  graphic and parametric EQ, the Conditioner (EQ plus limiter), tape,
  frequency shifter, ring modulator, vocoder, mid/side, and the Airwindows
  collection (dozens more, including compressors and console emulations).
* I11.2 setBfree's Leslie as a standalone node too.
* I11.3 Clouds (granular), already compiled, as listed in the README.

Risk

* R11.1 Surge XT has no dedicated amp/cab simulator beyond what its
  distortion and Airwindows give. If that is not enough, another GPL-
  compatible source is to be found.

## 12. Tempo

Stated (2026-10-10)

* S12.1 Tempo is optional, set per song.
* S12.2 Count-in only, or continuous: an environment setting.
* S12.3 Tap tempo, to set it easily.

Interpreted

* I12.1 The song's tempo drives tempo-synced parameters (delay times, LFO
  rates) of its instances.
* S12.4 (2026-10-11) The environment defines a **destination for the click**. It
  need not share a clock with anything: the click is its own source, so its
  own island (S3.11).
* I12.2 The click is an **audio click to an output port** chosen in the
  environment, and/or **MIDI clock** out to a MIDI port, and/or a **visual
  beat** on the stage view.
* I12.3 Tap tempo is a stage-view button and can be mapped to a footswitch or
  MIDI. A tapped tempo holds for the session, and is saved into the song
  only on request.

Risk

* R12.1 With the CP-88 as the only audio output, an audio click goes into the
  main mix, to the audience. A click to the player alone needs another
  output device (any clock, per S12.4), or stays visual.

## 13. Recovery

Stated (2026-10-10)

* S13.1 A re-plug does everything needed to **continue as it was, as fast as
  possible**.

Interpreted

* I13.1 On a detach the app keeps its whole state: environment, setlist, song,
  live set, every current value. Notes that were sounding are released, not
  left hanging.
* I13.2 When a device that a binding matches comes back, the app reopens it
  without being asked: USB permission (granted once and for all, as now),
  the same mode (ALSA, USB interface, USB device), the same buffer.
* I13.3 External synths are brought back in line: the CP-88 gets its live set
  selection again. If it was power-cycled, its reserved slots are checked
  and rewritten if needed (R5.2).
* I13.4 Time from re-plug to sound is measured and kept as a figure to beat.
* S13.2 (2026-10-11) The same after an app crash or restart: the app comes back
  by itself on the same environment, setlist, song, live set and current
  values.
* I13.5 For S13.2, the session state is saved continuously, cheaply, outside
  the library (it is not a library item, and not versioned).

## 14. Content and first run

Stated (2026-10-11)

* S14.1 **A small app plus an OCI catalog.** The app ships the engines, the DX7
  cartridges, Surge XT's factory patches, a small General MIDI SoundFont and
  one demo song. Larger packs (Salamander, Surge's full library) are
  downloaded in the app from a curated public OCI registry, verified by
  hash, with the same code as sync (8.3).
* S14.2 **First run**: suggest an environment from what is plugged in (S3.10),
  then open the demo song.

Interpreted

* I14.1 The catalog is itself a library entry (a list of packs by hash, with
  their licences), so adding content needs no app update.
* I14.2 Every catalog pack carries its licence and attribution, shown before
  download (Salamander is CC-BY; the SoundFont is chosen for a permissive
  licence).
* I14.3 Without a CP-88, nothing in the first run mentions one; the CP-88 shows
  up as an external synth type and a profile, like any device.

## 15. Stage layout

Stated (2026-10-11)

* S15.1 **One stage layout per song**: a grid of control cells next to the
  lead-sheet area, generated from the song's exposed controls and
  rearrangeable by hand.
* S15.2 Each live set **shows or hides** cells and never moves them, so the
  controls stay where the hands expect them.
* S15.3 The environment sets how the screen is split between lead sheet and
  controls.
* S15.4 (2026-10-11) **Portrait is derived automatically** from the song's
  landscape layout: lead sheet on top, a strip with the live set's visible
  controls below (in their landscape order), a swipe or tab for the rest.
  One layout to design per song; footswitch page turns work the same.

## 16. CPU, memory and heat

Stated (2026-10-11)

* S16.1 **At preload**, estimate each song's CPU (from measured per-engine and
  per-patch costs) and memory, and warn when it exceeds a safe budget
  (target 50% CPU, as in the prototype's measurements; RAM within a set
  limit).
* S16.2 **While playing**, show load and thermal state on the stage view
  (Android's thermal API), and under overload **steal voices before
  anything drops out**.

Interpreted

* I16.1 Per-engine costs come from a benchmark the app runs itself (per engine,
  per patch, per voice), stored per device model, so the estimate fits the
  tablet it runs on.
* I16.2 Voice stealing under overload starts with the quietest, oldest voices
  and never steals held notes of the top line first; the stage view says it
  is happening.

## 17. Stage mode

Stated (2026-10-11)

* S17.1 Dark, high-contrast stage view with large targets, readable at arm's
  length.
* S17.2 The screen stays on in stage view, and the app asks for Do Not
  Disturb so no notification sound or banner appears.
* S17.3 **Edit lock**: configuration views are reachable from the stage view
  only by a deliberate gesture (long-press).
* S17.4 On-screen faders and knobs **never jump on touch**; they move by
  dragging relative to where they are.

## 18. Distribution and devices

Stated (2026-10-11)

* S18.1 **Paid on Google Play; the source stays free** (GPL-3.0-or-later).
* S18.2 Distributed on **Google Play and F-Droid**.
* S18.3 Yamaha is named only as **compatible hardware** ("works with the
  Yamaha CP88/CP73"), with a not-affiliated disclaimer, no Yamaha logos, and
  no Yamaha name in the app's title or icon. Same approach as cp-liveset.
* S18.4 **Tablets and phones alike.**

Interpreted

* I18.1 The F-Droid build is free of charge, as F-Droid requires; it is the same
  app built from the same source. The Play listing says so.
* I18.2 Play needs: target SDK 36 (already), a privacy policy, and the GPL source
  offer (a link to the public repository). The app collects nothing, which
  keeps the privacy policy short.
* I18.3 F-Droid needs a build with no proprietary dependencies. The app has none
  today; that stays a rule (no Play-only SDKs, no closed analytics).
* I18.4 "Phones alike" means every view works on a phone screen, not only the
  stage view: layouts adapt to the screen (Android window size classes),
  rather than a separate phone app.

Risks

* R18.1 **The graph editor and the patch editors on a phone** are the hardest
  part of S18.4: a 6-inch screen for a node graph. Expect a different
  interaction there (lists and focused single-node views rather than a free
  canvas), which is real extra UI work.
* R18.2 **Phones and USB audio**: the phone measured in this project
  (Snapdragon 695) had a 124-140 ms ALSA round trip, so direct USB is the only
  playable path there. Other phones are unknown. The preload check (S16.1)
  and the environment's ALSA presets have to make that visible.
* R18.3 Many phones have one USB-C port and weak power delivery to USB
  devices. Bus-powered interfaces may not run without a powered hub.

## 19. Language, diagnostics and help

Stated (2026-10-11)

* S19.1 **English at release, translatable**: every string in Android resources
  from the start, so translations need no code changes.
* S19.2 **Diagnostics stay on the device.** A rotating diagnostic log
  (including the transport and engine figures: holds, xruns, load, heat),
  and a "share diagnostics" action that hands it to email or a GitHub issue.
  Nothing leaves the device unless the user sends it.
* S19.3 **Status figures** (path, holds, alt errors, xruns, CPU, peak callback,
  latency) live in the **environment view**, per binding. The stage view
  shows only a small **health indicator** that turns amber or red on xruns,
  holds, overload or heat, and opens the figures when tapped.
* S19.4 **An online user manual** (built from the repository, on the project's
  website), linked from each view, plus **short inline hints** in the app for
  the non-obvious concepts: islands, retimers, recall modes, reserved CP-88
  slots.

## 20. Carried over from the prototype

Stated (2026-10-11)

* S20.1 The six engines stay: sfizz, setBfree, Surge XT, Dexed (DX7),
  Plaits, FluidSynth. No additions for now; new engines can come later as
  nodes without changing the model.
* S20.2 **Both orientations in every view**, the stage view included.
* S20.3 Kept: the **MEASURE** tool (a click out through the device, heard on the
  built-in mic, reported as the acoustic round trip), in the environment
  view.
* S20.4 Kept: an **on-screen test keyboard**, in the environment and live set
  views, to check a binding or a song with no keyboard attached.
* S20.5 New: a **MIDI monitor** showing incoming and outgoing MIDI per port.

Stated (2026-10-11): the long-standing project rules, confirmed

* A20.1 minSdk 29 (AMidi), target SDK 36.
* A20.2 Must work on any CP-88 unit, so device-derived margins leave room for
  differences between units.
* A20.3 GPL-3.0-or-later. Release builds for any judgement.
* A20.4 Live playability first: latency, no dropouts, no surprises mid-song.

Risk

* R20.1 **The stage view in portrait** (S20.2) next to a lead sheet: a portrait
  phone has room for the sheet or the controls, hardly both. The stage
  layout (section 15) needs a portrait arrangement per song as well, or a
  rule that derives one (sheet above, controls below, or a swipe between
  them).

## 21. Open questions

(none outstanding)

---

## Decision log

| date | change | why |
|---|---|---|
| 2026-10-10 | File created; prototype baseline recorded as assumptions | Requirements gathering started |
| 2026-10-10 | Round 1 answers: five views, environment/live set/bank model, CP-88 integration | Michiel's first description |
| 2026-10-10 | "Bank" renamed "song"; setlists added (setlists hold songs, songs hold live sets) | Round 2 |
| 2026-10-10 | Clock domains: R3.1 (resampling between devices) dropped; only clean clocks (S3.7) | Round 2 |
| 2026-10-10 | The CP-88 slot question settled: songs are written into the CP-88 at song selection (S5.3) | Round 2 |
| 2026-10-10 | Held notes: was "same name across live sets is the same block". Now: instances belong to the song, a different patch is a different instance, parameter changes stay on the same instance (I4.1-I4.5, to confirm) | Round 2, S4.4 |
| 2026-10-10 | MIDI processing: all proposed processors in scope | Round 2 |
| 2026-10-10 | Several environments; everything a library item | Round 2 |
| 2026-10-10 | Library items content-hash addressed (S8.3). Replaces stable ids (old I8.2), import conflict resolution (old I8.4), and the copies-or-links question (Q-B1): a hash reference is immutable, so it is both | Round 3 |
| 2026-10-10 | Storage: git for app-authored items, Git LFS layout for large binaries, identity by own SHA-256 (I8.16-I8.21) | Round 4 |
| 2026-10-10 | Git and Git LFS demoted from requirement to implementation option (8.4). The requirement is SHA-256 content links (S8.3, S8.5); I8.16-I8.19 folded into 8.2-8.4 | Round 5 |
| 2026-10-10 | Song model confirmed (S4.7). Proposed: one graph per song (I4.9-I4.13) | Round 6 |
| 2026-10-10 | Profiles: documented format, user-supplied; guided measurement dropped (S3.9) | Round 6 |
| 2026-10-10 | Values on a switch: S6.3 stated; a recall design proposed (I6.3-I6.9), absorbing I6.2 | Round 6 |
| 2026-10-10 | Patch editors: simplest first, Surge XT if feasible (S7.2) | Round 6 |
| 2026-10-10 | Sync canonical (S8.6); indexes derived and local (I8.24); OCI, LFS batch and folder candidates (I8.25) | Round 6 |
| 2026-10-10 | One graph per song and the recall design stated. Note-offs follow their note-on (S4.8). Environment suggestion is an explicit action (S3.10) | Round 7 |
| 2026-10-10 | OCI chosen over Git LFS (S8.7); sync and export design proposed (I8.26-I8.32); folder sync dropped | Round 7 |
| 2026-10-10 | Stage view and lead sheets (9), external MIDI gear and switching sources (10), effects (11), tempo (12), recovery (13) added; "Retained" and "Open questions" renumbered 14 and 15 (later 18 and 19) | Round 8 |
| 2026-10-11 | Import of the CP-88's current sound into a patch (S4.9); verified possible via the Current Sound Buffer address 0E 7F 00 (I4.16) | Round 9 |
| 2026-10-11 | Clean clocks refined into clock islands with an explicit retimer (S3.11, S3.12), replacing one-device-per-environment (old I3.3). The click gets its own destination (S12.4) | Round 10 |
| 2026-10-11 | Confirmed: switching split (I10.4), MIDNAM (I10.3), crash resume (S13.2), CP-88 patch stored as bulk dump (S4.10), reserved slots only (S4.11), library and OCI design (S8.8) | Round 10 |
| 2026-10-11 | An island is a clock, not a port: a device's in and out are one island (I3.12); risks for ALSA mode (R3.6) and for returning the CP-88's own sound (R3.7) | Round 11 |
| 2026-10-11 | CP-88 audio routing checked in its manual (I3.14): USB out equals main out without the USB return, INPUT jacks never reach USB; R3.7 settled. The mic case laid out (I3.15). Proposed automatic, visible retimers (I3.16, I3.17), which would drop the dictated clock and the locked-devices declaration; Q-K1 and Q-K2 replaced by Q-K3 | Round 12 |
| 2026-10-11 | Automatic visible retimers confirmed (S3.13). Islands and retimers derived when a song is bound to an environment, never stored in the song (S3.14, I3.18-I3.21) | Round 13 |
| 2026-10-11 | Content and first run (14), stage layout (15), CPU/memory/heat (16), stage mode (17); "Retained" and "Open questions" renumbered 18 and 19 stated as recommended | Round 14 |
| 2026-10-11 | Distribution and devices (18): paid on Play, free source, Play and F-Droid, Yamaha named as compatible hardware only, tablets and phones alike. "Retained" and "Open questions" renumbered 19 and 20 | Round 15 |
| 2026-10-11 | Language, diagnostics and help (19). "Retained" and "Open questions" renumbered 20 and 21 | Round 16 |
| 2026-10-11 | Carried over from the prototype (20): six engines, MEASURE, test keyboard confirmed; MIDI monitor added; both orientations everywhere (replaces "landscape tablet UI") | Round 17 |
| 2026-10-11 | Portrait stage view derived from the landscape layout (S15.4); the four project rules A20.1-A20.4 confirmed | Round 18 |
| 2026-10-11 | The CP-88's page is kept silent where songs do not use it (S4.12, I4.19, I4.20) | Michiel, during the unattended session |
