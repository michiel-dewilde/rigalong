# Measurements

Every latency and performance figure for this rig, with how it was obtained and
how far it can be trusted. The rules these numbers led to live in
[FINDINGS.md](FINDINGS.md).

Rig: Samsung Galaxy Tab S9 Ultra (SM-X916B, Snapdragon 8 Gen 2, Android 16)
→ one USB-C to USB-B cable → Yamaha CP-88.

---

## 1. Confirmed, from our own code

Read out of the running app, release build, 2026-09-19.

| Quantity | Value | Confidence |
|---|---|---|
| Audio device | `USB-Audio - CP88/CP73` | certain |
| API / sharing / performance | AAudio, **EXCLUSIVE**, **LOW_LATENCY** | certain |
| Sample rate | 44100 Hz | certain |
| HAL format | I16 | certain |
| Output burst | **89 frames = 2.02 ms** | certain |
| Output buffer | **267 frames = 6.05 ms** | certain — and it does NOT follow the burst setting. Asked for 2 bursts = 178 fr on 2026-09-23 and AAudio granted 267 anyway, so the EXCLUSIVE stream floors at 3 bursts. The 2-burst default changes the speaker only. An earlier note here claiming 178 fr was wrong |
| Reported output latency | **9.0 - 10.4 ms** | AAudio's own report, unverified externally |
| xRuns while playing | **0** | certain |
| CPU, this synth, a few voices | 4 - 17 % | certain |
| Peak callback | 1.55 - 2.22 ms against a 2.02 ms period | certain |
| Input burst (built-in mic) | 96 frames | certain |
| Reported input latency (mic) | **2.6 ms** after the drain fix | AAudio's own report |

Independently corroborated by OboeTester before any of our code existed:
5.5 / 7.8 / 9.9 ms at a 1-burst buffer, 7.9 / 9.4 / 11.0 ms at 2 bursts, 0 xRuns
at 75 % synthetic load sustained indefinitely.

**Subjective, from the player:** *"it felt snappy"* — playing the CP-88 through
the app, release build. This is the judgement the numbers exist to serve.

## 2. Derived

Key-to-sound is the number that matters and it has not been measured directly.
Built from parts:

| Stage | ms | Source |
|---|---|---|
| Key contact → MIDI on the wire | 1 - 2 | CP-88 internal, not measurable from here |
| USB MIDI + AMidi | 1 - 3 | typical, not measured |
| Block quantisation (MIDI applied at block start) | 0 - 2 | by design |
| Output path (buffer + USB + DAC) | 9 - 10 | AAudio report |
| **Total estimate** | **~11 - 17 ms** | |

Treat this as an estimate. Every component except the output path is assumed.

## 3. What could not be measured, and why

### Internal loopback through the CP-88 — impossible

The plan was to use the CP-88's 2-in/2-out capability: emit a click, listen for it
on the piano's USB return, count frames. That would have given an exact round trip
with no room and no microphone.

**It cannot be done. The CP-88's capture and playback are mutually exclusive on
Android's USB audio HAL.** Established by experiment: whichever direction opens
first succeeds, and the other fails with `ErrorDisconnected`.

* Output first → output gets EXCLUSIVE/MMAP/44100/89, input fails.
* Input first → input gets EXCLUSIVE/MMAP/44100/2ch/96, **output fails**.

This is not a preference. **The app must always open output first**, because
opening capture first silently costs you the instrument — the worst possible
failure mode on a stage.

Note also that with the CP-88 attached, Android makes it the **default input
device**, so an input stream opened with an unspecified device id resolves to the
piano's capture and fails. The built-in microphone must be selected explicitly by
device id.

### Acoustic measurement from a phone video — insufficient

A 7 s recording of taps and playing was analysed (ffmpeg → WAV → RMS envelope in
Python). It confirms the system works end to end and shows no dropouts, but the
tone onset is the only detectable event: a fingertip on glass sits at or below the
-70 dBFS room floor, so there is no reference transient to measure against. No
number was taken from it.

### Microphone round-trip probe — MEASURED

The app emits a 10 ms click through the CP-88 and listens on the built-in mic.
Ten consecutive trials per configuration, release build, CP-88 audible across a
desk (~1.2 m).

**The microphone's sample rate turned out to matter enormously.**

| | mic pinned to 44100 | mic at native 48000 |
|---|---|---|
| mean | 26.9 ms | **22.6 ms** |
| median | 26.7 ms | 22.6 ms |
| **SD** | 1.7 ms | **0.51 ms** |
| range | 24.6 - 29.5 | 21.8 - 23.4 |
| reported input latency | 5.0 ms | 3.9 ms |
| detection | 10/10 | 10/10 |

Raw, native rate: `23.10 22.31 23.42 21.81 22.04 22.44 22.44 22.75 22.88 23.08`

Asking the microphone for 44100 — to match the CP-88 — inserted a resampler in the
capture path. Removing it cut **4.3 ms** and, more tellingly, **cut the spread by a
factor of three**. A resampler adds delay that `calculateLatencyMillis()` does not
report, so it corrupted both the figure and its stability.

**Lesson worth generalising: pin the sample rate on the device that demands one
(the CP-88, 44.1 kHz only) and let every other endpoint run native.**

**Accounting:**

```
measured round trip                       22.6 ms
  - output path      (AAudio reports)      9.2
  - mic input path   (AAudio reports)      3.9
  - air, ~1.2 m desk                       3.5
  = unexplained                            6.0 ms
```

That residual is most plausibly **hardware the reports do not cover**: the
microphone's analog and ADC front end, plus — if monitoring through active
speakers — their internal DSP, commonly 1-3 ms by itself. A longer air path than
assumed would also contribute.

**None of that is in the path when playing.** A microphone and a monitor speaker
are not part of key-to-sound. So this measurement corroborates the output figure
to within the accuracy of the method, rather than contradicting it.

**Best estimate of key-to-sound: ~11 - 15 ms**, consistent with the player's
"snappy" and with everything measured so far.

**What would settle it exactly**: detect *two* onsets through the same microphone —
the mechanical key thunk and the synth's response — and take the difference.
Both sounds reach the same mic by nearly the same path, so mic latency, speaker
DSP and air delay all cancel, leaving key-to-sound directly. Requires triggering
the probe from MIDI note-on rather than a button, and detecting the quieter
mechanical transient reliably.

A cheaper partial check: re-run at a second known distance. The difference should
be 1 ms per 34 cm; anything else means the chain is not behaving as modelled.

### Earlier attempt at this probe — blocked, now resolved

The app now has a **MEASURE** button that emits a 10 ms click through the CP-88
and listens on the **built-in microphone**. Because the click and any reference
event travel the same input path, the microphone's own latency largely cancels.

Status: the probe runs, the input stream is real-time (2.6 ms reported), but no
click has been detected yet — `in level` stays at the room floor (~0.005).

**It needs one physical condition: the CP-88's output must be audible in the room,
near the tablet.** Headphones, a silent monitor or a low volume all defeat it. With
speakers up, pressing MEASURE should return a figure.

What that figure will mean: `round trip = output latency + acoustic travel +
microphone input latency`. Subtract the reported input latency and roughly 3 ms
per metre of air to estimate the output path alone.


## 5. Instrument latency, compared (2026-09-19)

Recorded microphone capture with exact note-on markers, notes driven over adb so
no human and no key travel is involved. Same output path, air and microphone
throughout, so the differences are exact.

| instrument | first audible energy after note-on | sd |
|---|---|---|
| salamander-grand | 43.45 ms | 0.64 |
| **salamander-tight** | **35.90 ms** | 0.43 |
| test-saw | 29.04 ms | 0.64 |

**Salamander is 14.4 ms slower than the saw**, and the cause is the library:
its samples carry a mean **9.3 ms of recorded lead-in silence** (measured from
the WAV files, range 4.5-12.5 ms). Per-region `offset=` recovers **7.55 ms** of
it. The residual ~6.9 ms is mostly the piano's genuinely gentler attack.

Key thunk arrives **17.7 ms before** the note-on marker (sd 2.55) -- that is key
travel on a weighted action, not MIDI delay.

## 5b. The organ, and what a debug build does to every number (2026-09-19)

All from the app's own counters, release build unless stated, CP-88 attached,
MMAP + EXCLUSIVE + LOW_LATENCY, 3-burst buffer, at idle with no voices sounding.

| | debug build | release build | confidence |
|---|---|---|---|
| reported latency | 11.0 ms | **7.9 ms** | high — our own counter |
| ring underruns, 15 s | 17865 | **0** (1 at startup) | high |
| render peak | 34.07 ms | **0.98 ms** | high |
| adaptive ring target | pinned at the 16 ms ceiling | back at the 4.4 ms floor | high |
| xruns | 0 | 0 | medium — see the note on MMAP xrun reporting |

**Treat any measurement from a debug build as meaningless.** The two columns are
the same source, same device, minutes apart.

Per-node cost, release build, 128-frame block (2.90 ms of audio):

| node | per block | share of the block |
|---|---|---|
| Hammond (tonegen + preamp + reverb + Leslie) | ~0.13 ms | ~4% |
| sfizz, no voices sounding | ~0.01 ms | — |
| Reverb | ~0.03 ms | ~1% |
| Limiter | ~0.006 ms | — |

**The organ is audible and PANIC silences it** — measured through the tablet
microphone rather than assumed, since neither could be verified by reading code:

| state | mic level |
|---|---|
| 30-note cluster held on the organ, piano at zero | 0.0497 |
| after PANIC | 0.0016 (room floor) |

Confidence: high for "it makes sound" and "panic works", which is all this was
asked to establish. It says nothing about how the organ *sounds*.

## 5c. Surge XT (2026-09-20)

All release build, arm64, on the tablet.

### Cost, measured by a standalone probe over the whole factory library

A probe binary linking `surge-common` was pushed to `/data/local/tmp` and run
under `adb`. It loads **every one of the 641 factory patches**, plays eight
notes, and times 100 render blocks of 128 frames each. The whole sweep takes
**7 seconds**. A sample of eight patches says nothing about the worst case, and
the worst case is the only number that decides whether this is a stage
instrument.

Budget: a 128-frame block at 44100 Hz is **2.902 ms**.

| | avg ms | % of block |
|---|---|---|
| idle, no voices | 0.007 | 0.2% |
| init patch, 1 voice | 0.015 | 0.5% |
| init patch, 16 voices | 0.065 | 2.2% |
| **median of 641 patches, 8 voices** | **0.064** | **2.2%** |
| worst of 641, 8 voices (`10 Example - Both Time And Space`) | 0.577 | 19.9% |
| worst of 641, 16 voices | 1.080 | 37.2% |
| worst of 641, 24 voices | 1.130 | 38.9% |

The plateau from 16 to 24 voices is Surge's own polyphony limit.

Other figures from the same run: construction **41.5 ms** (probe) including the
library scan; patch load **2.8 ms mean, 63.8 ms worst**; memory **34 MB resident
steady**, 318 MB high-water across loading all 641 back to back.

The spike brief set the abort threshold at roughly 1 ms per block for a *single*
voice. The worst patch in the library costs **0.157 ms** for one voice.

### In the app, all three instruments in the graph

| | |
|---|---|
| Surge construction, in-app | **72.7 ms** (641 patches, 188 wavetables) |
| Patch load, in-app | **9–11 ms** |
| Idle, PART: ALL, after RESET PEAKS | **render peak 0.81 ms, 0 underruns** |
| 120 injected notes, PART: ALL | render peak 19.19 ms, 9 ring underruns, **xruns 0** |

**The 19 ms is sfizz, not Surge**, and the per-node profile says so:

| node | steady `last` | max |
|---|---|---|
| Surge XT | 0.53 ms | 3.0 / 7.06 ms |
| sfizz | 0.6–1.1 ms | 13.8 ms (note-on 13.4, render 1.2–2.1) |
| Hammond | 0.10 ms | 0.64 ms |
| Reverb | 0.017 ms | 0.21 ms |
| Limiter | 0.006 ms | 0.05 ms |

sfizz's note-on path is known, budgeted and unchanged by this work. Surge's
7.06 ms outlier is 30 simultaneous note-ons in a single block — a synthetic worst
case, since a forearm cluster is fewer notes spread over several blocks. `xruns`
stayed at 0 throughout: the adaptive ring absorbed all of it, which is what it is
for.

### Audibility, by the output meter

Each part alone, 30-note cluster, reading peak output after the mix, reverb and
limiter:

| PART | `out` peak |
|---|---|
| SYNTH | **0.944** |
| ORGAN | 0.235 |
| PIANO | 0.083 |
| after PANIC | **0.000** |

The microphone probe could not have produced this: the room was silent, so it
read its noise floor (~0.001–0.002) for every part including the piano. See
FINDINGS section 7.

## 5d. The DX7 (2026-09-20)

Release build, on the tablet, all four instruments in the graph.

| | avg / steady | peak |
|---|---|---|
| Dexed, 30 voices | **0.187 ms** per 128-frame block (6.4%) | 0.402 ms |
| Surge XT, same block | 0.482 ms | 5.44 ms |
| Hammond, same block | 0.181 ms | 0.369 ms |
| sfizz, same block | 0.770 ms | 6.91 ms (note-on 5.79) |
| Reverb + Limiter | 0.024 ms | 0.25 ms |

**Idle, all four instruments in the graph: render peak 1.30 ms**, 0 ring
underruns, 0 xruns, measured twice. That is up from 0.81 ms with three, and it is
a *peak* rather than a typical block — the per-node steady figures above sum to
well under a millisecond. The budget is 2.902 ms.

Audibility through the four-way fan-in mix, each part alone, 30-note cluster,
reading the output meter after the mix, reverb and limiter:

| | `out` peak |
|---|---|
| FM, init voice | **0.866** |
| FM, cartridge voice (`Dexed_01/Beatmehrdr`) | 0.107 |
| SYNTH | 0.944 |
| ORGAN | 0.235 |
| PIANO | 0.083 |
| after PANIC | 0.000 |

The init voice is six operators at full output and is deliberately the loudest
thing in the box; a real cartridge voice sitting at 0.107 is normal.

Bank loading: **33 cartridges, 1056 voices**, on the loader thread at startup,
fast enough that it has never been observed taking measurable time.

## 5e. Plaits (2026-09-20)

Release build, on the tablet, **five** instruments in the graph, CP-88 over USB,
AAudio EXCLUSIVE + LOW_LATENCY, 44100 Hz, 128-frame render block (2.902 ms).

### Cost

| | steady (`last`) | peak (`max`) |
|---|---|---|
| Plaits, 30 notes | **0.140 ms** (4.8%) | 0.765 ms |
| Plaits, idle — before the skip | 0.494 ms | 0.505 ms |
| Plaits, idle — after the skip | **0.002 ms** | — |

The idle figure is the interesting one. Before, the node fed and drained its
Lanczos resampler every block whether or not anything was sounding, so it cost
0.494 ms per block producing silence — the second most expensive node in the
graph while doing nothing. Skipping the resampler when no voice is live and the
block carries no MIDI takes that to 0.002 ms, a 247x reduction.

Same block, same run, for comparison (30-note stress, so these are not idle):

| | steady | peak |
|---|---|---|
| Surge XT | 0.058 ms | 1.232 ms |
| Dexed | 0.077 ms | 0.544 ms |
| Hammond | 0.023 ms | 0.218 ms |
| sfizz | 0.149 ms | 12.265 ms (note-on 3.655) |
| Reverb + Limiter | 0.005 ms | 0.252 ms |

### Idle, per node, with all five in the graph

Measured by tapping STRESS 30 and then PANIC immediately, so that the profile
logging 1.5 s later describes a quiet graph:

| | idle `last` |
|---|---|
| Plaits | 0.002 ms |
| Dexed | 0.003 ms |
| Hammond | 0.084 ms |
| Surge XT | 0.139 ms |
| sfizz | 0.045 ms |
| Reverb + Limiter | 0.024 ms |

Sum: about 0.30 ms of a 2.902 ms block with five instruments resident and silent.

**The graph-wide `render peak` is NOT usable for this comparison** and was not
used for it. It is a maximum over a window, so it catches scheduler jitter: two
samples of the identical five-instrument configuration read 1.07 ms and 1.30 ms.
Taken at face value it would have said the idle fix made things worse. See
FINDINGS section 7.

### Audibility, by the output meter

30-note cluster on TEXTURE alone, read after the mix, reverb and limiter:

| | `out` peak |
|---|---|
| TEXTURE, before the headroom fix | **1.000 — CLIPPING** |
| TEXTURE, after the headroom fix | **0.549** |
| after PANIC | 0.000 |

One Plaits voice reaches nearly full scale on its own, because the module is
monophonic and its post-processor is scaled for that. A fixed 0.25 scale puts a
single note near 0.2 — where the organ sits at 0.235 — and keeps a chord inside
the limiter.

### Memory

`16 voices, 48000 Hz -> 44100 Hz, 340992 bytes of voice state`, from the device
log. 4792 bytes per `plaits::Voice` plus a 16 kB arena each, shared by all
sixteen engines within a voice.

## 5f. SoundFonts — SF2 vs SF3 (2026-09-20)

Release build, on the tablet, **six** instruments in the graph, CP-88 over USB,
AAudio EXCLUSIVE + LOW_LATENCY, 44100 Hz, 128-frame render block (2.902 ms).

### The SF2/SF3 comparison, which is the point

`VintageDreamsWaves-v2` ships from FluidSynth as **both** an SF2 and an SF3 of
identical content, which makes it the right way to prove that decoding Ogg
Vorbis samples through stb_vorbis is *correct* and not merely non-crashing. Same
preset (`0:000 FM Bells 1`), same 30-note cluster, peak read from the output
meter immediately after the chord:

| | file size | load time | `out` peak |
|---|---|---|---|
| VintageDreamsWaves-v2.**sf2** | 315 kB | 16 ms | 0.885 |
| VintageDreamsWaves-v2.**sf3** | 519 kB | **57 ms** | **0.935** |

Within half a decibel. A decoder producing garbage, silence or the wrong sample
rate could not land there.

The load-time difference — 16 ms against 57 ms — is the Vorbis decode itself,
and is the other half of the same evidence: something clearly *happened* on the
SF3 path. (This particular SF3 is larger than its SF2 because the font is tiny
and Vorbis headers dominate; a real GM font compresses to roughly a third.)

### Cost

| | steady (`last`) | peak (`max`) |
|---|---|---|
| FluidSynth, 30 notes | **0.427 ms** (14.7%) | 2.083 ms |
| FluidSynth, idle | **0.013 ms** | — |

No idle skip is needed, unlike Plaits: with nothing sounding FluidSynth stops at
its own voice count and costs almost nothing.

Polyphony is set to 128, against FluidSynth's own default of 256 — still far more
than a sustain pedal will find, at half the per-voice memory.

### Six instruments, idle, per node

Measured the way FINDINGS section 7 prescribes: STRESS 30 then PANIC
immediately, so the profile logged 1.5 s later describes a quiet graph.

| node | idle |
|---|---|
| Plaits | 0.002 ms |
| Dexed | 0.004 ms |
| FluidSynth | 0.013 ms |
| sfizz | 0.049 ms |
| Hammond | 0.063 ms |
| Surge XT | 0.150 ms |
| Reverb + Limiter + Audio Out | 0.026 ms |
| **total** | **≈ 0.31 ms** of 2.902 ms |

**Six instruments resident and silent cost about a tenth of the block.** That is
the number HANDOFF section 10d was written to worry about, and it says the worry
was largely an artefact of measuring with the render peak — a maximum over a
window, which reads anywhere from 1.07 to 1.30 ms on an unchanged configuration.

Surge XT at 0.150 ms is now the most expensive silent node in the graph, by an
order of magnitude over Plaits and Dexed, and is the only one worth examining if
the idle total ever needs to come down.

### Under load

Same run, 30-note cluster across all six:

| | steady | peak |
|---|---|---|
| FluidSynth | 0.427 ms | 2.083 ms |
| Plaits | 0.763 ms | 5.287 ms |
| sfizz | 0.449 ms | 8.614 ms (note-on) |
| Surge XT | 0.101 ms | 1.372 ms |
| Dexed | 0.072 ms | 0.391 ms |
| Hammond | 0.040 ms | 0.401 ms |

sfizz's note-on path remains the largest spike in the rig, as it has been since
M2, and it is known, budgeted and absorbed by the ring rather than heard.

## 5g. After the rework: a numerical no-op (2026-09-20)

The product rework renamed the app, moved the graph compiler into a separate
portable library, replaced six patch files with commits on seven forks and
turned `third_party/` into submodules. **None of it was supposed to change a
number**, and this section is the check.

Same tablet, same cable, same release build path, six instruments live (ALL),
peaks reset before each reading.

### The stream is identical

```
path       AAudio  EXCLUSIVE  LOW_LATENCY
rate       44100 Hz
burst      89 fr  (2,02 ms)
buffer     267 fr (6,05 ms)
latency    8,5 ms idle
xruns      0  (total 0)
state      running  [clean]
```

Every one of those matches section 1. The graph is also unchanged — *10 steps,
11 audio buffers* — which matters because the compiler is the thing that moved.

### Idle cost, per node

`last` at idle, obtained by STRESS-then-PANIC so the profile fires 1.5 s later
on a block with nothing ringing. The `max` column is from the stress that
preceded it and is included only for scale.

| node | last (idle) | max (under stress) |
|---|---|---|
| MIDI In | 0,001 ms | 0,002 ms |
| FluidSynth | 0,009 ms | 0,240 ms |
| Plaits | 0,003 ms | 0,679 ms |
| Dexed | 0,003 ms | 0,221 ms |
| Surge XT | 0,069 ms | 7,437 ms |
| Hammond | 0,046 ms | 0,227 ms |
| sfizz | 0,043 ms | 2,389 ms |
| Reverb | 0,103 ms | 0,152 ms |
| Limiter | 0,005 ms | 0,071 ms |
| Audio Out | 0,002 ms | 0,051 ms |
| **total** | **0,284 ms** | |

**0,284 ms against a 0,31 ms baseline.** That is the same number: both are sums
of ten `last` readings taken one run apart, and the run-to-run spread on the
individual nodes is larger than the difference. The rework cost nothing.

### Under stress, the ring does its job

```
ring   13,2 ms reserve, 0 underruns, target 13,1 ms, render peak 10,10 ms
xruns  0  (total 0)
```

A 10,10 ms render peak against a 2,018 ms callback deadline, and **zero xruns
and zero underruns**. That is not a contradiction, it is the architecture: the
render thread is decoupled from the callback by the adaptive ring, which grew
its reserve to 13,2 ms and absorbed the spike. Surge XT owns the spike (7,437 ms
of it) and it is patch-loading, not steady-state synthesis.

### What this does NOT establish

The app was **started, stressed and profiled, not played**. Every number here
comes from synthetic chords driven over adb. The idle figure, the stream
configuration and the underrun count are solid; how it *feels* under hands is
not measured here and is not measurable this way.

## 5h. The tablet's own speaker: how deep the buffer has to be (2026-09-22)

The CP-88 is the instrument; the built-in speaker is what you get when the cable
is not plugged in, and Michiel's verdict on it was "still late". Measured
`latency 34,2 ms` against the CP-88's 11-15 ms.

**Swept under load, not at idle.** An empty graph survives any buffer, so a sweep
of a silent engine measures nothing at all. A note a second plus a chord every
fourth, 30 s per setting, on the tablet's own speaker at 48 kHz / 192-frame
burst:

| Bursts | Stream buffer | xruns |
|---|---|---|
| 3 | 576 fr = 12 ms | 0 |
| **2** | **384 fr = 8 ms** | **0** |
| 1 | 192 fr = 4 ms | 1 |

So 2 is the last setting that holds, and it is now the app's default. Confirmed
at boot with no broadcast: `stream open: rate=48000 burst=192 buffer=384
sharing=1 perf=12 aaudio=1`, then 20 notes with chords over 30 s at `xruns=0
ringUnder=0`. The adaptive ring reserve settles at **5,3 ms either way**, so the
saving is the full 4 ms rather than being handed back by a deeper reserve.

**Why the other 20 ms cannot be had.** EXCLUSIVE is not available on the built-in
speaker and no permission or setting unlocks it: MMAP cannot mix, and a built-in
speaker's excursion limiting and EQ have to live in the mixer path. Tested
directly -- I16 format, explicit exclusive request, full kill and a 4 s wait --
every attempt came back `sharing=1`, while the identical request to the CP-88
gets `sharing=0` and an 89-frame burst.

The tablet does declare `android.hardware.audio.pro` and
`android.hardware.audio.low_latency`, which looks like a contradiction until the
CDD wording is read: the latency requirement is over "at least one supported
audio output device". The USB path satisfies it. The speaker being shared is
compliant, not broken.

And there is nothing to switch off: `dumpsys media.audio_flinger` reports **0
Effect Chains** on every output thread, so Dolby Atmos and the equalizer are not
in our path and disabling them -- the standard forum advice -- buys exactly
nothing.

**What remains, and its one real argument.** The ring's reserve is additive
latency, so rendering straight in the Oboe callback would recover the 5,3 ms.
Measured on the device, that also buys scheduling we are otherwise refused:

```
AudioTrack    prio=-3  nice=-16  rtprio=2  policy=1   <- Oboe callback: SCHED_FIFO
usb-routing   prio= 1  nice=-19  rtprio=0  policy=0   <- our render thread
bos-sched: render thread: nice -19 (SCHED_FIFO refused: Operation not permitted)
```

AudioFlinger hands its own callback thread the real-time policy the kernel
refuses the app. Against that: the ring exists because a 2,5 ms cold-sample disk
read inside sfizz's `noteOn` once landed on a 2,018 ms callback deadline. That
measurement came from the CP-88, where the deadline is 2 ms; on the speaker the
callback is 8 ms and the work is ~3,7 ms, so the objection is weakest exactly on
the path in question. Not taken: it needs the cold-load worst case measured
first, and 128-frame sub-blocks inside the callback, or MIDI timing coarsens
from 2,7 ms to the full callback.

## 6. Still open

| Question | Status |
|---|---|
| Real key-to-sound latency | **estimated 11-15 ms**, corroborated by a measured 22.6 ± 0.5 ms acoustic round trip whose residual sits in mic and speaker hardware outside the playing path. Not yet measured directly |
| Does touching the screen stall the audio thread? | untested; the peak-callback readout exists to answer it |
| Does `xRun#` miss stalls in MMAP mode? | untested |
| Behaviour after an hour warm | untested; thermal throttling is what bites live |
| Survives screen-off, re-plug, notifications | untested |
| **THE LATCH** — direct USB mode goes silent while still acknowledging URBs, and only an ALSA round trip clears it | **open.** Reproduced in ~25 s at a 4 ms buffer at the instant of a part switch; a 37-minute run at 8 ms never latched, but was SILENT, so the comparison is not controlled. MIDI is exonerated — per-stage counters ran clean across a whole latch (`on=157 off=159` in and out). Two suspects: the level step discontinuity, now fixed by `SmoothedGain`; and the 4 ms buffer, which is the default as of 2026-09-23 precisely so the first one gets tested. No detector exists yet: nothing distinguishes "presenting" from "acknowledging and discarding". **RESET USB clears it, and which part does the work is now known.** The button shipped as a ladder — 1) disown and re-own, 2) plus a real ALSA stream on the card, 3) plus a USB reset — and Michiel met a latch and reported *"only rung 3 worked"*. So neither giving the interfaces back to the kernel nor letting ALSA stream on the card clears it; only USBDEVFS_RESET, unbinding and rebinding every driver, does. **That places the stuck state in the device or its endpoint configuration, not in which process owns the interfaces** — and it explains why USB-interface mode never helped. The ladder is gone; the button now always does the reset (~2,7 s). Still open: a detector, and why it latches at all |
