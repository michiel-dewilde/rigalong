# Findings

Durable, still-true knowledge for this project, organised by topic. Everything
here is forward-looking: a constraint to respect, a rule to follow, or a number to
design against.

**This is not a log.** It keeps only what remains true, with the dead ends
removed; when a finding here is superseded it is rewritten, not appended to. The
chronological record — the wrong turns and the corrections, which are useful in
their own right — is kept privately and is not part of this repository. Nothing
here depends on it: every claim below stands on the measurement quoted beside
it.

---

## 1. Android audio on this hardware

**AAudio grants MMAP + EXCLUSIVE + LOW_LATENCY over USB here.** This is not
guaranteed on Android generally and was the project's single biggest risk. It
works with the CP-88 on a Galaxy Tab S9 Ultra. Do not assume it transfers to other
interfaces or devices without testing.

**Always select the audio device explicitly, by name.** Auto-selection can resolve
to the built-in speaker, which reports excellent latency and tells you nothing
about the rig you actually play through. The app searches `AudioDeviceInfo` for a
USB output naming itself CP88/CP73/Yamaha.

**Pin the sample rate only on the device that demands one; let every other
endpoint run native.** The CP-88 is 44.1 kHz only. Asking the *microphone* for
44.1 to match it inserted a resampler that cost 4.3 ms and tripled the measurement
variance — delay that `calculateLatencyMillis()` never reports.

**A reported latency that grows over time means a stream you are not draining fast
enough.** Reading one output block's worth of input per callback built a backlog
until the reported input latency reached 3081 ms. Drain fully, every callback.

**`Usage = Game`, `ContentType = Music`, `Spatialization = Never`.** Game rather
than Media: this is interactive audio, not a media session, and it avoids
media-session behaviours (headset transport keys, "now playing") and any
media-oriented post-processing. Verified to keep MMAP/EXCLUSIVE.

**ADPF, not manual CPU affinity.** The SoC is 1×3.36 GHz + 4×2.8 + 3×2.02, so
core placement is worth ~1.7×. But an affinity mask prevents migration away from a
thermally throttling core, and the prime core throttles soonest under sustained
load. `setPerformanceHintEnabled(true)` plus `reportWorkload()` is the sanctioned
mechanism and takes an array of thread ids.

## 2. The CP-88

| | |
|---|---|
| USB audio | 2 in / 2 out, **44.1 kHz only**, **16-bit** at the HAL |
| Routing | USB audio in is mixed to OUTPUT L/R, the XLRs and PHONES |
| Bit depth | Not published by Yamaha; measured as `format=I16` whatever is requested |
| Controls | Panel knobs, sliders and switches transmit MIDI CC |
| Master mode | Local Control off makes it a 4-zone controller over USB and DIN at once |
| Quirk | A `MIDI Control Delay` setting (0–1500 ms) exists; leave at 0 |

**Capture and playback are mutually exclusive on Android.** Whichever direction
opens first wins; the other fails `ErrorDisconnected`. **The app must always open
output first** — opening capture first silently costs you the instrument, which is
the worst failure available on a stage.

**With the CP-88 attached, Android makes it the default input device.** An input
stream opened with an unspecified device id therefore resolves to the piano's
capture and fails. Select the built-in mic explicitly by `TYPE_BUILTIN_MIC`.

**16-bit output means dither.** The engine renders float, so something must
quantise. Truncation produces signal-correlated distortion exactly where this
instrument is judged — reverb tails decaying into silence, quiet solo passages.
TPDF dither after the limiter costs nothing.

## 3. Audio architecture

**The callback does not synthesise.** It copies finished audio out of a ring
buffer — an operation that cannot be late. A render thread fills the ring ahead of
playback. This converts a **hard** deadline into a **soft** one: a blocking read
inside a sampler no longer lands on a 2 ms callback deadline, it merely eats into
the reserve.

Consequences when reading the status panel:
* `xruns` is structurally 0 and means nothing. **`ring … underruns` is the real
  counter.**
* `cpu` and `peak callback` read ~0 for the same reason. **`render peak` is the
  number that matters**, measured against the render block's duration.

**Rendering ahead does not reduce latency** — N ms of reserve costs N ms, exactly
as a larger output buffer would. It buys immunity to jitter, not speed.

**The reserve adapts; do not replace it with a constant.** A constant was tried at
both ends and failed at both: 11.6 ms was clean but dulled the feel, 2.2 ms felt
right but clicked. Policy: 192-frame floor, +256 on any underrun (react hard —
latency is cheaper than a dropout), −32 after 6 s without one.

**Adapting is seamless in both directions and needs no sample inserted or
dropped.** Growing means the render thread runs further ahead; shrinking means it
idles briefly while the callback drains the ring.

**Once a render thread exists, the output buffer only has to cover scheduling
jitter**, not synthesis — so it can go back to AAudio's 3-burst floor. Layers
should do *different* jobs rather than all hedging against the same worst case.

**Note-ons are budgeted by time, not by count** (0.35 ms per block, excess
queued). Starting a note costs ~50 µs on a cold sample and ~1 µs on a warm one, a
50× spread, so a fixed count needlessly delays cheap notes. Note-offs, CCs and
pitch bend are never delayed — they are cheap, and holding them back sounds worse
than the problem being solved.

**A rate limiter protects against *many* expensive operations, never against
*one* slow one.** A single cold note-on measured up to 12 ms; the budget is only
checked after a call returns, so it was already too late. If a single call on the
audio thread can block, remove the blocking — do not schedule around it.

**Everything in the callback and the render loop is preallocated and bounded.** No
allocation, no locks, no file I/O, no JNI. Denormals are flushed via FPCR, which is
per-thread and must be set on the thread that does the arithmetic.

**Fan-in is mixed by the compiler, not by nodes.** Several edges into one input
pin become explicit mix steps attached to the consuming step: the first source
copies, the rest add, and the executor stays a flat loop. Nodes never learn how
many sources feed them. Only `numFrames` are mixed — the buffers are sized for the
largest block the engine might ask for, and we render 128 at a time.

This is what makes layering two instruments into one effects chain work, and it
was **not** implemented until the organ needed it: a second edge into any input
pin used to produce silence, with no error.

## 4. sfizz

**`renderBlock(buffers, frames, numOutputs)` counts stereo PAIRS, not channels.**
Passing 2 makes it write to `buffers[2]` and `[3]` and segfault immediately.

**`loadSfzFile()` must not run concurrently with `renderBlock()`.** Use a
handshake, not a lock: a lock held across a multi-second load would stall
rendering for its whole duration. The loader raises a flag, the render thread
parks and emits silence, and only then is sfizz touched.

**sfizz carries a large fixed cost per call. Do not shrink the render block below
128 frames.** At 64 frames the render peak was 1.38 ms to produce 1.45 ms of audio
(95% utilisation); at 128 it was 0.90 ms for 2.9 ms (31%).

**Voice count is a hard CPU budget, not a generosity dial.** At 128 voices with
callback rendering the sustained cost sat permanently over the deadline and no
amount of buffer rescued it. Currently 64 — the canonical figure, chosen to leave
headroom for effects.

**Warm the sample heads, not the whole library.** What blocks at note-on is sfizz
reaching for a sample's preloaded head; streaming deeper is absorbed by the ring.
sfizz preloads 65536 frames = 256 kB at 16-bit stereo, so reading the first 512 kB
of each file suffices: ~330 MB in about a second for Salamander, versus 1.2 GB
over a minute. Minimum priority, deliberately unpinned.

## 5. The tonewheel organ (setBfree / Beatrix)

Linked as a **static C library** built from `third_party/setbfree`: the DSP
sources only, not the LV2 plugin and not the JACK program. The file list is
upstream's own `LV2OBJ` from `common.mak`, minus `memstream.c`.

* Compile **without** `HAVE_ASEQ` and **without** `HAVE_ZITACONVOLVE`, as
  upstream's note in `common.mak` instructs for a non-JACK build. Define
  `HAVE_MEMSTREAM`: bionic has `open_memstream`, and the whole of `memstream.c`
  is guarded by its absence.
* `src/main.c` is the JACK entry point and is NOT compiled. The three symbols it
  would define — `mainConfig`, `mainDoc`, `SampleRateD` — are in
  `cpp/beatrix/beatrix_glue.c`.
* **`SampleRateD` is a global**, read by `tonegen.c` and `vibrato.c`. Set it
  before initialising anything. One output device means one rate, so this is
  survivable, but a second organ at a different rate would be wrong.
* Headers have no `extern "C"` guards of their own — wrap them at the include
  site, with system headers included first.
* The internal chain is upstream's, in upstream's order:
  `oscGenerateFragment -> preamp -> reverb -> whirlProc3`.
* **`BUFFER_SIZE_SAMPLES` is 128**, the same as our render block. One fragment per
  block, no carry.
* `initWhirl(w, NULL, rate)` works in b_whirl's own LV2 because that plugin
  supplies a stub `useMIDIControlFunction`. Ours links the real one, which
  dereferences the config — so **every component needs a real `midicfg`**.

**Cost: ~0.13 ms per 128-frame block**, about 4% of the block's time, release
build. Cheap enough that the organ runs continuously whether or not it is audible,
which is what makes layering free.

**Parameters must not be set from the UI thread.** setBfree's control functions
mutate engine state directly — recomputing wheel gains, reloading key tables — so
`HammondNode` queues changes in a lock-free ring and drains them at the top of
each block. Same class of race as sfizz's loader, caught before it bit.

**Intercept before `parse_raw_midi_data`:**

* **Program change** — the CP-88 sends one on a voice change and `installProgram()`
  would rewrite the drawbars and the Leslie mid-set.
* **CC 120 / CC 123** — setBfree's controller table does not carry them, so PANIC
  is ignored. An organ note does not decay; it drones until the app is killed.
  Map them to `midi_panic()`.

**setBfree's default CC map is a gift on this rig**, needing no configuration:
CC 64 (sustain pedal) toggles Leslie fast/slow, CC 1 (mod wheel) selects the
speed preset, CC 7/11 is swell.

**One keyboard, three manuals:** `setKeyboardSplitMulti()` routes below one split
point to the pedal bus and below another to the lower manual. Without it, the
lower and pedal drawbars are unreachable on a single controller.

## 5b. Patches and persistence

Saved as JSON in **internal** storage (`filesDir/patches/`), not the external
instrument directory — patches are the app's own state and internal storage
avoids the mode-770 trap that instrument packs hit.

* **Parameters are stored by NAME.** An index is what changes when an enum gains
  a value; a file must not contain one.
* **Nodes are matched on load by type and ordinal-within-type.** Inserting a node
  earlier in the graph then does not shift every saved patch by one.
* **Unknown nodes and parameters are logged and skipped**, never fatal. A patch
  from a newer build degrades rather than refusing.
* **The topology is written but not rebuilt** — the graph is still built in code,
  so loading applies parameters to existing nodes. The edges are in the file so
  today's patches stay readable once the editor can create nodes.
* `last.json` is written on **every** `onPause` and restored on launch. A patch
  you must remember to save is a patch you lose.
* **Content is referenced by id, not stored as a parameter.** `instrument` holds
  the library id, `surgePatch` Surge's `"Category/Name"`, `dexedVoice` the DX7's
  `"Bank/NAME"`. All are applied through the ordinary asynchronous load, because
  all of them open files.
* **A restore that names content RACES the library that holds it.** The patch is
  applied during `onResume`; the libraries load on a background thread precisely
  because they are slow, so the restore always arrives first and finds nothing.
  The fix is for the node to **record the request unconditionally and honour it
  when loading finishes** — never to look it up and give up. Both `SurgeNode` and
  `DexedNode` do this, and an explicit choice clears the pending request so a
  late restore cannot overwrite it.
* **Only restarting the app finds this.** It builds, it loads, it sounds, and
  patches select correctly by hand; the broken path is the one nobody watches.
  Set something, press home, kill, relaunch, read the log.
* **`am force-stop` does not call `onPause`.** Testing auto-save with it alone
  proves nothing; send `KEYCODE_HOME` first, then kill.

The JNI surface behind this (`nativeNodeCount`, `nativeNodeType`,
`nativeNodeParamName`, `nativeGetNodeParam`, `nativeSetNodeParam`, `nativeEdges`)
is deliberately generic — it is also what the graph editor will read.

## 5c. Surge XT

Linked as `surge-common` from `third_party/surge` via `add_subdirectory`, with
**no JUCE**. The escape hatch is upstream's own: `SURGE_SKIP_JUCE_FOR_RACK`
exists so the VCV Rack port can consume the engine headless, and every JUCE
target hides behind it. This is a maintained path, not a forced one.

**Configure with:** `SURGE_SKIP_JUCE_FOR_RACK`, `SURGE_SKIP_LUA` (LuaJIT
cross-compiles only by building host tools first; `HAS_LUA=0` is supported
upstream and costs only formula modulators and wavetable scripts),
`SURGE_SKIP_ODDSOUND_MTS`, `SURGE_SKIP_STANDALONE`, `SURGE_SKIP_WERROR`,
`ENABLE_LTO=OFF`, and the plugin/test targets off. Only 15 of the 23 submodules
are needed — **not** JUCE, LuaJIT, pybind11, melatonin_inspector,
clap-juce-extensions, MTS-ESP or surge-rs.

**Four modifications, all tiny, carried as commits on the forks:**

* bionic has no `execinfo.h`; `sst-plugininfra`'s `misc_linux.cpp` calls
  `backtrace()`. It is a crash-path diagnostic, so it is stubbed on Android.
* `src/common/CMakeLists.txt` used `${CMAKE_SOURCE_DIR}` for r8brain — the
  *top-level* source dir, i.e. ours. `${SURGE_SOURCE_DIR}` is already defined.
* the same file passed `${CMAKE_PROJECT_VERSION_MAJOR}` to its version
  generator. Nested, that is the top-level project's version — empty — and it
  emits `const int Build::MajorVersionInt = ;`. Use `${Surge_VERSION_MAJOR}`.
* sfizz's vendored tuning library is renamed — see section 8.

The last two are latent upstream bugs that only appear via `add_subdirectory`.
None is in DSP.

**Bionic has no `libpthread`** — the symbols are in libc — so Surge's
`target_link_libraries(... pthread)` fails to link. Declare an empty INTERFACE
target of that name before `add_subdirectory` rather than patching upstream.

**`SurgeStorage` reaches for `HOME`**, and `sst-plugininfra`'s `homePath()`
**throws** when it is unset or empty. Android promises nothing about `HOME`, so
`setenv` it (and `XDG_DATA_HOME`, `XDG_CONFIG_HOME`) to an app-private directory
before construction. Supplying the data path explicitly also skips the whole
portable-install search waterfall.

**Construction is not free and is not `prepare()`'s job.** It scans the patch
library — 641 patches, 188 wavetables, **72.7 ms** measured in the app — so it
runs on a loader thread and `process()` renders silence until an atomic pointer
publishes the engine.

**`BLOCK_SIZE` is a compile-time 32**, so a 128-frame render block is exactly
four `process()` calls and MIDI lands with 0.73 ms resolution. No fragment carry
is needed in practice.

**`loadPatch()` opens and parses a file on the calling thread** — 2.8 ms mean,
63.8 ms worst across the factory library. It must never touch the audio thread;
it uses the same park handshake as sfizz's sample loading, so only this node
falls silent while the rest of the rig plays on.

**Intercept MIDI the same way the organ does:** drop program change (the CP-88
sends one on every voice change), and map CC 120 / 123 to `allSoundOff` /
`allNotesOff`.

**Patches are addressed by `"Category/Name"`, never by index** — an index means
something different the moment the library changes. `surgePatch` in a saved patch
holds that string.

**What is exposed is the patch, the eight macros and a level** — not an editor.
Surge has around 2000 parameters; the macros are what the patch author chose to
put under your hand, so they are the right performance controls by construction.

**Content lives outside the APK**, beside the instrument library, at
`files/surge-xt/`. Surge takes a filesystem path and cannot read from an APK.
The useful core is **33 MB** (`wavetables` 6.8 MB + `patches_factory` 25 MB +
the two preset folders); the full `resources/data` is 483 MB, most of it
3rd-party extra content, GUI skins and impulse responses we do not use.
`tools/install_surge_data.sh` installs it, with the same `chmod` that the
instrument library needs. Absent, Surge still runs on its init patch.

**Ask the engine what the patch author named things.** Surge stores eight macro
labels with every patch in `SurgePatch::CustomControllerLabel`, and showing them
turns eight faders called M1..M8 into Cutoff, Osc Mix, Sync. 71 of the 641
factory patches name at least one. Two traps: the fields are fixed-size `char`
arrays that are not guaranteed NUL-terminated, so bound the read by
`CUSTOM_CONTROLLER_LABEL_SIZE`; and Surge's "unnamed" value is the string
**`"-"`**, not empty — use its own test from `ModulationSource.cpp` rather than
guessing at one.

## 5d. The DX7 (Dexed / msfa)

Linked as a **static library from `msfa` only** — the six-operator FM core inside
Dexed, Apache-2.0, written for Android originally and with no dependencies at
all. **Not** the Dexed plugin, which is GPL-3 and JUCE-bound. Voice allocation,
cartridge parsing and MIDI are ours.

* **`tuning.cc` is NOT compiled.** It is the one file in msfa that pulls in JUCE,
  and the only user of the tuning library. `createStandardTuning()` is twenty
  lines and lives in `cpp/dexed/dexed_glue.cpp`.
* **`Tunings.h` is patched out of `tuning.h`** — four lines. It would have been
  the third incompatible copy of that library in one binary. See section 8.
* **`libMTSClient.h` is a stub of ours**, found because `dx7note.h` includes it
  with quotes and it is not beside that file. No upstream patch needed. msfa
  touches two functions and both are guarded by `MTS_HasMaster()`.
* `env.cc` includes `../Dexed.h`, which sounds alarming and is not: it is a
  `TRACE` macro and one function declaration, no JUCE. Define `DEXED_ID`.
* **msfa's tables are static and shared** — `Freqlut::init`, `Lfo::init`,
  `Env::init_sr`, `PitchEnv::init`, `Porta::init_sr`, `Sin::init`, `Exp2::init`.
  One rate for the process. Same shape of constraint as setBfree's `SampleRateD`.
* **`Controllers::mpeEnabled` defaults to TRUE upstream.** Turn it off for a
  single-channel controller, or MPE pitch handling gets in the way.
* **`N` is 64**, so a 128-frame render block is two `compute()` calls.
* msfa's voices **add** into an int32 buffer and upstream converts and clears
  per voice rather than summing in fixed point, which would overflow. Do the
  same.
* **A DX7 is mono.** Copy to the second channel rather than pretending
  otherwise; width belongs to an effect.

**Cartridges** are DX7 SysEx bulk dumps: 4104 bytes, `F0 43 0n 09 20 00`, then
32 voices of 128 packed bytes. Only that exact shape is accepted; a single-voice
dump or a short file is skipped rather than guessed at. Unpacking to msfa's
155-byte form is mostly bit-unpacking of paired fields, and the "don't care"
masks matter — cartridges in the wild rely on them.

**The 33 cartridges Dexed ships are committed and bundled** (1056 voices,
268 kB). That is the difference between an instrument and an instrument that
needs a shell script before it makes a sound. User cartridges go in the external
`dx7/` folder and are read as well.

**Three FM cores, selectable**: MARK I models the original hardware's quirks and
is the default; MODERN is msfa's own cleaner arithmetic; OPL is the cheaper
Yamaha voicing. They are not subtle and the choice is musical.

**Cost: 0.187 ms per 128-frame block at 30 voices**, peak 0.402 ms — about 6%.
Fixed-point FM is cheap.

**A hand-transcribed table of magic numbers must assert its own shape.** The DX7
init voice is 155 bytes; one row was transcribed with 20 entries instead of 21,
which shifted the algorithm, the LFO block and the transpose, and the instrument
was silent. Not wrong-sounding — silent. A `static_assert` on the total size
catches it at compile time, and the compiler is better at counting than the eye.

## 5e. Plaits (Mutable Instruments)

**Sixteen synthesis models for the cost of a node wrapper, because Surge already
vendors them.** `surge/libs/eurorack` is compiled and linked already — Surge's
Twist oscillator *is* `plaits::Voice` and its Nimbus effect *is*
`clouds::GranularProcessor` — and `surge-common` links `surge::eurorack` PUBLIC.
No fetch, no pin, no patch, no new archive. MIT, the loosest licence in the rig.

**Plaits' sample rate is a compile-time constant and cannot be changed.**
`kSampleRate` in `plaits/dsp/dsp.h` is 48000, baked into every phase increment
and wavetable; there is no setter. Running at 44100 therefore means rendering at
48 kHz and resampling, which is what Surge does too.

**Resample the polyphonic mix once, not each voice.** Resampling is linear, so
summing sixteen voices at 48 kHz and converting the sum is arithmetically
identical to converting each voice — at one sixteenth of the filter cost and
64 kB less ring buffer per voice. Plaits' `out` and `aux` are two *different*
signals rather than a stereo pair, so they ride the resampler's two channels and
are blended afterwards by the AUX fader, free and both band-limited.

**A monophonic module's output needs headroom before it is summed.** Plaits'
post-processor is scaled so that one voice reaches nearly full scale. Sixteen
summed read a flat `out 1.000 CLIPPING` on the first chord. A fixed 0.25 scale
fixes it. Deliberately **not** a divide by the live voice count: normalising by
polyphony makes an instrument duck every time a note is added, which is far more
audible than a limiter catching a ten-note cluster.

**Drive the low-pass gate rather than adding an envelope.** `level_patched` plus
`trigger_patched` is how the hardware is played: LEVEL from the gate makes the
LPG sustain under a held key and turns DECAY into the release, TRIGGER gives the
rising edge the struck and plucked models need, and with LEVEL patched velocity
becomes `p.accent`, so it moves brightness and loudness together. An ADSR of ours
on top would have worked and would have sounded wrong.

**Plaits needs the trigger to fall before it will fire again.** Restriking a key
or stealing a still-gated voice produces no edge at all unless trigger is forced
low for one sub-block first. Without it the stolen voice is simply silent.

**Retire a voice on silence, not on its gate.** The gate is wrong twice over: the
LPG rings on after it falls, and the drum models are one-shots that never had a
gate. The rule that holds is no gate plus rendered output below -84 dB for 10 ms.

**`TEST=1` is a portability flag here, not a test flag, and it must match.**
stmlib selects between portable C and 32-bit ARM assembly (`ssat`, `vsqrt.f32`)
on it, so without it the file will not assemble on arm64. It is scoped to the one
source file — `TEST` is far too common a macro to define globally — and it has to
agree with what `eurorack` itself was built with, because it chooses between two
bodies for the same inline functions.

**Label the three knobs per model.** HARMONICS, TIMBRE and MORPH mean something
different in each of the sixteen engines. The names are in the Plaits manual
rather than the source, so do not write them from memory — Surge XT's
`TwistOscillator` carries the table already, for the same engines in the same
registry order, and it is GPL-3 like this project.

**Cost: 0.140 ms per 128-frame block at 30 notes**, peak 0.765 ms, and
**0.002 ms idle** (see section 7 — idle was 0.494 ms before the node learned to
skip its own resampler). 16 voices cost 340,992 bytes of state.

## 5f. SoundFonts — SF2 and SF3 (FluidSynth)

**FluidSynth 2.6 needs no patch to build for Android, and no glib.** GLib was the
historical blocker and it is gone — GLib's hash table is vendored as
`src/utils/fluid_hash.c` under the LGPL. The OS abstraction is C++11 threads
rather than pthreads, so the bionic `-lpthread` trap does not arise either. A
standalone spike built `libfluidsynth.a` for arm64 on the first attempt.

**Compile out every driver.** FluidSynth can open ALSA, JACK, PulseAudio, Oboe,
OpenSLES and more by itself, and it must do none of it inside a graph — the only
thing allowed to touch the device is `AudioEngine`. `enable-network=off` is not
only about size: FluidSynth's shell server listens on a TCP socket, and a stage
instrument has no business opening a port.

**This node owns no voices.** Unlike the other five instruments, FluidSynth has
its own polyphony, channels and SoundFont, so the node is a MIDI adapter and a
level. `fluid_synth_write_float` plus the MIDI entry points is the whole
interface, and there is no voice array to write.

**It does not split the block at each MIDI event, deliberately.** FluidSynth
queues note events to its own rvoice mixer and starts them at its internal
64-frame boundary whenever they arrive, so splitting would cost extra calls to
buy a precision the engine does not offer. The resulting quantisation is 64
frames — 1.45 ms, under half a render block, and below what the 89-frame USB
burst already imposes.

**SF3 does not need libsndfile.** SF3 is SF2 with Ogg Vorbis samples, and
upstream decodes them through libsndfile, which drags in libogg and libvorbis.
`stb_vorbis` does the same job in one public-domain file. The patch leaves the
part that is genuinely FluidSynth's — locating the compressed bytes under its own
file callbacks and mutex — and calls out to `cpp/fluid/fluid_glue.c` for the
codec. **Patch BOTH places**: besides the decode stub there is a version check
that rejects an SF3 outright when libsndfile is absent, and missing it yields a
build that politely refuses every SF3 it is given.

**A CMake target name is global to the whole build.** FluidSynth's `uninstall`
convenience target collides with the identically named one in Surge's vendored
zstd, and neither project is at fault. `if(NOT TARGET uninstall)` fixes it and is
harmless upstream. Expect this from any project that offers conveniences.

**A static library exports even its PRIVATE dependencies, and that breaks
`export()`.** `target_link_libraries(libfluidsynth PRIVATE fluidglue)` makes
FluidSynth's own `install(EXPORT)` and `export()` refuse to export a target
depending on something outside their export set. `$<BUILD_INTERFACE:>` fixes the
install and **not** the build-tree export — which is exactly what it excludes.
The way out needs no patch at all: list the glue AFTER the library in our own
link line, because an archive resolves its undefined symbols against what follows
it.

**Android Gradle builds every target it discovers, not `all`.** FluidSynth marks
its unit tests `EXCLUDE_FROM_ALL` and AGP built them anyway, failing the build.
`targets += "bagosynths"` in `build.gradle.kts` says what was always meant, and is
faster.

**Content must be addressed by a name that is UNIQUE, not merely by a name.** The
same SoundFont routinely ships as both `.sf2` and `.sf3` — FluidSynth's own test
font does — so naming fonts by their stem produced two identical entries, and
`loadSoundFontNamed` would always have returned the first. A patch saving the SF3
would silently have restored the SF2. Fonts are named by file name, extension
included. This is the third content-addressing bug in this project; see also 5b.

**Record the preset BEFORE loading the font.** A font load takes tens of
milliseconds and lands on its first preset when it completes, so asking for the
font first means the load overwrites the preset that was just restored.

**SoundFonts are content, like SFZ instruments.** They have their own licences,
a General MIDI font is 30 MB or more, and none of it belongs in the APK. They
live beside the instrument library; `tools/install_soundfont.sh` puts them there
and does the `chmod -R 777` that `adb push` makes necessary — without it the app
lists the directory, finds nothing, and says so as though the folder were empty.

**Cost: 0.427 ms per 128-frame block at 30 notes**, peak 2.083 ms, and
**0.013 ms idle** — no skip needed, because it stops at its own voice count.

## 6. Instrument content

**Instruments are content, not code.** They live outside the APK under
`instruments/<id>/`, addressed by a stable **id** and resolved to a path only at
load time. A patch records `"salamander-grand"`, never a path — so patches stay
small and portable, and a missing library is a nameable condition rather than a
broken path.

**A pack can reference another pack's samples.** `salamander-tight` is a 77 kB
`.sfz` whose sample paths point into `../salamander-grand/`, so a variant costs
kilobytes rather than a second copy of 1.2 GB.

**Sample libraries carry recorded lead-in silence, and it is pure latency.**
Salamander's samples average 9.3 ms of it (range 4.5–12.5 ms). Removing it with
per-region `offset=` took the instrument from 43.5 ms to 35.9 ms.

Three rules make `offset=` safe, learned by shipping a clicking version first:
1. **Absolute onset threshold**, relative to the file's own noise floor — never a
   fraction of its peak, which is reached late in a loud sample and cuts the attack.
2. **A safety margin** before the detected onset.
3. **Snap the offset back to a zero crossing.** Starting playback on a non-zero
   sample is a step discontinuity, and a step is a click. This is the rule that
   matters most and costs nothing.

**The lead-in is not pure silence** — it sits ~40 dB below the attack and contains
quiet mechanical noise. Trimming is a deliberate trade, so keep the untouched
instrument alongside. The SFZ format has no way to declare "this sample begins
before the musical onset": there is `offset` and `delay`, but no negative delay.

**Choose the sample rate that matches the output.** The 44.1 kHz/16-bit Salamander
build was taken over 48/24 and FLAC so sfizz never resamples and the bit depth
matches the endpoint.

## 7. Measurement technique

**An output meter beats a microphone for "is it making sound".** The status line
shows the peak of what actually leaves the graph — after the mixing, the reverb
and the limiter — as a bar, decayed ~60 ms. It is computed in the loop that
already interleaves the block, so it is very nearly free. It settled in one
reading what the microphone could not settle at all: on a quiet morning the mic
probe reads its noise floor for *every* part, including the piano, so it proves
nothing about any of them.

**A peak you cannot re-arm is not a measurement.** `RESET PEAKS` once cleared
only the callback peak, leaving the render peak and underrun count as high-water
marks since launch — in practice, engine construction. Two measurements were
wasted on a stale 22.79 ms before the identical value appearing twice gave it
away. It now clears the render peak, the underruns and every node's profile.

**A maximum over a window cannot resolve a change in a mean.** The graph-wide `render peak` is a high-water mark, so it catches scheduler jitter: two samples of the identical configuration read 1.07 ms and 1.30 ms. Used as a before/after for a 0.5 ms saving it said the fix made things worse. The per-node `last` value is the right instrument for a steady state, because at idle it IS the steady state.

**To profile the graph at idle, stress it and then panic it.** `nativeLogProfile` only fires 1.5 s after STRESS 30, so there is no idle profile button. Tapping STRESS and then PANIC immediately leaves the graph quiet by the time the profile logs, and every `last` is then an idle figure while every `max` still describes the stress.

**An instrument can be expensive while completely silent.** Plaits cost 0.494 ms per block with every voice panicked -- the second most expensive node in the graph, all of it resampling silence. Nothing on the status line shows this; only the per-node profile at idle does. Worth checking for every engine that carries a fixed per-block cost, which is any engine with a resampler, a reverb tail or a fixed-size FFT.

**Per-node profiling is what tells you whose spike it is.** A 19 ms render peak
under a 120-note stress looked alarming until the profile named it: sfizz's
note-on path at 13.4 ms, which is known, budgeted and pre-existing. Surge was
0.53 ms steady. Without the per-node split, the new dependency takes the blame
for the old behaviour.


**Never threshold raw samples.** Audio crosses zero twice per cycle, so "N
consecutive samples above threshold" is unachievable for real signal. One probe
failed at `maxPeak=0.1914 thresh=0.0150` — 13× over threshold and still missed.
Use an envelope. (A synthetic click can pass only because a full-scale square
never approaches zero.)

**"Time to X% of peak" measures attack shape, not latency.** A piano and a saw
reach their peak on completely different timescales. For latency, use first energy
above the room floor.

**Record once, analyse many times.** The app captures the microphone plus a marker
at every note-on; the capture is then analysed offline as often as needed. This
replaced arming a live probe and asking a human to replay a passage after every
threshold tweak.

**Drive notes over adb to remove the human and the key-travel confound.** A player's
key travel on a weighted action is ~17.7 ms — the mechanical thunk reaches the mic
*before* the note-on, because the note triggers near escapement, not at first
touch. That is real and worth knowing, but it is not electronic latency.

**A sampler stress test must sweep the keyboard with fresh samples.** Repeating one
cluster keeps those samples resident and reports everything clean — it did, at
4 xruns, while real playing was producing 1290.

**Differences are trustworthy even when absolutes are not.** Measurements sharing
the same output path, air and microphone cancel their common terms, so
instrument-to-instrument comparisons are exact even where the absolute figure
carries an unknown constant.

**Judge performance only from release builds.** Debug native builds are
unoptimised and will glitch regardless.

## 8. Build and toolchain

**Judge audio performance ONLY from a release build.** This is not a preference,
it is the difference between a working instrument and a broken one. Measured on
identical code, at idle, with the organ in the graph:

| build | latency | ring underruns | render peak |
|---|---|---|---|
| debug | 11.0 ms | 17865 and climbing | 34.07 ms |
| **release** | **7.9 ms** | **0** | **0.98 ms** |

A debug build produces numbers so bad that they look like a design fault and will
send you hunting one. `app/build.gradle.kts` says so in a comment; believe it.
Install with `./gradlew installRelease` (the release build is signed with the
debug key precisely so it can be installed for testing).

`JAVA_HOME` must point at a **JDK 21** — Android Studio's own `jbr` is now 25 and
Gradle 8.14.5 refuses it with a bare `25.0.3` as the entire error message.
`C:/Users/michi/.jdks/jbr-21.0.11` is the one that works.

Working set: **AGP 8.13.2, Gradle 8.14.5, JDK 21, Kotlin 2.0.21, NDK
30.0.16248370, CMake 4.1.2, compileSdk/targetSdk 36, Oboe 1.9.3**, sfizz built
from `third_party/sfizz` via `add_subdirectory`.

* **CMake 4.x works** with AGP 8.13 and NDK 30. No downgrade needed.
* **NDK 30's Clang 19 errors on sfizz's vendored `atomic_queue`**
  (`-Wmissing-template-arg-list-after-template-kw`). The code is correct, the
  compiler is newer than the dependency; suppress that one diagnostic.
* **Oboe's prefab needs `-DANDROID_STL=c++_shared`**; the NDK defaults to static
  and the configure fails with CXX1212.
* `minSdk 29` is the only setting limiting who can install (forced by AMidi).
  `compileSdk`/`targetSdk 36` cost nothing in device support, and Play has
  required target 36 since 2026-08-31.

**Two dependencies that vendor the same third-party code are a crash waiting for
a trigger, and nothing will warn you.** sfizz and Surge XT both vendor the Surge
Synth Team tuning library, at different versions, in the same namespace
`Tunings`, with **incompatible struct layouts**. Linked into one shared object,
the linker keeps one copy of each inline constructor and destructor — they share
a mangled name and a comdat group — and whichever library lost then operated on
the other's layout. Visibility does not help: comdat merging is by symbol name.

The tell is a **deterministic** SIGSEGV whose fault address decodes as a
plausible float or double: `0x4089000000000000` is 800.0, appearing where a
`std::string*` belonged. Deterministic also rules out a stack overflow, which is
the first thing one reaches for and cost a wrong turn here.

The fix is a namespace rename on one copy plus an alias, so the other library's
sources compile unchanged: `namespace TuningsSfz { ... }` then
`namespace Tunings = TuningsSfz;`. An alias is a compile-time name and emits no
symbols.

**`tools/check_odr.sh` now does this check properly** and it should be run after
adding any large dependency. It lists every symbol defined by more than one
*dependency tree*, grouping archives by which upstream they came from — because
two copies of one library merging with itself is what comdat is FOR, and only
cross-tree duplicates are the bug. It filters out the standard library and the
compiler's own helpers, which are meant to merge, and it is tested against a
synthetic input carrying the real collision.

**It earned its keep immediately:** Dexed's msfa turned out to carry a THIRD copy
of the same tuning library, found in a minute instead of an hour.

**Know what a dependency already gives you before adding a second copy of it.**
Surge vendors `libs/eurorack`, which is **Mutable Instruments** code: Surge's
Twist oscillator *is* `plaits::Voice` and its Nimbus effect *is*
`clouds::GranularProcessor`, both already compiled into the binary in namespaces
`plaits`, `clouds` and `stmlib`. So Plaits and Clouds cost a node and no new
dependency — while cloning `pichenettes/eurorack` separately for Rings or
Elements would bring a second `stmlib` and a collision to manage. The cheapest
engine is one that is already linked.

**Confirmed by doing it (2026-09-20).** Plaits went in with no entry in
`init_deps.sh`, no patch and no new archive, because `surge-common` links
`surge::eurorack` **PUBLIC** — and `sst-basic-blocks`, which carries the Lanczos
resampler, with it. Whether a vendored library's include directories reach you is
a question of PUBLIC versus PRIVATE on the target that links it, and it is worth
reading that line before assuming either way.

**A CMake target name is global to the whole build.** Two subprojects that both offer an `uninstall` (or `check`, or `docs`) target cannot coexist under `add_subdirectory`, and neither is at fault. FluidSynth collided with Surge's vendored zstd exactly this way. Guard with `if(NOT TARGET x)`.

**A static library exports even its PRIVATE dependencies.** They are needed at the final link, so they land in `INTERFACE_LINK_LIBRARIES`, and a dependency's own `export()` then refuses to export a target that depends on one of yours. `$<BUILD_INTERFACE:>` fixes `install(EXPORT)` and NOT the build-tree `export()`. Usually the right answer is to add no dependency at all and order the link line instead: an archive resolves its undefined symbols against what follows it.

**Android Gradle builds every target it discovers, not `all`.** So a dependency's `EXCLUDE_FROM_ALL` test programs get built, and their link errors become yours. `targets += "<your lib>"` in `build.gradle.kts` is both the fix and a build-time saving.

**`third_party/` is submodules of forks, and the modifications are commits.**
Each fork carries branch `bagosynths/main`, and `.gitmodules` uses a RELATIVE
url (`../sfizz.git`), which resolves against whatever origin the superproject
was cloned from — so ssh and https both work and neither is written down.
`tools/init_deps.sh` checks them out; it exists only because Surge carries 23
submodules and this build needs 15.

**On Windows, `core.autocrlf false` and `core.eol lf` must be set BEFORE the
checkout, not after.** Git for Windows ships `core.autocrlf=true` in its SYSTEM
gitconfig, and `git submodule update` clones and checks out in one subprocess,
so a `foreach` that sets the flag afterwards is too late: the files are already
CRLF, every one reads as modified, and the superproject reports every submodule
dirty. Pass `-c core.autocrlf=false -c core.eol=lf` to the `submodule update`
itself — it propagates to the clone through `GIT_CONFIG_PARAMETERS`, which is
the only place early enough.

**Build Surge somewhere with a short path.** `sst-effects` generates object files
named after preset files, and `CMAKE_OBJECT_PATH_MAX` is 250 characters.

## 9. Deployment and storage

**Deploy over wireless adb** — the USB-C port belongs to the piano.
`adb mdns services` rediscovers the tablet when the adb server restarts.

**`adb push` into the app's external directory fails silently.** Pushed folders are
created by the `shell` user with mode 770 and the app cannot traverse them; the
files inside being world-readable is irrelevant. Fix with
`adb shell chmod -R 777 <pack>`. Also, pushing a folder into an existing folder of
the same name nests it one level deeper.

**Always verify the app is in the foreground before sending taps.** Blind
coordinate tapping once sent 18 taps into the launcher and opened random apps,
because the app had crashed and nothing checked:

```bash
adb shell dumpsys window | grep -c "mCurrentFocus.*be.mdw.bagosynths"   # 1 = safe
```

`tools/stress.sh` has the guard built in and aborts loudly with the crash buffer
instead of tapping blind. **Button coordinates shift whenever a button is added** —
re-read them from a screenshot after any UI change.

## 9a. Recovering from a disconnect

**A request is not a result: `setDeviceId()` does not fail on a device that is
gone.** AAudio falls back to the default output — the built-in speaker — and
returns OK. Measured on a real unplug: asked for device 10051, got device 2 in
Legacy mode at an 882-frame burst, and logged a successful recovery. **Compare
`getDeviceId()` against what was asked for and refuse the stream otherwise.**
Specifying the device guarantees nothing on its own.

**Record the REQUESTED device id, never the one that came back.** Storing the
fallback makes the speaker "home", so every later disconnect recovers onto it
deliberately.

**Watch for "running on the right device", not "running".** A silent fallback
leaves `running` true, so a watchdog that waits for a stopped stream never fires.
This is the dangerous failure: audio works, the meters move, it sounds fine in a
quiet room, and a stage hears nothing.

**A replug always gets a NEW device id.** Measured across three replugs of the
same CP-88: **10051, 10075, 10119**. No remembered id will ever find it again;
only a lookup by name will, which means `AudioManager`, which means Java. Hence
the split: native retries the id briefly (which covers a merely jogged cable,
where the id can survive), and a Kotlin watchdog re-resolves by name for the
rest. **Verified end to end 2026-09-20**: six refusals of the speaker fallback,
then recovery onto the piano at a new id, in EXCLUSIVE / LOW_LATENCY, with MIDI
reopened 16 ms later.

**`start()` short-circuits when it thinks it is running**, so a watchdog must
stop before it starts or it will leave a stream sitting on the wrong device.

**Two recovery paths must not fight.** The native retry stands down as soon as
the stream is running again, or its next attempt closes a good stream and reopens
a dead id.

**MIDI shares the cable and does not come back by itself.** `openMidi()` runs
once from `onResume`; after an unplug the port is dead and a perfect audio
recovery still leaves a silent instrument. Reopen it when the port is closed and
audio is healthy — rate-limited, because `MidiManager.openDevice` is
asynchronous and a 4 Hz poller would stack up opens before the first replied.

**Check the pause path before adding a watchdog.** One that restarts audio
whenever the engine is stopped will fight `onPause` unless the poller is
cancelled first. Here `onPause` removes the poller before calling `nativeStop`,
and both are on the main thread, so it cannot observe the stopped stream.

**Make the failure visible.** The original bug was not that recovery failed —
it was that recovery failed *and reported success*. The status line now names the
device the stream is really on and whether MIDI is connected.

## 9b. The user interface

**Parts are LEVELS, never a switch.** Nothing enables or disables a node; the
instrument selector moves faders. That is what makes moving between sounds
gapless and layering free, and it is the reason the whole rig is "mixed, not
switched".

**A control that reflects state must DERIVE it, not store it.** The instrument
tabs read the six levels every poll and paint from that, so a fader moved by
hand, or a patch restored from disk, shows up without anything having to notify
them. Storing "which part is selected" alongside the levels would mean two
sources of truth and, eventually, two different answers.

**Two independent states on one control need two visual channels.** A tab says
both "this is the panel you are looking at" and "this part is making sound", and
those are genuinely independent. Background carries one, text colour and weight
the other. Expressing both as "highlighted" would make the common case look right
and the interesting case unreadable.

**Tap for the common action, long-press for the one that would otherwise be
impossible.** Tapping a tab solos that part, because that is what is wanted
mid-set. Long-pressing brings a part in *without* silencing the others, which is
how a layer is built and which the old seven-position cycle could not express at
all. No visible mode, no extra control.

**A cycle is fine for three positions and absurd for seven.** The `PART` button
cycled through every part plus ALL; by six instruments, getting from the piano to
the SoundFont was five taps with no way back. Replace a cycle with a selector
before it reaches about five positions.

**One panel at a time, past about four panels.** Stacked panels on a 14.6"
screen reached eight swipes to the last one. That number was measured by having
to do it repeatedly while testing, which is the only way such things get noticed.

## 10. Design decisions that constrain future work

* **One native app, statically linked engines — not a plugin host.** AAP exists but
  is out-of-process over Binder and adds latency outside our control.
* **The signal path is a node graph** compiled to a flat schedule, published by an
  atomic pointer swap. See [GRAPH.md](GRAPH.md).
* **Pedalboard model, not per-voice modular.** Pin `width` is the reserved
  dimension should per-voice ever be wanted.
* **Thread-per-note rendering is rejected.** A voice costs ~20–30 µs per block and
  a thread wake 10–50 µs — synchronisation would exceed the work. The render
  thread provides the same "takes the next train" behaviour at block granularity.
* **GPL-3.0-or-later.** Avoid AGPL; check every GPL-2 dependency for the "or
  later" clause. See [LICENSING.md](LICENSING.md).
* **Both instruments always run and are always fed MIDI; only their levels
  differ.** That is the pedalboard model taken at its word — parts are mixed, not
  switched, so moving between them has no gap, no voice stealing and no reload.
  It costs the organ's CPU even when silent, which measurement says is affordable
  at ~4% of a block.
* **The Leslie lives inside the organ node**, because an organ and its speaker are
  one instrument. Pulling `b_whirl` out into a reusable RotaryNode — a Rhodes
  through a Leslie is worth having — needs only its own `midicfg` and is a
  genuinely small job when wanted.
