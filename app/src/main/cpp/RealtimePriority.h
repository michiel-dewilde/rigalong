#pragma once

#include <cstdint>
#include <vector>

#include <sys/types.h>

// Scheduling for the threads that must not be late.
//
// Three threads carry the sound and all three used to ask for nothing better
// than a nice value:
//
//   * the RENDER thread, which runs the graph and fills the ring;
//   * the USB REAP thread, which submits and reaps the isochronous URBs;
//   * the USB MIDI reader.
//
// A nice value only biases a thread inside the ordinary scheduler. It does not
// stop the thread being preempted by anything the system feels like running,
// and the measured symptom of that is `late` climbing in the health line.
//
// So two things are asked for, in order, and WHAT WAS GRANTED IS LOGGED --
// because on Android most of this is refused, and a priority you believe you
// have but do not is worse than none.
namespace bos {

// THE TRANSPORT OUTRANKS THE SYNTH, and these two constants are that rule.
//
// All three threads used to ask for nice -19, which put the render thread and
// the USB reap thread in the SAME band -- so when sfizz went to 64 voices they
// fought each other as equals, and the transport lost often enough to matter.
// Measured in usbhost/: eight EXTERNAL spinners could not move the bare
// transport's `late` at all (666 -> 666), while Bag-O-Synths under its own
// render load reached `late` in the thousands. The competition was never the
// system. It was us.
//
// The project's own design already says which one may be late. The ring exists
// precisely to turn the render thread's hard deadline into a soft one: a late
// block eats into the reserve and is inaudible. Nothing absorbs a late URB --
// the audio is simply not on the wire, and `deficit` grows by exactly that
// much. So the render thread is the one that must yield.
//
// The two values are Android's own audio bands, which is what makes them the
// right pair rather than two numbers pulled apart by three:
//   ANDROID_PRIORITY_URGENT_AUDIO = -19   the transport
//   ANDROID_PRIORITY_AUDIO        = -16   everything that feeds it
constexpr int kTransportNice = -19;
constexpr int kRenderNice = -16;

/**
 * Asks for real-time scheduling for the CALLING thread.
 *
 * SCHED_FIFO first. Ordinary Android apps have RLIMIT_RTPRIO of 0, so this is
 * normally refused with EPERM -- it is attempted anyway because it costs one
 * syscall, it is the right thing to ask for, and a device or build that does
 * allow it should get it.
 *
 * Then the nice value, which apps ARE allowed. Pass [kTransportNice] for a
 * thread that puts bytes on the wire and [kRenderNice] for one that produces
 * them; see the note above for why they must differ.
 *
 * `what` names the thread in the log.
 */
void raiseRenderPriority(const char* what, int niceValue = kTransportNice);
/**
 * CPU AFFINITY FOR THE TRANSPORT THREAD. OFF BY DEFAULT, AND IT STAYS OFF.
 *
 * The idea: give the USB reap thread a core to itself and keep every other
 * thread in this process off it, so nothing the app does can deschedule the one
 * thread that must never be late.
 *
 * It was on by default for a while, hard-coded to cpu6, and was deleted on
 * 2026-09-23 because it prevented no latches. Then Michiel found the flaw in
 * the experiment that "showed" that: the render and MIDI threads were excluded
 * from the core, but the TEST LOAD -- up to sixteen shell spinners -- was
 * launched with no affinity at all, so the scheduler could put all of them on
 * the very core that was supposed to be private. The measurement was of a
 * dedicated core that was never dedicated.
 *
 * So it comes back as a KNOB, not as behaviour: `setTransportCore(-1)` is the
 * default and disables every call below. `tools/latchhunt.sh` takes SPIN_MASK
 * to keep the spinners off the chosen core, which is what makes the experiment
 * mean anything.
 *
 * What still cannot be had: kernel threads, IRQ handlers and other processes
 * use any core they like. Genuine isolation needs isolcpus or a boot cpuset,
 * which needs root, which this tablet will not get. The honest description of
 * the best case here is "no app threads and no test load on that core".
 *
 * WHICH CORE IS AN OPEN QUESTION, and speed is the wrong way to pick it.
 * Michiel: "it isn't so cpu heavy - just needs to avoid scheduling delay."
 * Exactly so. This thread does tens of microseconds of work per millisecond,
 * so throughput is irrelevant and WAKE-UP LATENCY is everything. By that
 * measure the prime core is a poor candidate rather than an obvious one: it is
 * where the scheduler parks bursty foreground work, so it is contended in the
 * way that matters. A quiet little core may well be scheduled sooner.
 *
 * On this SoC: cpu0-2 are 2,016 GHz, cpu3-6 are 2,803 GHz, cpu7 is 3,36 GHz.
 * The old default of 6 was one of four mid cores and was never justified. Sweep
 * it and measure `late` rather than reasoning from clock speed.
 */
void setTransportCore(int core);
int transportCore();

/**
 * Whether the reap thread is PINNED to the transport core, or merely has it
 * kept clear for it.
 *
 * Michiel: "the pinning also prevents the reaper to run from a free core if
 * there was one." Just so, and the two effects are separable:
 *
 *   reserve (false) -- render and MIDI are excluded from the core, the reap
 *     thread is left free. It can use the reserved core whenever it likes,
 *     because this app's renderer is never on it, AND it can still migrate to
 *     any other idle core. The exclusion without the constraint.
 *   pin (true) -- as above, plus the reap thread is confined to that one core.
 *     Buys nothing extra against our own threads and forfeits migration.
 *
 * Default false, because the constraint is the half with a cost.
 */
void setPinTransportThread(bool pin);

/** Pins the calling thread to the transport core. No-op unless one is set. */
void pinToTransportCore(const char* what);

/** Keeps the calling thread OFF the transport core. No-op unless one is set. */
void pinOffTransportCore(const char* what);

/**
 * Android's Performance Hint session (ADPF), the supported way to tell the
 * scheduler that a set of threads has a deadline.
 *
 * This is the part that actually helps on this tablet. Nice values only order
 * threads against each other; a hint session tells the governor how long the
 * work is ALLOWED to take and how long it actually took, so it raises the
 * clock and keeps the threads off the little cores instead of discovering the
 * deadline was missed after the fact.
 *
 * API 33+, so every call is guarded at runtime -- minSdk here is 29, and on an
 * older device this silently does nothing, which is the correct behaviour
 * rather than a reason to refuse to run.
 */
class PerfHint {
public:
    ~PerfHint();

    /**
     * Opens a session for `tids` with a per-block deadline of `targetNs`.
     * Safe to call again; the previous session is closed first. Off the audio
     * thread.
     */
    void start(const std::vector<pid_t>& tids, int64_t targetNs);

    /** The deadline changed -- a new buffer size, or a new sample rate. */
    void setTarget(int64_t targetNs);

    /**
     * How long the last block actually took. Called from the render thread,
     * once per block. Cheap and lock-free when no session exists.
     */
    void report(int64_t actualNs);

    void stop();

    bool active() const { return mSession != nullptr; }

private:
    void* mSession = nullptr;   // APerformanceHintSession*, hidden from callers
    int64_t mTargetNs = 0;
};

}  // namespace bos
