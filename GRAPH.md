# The node graph

Target architecture: the instrument is a **user-editable graph** of nodes —
inputs, outputs, and processors in between — connected by dragging from pin to
pin, with a parameter editor behind each node.

This document is the design. [FINDINGS.md](FINDINGS.md) holds the constraints it
has to respect, and [MEASUREMENTS.md](MEASUREMENTS.md) the numbers.

---

## 1. The one decision that matters now

**Build the engine as a graph immediately, even while the UI still shows a fixed
patch.**

The default patch (sfizz → reverb → output) is then simply a three-node graph
constructed in code. Nothing about M3 looks different to the player, but the
engine is right from the start.

The alternative — a hardcoded chain now, a graph later — means rewriting the
entire signal path once M4 and M5 have built on top of it. The graph is not the
expensive part; the retrofit is.

**Corollary: the editor UI can arrive much later, in stages, at no cost.**

---

## 2. Prior art, and what to take from each

There is no need to invent any of this. Four bodies of work cover it:

| Source | What to take |
|---|---|
| **JUCE `AudioProcessorGraph`** | The canonical engine design: compile the graph to a flat, ordered schedule with pre-assigned buffers, then let the audio thread walk it. **We implement this pattern without taking the JUCE dependency** — it is a few hundred lines and we keep control of the realtime behaviour. |
| **Pure Data / Max-MSP** | The paradigm itself, and one hard-won rule: keep **signal** and **message** streams explicitly distinct. Our Audio / MIDI / Param pin types are exactly that distinction. Also their treatment of cycles: a feedback loop must contain an explicit delay. |
| **VCV Rack, Reaktor, Bitwig Grid** | Evidence that per-voice modular graphs are wonderful and expensive. We deliberately do **not** copy this — see polyphony below. |
| **Blender / Unreal Blueprints / Rete.js / litegraph** | The **interaction vocabulary** users already know: nodes as rounded cards, inputs on the left, outputs on the right, bezier edges, drag from pin to pin, pinch to pan and zoom. Do not invent new gestures for a solved problem. |

**MOD Devices' pedalboard** and **Zynthian's chains** are the closest existing
things to what this wants to be, and both are worth looking at for how they keep
a graph comprehensible on a small screen.

---

## 3. Data model

```
Graph
 ├── Node[]        id, type, position, parameters
 ├── Pin           (node, index, direction, DataType)
 └── Edge[]        from Pin  ->  to Pin
```

### Pin types

Three stream types, never interchangeable. The UI colours them differently and
refuses invalid connections while dragging.

| Type | Carries | Rate |
|---|---|---|
| **Audio** | float samples | per sample |
| **MIDI** | note/CC/pitch-bend events | per block, timestamped |
| **Param** | a float | per block (control rate) |

**Param is what makes the interesting things possible.** A "pedal extraction"
node takes MIDI in and emits a Param; that Param can drive *any* parameter of any
node — filter cutoff, reverb mix, drawbar position. It is the modulation system,
and it costs almost nothing because it is control rate.

### Pin width — reserve the dimension now

Every pin carries a **width**: how many parallel channels flow along that edge.

```
Pin { type: Audio | MIDI | Param,  width: int }
```

Today width is 1 for mono and **2 for stereo**, so a stereo synth is one pin and
one edge on the canvas rather than two of each. Buffers are allocated as
`width × blockSize`, and a node that processes per channel simply loops.

**This is the extension point for polyphony.** If per-voice behaviour is ever
wanted, it arrives as *wider edges* — width 16 meaning sixteen voices — which is
exactly what VCV Rack does with polyphonic cables and Bitwig with voice stacking.
Nodes that are width-agnostic (filters, gains, most effects) need no change at
all; only nodes that must collapse voices (reverb, output) care.

Adding the field now costs nothing. Adding it later means touching every node,
every buffer assignment and every serialized patch.

### Width rules

* **Equal widths** connect directly.
* **1 → N broadcasts.** Mono into a stereo input is the common, unsurprising case.
* **N → 1 and N → M require an explicit adapter node** (mixdown, pick, spread).
  Implicit summing would silently change gain and fold channels together — fine in
  a modular toy, wrong in an instrument you have to trust on stage.
* **Several edges into one input pin sum**, as everywhere else.

Nodes declare per pin whether width is fixed (an output node is stereo, full stop)
or inherited from what is connected (a filter is whatever its input is). The
compiler resolves inherited widths when it compiles the graph, and reports a clear
error rather than guessing.

---

## 4. Node catalogue

Staged by when they are needed, not by importance.

### Sources
* **MIDI In** — a hardware MIDI device (the CP-88). Multiple instances allowed.
* **Audio In** — see the hardware constraint below.
* **Test Keys** — the on-screen octave, so the graph is playable with nothing attached.

### Sinks
* **Audio Out** — the CP-88's USB audio. Usually exactly one.
* **MIDI Out** — for driving external gear.

### Instruments
* **sfizz** (SFZ), **FluidSynth** (SF2/SF3), **Beatrix** (tonewheel organ),
  **msfa** (DX7 FM), **Surge XT**, **Mutable** voices.
  MIDI in → Audio out, plus Param inputs for their main controls.

**Built: `SfizzNode`, `HammondNode`, `SurgeNode`, `DexedNode`, `PlaitsNode` and
`FluidNode`.** The organ's Leslie is *inside* the organ
node, because an organ and its speaker are one instrument and nobody patches
between them. Pulling `b_whirl` out into a reusable **Rotary** node — a Rhodes
through a Leslie is worth having — needs only its own `midicfg` and stays on the
list.

`HammondNode` is also the first node with a parameter space big enough to matter
(44 entries: three manuals of drawbars, percussion, scanner vibrato, overdrive,
Leslie, splits). Its parameters cross from the UI thread through a **lock-free
ring drained at the top of each block**, because setBfree's control functions
mutate engine state directly. Any node wrapping a third-party engine should
assume the same and do the same.

`SurgeNode` is the first node whose **construction** is too expensive for
`prepare()` — building it scans a 641-patch library — so it is built on a loader
thread and publishes itself through an atomic pointer, rendering silence until
then. It is also the first whose content (the patch) is addressed by **name**
rather than being a parameter, exactly as an instrument is addressed by id.
Loading one parks the render thread with the same handshake sfizz uses, so the
other instruments play straight through the gap. Any future node wrapping a
library-backed engine wants all three of these.

`DexedNode` shows the cheaper version of the same shape: its content is a 128-byte
cartridge slot rather than a file, so instead of parking the render thread it
stages the unpacked patch in a single slot and the render thread takes it at the
top of a block. **Pick the lightest handoff the cost justifies** — a park for
something that touches the filesystem, a slot for something that does not.

`PlaitsNode` adds a third thing a node may have to own: **its own sample rate**.
Plaits is firmware whose rate is a compile-time constant, so the node renders at
48 kHz internally and resamples to the graph rate at its output pin. The graph
does not know and does not need to — a node's contract is frames in, frames out
at `args.sampleRate`, and how it gets there is its business. Any engine that is
really a port of fixed-rate hardware will want the same treatment.

It also demonstrates the thing section 10d of HANDOFF is about: **a node that is
silent may still be expensive.** `PlaitsNode` cost 0.494 ms per block with every
voice released, all of it resampling silence, and 0.002 ms once it learned to
skip its own resampler when no voice is live and the block carries no MIDI. The
discipline that makes such a skip safe is to prove silence rather than assume it
— wait several blocks so any filter tail flushes first, and treat a block
containing any MIDI as unconditionally awake.

### Effects
* Reverb, delay, EQ, filter, drive, rotary speaker, chorus, compressor, limiter.
  Audio in → Audio out.

### MIDI utility — small, cheap, and where a lot of the rig lives
* **Note range filter** (splits), **transpose**, **channel filter**,
  **velocity curve**, **layer/merge**, **sustain-pedal handler**.

### Control
* **CC → Param** (the "pedal extraction"), **LFO**, **envelope follower**,
  **smoother** (essential: raw CC steps will zipper if fed straight to a filter).

---

## 5. Engine

### Compile, then run

Editing happens on the UI thread. The audio thread never sees a graph being
modified — it sees only a finished, immutable **schedule**.

```
edit  →  validate  →  topological sort  →  assign buffers  →  compute latency
      →  produce Schedule  →  atomically publish  →  audio thread picks it up
```

A `Schedule` is a flat array of steps: *call node N's process() with these input
buffer indices and these output buffer indices*. The audio thread walks it
linearly. No pointer chasing, no map lookups, no branching on graph shape.

It also carries **per-node dependency counts and a ready-list**, even though the
executor walks it serially to begin with. That is what lets parallel execution be
switched on later as an executor swap rather than a redesign — see Threading.

### Swapping without locks

`std::atomic<Schedule*>`, read once at the top of each callback. The UI thread
builds the new schedule completely, then stores it. The old schedule is retired
to a garbage queue and freed **on a non-audio thread**, never in the callback.

The same applies to nodes themselves: a node being removed must not be destroyed
while the audio thread might still be inside it. Retire, then free later.

### Buffers

One preallocated pool of mono blocks, sized at compile time. The scheduler
assigns and reuses them the way a register allocator does — a buffer is free
again once every consumer of its edge has run. Node count is bounded, buffer
count is bounded, and nothing allocates at runtime.

*(Reuse is still not implemented: every pin gets its own buffer. Correctness
first, and the measured headroom does not call for it.)*

### Fan-in — several edges into one pin

**Implemented.** One source is aliased straight through with no copy at all. Two
or more become explicit **mix steps** attached to the consuming step: the first
source copies into the pin's own buffer and the rest add. That keeps the executor
a flat loop and means a node never learns how many sources feed it.

Only `numFrames` are mixed, not the whole buffer — buffers are sized for the
largest block the engine might ever ask for and the render thread works in 128.

This is what makes the organ and the piano share one effects chain. It is worth
knowing that until the organ needed it, **a second edge into any input pin
produced silence with no error** — the compiler had a comment promising the sum
and no code doing it.

### Cycles

Reject them at validation, **except** through an explicit **Delay** node, exactly
as Pd does. A feedback path with a one-block delay is well defined; a zero-delay
loop is not. The UI should refuse the connection and say why.

### Latency compensation

Each node reports its latency in frames. Convolution reverb and lookahead
limiters have real latency; most nodes have none. Where parallel paths reconverge
with differing latency, the compiler inserts delay to align them. Without this, a
dry/wet split through a convolution reverb smears the transient.

Worth building into the compiler from the start — retrofitting PDC is famously
miserable.

### Threading

Three kinds of thread, with very different rules. Conflating them is how audio
apps acquire mysterious glitches.

#### 1. The audio callback thread

Created and prioritised by AAudio. **Do not set its affinity.** Its placement and
priority are managed by the system, and forcing affinity can cost the fast path.
Influence it through **ADPF** instead — `setPerformanceHintEnabled(true)` plus
`reportWorkload()`, already wired up in M2.

#### 2. Background workers — non-realtime, and needed now

Sample streaming from disk, patch loading, graph compilation, preset scanning.
These are ordinary priority threads and they must never touch the callback.

**Do not pin these to the fast cores.** They would compete with the audio thread
for exactly the resources it needs. If anything they belong on the little cores:
a sample loader finishing in 40 ms instead of 25 ms costs nothing, while stealing
a big core from the callback costs a glitch.

#### 3. Parallel graph execution — design for it, do not build it yet

The technique is standard (Reaper, Bitwig, Ardour, VCV all do it): a worker pool
pulls nodes whose dependencies are satisfied, and the callback thread joins in.

**But at a 2.02 ms block it is not free.** Waking a worker costs tens of
microseconds — 1-2% of the entire budget per wake — and every worker must reach
the deadline, so one preempted worker glitches the whole block. Parallelism pays
only when the critical path is long relative to that overhead: **a node must be
worth roughly 50-100 µs before farming it out makes sense.**

Current reality: the M2 synth runs at 4-17% CPU and the measured ceiling is ~75%
of one core. **There is nothing to parallelise yet.** Building it now would add
jitter and complexity to buy headroom that is not needed.

**So: reserve the space, exactly as with pin width.** The compiler emits a
`Schedule` carrying per-node dependency counts and a ready-list, even while the
executor walks it serially. Turning on parallel execution then becomes an executor
swap rather than a redesign.

When it is needed, the order of preference is:

1. **Parallelise inside expensive nodes first** — convolution partitions, sfizz
   voice rendering. The work is homogeneous, synchronisation happens once, and no
   graph machinery is involved.
2. **Then graph-level parallelism**, with a small fixed pool (2-3 workers, not one
   per core), spin-then-yield synchronisation with a bounded spin, and no
   allocation or locking anywhere in the path.

#### On pinning to fast cores

The SoC is 1×3.36 GHz prime, 4×2.8 GHz mid, 3×2.02 GHz little, so core placement
is worth roughly 1.7x — real, and worth caring about. But manual affinity is the
wrong instrument:

* **ADPF is the sanctioned mechanism.** `APerformanceHint_createSession()` takes
  an array of thread IDs, so worker threads join the same deadline-aware session
  as the callback. The governor then handles both placement and clocks. This is
  what game engines do for render threads.
* **Pinning fights the scheduler.** An affinity mask prevents migration away from
  a core that is thermally throttling — and thermal throttling is precisely what
  bites during a three-hour set.
* **The prime core is not automatically the right home.** Sustained load on the X3
  throttles sooner than the same work spread across mid cores.

If affinity is ever used regardless, use a **mask of the big cores, never a single
core**, and discover which those are at runtime by reading
`/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` rather than hardcoding
indices, which are not portable between devices.

**Decision: ADPF with all participating thread IDs; no manual affinity unless a
measurement shows it helps.**

### Realtime rules, unchanged

Everything from M2 still applies: no allocation, no locks, no file I/O, no JNI in
the callback. Nodes are written to that contract or they do not go in.

---

## 6. Polyphony: settled — pedalboard, with the door left open

**Decided 2026-09-19: the pedalboard model. Nodes process streams; instruments are
polyphonic internally; the graph is not per-voice.** Per-voice patching is not
wanted — the goal is wiring up finished instruments, not building voices out of
oscillators and filters.

This is the Pd / pedalboard / MOD model, not the VCV Rack model.

Per-voice graphs — where the whole patch is instantiated 64 times — give you
per-note modulation and true modular behaviour. They also multiply CPU by the
voice count, make voice allocation a graph-wide problem, and turn every edit into
a reallocation of 64 subgraphs.

For a stage instrument the pedalboard model is the right call: it is predictable,
cheap, and matches how the player thinks ("piano into reverb", not "one oscillator
per key"). Per-voice modulation stays available *inside* an instrument node, where
sfizz, Beatrix and Surge already implement it well.

The budget argument is decisive on this hardware: roughly 75% of one core is
available, and a per-voice graph of anything interesting at 32 voices would spend
it immediately. In the stream model a ten-node patch costs the same whether one
note is sounding or a two-handed chord.

**The door left open is pin width, not graph duplication.** Should per-voice
behaviour ever be wanted, it arrives as wider edges (see above) — the same route
VCV and Bitwig took. That is a data-model extension, not a rewrite, which is
exactly why width exists in the model from day one.

---

## 7. A hardware constraint the UI must express

**Audio In and Audio Out cannot both use the CP-88.** Established by experiment:
the piano's capture and playback are mutually exclusive on Android's USB audio
HAL — whichever opens first wins and the other fails.

So an Audio In node can offer the built-in microphone, and nothing else, while the
CP-88 is the output. The node editor must present this as a fact rather than
letting the player build a patch that cannot run. A validation error at edit time,
not a silent failure on stage.

---

## 8. Serialization

Graphs are JSON: nodes with type, id, position and parameters; edges as pin pairs.

This gives patches, and patches give **live sets** — an ordered list of graphs,
switchable from the CP-88's program changes. Same file format throughout.

Loading must be tolerant of unknown node types (a patch from a later version
should degrade, not crash) and must happen entirely off the audio thread, with
the new schedule published only once fully built.

---

## 9. The editor

### Conventions to follow, not reinvent

Nodes as rounded cards with a title bar and a body. **Inputs on the left, outputs
on the right.** Bezier edges, coloured by data type. Drag from a pin to a pin to
connect; drop on empty canvas to open a filtered node-creation menu. Pinch to
zoom, two-finger drag to pan. Tap a node to open its parameter sheet. Long-press
for delete/duplicate.

Anyone who has used Blender, Unreal, TouchDesigner or any modular plugin already
knows this. The 14.6" screen is genuinely large enough for it.

### Touch specifics that matter

* **Pin hit targets must be far larger than the drawn pin** — around 44 dp. This
  is the single most common way node editors fail on touch.
* While dragging an edge, **dim incompatible pins** and snap to compatible ones.
* Edges need to be selectable and deletable — a tap target along the curve.
* Auto-layout for a newly loaded patch, so a graph is never a pile at the origin.

### Rendering

Custom canvas drawing. **Compose's `Canvas` with its gesture detectors is the
pragmatic choice here** — the pan/zoom/drag maths in raw `MotionEvent` handling is
a great deal of fiddly work for no benefit. This is a reversal of the M2 decision
to avoid Compose, and it is justified: M2's UI was a status readout, where plain
Views were simpler; a node editor is the case Compose is actually good at.

Keep the draw path allocation-free regardless. The audio thread is protected by
being native and separate, but GC pauses still cause visible jank, and jank while
you are trying to repatch mid-gig is its own kind of failure.

### Parameter sheets

Tapping a node opens its parameters. Generated from a **parameter descriptor** the
node exposes — name, range, curve, unit, default — so a new node type gets a UI
for free rather than a hand-built screen. Each parameter also shows a Param input
pin, so anything can be modulated.

---

## 10. Staging

**A — graph engine, fixed patch (with M3).** Node/Edge/Schedule, the compiler, the
lock-free swap, three node types: MIDI In, sfizz, Audio Out, plus a reverb. The
patch is built in code. The player sees no difference; the foundation is correct.

**B — read-only visualisation (with M4).** Draw the graph. No editing. Validates
the layout, the rendering and the data model against real patches, and is
genuinely useful for understanding a rig at a glance.

**C — editing.** Drag to connect, node creation menu, delete, parameter sheets.
The point at which it becomes the thing described at the top of this document.

**D — the interesting nodes.** Note-range filters and splits, CC → Param
extraction, LFOs, smoothers. These are what turn a signal chain into a rig, and
they are cheap once the framework exists.

**E — patches and live sets.** Save, load, order, and switch from the CP-88.
