# Plan: Bag-O-Synths, from prototype to usable app

Made 2026-10-11 from [REQUIREMENTS.md](REQUIREMENTS.md). Requirement ids (S, I, R,
A) are cited so every step can be traced back to what was asked. Items marked
**Interpreted** there are taken as proposals: the plan builds them, and any of
them can still be revisited.

---

## 0. Progress (updated 2026-10-11, night)

| milestone | state |
|---|---|
| M0 | V1-V4 done; V6's 22-minute sequence and the hour soak clean on the fixed build, after the first run found and fixed a hold cascade (LOG.md). Open: real playing under load (V6), FORMAT.md review. Everything is on `main`. |
| M1 | Core done: `model/` (canonical JSON, SHA-256 store as OCI layout, entries and history, export/import, merge, typed objects of schema 1, and `Library`: songs, setlists, environments, the ripple of edits, restore and used-by -- moved out of the app so it is tested on the laptop). Not yet: SQLite index (an in-memory scan serves for now), migration of the prototype's patches. |
| M2 | First slice: switchable wires with note tracking and the MIDI zone in `engine/` (host-tested), the rack in `AudioEngine` (one zone and two switchable wires per engine), `LiveSetSwitcher` and `RackSong` in `model/` (tested). Not yet: arbitrary graphs and several instances of one engine, parameter metadata (D6), transition ramps. |
| M3 | The new UI (Compose) beside the prototype, seen working on the tablet. Stage: setlist, songs, live sets, level and master faders, organ drawbars, tempo and tap, lead sheets, panic, health and heat, edit lock, safe Back, page-turner keys and switching controllers, following the CP-88's buttons. Songs: form editor with patch editing. Library: setlists, songs, export/import, instrument install. Setup: environments (saved, applied, suggested), routing, CP-88, Do Not Disturb, MEASURE, MIDI monitor, test keys, status. Strings in resources. Not yet: named ports beyond the rack's fixed two, the prototype's removal (Michiel's call). |
| M4 | First slice: a live set can leave the CP-88, select one of its sounds, or own one -- imported from the CP-88's current sound, written into the reserved page at song selection, selected per live set, with the CP-88's echo suppressed. `Cp88` in `model/` tested byte for byte against cp-liveset. For any other keyboard or module: a program change (with optional bank) per live set, sent on select (I6.7). Not yet: the full CP-88 edit page (YAML view, files, parameter changes), MIDNAM, external synths as environment items. |
| M6 | First slice: the click (count-in or continuous, sample-placed, accented, into the main out; `engine/` Click, tested) with its beat on the stage, MIDI clock out (24 ppq, Start aligned with the click as heard) on the direct path, both in the environment; beats per bar and count-in bars in the song editor. Measured on the CP-88's line out: clicks 644,5 ms apart at 93,1 BPM; 455 ticks where 455 were due. Lead sheets were done in M3. Tap tempo and the click can be taught to footswitches (I12.3). The song's tempo reaches every node, and Surge's tempo-synced parameters follow it (I12.1), though this has not been measured with a synced patch. The reverb the engines meet in is set per song (editor, stored on the song's `mix` node). An insert effect per engine (`FxNode`: Surge XT's Delay, Phaser, Flanger, Rotary and Reverb2 from sst-effects, values shown as the effect formats them, times and rates synced to the song's tempo), measured on the line out: a 1/4-note delay echoes after 499,7 ms at 120 BPM and 400,1 ms at 150. Per live set, an engine's effect on or off (a send: tails ring out). A master effect slot after the room reverb (Hall = Reverb2 over the whole mix). Cost: six engine effects together 0,04 ms a block as delays, 0,11 ms as Hall, against 2,9 ms. Not yet: the click on a device of its own (M7), Clouds, R11.1 (amp/cab). |
| M8 | First slice: parameter ranges in the node API (D6) and a generic patch editor per engine in the song editor, over the preset, auditioned live and applied when the song opens. Not yet: custom panels beyond the stage organ, the DX7 operator editor, Surge XT's full parameter set and modulation. |
| M9 | First slice: an entry's details in the Library -- rename, delete (hold), history with restore (a new version; a song's setlists follow), the setlists using a song with "update to the latest version" as a choice (I8.5), and diverged imports resolved (I8.31). Not yet: tags and search, OCI registry sync, the catalog. |
| M11 | First piece: the diagnostic log (S19.2) -- health every 10 s, full status every 5 min and on a change of state, events (songs, CP-88 writes, MIDI out, crashes with their stack), two rotating 512 kB files -- and "Share diagnostics" through the share sheet. |
| M10 | First piece: the tablet's thermal status and headroom on the stage. Not yet: CPU and memory estimates at preload, overload voice stealing, phone testing. |

## 1. Shape of the plan

**Twelve milestones, M0 to M11.** After each one, the app is installable and
playable. Nothing is built that only pays off at the end.

* **M0-M3 lay foundations without losing the instrument.** The prototype UI
  stays installable until M3 replaces it.
* **M3 is the first gig-ready app**: songs, live sets, setlists and the stage
  view, with the internal engines.
* **M4 brings in the CP-88**, which is the reason the project exists.
* **M5-M10 complete the requirements**, in order of value on stage.
* **M11 is release**: Play (paid) and F-Droid.

Measurements that can overturn a design come **before** the design is built on
(section 3). That is how this project has always worked, and it stays so.

## 2. Architecture decisions the plan makes

These are implementation choices within the requirements. Each can be changed
before its milestone starts.

| # | decision | why |
|---|---|---|
| D1 | **A pure Kotlin/JVM module `model/`**: object types, canonical JSON, hashing, the store, songs and environments as data. No Android imports. | Testable on a laptop in seconds, like `engine/` for the C++ side. The data model is the part most worth testing. |
| D2 | **Canonical JSON per RFC 8785 (JCS), SHA-256**, references written as `"sha256:<hex>"` everywhere (S8.3, I8.11, I8.22). | Standardised canonical form with published test vectors; one reference syntax makes closure, GC and reverse index generic. |
| D3 | **The local store is an OCI Image Layout directory** (`blobs/sha256/...`, `index.json`, `oci-layout`). Entries are tags; each entry version is a manifest listing its closure (I8.26-I8.29). | Export is then a tar of a subset, and sync pushes the same objects: one format on disk, in files and on the wire. The mutable `index.json` is no problem locally (no file sync, I8.32). |
| D4 | **Indexes in SQLite**, rebuilt from the store on demand (I8.24). | Derived, disposable, fast queries for the library and the reverse references. |
| D5 | **Jetpack Compose** for the new UI, with window size classes for tablet and phone, both orientations (S18.4, S20.2). | The canonical Android toolkit for adaptive layouts; free software, fine for F-Droid. The prototype's View code is not carried over. |
| D6 | **Parameters get string ids and metadata** (name, range, unit, group, default, smoothing, note-on-only) in the native node API, replacing index-only access. | Patches already store names (PatchStore). Metadata drives the generic editor (I7.2), recall (I6.4), MIDI learn and the stage controls. |
| D7 | **A live set switch is one atomic batch** of parameter, enable and wire-gain changes, applied at a block boundary, each ramped (I4.2, I6.5). | No graph recompile at a switch (I4.9); the existing `ParamQueue` is the starting point. |
| D8 | **Every MIDI wire tracks the notes it passed**, and every MIDI transform remembers what it turned each note into (S4.8, I4.14). | A switched-off wire still passes the note-offs, pedal-ups and poly aftertouch of notes it carried; a transpose change cannot strand a note. |
| D9 | **One render thread per island**, each driven by its device (I3.11, S3.11). MIDI and tempo cross islands through lock-free queues. A retimer is a node pair across two schedules (I3.16). | Each clock gets its own loop, and the retimer is the only point where islands meet. |
| D10 | **USB profiles as JSON**, a direct serialisation of `struct fbx_profile` plus identification (vendor, product, firmware) (S3.9). `FBX_PROFILE_CP88` becomes the first shipped file. | The profile already is a small struct; the format document grows out of `tools/fbx/PROFILING.md`. |
| D11 | **cp-liveset's codec ported to Kotlin** (MIT notice kept), plus the Current Sound Buffer address (I4.8, I4.16). | The app needs it on-device; MIT into GPL is fine. |
| D12 | **OCI registry client**: evaluate `oras-java` first; if it does not fit Android, a small own client (the Distribution spec is a handful of HTTP calls). | Canonical protocol (S8.7) without writing more than needed. |
| D13 | **Resampler for retimers**: chosen at M7 from libsamplerate (BSD-2), speexdsp (BSD) and r8brain (MIT), by measured quality, latency and CPU. | Drift tracking needs a variable-ratio converter; the choice is a measurement, not a guess. |

## 3. Measurements and checks that gate the design

Each one comes before the milestone that would build on it. Results go to
LOG.md as found.

| id | question | gates | how |
|---|---|---|---|
| V1 | Does the CP-88 send Bank Select + Program Change when its own live set buttons are pressed? On which channel, under which settings? (R3.4) | CP-88-to-app switching, M4 | **Done 2026-10-11: yes.** MSB 63, LSB page - 1, PC set - 1, channel 1, default settings (LOG.md). |
| V2 | Does a Bulk Dump Request to `0E 7F 00` return the current sound, unsaved edits included, and under which address? (I4.16) | Import current sound, M4 | **Done 2026-10-11: yes**, under `7F 00`, 163 ms, live panel state included (LOG.md). A deliberate panel edit still to try. |
| V3 | How long does writing one Live Set Sound into a slot take, and does the CP-88 keep playing cleanly while it lands? (R5.1) | Preloading the next song during the current one, M4 | **Done 2026-10-11:** 7,2 ms unpaced, read back identical; no drops in the CP-88 or the tablet while writing (LOG.md). |
| V4 | Do slot writes persist across a power cycle (flash), and how fast is a write of a whole page? (R5.2) | Reserved pages and recovery (I13.3), M4 | **Known (Michiel): stored slots survive a power cycle.** A page writes in well under 0,1 s (V3). |
| V5 | In ALSA mode, do the CP-88's input and output stay locked sample for sample through the HAL's 44,1-to-48 kHz conversion? (R3.6) | Audio inputs as one island in ALSA mode, M7 | Long run, count frames in against frames out. |
| V6 | The remaining stress items from HANDOFF on the FIFO build: the full 22-minute sequence, an hour of heavy render load, real playing under load. | M0 exit | **22-minute sequence and an hour of render load both clean on the fixed build (2026-10-11, LOG.md)**, after the first run found and fixed a hold cascade. Left: real playing under load. |
| V7 | Is a retimer's latency and drift tracking good enough for a mic monitored by a singer? (I3.15 B) | Retimer defaults, M7 | Focusrite in, CP-88 out, measure round trip and long-run drift. |

## 4. Milestones

### M0. Land the prototype and measure what blocks the design

* Finish V6 on the FIFO build, then commit the `usb-direct-modes` work and
  `tools/bosstress/` to `main` and push (project rule: straight to main).
* Run V1-V4 on the CP-88 now, since they decide M4's shape. V5 waits for M7.
* Write `docs/FORMAT.md` v0: the object types, canonical form, reference syntax,
  entry versions, the OCI mapping. It is the contract for M1.
* Exit: stress results in LOG.md, the branch on main, V1-V4 answered,
  FORMAT.md reviewed by Michiel.

### M1. The data model and the library core (laptop-testable)

Covers S8.2-S8.8, I8.1-I8.31 (core and exchange), S4.10.

* `model/` module (D1): object types v1 (patch per engine, song, live set,
  setlist, environment, USB profile, CP-88 sound as bulk-dump bytes, instrument
  pack manifest, lead sheet, catalog), each with a schema version.
* JCS canonical JSON and SHA-256 (D2), tested against RFC 8785's vectors and
  round trips; R8.1 (float stability) tested explicitly, including values read
  back from engines.
* The store (D3): put/get objects, entries as tags, entry history as objects
  (I8.23), closure, garbage collection (I8.14), export and import of OCI Image
  Layout archives with hash verification (I8.4, I8.27).
* The SQLite index (D4): by name, type, tag, author; reverse references.
* Migration: the prototype's `last.json` and saved patches import as patch
  objects, so nothing Michiel built is lost.
* Exit: `model/` tests green on a laptop and in CI; a library created in a
  test, exported, wiped, re-imported, identical hashes.

### M2. The engine plays songs from data

Covers S4.1-S4.8, I4.1-I4.15, I4.9-I4.13, S6.3-S6.4 (engine side), I6.3-I6.9.

* Node factory by type name; the graph built from a Song object instead of in
  code. The six engines, gain, reverb and limiter as factory types.
* Parameter metadata and string ids (D6) on every node; the JNI surface
  rebuilt around them.
* Wire on/off and gain as schedule data (`AudioSum` gain, `MidiSum` gate),
  ramped (I4.9).
* MIDI processor nodes: channel filter and remap, message-type filter, CC
  filter and remap, key range, velocity range, transpose, octave (S4.2).
* Note tracking (D8): note-offs, sustain, poly aftertouch follow their
  note-on; layered notes released everywhere; panic (I4.15).
* Live set application (D7): song values, overrides, recall modes Recall /
  Keep / Resume, transition times, controller state chase (I4.13), disabled
  effects bypass or ring out (I4.12). Note-on-only parameters flagged (R4.1).
* Instance reuse across songs when engine and patch are equal (I4.5).
* Tests: all of the above in `engine/tests` on the host, especially note
  tracking across switches (the bug class Michiel found with the flushing
  engine).
* The prototype UI drives a fixed demo song through the new path, so the
  instrument stays playable.
* Exit: a two-live-set song switched under held notes and pedal, with no stuck
  or cut notes, by test and by ear; release-build latency unchanged.

### M3. Environment, stage view, songs: the first gig-ready app

Covers S2, S3.1-S3.10 (single device), S3.14, I3.1-I3.2, S5.1-S5.2, S10.2,
I10.4, S13.1-S13.2, I13.1-I13.5, S15.1-S15.3, S16.2 (memory part), S17.1-S17.4,
S19.1, S19.3, S20.3-S20.5.

* The Compose app shell (D5), strings in resources from the first screen
  (S19.1). The prototype UI is deleted.
* **Environment view**: ports, bindings (ALSA out and in, MidiManager MIDI in
  and out, USB interface and USB device with fbx), profiles loaded from JSON
  (D10, `FBX_PROFILE_CP88` as the first file), ALSA presets that probe for
  exclusive and MMAP (S3.3), several environments and switching (S3.8),
  "suggest from what is plugged in" as an explicit action (S3.10). Status
  figures per binding (S19.3), MEASURE, test keyboard, MIDI monitor
  (S20.3-S20.5).
* **MIDI out** on every binding type (R3.3): AMidi input ports, and USB bulk OUT
  on the direct path.
* **Switching sources**: the environment's inputs (Program Change, CC,
  footswitch keys) and the song's mapping (I10.4).
* **Song editing, form-based**: instances (engine plus patch), a default wiring
  template (MIDI In -> instance -> Main Out), live sets as columns of values
  and enables. The full graph editor comes in M5; this is enough to build
  real songs.
* **Setlists** and **preloading** a song's instances, with the memory estimate
  (R4.2).
* **Stage view**: live set buttons, next and previous song, the auto-generated
  control grid with show/hide per live set (S15.1-S15.2), panic, health
  indicator, dark theme, keep screen on, Do Not Disturb, edit lock, relative
  faders (S17).
* **Recovery**: re-plug rebinds by itself in the same mode and buffer; session
  state saved continuously; crash and restart resume (S13).
* Exit: Michiel plays a rehearsal from a setlist on the S9 Ultra with the
  internal engines, switching by screen and by footswitch, with an unplug and
  replug in the middle.

### M4. The CP-88 as external synth

Covers S3.6, I3.1, I3.4, S4.5-S4.6, S4.9-S4.11, I4.7, I4.16-I4.18, S5.3,
I5.1-I5.5, S10.1, I10.1-I10.3, I10.5.

* Kotlin port of cp-liveset (D11): SysEx framing, bulk dump read and write,
  YAML and JSON, `.X9*` files, the Current Sound Buffer.
* **CP-88 node** in a song: leave it, select (page:set), own (bulk-dump object).
* **CP-88 edit page**: import the current sound (S4.9, given V2), from a slot,
  from files; export to all; edits auditioned on the CP-88 with parameter
  changes (I4.18); the YAML as the view of the stored bytes (S4.10).
* **Reserved pages** in the environment, a backup of their contents before the
  first write, writing a song's CP-88 sounds at song selection, two areas in
  turn for preloading the next song (given V3 and V4).
* **Two-way switching** (I3.1, given V1) with echo suppression.
* CP-88 parameters as stage controls (I4.7).
* **Generic external MIDI synth node** with MIDNAM import (I10.1-I10.3) and
  on-select messages (I10.5, I6.7).
* Exit: a setlist where each song's live sets switch the CP-88 and the tablet
  together, from either side, with no audible gap.

### M5. The live set configuration editor

Covers S4.3, I4.11, I4.3, S6.1-S6.2, I6.1, I6.4, I6.6-I6.9, I3.19, I3.21.

* The graph canvas on tablet: nodes, typed pins, wires (Blender/Rete-style
  gestures, GRAPH.md section 2), the graph **as seen from one live set**
  (dimmed or hidden), islands and retimer badges for the active or a chosen
  environment (I3.19).
* Per parameter: recall mode, overrides, transition time; snapshot capture
  (I6.9); on-select actions (I6.7).
* Patch changes in one live set ask "whole song or new instance" (I4.3).
* Controls: expose, label, widget type, MIDI learn, pickup / jump / scale
  (I6.6); the stage layout editor (S15.1).
* Copy, rename, reorder live sets and songs.
* Exit: Michiel builds a song with a split, a layer and a wire switched per
  live set entirely on the tablet, and it plays.

### M6. Effects, tempo, lead sheets

Covers S11.1, I11.1-I11.3, S12.1-S12.4, I12.1-I12.3, S9.1-S9.3, I9.1-I9.6.

* Surge XT's effect units as standalone nodes; setBfree's Leslie; Clouds.
  R11.1 assessed: is an amp/cab model missing in practice?
* Tempo per song, tap tempo, tempo-synced parameters, click node, MIDI clock
  out, visual beat, count-in or continuous (S12).
* Lead sheets: PDF objects, `PdfRenderer`, 1-up and 2-up, half or full turns,
  page-turner keys in the environment, page per live set (S9).
* Exit: a song with a tempo-synced delay, a count-in, and its lead sheet turned
  by the Butterfly.

### M7. Several devices: islands and retimers

Covers S3.11-S3.14, I3.12-I3.21, R3.2, S12.4, I3.15.

* Island derivation at binding time; one render thread per island (D9).
* The retimer: resampler chosen by measurement (D13), drift tracking, latency
  shown, settings per device pair in the environment (I3.20). V7.
* Audio inputs on every binding type, including USB IN data on the direct path;
  V5 decides whether ALSA mode can treat a device's in and out as one island.
* The click on its own device.
* **Generic class-compliant direct USB** (S3.5, R3.2): explicit feedback,
  synchronous and adaptive endpoints, generic USB MIDI. Needs hardware that
  has each mode; the Scarlett and the Pi Zero mock (`tools/cp88mock`) are the
  starting points.
* Exit: the mic case A and B (I3.15) both working through a hub, measured.

### M8. Patch editors

Covers S7.1-S7.2, I7.1-I7.2.

* The generic editor from parameter metadata (D6).
* In order: FluidSynth, sfizz, Plaits, the organ (drawbars), the DX7
  (operators, algorithm), Surge XT. For Surge, the generic grouped editor
  first, then a decision on its modulation routing UI ("if feasible").
* Exit: a new patch made from scratch in each editor and used in a song.

### M9. Library UI, sync and the catalog

Covers S8.1, I8.3-I8.5, I8.13, I8.21, I8.26-I8.31, S14.1, I14.1-I14.3.

* The library browser: by type, name, tag; history per entry; "used by"
  (reverse references); "update reference to newer version" (I8.5).
* Import and export through the file picker and share sheet (I8.8).
* OCI registry sync (D12): one repository, a tag per entry, closures, the
  divergence view (I8.31).
* The public catalog: curated packs by hash with licences (I14.2), downloads
  verified by hash, "fetch from source" for content left out of exports
  (I8.30).
* Exit: the library synced between the tablet and a zot registry on the NAS;
  Salamander installed from the catalog with no adb.

### M10. Load guard and phones

Covers S16.1-S16.2, I16.1-I16.2, S18.4, I18.4, R18.1-R18.3, S15.4, S20.2, R20.1.

* A per-device benchmark of engine, patch and voice costs (I16.1); the preload
  estimate; overload voice stealing (I16.2); thermal state on stage.
* Phone and portrait layouts for every view; the graph editor's phone
  interaction (lists and single-node focus, R18.1); the derived portrait stage
  view (S15.4).
* Testing on one or two cheaper tablets and a phone (R18.2, R18.3).
* Exit: a song refused or warned on a weak device for the right reason; a
  rehearsal played from a phone.

### M11. Release

Covers S18.1-S18.3, I18.1-I18.3, S19.2, S19.4.

* Diagnostics log and "share diagnostics" (S19.2); inline hints and the online
  manual (S19.4).
* Play: paid listing, privacy policy (the app collects nothing), GPL source
  link, "works with the Yamaha CP88/CP73" wording and disclaimer (S18.3).
* F-Droid: metadata, a build with no proprietary dependencies, free of charge
  (I18.1, I18.3).
* The demo song and starter content (S14.1).
* Exit: published on both stores.

## 5. Order and dependencies

```
M0 -> M1 -> M2 -> M3 -> M4 -> M5 -> M6
                    \-> M7              (needs M3's bindings)
              \-> M8                    (needs M2's parameter metadata)
M1 + M5 -> M9
M3 .. M9 -> M10 -> M11
```

M8 (patch editors) depends only on parameter metadata, so it can run beside
M4-M7 whenever there is room. M7 is the riskiest milestone (new transports,
hardware needed) and is deliberately after the gig-ready app.

## 6. Rules that apply throughout

* **Release builds** for every judgement (A20.3).
* **Findings to LOG.md** as they happen, corrections included.
* **Fixes that did not measurably help come out again** (PROVISIONAL.md).
* **Any CP-88 unit**: device margins keep room for unit variance (A20.2).
* **Commit and push straight to main.**
* `engine/` stays free of Android headers; `model/` stays free of Android
  imports. Both are tested on a laptop and in CI before anything is built.
* Strings in resources, both orientations, nothing that blocks F-Droid, from
  the first line of new UI on.

## 7. Risks carried by the plan

| risk | where | mitigation |
|---|---|---|
| V1 negative: the CP-88 does not announce its own live set changes | M4 | App-to-CP-88 switching still works; CP-88-to-app becomes unavailable or needs a CP-88 setting, stated in the manual. |
| V3 negative: writing slots while playing glitches | M4 | No next-song preloading for the CP-88 part; write at song selection between songs only. |
| Generic direct USB needs hardware we do not have | M7 | Start with the modes the Scarlett and the Pi mock provide; ALSA stays the fallback for the rest (I3.7). |
| The graph editor on a phone | M10 | A different interaction, budgeted as its own work (R18.1). |
| Surge XT's full editor | M8 | Generic editor first; the modulation UI is a separate decision. |
| Phones' USB audio latency and power | M10 | The preload check and ALSA presets make it visible; a powered hub documented. |
