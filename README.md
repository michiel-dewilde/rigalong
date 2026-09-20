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

## Build

Builds and runs. Working toolchain: **AGP 8.13.2, Gradle 8.14.5, JDK 21, Kotlin
2.0.21, NDK 30.0.16248370, CMake 4.1.2, compileSdk/targetSdk 36, Oboe 1.9.3**.

First, fetch the upstream engines. They are not in this repository — ~183 MB of
other people's code, recoverable exactly from the commits pinned in
[LICENSING.md](LICENSING.md):

```
tools/fetch_deps.sh          # sfizz, setBfree, Surge XT, Dexed, FluidSynth and stb
                             # (Plaits needs no clone — it is inside Surge's libs/eurorack)
tools/install_soundfont.sh   # put an .sf2/.sf3 on the tablet
```

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

`third_party/` is over a gigabyte of other people's code and is not committed.

```
tools/fetch_deps.sh
```

clones sfizz, setBfree, Surge XT and Dexed at the exact pinned commits, fetches
only the submodules that are actually built, and applies the five small patches
in `tools/patches/` (bionic has no `execinfo.h`; two upstream CMake bugs that only
appear via `add_subdirectory`; and a namespace rename that keeps sfizz's and
Surge's vendored copies of the same tuning library from colliding, and one that
drops a third copy out of Dexed). It is safe to re-run — already-applied patches
are skipped.

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

## Reading the status panel

| Field | What to watch for |
|---|---|
| `path` | Must say `AAudio EXCLUSIVE LOW_LATENCY`. Anything else means the fast path was refused. |
| `rate` | 44100. The CP-88 does not do 48 kHz. |
| `burst` | ~89 frames / 2.0 ms on this rig. |
| `latency` | 5–10 ms depending on buffer size. |
| `xruns` | Should stay at 0. Any climb is a real glitch. |
| `cpu` | Percentage of the block deadline consumed. Target 40–50% for a typical patch; ~75% is the measured ceiling. |
| `peak callback` | The worst single block since the last reset. This catches spikes the averaged CPU figure hides. |

The **Buffer** button cycles 1→4 bursts and restarts the stream. 2 is the
sensible default: it costs about 2 ms over a single burst and doubles the margin
against callback jitter.

## Architecture notes

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

