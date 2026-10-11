# On probation

Changes made while hunting THE LATCH that are kept only provisionally.

Michiel's rule: **"eventually I want all of that removed if it wasn't eventually
improving the outcome."** So each one is listed with what it was supposed to do,
what it measurably did, and the condition under which it should be deleted.

The outcome that matters is THE LATCH: the CP-88 going silent in a direct USB
mode while every host-visible counter still reads healthy, recoverable only by
`USBDEVFS_RESET`. **Nothing in this file fixed it.** Some of these improved
other things enough to earn their keep on those grounds instead, and that is
stated where it applies — improving *an* outcome is not the same as improving
*the* outcome, and the distinction is the whole point of this file.

---

## Settled 2026-10-04 -- the latch understood, the transport replaced

The latch is understood (workspace `CP88-USB-AUDIO.md`): the CP-88's OUT FIFO
dies when a packet lands at one narrow level, when a packet is not whole frames,
or when it is fed past its top; and behind a hub, cancelling an OUT URB on the
wire can deliver exactly such a cut packet. `UsbAudioOut` is now fbx, ported
from `tools/fbx` without its test instrumentation, and cannot reach any of
those. So everything below that was kept "until the latch is understood" has
been decided.

### DELETED with the old transport

| what | why |
|---|---|
| the size FIFO, pre-roll, resync gap, trim, `fifoResync`, empty-queue resync, and their `FIFO` / `DRYRESYNC` / `INDEPTH` broadcasts | Replaced by fbx's rules: echo exactly, prove every append, hold and empty the FIFO with an alt cycle. A timed gap does NOT empty this FIFO (a stopped CP-88 keeps its frames for at least 3 s). |
| the render-ring flush and its pre-roll silence | The render thread fills on demand, so a hold or a descent never leaves a backlog to discard. |
| `ZLP`, `SIZE`, `BIAS`, `STARVE`, `HOLD`, `TONE` injectors | Reproducers for faults now proven and measured to the frame on the workspace bench (`tools/cp88bench`), which is where such tests belong. The product cannot produce any of them. |
| `DUMP` wire trace | Answered; the Pi Zero mock (`tools/cp88mock`) now records the wire itself. It also shadowed the graph `DUMP`, which works again. |
| `bal` / `rise` / `fifo`, `inSize`, `fbDrop`, `fbClamp`, `zeroAvoided`, `outShort` / `outOdd`, `outDry` / `outEmpty`, `late`, `starved`, `inLost` | Counters of the old transport's own mechanisms. A zero-length or cut packet is now impossible by construction; an OUT packet not sent whole is a hold (`errors`). |
| the 4 ms buffer choice | 4 packets is the least the proof allows at all, at zero lag; on the tablet lag 2 occurs. |
| explicit-feedback and no-feedback direct audio | The transport echoes the device's asks; such a device has none. Direct audio refuses it with a message; ALSA mode plays it. |

### KEPT

| what | why |
|---|---|
| split nice bands, ADPF on both threads | Unchanged reasons: nothing absorbs a late URB. Now a late reap thread costs a hold rather than a latch, so fewer is still better. |
| the device-format byte ring, concealment, reserve adaptation | Render-side, unchanged. |
| async MIDI reaped by the transport's loop | Still needed: usbfs completions are per descriptor. The transport also hands MIDI completions on while it stops. |
| `usb hold` log line, `holds` / `alt errors` / `errors` | A hold is the one event this transport has; its reason says whether the thread was late or the wire failed. |
| `REINIT` (with the `CLEARHALT` rung) and `DEVHEALTH` | Cheap recovery and inspection paths; no behaviour on their own. Delete if nobody uses them again. |
| the reap loop on a borrowed AAudio callback thread | The one way an app gets SCHED_FIFO here (RLIMIT_RTPRIO is 0; `pthread_setschedparam` and `sched_setscheduler` are refused). A silent low-latency stream on the built-in speaker; its first callback runs the blocking reap loop until stop. Measured below. `ROUTE --ei aaudio 0` runs the own-thread side of the comparison. |

### The borrowed thread, measured (2026-10-04, tablet, CP-88 direct, 6 ms)

Line-out dropouts a minute, chord held, 60-90 s a phase:

| | rest | 64-voice sweeps | 16 CPU spinners | both |
|---|---|---|---|---|
| own thread, nice -19 | 13 | 80 | 43 | 207 |
| AAudio callback, SCHED_FIFO 2 | **0** | **1,9** | **0-4** | 164-176 |

"Both" is past the kernel: the IN completions themselves arrive late (gaps of
160-420 ms even on the FIFO thread), and the render thread conceals most of
it. The silence of the borrowed stream is AudioFlinger's own count: the track
never had a frame consumed (Server 0, underruns only).

Why the loop BLOCKS in the callback instead of the canonical one non-blocking
pass per callback: the speaker's callback comes every 4 ms, and a 6-packet
queue cannot be proven at that pace -- 52 holds in the first second. Blocking
keeps the 1 ms pace of the USB completions.

### Tried with it, and removed (no measurable gain)

| what | measured |
|---|---|
| audio threads kept off the little cores (cores 3-7 of 0-7) | 0 / 1,9 / 5 against 0 / 1,9 / 0 without |
| render thread at nice -19 instead of -16 | 0 / 1,25 / 5 against 0 / 1,9 / 4 |

---

## Earlier rounds (history; the transport they refer to is gone)

## Delete outright once the latch is understood

### 1. Zero-length packet on a dry feedback queue
`UsbAudioOut::nextFrames()` returns 0 instead of inventing a nominal size.

* **Meant to:** stop breaking the implicit-feedback contract. The invented
  frames were a permanent surplus — the real requests arrive late and are
  honoured anyway, so a guess is sent *in addition* to them.
* **Measured:** three heavy trials after the change latched 3 of 3. An A/B in
  the bare transport latched WITH it and survived without it on the next trial,
  which is the stochastic trigger, not a result.
* **Argument for keeping:** the reasoning is sound on its own terms and it
  removes a real protocol violation.
* **Delete if:** the true cause is found and is unrelated, AND sending nominal
  is shown to be harmless. Keep if the cause turns out to be anywhere near the
  feedback path.

### 2. Reap thread pinned to cpu6
`bos::pinToTransportCore` / `pinOffTransportCore`.

* **Meant to:** stop the render thread competing with the transport.
* **Measured:** `late` 126/234/155 → 33/97, `starved` → 0, `deficit` → 83 ms.
  **Latched anyway, every time.**
* **Argument for keeping:** a forty-fold quieter transport means fewer holes,
  which means fewer watchdog resets mid-song.
* **Costs:** hard-codes a core number for one SoC layout, and cpu6 is not
  actually reserved — Android gives an app no cpuset control, so this only
  keeps *our* threads off it.
* **Delete if:** the latch is fixed at its source and a sweep then shows `late`
  under load is acceptable without pinning. This is the most arbitrary thing
  in the file and should be the first to go.

---

## Keep, but revisit

### 3. Split nice bands (-19 transport / -16 render and MIDI)
* **Measured:** `late` 1292/1537/1762 → 126/234/155. Latched anyway.
* **Why it survives this list:** unlike the pinning it is not arbitrary. The
  ring exists precisely to make a late render block inaudible and nothing
  absorbs a late URB, so the ordering follows from the design rather than from
  a measurement. Android's own two audio bands.

### 4. The latch watchdog and automatic reset
* **This one DOES improve the outcome** — not by preventing the latch but by
  recovering from it unattended, verified end to end. It is on this list only
  because it should be reconsidered the moment the fault is fixed: a watchdog
  for a fault that no longer happens is a liability, and its trigger (`late`
  increasing) is known to be a correlate rather than a cause.
* **Delete if:** the latch is fixed at source. Until then it stays.

---

## Instrumentation, not hacks

Cheap, no behavioural effect, keep unless it gets in the way: the `usb hole`
transition log, `inSize` min/max, `fbDrop`, `fbClamp`, and the `PART`,
`MEASURE`, `WATCHDOG`, `DEVHEALTH` and `PANIC` debug broadcasts.

**Not on probation at all**, because they are ordinary bug fixes that happened
to be found here: clearing the probe envelope on arming (it was reporting
`roundTrip=0.00 peak=0.0000` as a success), and `openInput` no longer reopening
an already-open capture stream.

## Settled, so not on probation

The **4 ms direct-USB buffer** was introduced as a latch experiment and is no
longer one: depth has since been tested directly and latches 3 of 3 at 8 ms as
well. It stays on its own merits — it halves the latency of the path we play
through — and its comment has been corrected to say so.

---

## Added 2026-09-23, second round

### URB flight time (`flightUs` in the health line) — INSTRUMENTATION, keep for now

`UsbAudioOut` records the shortest submit-to-reap interval per direction, reset
on read. It exists to answer one question: does the endpoint queue ever run
shallow enough that ISO_ASAP restarts the stream instead of resuming it.

**What it measurably did:** answered the question, in the negative. The queue
does not drain — minimum 6819 us under `late=158 starved=68 deficit=638 ms`
against a full-queue ~7500. It also showed ~7,6 ms identically in a confirmed
latched and a confirmed healthy state, so it is one more thing the latch does
not disturb.

**Condition for deletion:** it has already answered its question, so it is kept
only as a cheap continuity check — two atomics on the reap thread. Delete it
with the rest of the latch instrumentation when the fault is understood. It does
NOT get to stay on the grounds that it is harmless.

### Submitted-minus-reaped URB counting — DELETED THE SAME DAY

Recorded because the reasoning was good and the instrument was not, and someone
will think of it again. It cannot measure endpoint queue depth: the kernel
completes URBs whether or not they are reaped, so the counter tracks the
completion backlog, which is HIGHEST when the transport is worst. It read its
healthy floor through a run with maxGap 323,9 ms and 1337 ms of deficit.

Already gone. Listed so it is not rebuilt.

### `inSize` min/max — a fix, not a hack

`mInSizeMin`/`mInSizeMax` were never assigned. Not on probation: a counter that
is printed must be written, and this one was cited as evidence while reading an
untouched variable. It stays.

---

## Settled 2026-09-23, third round

### cpu6 affinity pinning - DELETED

Gone from the tree, along with `pinToTransportCore`, `pinOffTransportCore`,
`applyAffinity`, `allowedList`, `kTransportCore` and `kMinCoresToPin`.

The measurement that settled it: with the feedback capture depth raised to
1024 ms, three trials survived with the pinning removed entirely and `inLost=0`.
With only 256 ms of capture, removing it latched 3 of 3 and `inLost` jumped to
567 packets - so what the pin was really doing was keeping stalls inside an
under-sized budget, not preventing latches. A hard-coded core number for one SoC
layout was always the most arbitrary thing here; it is also now unnecessary.

### Split nice bands (-19 transport, -16 render) - KEPT, off probation

Not arbitrary. The ordering follows from the design rather than a measurement:
the render ring exists so that a late render is inaudible, and nothing absorbs a
late URB. The endpoint drain is still a live mechanism, so fewer holes is fewer
chances to hit it. Keep.

### Deep IN capture (1024 ms) and the 64-entry size queue - THE FIX, not a hack

Not on probation. They remove a real protocol violation, are justified by a
proved mechanism, and cost nothing in latency. See LOG.md.

### Packets per OUT URB - a real variable, default 2

8 ms as 8 URBs x 1 packet latched 0 for 6; as 4 URBs x 2 packets it is 7 of 9.
Same latency. One-millisecond URBs double the syscall rate on the thread that
must not fall behind. Default 2, sweepable with `ROUTE --ei pkts N`.

---

## Settled 2026-09-23, final round — the tree is now clean

Everything below was either kept with a reason or deleted. Nothing is left in
"because it is harmless".

### DELETED

| what | why |
|---|---|
| all CPU affinity, and its knob | The corrected experiment gave cpu3 `late` 18-33 against 250-290 — but reserve-versus-pin showed the confinement contributes none of it, the spinner exclusion that made it work is unrealistic, and identical configurations varied eightfold. Not trustworthy at three trials a cell. |
| the latch watchdog | Michiel's rule: no recovery that cannot conclusively detect the latch over the wire. Holes are now routine and survivable, so a hole-triggered reset is a dropout generator. |
| `STARVE` injector | Superseded by `ZLP`, which is the same mechanism isolated exactly. |
| `STALL` injector | Answered: 16 s of drain, including five 1 s holes, never latched. |
| `TRACE` wire trace | Answered: the submitted cadence is a flawless 44/45 straight through a 500 ms deficit jump. |
| `MIDIUSB` block | Answered: 0 of 5 with the reader verifiably off. |
| `RENDER` pause | Answered: `late` +4 with the render thread running, +4 paused. |
| `outEmpty` / `outShort` | Answered: the controller never emits an empty or truncated packet. 0 throughout. |
| `flightUs` | Answered its question twice, in both directions. |
| `packPeak` | Verified the packing path once (`out` and `packPeak` identical); done. |
| `start_frame` probe | Answered: 0 on every URB, so explicit scheduling is unreachable. |
| float scratch + dither in `UsbAudioOut` | The reap thread no longer touches samples. |

### KEPT, with the reason

| what | why |
|---|---|
| the zero-length invariant, `zeroAvoided` | The one PROVEN mechanism: a single zero-length OUT packet wedges the CP-88, 11 trials of 11. Not on probation. |
| `ZLP` injector | The only deterministic reproducer of a proven fault. Regression-test infrastructure, not a hack. |
| deep IN capture, 64-entry size queue | Never invent a size, never use a stale one. `starved` 82 → 0, `inLost` → 0, and IN depth costs no latency. |
| split nice bands (−19 / −16) | Follows from the design rather than a measurement: the ring absorbs a late render, nothing absorbs a late URB. |
| ADPF on both threads | The supported substitute for the SCHED_FIFO this tablet always refuses. The work-duration fix took `late` from 550-650 to 242-355. |
| concealment | Judged by ear against its own absence, same load: "less clicky - better". |
| the reserve adaptation | Grows by what was missed rather than 5,8 ms for any hiccup; returns from the ceiling in ~45 s instead of ~96. |

### KEPT WITHOUT A MEASURED GAIN, and flagged as such

**The device-format byte ring.** Moving channel mapping, dither and packing to
the render thread left `late` unchanged — 301 against 242-400. It did not
improve the outcome it was aimed at.

It stays anyway, and this is the one place in this file where that is allowed,
so the reason has to be good: the thread with a hard deadline should do the
least work that is possible, which is ordinary real-time practice independent
of this bug. The rule exists to stop unproven FIXES accumulating; this is not a
fix, it is a simplification that happens to have been motivated by a hypothesis
that failed. If that reads as special pleading, delete it — the previous code
is one revert away and is documented in LOG.md.

---

## Settled 2026-09-23, night — the balance ceiling

### KEPT, with the reason

| what | why |
|---|---|
| `bal` / `rise` / `fifo` counters | Measurement, not a fix. `rise` is the balance from its own running minimum, which is the only part of a deficit the device can feel: its FIFO stops at empty and our books do not. `fifo` reads exactly 265 frames at rest on a 6 ms ring — the ring itself — stable to the frame. |
| `SIZE` / `BIAS` injectors | They established the second proven mechanism: over-feeding wedges the device at about 170 frames, under-feeding never does at any depth. Regression infrastructure for a proven fault, like `ZLP`. |
| `STARVE` injector | Answered a question that had stood since the first `starved=82`: starvation is self-cancelling, because the request it skips stays in the queue. Kept because that answer is easy to forget. |
| `HOLD` injector | The one disturbance shape a uniform reap stall cannot make: OUT off the wire while IN keeps flowing, which is what contention actually does here. Twelve seconds of it is survivable — worth being able to re-check. |
| `mInFramesTotal` reset in `start()` | A bug fix, not a hack: every drift figure taken after a REINIT or a RESET was the previous stream's total. |

### KEPT BUT DEFAULTED OFF, and flagged as such

**The balance ceiling** (`GUARD`). A saturating model of the device's FIFO,
clamped at the ring level plus two packets. It pinned exactly where it was told
to — `fifo=352 held=3852` — and the sustained overfeed wedged the device anyway.
So the model gets the resting level right and the cliff wrong, and a fix that
does not fix does not run by default. It stays behind the broadcast because it
is the only handle on the one over-feeding mechanism that is proven, and because
the counters beside it are worth having. If it is still off and still unproven
next time this file is read, delete it.

---

## Added 2026-10-11 -- M0 measurements

| what | status | delete when |
|---|---|---|
| `USBSTALL` broadcast (`UsbAudioOut::testStall`) | **Injector**, regression infrastructure for the starved-IN-queue cascade (LOG.md 2026-10-11): one reap-loop stall longer than the IN queue. Its A/B switch (re-anchoring off) was removed once the fix was proven. | The re-anchoring has a host test, or the transport is rewritten. |
| `SYSEX` broadcast (`MainActivity.sysexExchange`) | **Test hook** for `tools/cp88sysex/`: sends MIDI on the direct path and logs every SysEx reply. | The CP-88 edit page (M4) does the same through the app's own code. |
| `midi monitor` log line (`UsbMidiIn::drainMonitor`) | **Measurement**, the first piece of the MIDI monitor the plan wants (S20.5). | The MIDI monitor view replaces it. |
| `usb/UsbMidiOut`, SysEx capture in `UsbMidiIn` | **Product code**, not provisional: MIDI out on the direct path is a requirement (R3.3). | -- |
| `kHoldStarve` and the re-anchoring | **A fix that measurably fixed it**: 275 holds -> 1 after a 600 ms stall. | -- |
