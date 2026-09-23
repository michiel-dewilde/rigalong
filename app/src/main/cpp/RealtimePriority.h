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

// --- CPU affinity ------------------------------------------------------------
//
// A nice value biases WHEN a thread runs. Affinity decides WHERE, and on this
// SoC that is the larger effect, because the cores are not equal:
//
//   cpu0-2   2,016 GHz   little
//   cpu3-6   2,803 GHz   mid
//   cpu7     3,360 GHz   prime
//
// The reap thread gets a core of its own and the threads that feed it are kept
// off it. Splitting the nice bands already cut late URBs 8-11x under load,
// which says the render thread was the competition; this removes the contest
// rather than settling it.
//
// cpu6 rather than cpu7: the prime core is where the top app's UI work lands
// and where thermal throttling bites first, and the reap thread does not need
// speed -- it wakes about a thousand times a second and does almost nothing.
// What it needs is to be scheduled PROMPTLY, and an uncontended mid core gives
// that more reliably than a fast contended one.
//
// BE HONEST ABOUT WHAT THIS IS NOT. Android gives an app no cpuset control, so
// cpu6 is not reserved -- other processes still run there. This only guarantees
// that OUR threads stay off it, which is exactly the contention that was
// measured to matter, and nothing more.
constexpr int kTransportCore = 6;
constexpr int kMinCoresToPin = 8;

/** Pins the calling thread to [kTransportCore] alone. Logs what happened. */
void pinToTransportCore(const char* what);

/** Restricts the calling thread to every core EXCEPT [kTransportCore]. */
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
