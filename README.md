# Bag-O-Synths

Android sound module for the Yamaha CP-88, running on a Galaxy Tab S9 Ultra.
One USB-C to USB-B cable carries MIDI in and audio out; the synth is mixed into
the piano's own outputs.

It is a bag of synths: an SFZ sampler, a tonewheel organ, Surge XT, a DX7, a
Plaits macro oscillator and a SoundFont player, all of them playable together
from one keyboard. Everything is decided against measured numbers, because the
only thing that matters is whether it can be played live.

* [FINDINGS.md](FINDINGS.md) — **the distilled knowledge**: constraints, rules and
  numbers that still hold, categorised. Start here before changing the engine.
* [MEASUREMENTS.md](MEASUREMENTS.md) — every latency figure, with its confidence.
* [GRAPH.md](GRAPH.md) — the target architecture: a user-editable node graph of
  inputs, outputs, synths and effects.
* [SURGE.md](SURGE.md) — what the Surge XT integration turned out to cost and buy.
* [LICENSING.md](LICENSING.md) — the project is **GPL-3.0-or-later**; what that
  obliges, and the GPL-2-only compatibility trap.

One spelling, everywhere: the display name is **Bag-O-Synths** and every
identifier is `bagosynths` — repo, package, namespace, native library, Gradle
project. The single exception is the logcat tags, which are `bos-audio`,
`bos-fluid` and so on, because those get typed by hand dozens of times a
session.

## Status: M3 done, the organ built (M4)

Measured on the CP-88: AAudio **EXCLUSIVE LOW_LATENCY**, 44100 Hz, 89-frame burst,
**9-10 ms output latency, 0 xruns**, 4-17% CPU while playing. Acoustic round trip
22.6 ± 0.5 ms; best estimate of key-to-sound **11-15 ms**. Player's verdict:
*"it felt snappy"*. Details in [MEASUREMENTS.md](MEASUREMENTS.md).

What works:

* **Node graph engine**: compiled schedule, lock-free publish, preallocated
  buffers, dependency counts ready for parallel execution ([GRAPH.md](GRAPH.md))
* **sfizz** SFZ sampler as a graph node, loaded on a background thread
* **Instrument library**: instruments live outside the APK, addressed by a stable
  `id` that patches reference instead of a path. Salamander Grand Piano V3
  (1.2 GB, 641 regions) runs from it
* **setBfree's Beatrix tonewheel organ** as a graph node, with its Leslie, scanner
  vibrato, percussion and valve preamp. Nine touch drawbars in the B-3 colours,
  the registrations by name, an UPPER / LOWER / PEDAL selector, and one-keyboard
  splits so a single CP-88 reaches all three manuals
* **Surge XT's synthesis engine** as a graph node, without JUCE: three
  oscillators a voice across a dozen oscillator types including wavetable and FM,
  two filter units, dual FX chains, twelve modulation sources, and **641 factory
  patches** browsable by name. The panel exposes the patch, the eight macros and
  a level — not an editor ([SURGE.md](SURGE.md))
* **The DX7**, as Dexed's `msfa` engine: six-operator FM with all three of
  Dexed's cores — MARK I (the authentic one, and the default), MODERN and OPL.
  **1056 factory voices ship in the APK**, in 33 cartridges, browsable cartridge
  then voice. Drop your own `.syx` banks in the `dx7` folder and they load too
* **Plaits** (Mutable Instruments): sixteen synthesis models in one
  oscillator — virtual analog, waveshaping, FM, granular, additive,
  wavetable, chord, speech, swarm, noise, particle, Karplus-Strong string,
  modal resonator and three drum voices. Rendered at its native 48 kHz and
  resampled, with Plaits' own low-pass gate as the envelope, so DECAY is the
  release and velocity moves brightness as well as loudness
* **Patches**: the whole graph — every node parameter, the instrument id, the
  Surge patch and the DX7 voice — saved as JSON, auto-restored on launch, with
  named saves you can cycle through
* Freeverb-style reverb and a master limiter as nodes
* An **output meter** in the status line: the peak of what actually leaves the
  graph, so "is it making sound, and which part" is answerable at a glance
* An **instrument selector**: tap a tab to show that panel and solo that part,
  long-press to bring a part into the mix without silencing the others. The tabs
  are a view of the levels, so a layer built by hand shows up in them
* Default patch — all six instruments run, and are **mixed rather than
  switched**, so moving between them is a fader and never a reload:

```
MIDI In --+--> sfizz    --+
          |               |
          +--> Hammond  --+
          |               |
          +--> Surge XT --+--> Reverb --> Limiter --> Audio Out
          |               |
          +--> Dexed    --+
          |               |
          +--> Plaits   --+
          |               |
          +--> FluidSynth-+
```

* Oboe output stream to the CP-88 — AAudio, MMAP, **EXCLUSIVE**, 44100 Hz, I16
* Native MIDI in over the same cable via **AMidi**, polled from the audio callback
* A real polyphonic synth voice (two detuned polyBLEP saws, SVF lowpass with an
  envelope-modulated cutoff, ADSR), 64 voices with sensible stealing
* Sustain pedal (CC64), mod wheel (CC1), volume (CC7), pitch bend, all-notes-off
* TPDF dither on the float → 16-bit conversion
* Live readout of latency, xruns, CPU load, peak callback time and voice count
* Automatic recovery from stream disconnect
* An on-screen test octave, so the audio path can be checked with no piano attached
* A **MEASURE** button: emits a click through the CP-88, listens on the built-in
  mic, and reports the acoustic round trip — latency verifiable on any stage

## The app in progress (2026-10)

The prototype is becoming an app: [REQUIREMENTS.md](REQUIREMENTS.md) holds what
it must do, [PLAN.md](PLAN.md) the milestones and where they stand, and
[docs/FORMAT.md](docs/FORMAT.md) the library format.

What runs today, beside the prototype screen (a button on each side switches):

* **Stage**: a setlist's songs with previous/next, each song's live sets as
  large buttons, a level fader per engine that moves only by dragging, panic,
  and a health line from the audio path. It comes back where it was after a
  restart.
* **Songs**: a song is the rack of six engines -- which ones it uses, with
  which patch, at what level -- and its live sets: per live set, which engines
  play, over which keys, transposed how far, from which channel. Switching
  live sets never strands a note: a note-off follows its note-on, a pedal-up
  its pedal-down, and an engine switched off lets its tails ring.
* **Library**: setlists and songs are content-addressed library entries with
  history; a setlist exports to, and imports from, an OCI Image Layout tar.

## Build

Builds and runs. Working toolchain: **AGP 8.13.2, Gradle 8.14.5, JDK 21, Kotlin
2.0.21, NDK 30.0.16248370, CMake 4.1.2, compileSdk/targetSdk 36, Oboe 1.9.3**.

First, check out the upstream engines. They are submodules of forks, on the
branch `bagosynths/main`, with relative urls — so this works whether the clone
came over ssh or https, and nothing has to be edited:

```
tools/init_deps.sh           # sfizz, setBfree, Surge XT, Dexed, FluidSynth and stb
                             # (Plaits needs no clone -- it is inside Surge's libs/eurorack)
tools/install_soundfont.sh   # put an .sf2/.sf3 on the tablet
```

Not `git submodule update --init --recursive`: Surge carries 23 submodules and
this build uses 15, and the eight left out are JUCE and friends. That is the
whole reason the script exists.

Then:

```
export JAVA_HOME="/c/Users/michi/.jdks/jbr-21.0.11"   # a JDK 21, NOT Studio's 25
./gradlew installRelease
```

**JDK 21 specifically.** Android Studio's bundled `jbr` is now 25 and Gradle
8.14.5 rejects it with `25.0.3` as the entire error message.

**Release, always, for anything you intend to judge by ear or by number** — see
below.

### Install

Android Studio (latest stable), then via its SDK Manager:

* **SDK Platform 36** (Android 16)
* NDK (Side by side), r26 or later
* CMake 3.22.1 (SDK Tools tab)
* Android SDK Platform-Tools

### On SDK levels

`minSdk = 29` is the only setting that limits who can install this, and it is
forced by AMidi. `compileSdk`/`targetSdk = 36` cost nothing in device support.

Targeting 36 is not optional if this is ever published: **since 31 August 2026,
Google Play requires new apps and updates to target API 36.** That in turn
requires AGP 8.9.1 or newer — AGP 8.7 cannot compile against 36.

### Deploy over Wi-Fi, not USB

The tablet has one USB-C port and the CP-88 will be in it. Use **Developer
options → Wireless debugging → Pair device with pairing code**, and connect from
Android Studio. Otherwise every deploy means unplugging the piano.

### Build a release variant for any performance judgement

Debug native builds are unoptimised and *will* glitch. This is not a small effect
and it does not look like one — it looks like a design fault. Identical code,
minutes apart, on the same device:

| build | latency | ring underruns | render peak |
|---|---|---|---|
| debug | 11.0 ms | 17865 and climbing | 34.07 ms |
| **release** | **7.9 ms** | **0** | **0.98 ms** |

`installRelease` is signed with the debug key so it installs for testing.

## Installing an instrument library

Instruments are folders under
`/sdcard/Android/data/be.mdw.bagosynths/files/instruments/`, the folder name being
the id a patch refers to. An optional `instrument.json` sets `name` and the entry
`sfz`.

`adb push` needs care, and gets this wrong silently:

```
# 1. launch the app once so IT creates the instruments folder
# 2. push the pack
adb push SalamanderGrandPianoV3_44.1khz16bit     /sdcard/Android/data/be.mdw.bagosynths/files/instruments/salamander-grand
# 3. adb push creates that subfolder as the shell user, mode 770,
#    which the app cannot traverse. Fix it:
adb shell chmod -R 777     /sdcard/Android/data/be.mdw.bagosynths/files/instruments/salamander-grand
# 4. press RESCAN in the app
```

An in-app import via the Storage Access Framework would remove this dance and is
on the list.

## Installing Surge XT's patch library

Surge reads wavetables and patches from a real directory — it takes a filesystem
path and cannot read out of an APK — so its content lives beside the instrument
library rather than being bundled. Without it Surge still runs, on its init patch
only.

```
tools/install_surge_data.sh          # the 33 MB core: wavetables + 641 patches
tools/install_surge_data.sh --full   # everything, ~483 MB
```

The script does the same `chmod` dance the instrument library needs, for the same
reason. Launch the app once first so it creates the files directory.

## Getting the dependencies

`third_party/` is over a gigabyte of other people's code, and every directory in
it is a **submodule of a fork** on the branch `bagosynths/main`.

```
tools/init_deps.sh
```

checks out the six, then the 15 Surge submodules this build actually needs and
sfizz's own, all shallow. It is safe to re-run.

The six modifications those forks carry are commits, not patch files, each
explaining itself in its message: bionic has no `execinfo.h`; three upstream
CMake bugs that only appear via `add_subdirectory`; a namespace rename that
keeps sfizz's and Surge's vendored copies of the same tuning library from
colliding, and the removal of a third copy from Dexed; and SF3 decoding routed
through stb_vorbis instead of libsndfile. [LICENSING.md](LICENSING.md) names
each fork and the commit it sits on.

After adding a dependency, run:

```
tools/check_odr.sh
```

It lists any symbol defined by more than one dependency tree. That is not a
theoretical concern here: it is exactly the bug that made the app crash on every
launch when sfizz's and Surge's tuning libraries met.

## Wiring it up

1. CP-88 powered on, USB-C → USB-B cable to the tablet.
2. On the CP-88, set **Local Control off** if you want the tablet to be the only
   sound source; leave it on to layer the app under the piano.
3. Launch the app. It looks for a USB audio device naming itself CP88/CP73/Yamaha
   and opens that explicitly — auto-select can land on the built-in speaker and
   report misleadingly good latency.
4. Play. `midi msgs` should climb; `xruns` should not.

## Audio and MIDI modes

Audio out and MIDI in are chosen **separately** -- a Scarlett can carry the
sound while the CP-88 carries the notes -- and each has a mode. **Tap** the
Audio or MIDI button to cycle devices; **long-press** it to choose the mode.
Every name shown is the ALSA name (`CP88/CP73`, `CP88/CP73 MIDI 1`) in every
mode, computed by snd-usb-audio's own rule.

| Mode | What it means |
|---|---|
| **ALSA** | Through Android: AAudio for sound, MidiManager for notes. What the app has always done. |
| **USB interface** | This process drives that use's own interfaces directly through usbfs; the rest of the device stays with the kernel. For audio on a UAC2 device that includes AudioControl, because the clock lives there. |
| **USB device** | This process owns *every* interface of the device. The ALSA card disappears while it does. |

**USB device mode applies to the whole device.** Moving any use of a device to
it moves all of them; moving one away puts the others on USB interface. The app
cannot own half a device and share the other half.

**Why the direct modes exist.** On the Galaxy Tab S9 Ultra, Android's USB audio
runs through Qualcomm's DSP offload -- the transfer ring is handed to the audio
DSP, where neither the app nor the kernel can see it. The direct modes take the
DSP, the HAL and AudioFlinger out of the data path: the app talks to the
endpoints itself. Direct audio drives **implicit-feedback devices** (the
CP-88 is one) with fbx, a transport built so that no delay of this process can
latch the device: every packet is the size the device asked for, every append
is proven contiguous, and anything it cannot prove becomes a short silence
(a *hold*) instead of a gap the device could misread. See
`app/src/main/cpp/usb/UsbAudioOut.h`, and in the workspace `tools/fbx/` and
`CP88-USB-AUDIO.md` for the device behaviour behind every rule.

**Which direct mode.** Prefer **USB device**. In **USB interface** mode for
audio the ALSA card survives, so Android still lists the device as an output --
and any sound it routes there (the lock sound, a notification) reconfigures the
interface underneath the app and kills the stream. Measured, not feared.

USB interface mode for audio **also claims AudioControl** on a UAC2 device.
That is not a wider claim than it needs: on UAC2 the clock is an entity on
AudioControl, so the request that sets the sample rate is addressed to that
interface, and usbfs refuses a control transfer to an interface this process
has not claimed. Without it the mode could never start
(`clock 1: GET RANGE failed: Device or resource busy`). Measured on the CP-88:
the ALSA card survives anyway, MIDIStreaming stays with `snd-usb-audio`, and
audio on USB interface with MIDI on ALSA runs. On UAC1 the rate is set on the
streaming endpoint instead, so AudioControl is left alone.

**Two things to know before switching:**

* Direct modes bypass Android's stream volume, so they are **about 28 dB
  louder** at the same settings. Check the master fader first.
* Entering a direct mode is not instant: the app waits for the kernel, and the
  DSP behind it, to release the interface before claiming it. Leaving one hands
  every interface back and waits for Android to see the device again.

**Buffer.** In the direct modes the buffer is the audio queued on the wire:
6 or 8 ms, in the Audio long-press menu, default **6 ms**. It is the whole
output latency of that stage -- there is no mixer or resampler underneath it.
It decides how often the transport holds, never whether the device latches:
on the tablet plugged in directly, at 6 ms, none at rest and ~2 a minute under
64-voice sweeps on every engine. That rests on the reap loop running on a
borrowed AAudio callback thread -- SCHED_FIFO, which an app cannot get for a
thread of its own; it plays silence on the built-in speaker while it does.
(The Buffer button itself sets AAudio bursts and applies to ALSA mode only.)

## Master volume

A centred fader in dB at the **end** of the chain, `-40` to `+12` dB, 0 dB at
centre; tap the label to return to 0 dB. It is the last thing before the single
conversion to the device's format, which saturates on overs and dithers only for
a 16-bit target.

The whole graph renders in **float** and converts once, at the sink. There is
deliberately **no limiter in the default patch**: a gain reduction with no
control in the UI changes how the instrument sounds rather than how loud it is.
`LimiterNode` exists for the graph editor to place where it can be seen and set.

The volume lives in preferences, not in a patch: how loud the room needs it is a
property of the room.

**Every gain here is ramped, never stepped.** The master and each instrument's
level move to a new value over 15 ms rather than jumping. A jump is a step
discontinuity in the samples, which is a click -- switching parts while notes
were sounding used to produce one. See `engine/include/bagosynths/Gain.h`.

## Parts

The tab row selects what the keyboard plays. **Tap** to show that panel and
make it the only part sounding; **long-press** to bring a part into the mix
without silencing the others, which is how a layer is built.

**NONE**, leftmost, puts every engine at zero. That is the position for playing
the CP-88's own voices: nothing here makes a sound, and because a silenced
engine is parked rather than merely muted, nothing here costs CPU either. MIDI
stays connected throughout.

**ALL** is the other end of the same axis, and it is the only place the
**per-instrument mix faders appear**. Soloed, a part's level is not a choice --
it is up because it is the part that is playing -- so a fader there would
either do nothing or quietly break the solo it sits in.

A silenced instrument is not stopped at once. Its level fades out, its voices
are then released, and it keeps rendering until its own output has been
**silent for a full second**, so reverbs and rotary speakers drain away instead
of being frozen and replayed on the way back. Measured tails on this rig: sfizz
3,7-6,4 s, the Hammond 10,8 s, Surge XT 22,4 s. A 30 s ceiling stops an engine
that never goes quiet, and says so in the log.

## Thread scheduling

The render thread, the USB reap thread and the USB MIDI reader all ask for
`SCHED_FIFO` and fall back to nice -19 (`THREAD_PRIORITY_URGENT_AUDIO`). On
this tablet real-time scheduling is **refused** -- ordinary Android apps have
`RLIMIT_RTPRIO` of 0 -- and the log says so rather than leaving you to assume:

```
render thread: nice -19 (SCHED_FIFO refused: Operation not permitted)
```

What does work, and is the part that matters, is an **ADPF performance hint
session** for the render thread, with the render block as its deadline and the
actual time reported every block. That tells the governor there is a deadline
to meet, so it raises the clock and keeps the thread off the little cores:

```
performance hints: session for 1 thread(s), target 2.902 ms
```

It needs Android 13; the symbols are resolved with `dlsym`, so an older device
simply runs without it.

## Reading the status panel

| Field | What to watch for |
|---|---|
| `path` | In ALSA mode, must say `AAudio EXCLUSIVE LOW_LATENCY`; anything else means the fast path was refused. In a direct mode it reads `USB direct (...)` with the buffer and the device's measured clock. |
| `usb` | Direct modes only. `holds`: short silences taken by design whenever the transport could not prove the next packet joins the stream; the log line `usb hold:` gives each one's reason. **`alt errors` must stay 0**: a hold whose FIFO reset was refused. `errors` counts OUT packets not sent whole and IN packets with no size; `deficit` grows by the silence of every hold. |
| `rate` | 44100. The CP-88 does not do 48 kHz. |
| `burst` | ~89 frames / 2.0 ms on this rig. |
| `latency` | 5–10 ms depending on buffer size. |
| `xruns` | Should stay at 0. Any climb is a real glitch. |
| `cpu` | Percentage of the block deadline consumed. Target 40–50% for a typical patch; ~75% is the measured ceiling. |
| `peak callback` | The worst single block since the last reset. This catches spikes the averaged CPU figure hides. |

The **Buffer** button cycles 1→4 bursts and restarts the stream. 2 is the
sensible default: it costs about 2 ms over a single burst and doubles the margin
against callback jitter.

## Tests

```
engine/tests/run_tests.sh              # everything
engine/tests/run_tests.sh fan_in       # just the cases whose name contains this
```

One compiler invocation, about a second, **no device and no Android SDK**. On
Windows there is no host compiler in this toolchain — the NDK's clang targets
Android — so run them under WSL: `wsl bash engine/tests/run_tests.sh`. CI runs
them on Linux before it builds anything, because they need nothing and they are
the fastest way to learn that the graph compiler is broken.

They cover `Graph::compile`, which is pure logic and was untestable until
`engine/` existed: fan-in mixing, the `first` flag that makes one edge copy
where the rest add, width and type validation, cycle detection, topological
order, buffer assignment, and the bounds on `MidiBuffer`.

The first case is a regression test for a real bug — **a second edge into any
input pin produced silence, with no error** — which survived in this project
until an organ needed fan-in, because the only way to exercise the compiler was
to play a tablet.

The harness is sixty lines in `engine/tests/harness.h` and has no dependency.
GoogleTest was considered and rejected: `engine/` exists to prove that a piece
of this code needs nothing, and a test runner that needs a package manager
undoes that on the first line.

## Architecture notes

**`engine/` is the portable half**, and the rule is absolute: nothing in it may
include an Android, Oboe, JNI or third-party-engine header. It holds the node
graph, its compiler, and the numeric helpers around them, and it builds and runs
on a laptop. Everything device-shaped — the Oboe stream, AMidi, the render
thread, the JNI bridge and the six instrument nodes — lives under
`app/src/main/cpp/`.

The instrument nodes are the deliberate exception: each includes a third-party
engine header, none of those libraries is built for the host here, and moving
them would mean cross-compiling six engines for a laptop to gain nothing
testable. They move when there is a reason better than symmetry.


The audio thread lives entirely in C++ and never calls into Java. MIDI is polled
from inside the audio callback (as in Google's native-midi sample) rather than
from a reader thread, because `AMidiOutputPort_receive` is non-blocking — a
separate thread would have to spin or sleep, costing either a core or latency.
The tradeoff is that MIDI events are quantised to the 2 ms block boundary.
Sample-accurate scheduling is an M5 refinement.

Everything in the callback is preallocated and bounded: the render scratch is
sized at stream-open time, the voice array is fixed at 64, and the MIDI poll is
capped at 64 messages per block so a flood cannot blow the deadline.

## Next

Six instruments are shipped and **all six have been played**: *"all instruments
play well"*. Earlier verdicts were *"perfect!"* for the sampler under forearm
clusters and *"works beautifully!"* for the organ, Surge XT and the DX7.

The next choice is open. The one thing still standing between this and a gig is
**an hour warm** — thermal throttling is what bites live rather than at a desk,
and it is the last piece of abuse testing left. After that: a real General MIDI
SoundFont, Clouds as a granular effect node (already compiled, as Plaits was),
or the graph editor ([GRAPH.md](GRAPH.md)).

