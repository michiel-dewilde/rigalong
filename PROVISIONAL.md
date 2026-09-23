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
