#pragma once

// Direct-USB audio output: this process drives the device's isochronous OUT
// endpoint itself, through usbfs, instead of handing samples to AAudio.
//
// WHY. On the Galaxy Tab S9 Ultra, Android's USB audio path runs through
// Qualcomm's DSP offload: the xHCI ring is handed to the ADSP, and neither the
// app nor the kernel can see or influence what reaches the device. The CP-88
// dropout (cp88-usb-audio-halt/ in the workspace) was investigated for days
// through that opaque path. Driving the endpoint from here takes the DSP, the
// HAL and AudioFlinger out of the data path entirely.
//
// Measured on the CP-88 with the prototype this was grown from (usbhost/ in
// the workspace):
//   * output depth IS the latency of this stage -- urbs x packets x period --
//     with nothing hidden beneath it; 8 ms (4 URBs x 2 packets) measured
//     cleanest: zero late reaps, zero wire deficit growth;
//   * implicit feedback held the CP-88's clock to -4 ppm over 27 minutes;
//   * 102 M frames continuous with the screen on.
// And one open finding: with the screen off, scheduling holes accumulated and
// the CP-88 latched silent at 46,9 M frames. That is why `deficitMs` exists
// and is shown: it is the controller's own account of audio NOT sent.
//
// The render ring is the engine's; this class only drains it, exactly as the
// Oboe callback does. Nothing here allocates after start().

// THE SPLIT BETWEEN THE RENDER THREAD AND THE REAP THREAD
// -------------------------------------------------------
// Settled by measurement on 2026-09-23, and worth stating once rather than
// rediscovering:
//
//   THE REAP THREAD (here) owns PROTOCOL TIMING and nothing else.
//     reap a URB -> account for it -> pop the next packet size from the
//     feedback queue -> ONE memcpy of already-formatted bytes -> resubmit.
//     No channel mapping, no dither, no packing, no float arithmetic, no
//     allocation, no logging on the healthy path. It runs at nice -19 with an
//     ADPF session whose deadline is one URB.
//
//   THE RENDER THREAD (AudioEngine) owns EVERY SAMPLE OPERATION.
//     the graph, master gain, the latency probe, channel mapping, dither,
//     packing to the endpoint's format, and concealing its own shortfall. It
//     runs at nice -16, one band below, because the ring exists so that a late
//     render is inaudible and NOTHING absorbs a late URB.
//
// The ring between them is device-format bytes, and it stays: it absorbs a late
// render, which includes sfizz reaching for a sample that is not in memory yet.
// That is I/O latency, and no amount of priority fixes it.
//
// What this split is NOT: it is not what stops the device latching. Moving the
// conversion off this thread was measured and changed `late` not at all
// (301 against 242-400 before). It is kept because the thread with a hard
// deadline should do the least work, which is ordinary real-time hygiene, not
// because it bought anything here.

#include <limits>
#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include <bagosynths/Dither.h>

#include "../RealtimePriority.h"

struct usbdevfs_urb;

class UsbAudioOut {
public:
    enum Feedback : int { kNone = 0, kImplicit = 1, kExplicit = 2 };

    struct Config {
        int fd = -1;                 // usbfs descriptor, from UsbDeviceConnection
        bool highSpeed = false;      // period unit: 125 us microframes, else 1 ms frames

        int outEp = 0;
        int outMaxPacket = 0;        // bytes per service interval, incl. HS mult
        int outInterval = 1;         // bInterval

        int feedback = kNone;
        int fbEp = 0;
        int fbMaxPacket = 0;
        int fbInterval = 1;
        int fbFrameBytes = 0;        // implicit: bytes per frame on the IN stream

        int channels = 2;
        int subslotBytes = 3;        // 2 = S16, 3 = S24_3LE, 4 = S32
        int rate = 44100;

        int bufferMs = 8;            // total OUT depth in flight

        // IN DEPTH IS FREE, AND IT IS NOT THE SAME NUMBER AS OUT DEPTH.
        //
        // These were one value until 2026-09-23, which is an arbitrary symmetry
        // that quietly caps how long this process may be descheduled before it
        // breaks the implicit-feedback contract.
        //
        // OUT depth IS latency: a URB queued now carries audio rendered now and
        // reaches the device `bufferMs` later, so it stays small and a live
        // instrument pays for every millisecond of it.
        //
        // IN depth is not latency. An IN URB is an empty receive buffer; the
        // device fills it on its own clock whenever it likes. Queueing 256 ms
        // of them costs 128 URBs x 2 packets x 270 bytes -- about 70 kB -- and
        // nothing else. No added delay, in either direction.
        //
        // What it buys is the whole contract. Every IN packet IS the device
        // stating how many frames it wants back. When the reap thread is
        // descheduled for 172 ms (measured, repeatedly) the device goes on
        // sending IN packets on its own clock, and any packet with no URB
        // queued to catch it is LOST -- a request that can never be answered,
        // and a permanent frame debt rather than a transient one. With OUT and
        // IN both at 8 ms, a 172 ms stall loses ~164 ms of the device's
        // requests. With IN at 256 ms it loses none, and the OUT gap becomes an
        // ordinary latency blip that drains off.
        // Sized against the WORST stall this process can suffer, not against
        // anything musical. 2026-09-23: 256 ms was not enough once the cpu6
        // pinning came out -- inLost jumped from 0-2 to 567 packets and the
        // latch came back 3 of 3. The budget, not the principle, was wrong.
        // 1024 ms is 512 URBs x 2 packets x 270 bytes, about 276 kB, and still
        // costs exactly nothing in latency.
        int inBufferMs = 1024;       // feedback capture depth; NOT latency

        // PACKETS PER URB, which is NOT the same knob as latency and was
        // hardcoded to 2 ms until 2026-09-23.
        //
        // Total OUT depth -- urbs x packets x period -- is the latency. How it
        // is DIVIDED between those first two is free, and it decides how many
        // URBs remain queued on the endpoint while the reap thread refills one.
        // At 4 ms with 2 packets per URB that is 2 URBs, so exactly ONE is
        // queued during a refill and any refill over 2 ms empties the endpoint.
        // At 4 ms with 1 packet per URB it is 4 URBs and three stay queued, for
        // identical latency.
        //
        // That matters because 4 ms latched 3 of 3 with starved=0 and
        // inLost=0 -- no invented packet and no lost request, so the
        // starvation mechanism cannot explain it and a second one exists.
        // Costs one submit and one reap per millisecond instead of per two.
        int packetsPerUrb = 2;
    };

    // Fills `frames` frames of audio ALREADY IN THE DEVICE'S FORMAT -- the
    // endpoint's channel count and sample width, packed -- and returns how many
    // it wrote. Called on this class's reap thread, which does nothing else to
    // the data: no channel map, no dither, no packing, no concealment.
    //
    // That conversion used to happen here, per packet, on the one thread in the
    // process that must never be late. The producer does it now. See the note
    // on AudioEngine::mByteRing for why: usbhost, whose reap thread computes a
    // sine inline and touches nothing shared, holds `late` at 32 under a system
    // load of 62, where this app reached 250-600.
    using PullFn = int (*)(void* ctx, uint8_t* dst, int frames);

    ~UsbAudioOut() { stop(); }

    bool start(const Config& config, PullFn pull, void* ctx, std::string* error);
    void stop();

    bool running() const { return mRunning.load(std::memory_order_acquire); }
    std::string fatal() const;

    // Frames of audio per URB -- the "burst" the engine sizes its ring floor by.
    int framesPerUrb() const { return mFramesPerUrb; }
    int urbCount() const { return mUrbs; }
    int packetsPerUrb() const { return mPackets; }
    double periodUs() const { return mPeriodUs; }

    struct Stats {
        int64_t frames = 0;          // frames handed to OUT URBs
        int64_t outBytes = 0;        // bytes the controller reports it sent
        int64_t outErrors = 0;       // per-packet errors, OUT
        int64_t inErrors = 0;        // per-packet errors, feedback stream
        int64_t starved = 0;         // implicit: packets sized from the nominal rate
        int64_t late = 0;            // URBs in flight far longer than the ring
        int32_t inSizeMin = 0;       // IN packet frames, this interval
        int32_t inSizeMax = 0;
        int64_t inPackets = 0;
        int64_t inFramesTotal = 0;   // what the device ASKED for, cumulative
        int64_t inLostPackets = 0;   // IN packets the device sent with no URB waiting
        int64_t zeroAvoided = 0; // zero-length OUT packets prevented; MUST stay 0
        int64_t shaped = 0;      // OUT packets a size/bias injection rewrote
        int64_t balance = 0;     // frames SENT minus frames ASKED; must stay <= 0
        int64_t balPeak = 0;     // worst (highest) balance this interval
        int64_t balRise = 0;     // furthest the balance climbed off its own floor
        int64_t fifoPeak = 0;    // modelled device FIFO, worst this interval
        int64_t outShort = 0;    // OUT packets the controller cut short; MUST stay 0
        int64_t outOdd = 0;      // OUT packets not a whole number of frames; MUST stay 0
        int64_t balanceHeld = 0; // packets the ceiling cut down; should stay 0
        int64_t fbDrop = 0;          // requests discarded on queue overflow
        int64_t fbClamp = 0;         // requests cut down to the max packet
        float deficitMs = 0.0f;      // wall time minus wire time; GROWTH is a hole
        float maxGapMs = 0.0f;       // worst reap interval since resetPeaks()
        float feedbackRate = 0.0f;   // the device's rate as last measured, Hz
    };
    Stats stats() const;
    void resetPeaks() { mMaxGapUs.store(0, std::memory_order_relaxed); }

    // THE MECHANISM TEST, and nothing else uses it.
    //
    // The deep IN queue stops the latch, but "it stops latching" does not say
    // WHY, and the difference matters: if the cause is the hole, this is a
    // mitigation that will fail on a longer stall; if the cause is inventing a
    // packet size the device never asked for, it is a fix.
    //
    // This forces exactly that one path -- the next `packets` OUT packets are
    // sized without consulting the feedback queue, as a dry queue would size
    // them -- with no load, no lateness and no hole anywhere near it. If the
    // device wedges on this alone, the mechanism is settled.
    // TEST ONLY, and it deliberately breaks the invariant below.
    //
    // Once fillOut refuses to emit a zero-length packet there is no way left to
    // produce a latch on purpose, which makes every recovery path untestable --
    // the fault would have to be waited for. This forces `count` genuinely
    // zero-length packets onto the wire, bypassing the guard, so that recovery
    // can be measured against a deterministic stimulus instead of a rare one.
    // Nothing in normal operation calls it.
    void injectRawZero(int count) { injectSize(count, 0); }

    // The same knob, generalised, because "zero is fatal" is only half an
    // answer. The transport now turns a would-be zero into a ONE-frame packet
    // and calls the invariant kept -- and nobody has asked whether one frame is
    // any less fatal than none. If it is not, that guard is a disguise.
    //
    //   injectSize(n, v) -- the next n OUT packets carry exactly v frames,
    //                       whatever the device asked for. v=0 is the wedge.
    //   injectBias(n, d) -- the next n packets carry what was asked PLUS d,
    //                       clamped: a sustained surplus or deficit at the
    //                       normal 44/45 cadence and with no lateness at all.
    //                       Surplus has only ever been tried twenty frames
    //                       deep; this can run it for ten seconds.
    // TEST ONLY. Both sit below the invariant deliberately.
    void injectSize(int count, int frames) {
        mInjectBiasLeft.store(0, std::memory_order_relaxed);
        mInjectSizeFrames.store(frames, std::memory_order_relaxed);
        mInjectSizeLeft.store(count, std::memory_order_relaxed);
    }
    // And the shape that matters most, because it is the one this transport can
    // produce by itself: STARVATION. The next `count` packets skip the feedback
    // queue entirely and are sized from the nominal rate, exactly as a dry
    // queue sizes them -- which is not a substitution but an ADDITION, because
    // the request that was skipped is still in the queue and will be honoured a
    // packet later. Every starved packet is therefore ~44 frames of PERMANENT
    // surplus, and nothing anywhere drains it.
    // A/B for the ceiling itself: off reproduces every latch measured before it
    // existed, on should make them impossible. TEST ONLY.
    void setBalanceGuard(bool on) { mBalanceGuard.store(on, std::memory_order_relaxed); }

    // Delay the resubmission of OUT URBs only, `count` times, `us` each. IN
    // keeps being serviced from its own queue throughout. TEST ONLY.
    void holdOut(int us, int count) {
        mHoldOutUs.store(us, std::memory_order_relaxed);
        mHoldOutLeft.store(count, std::memory_order_relaxed);
    }

    void injectStarve(int count) {
        mInjectStarve.store(count, std::memory_order_relaxed);
    }
    void injectBias(int count, int delta) {
        mInjectSizeLeft.store(0, std::memory_order_relaxed);
        mInjectBiasDelta.store(delta, std::memory_order_relaxed);
        mInjectBiasLeft.store(count, std::memory_order_relaxed);
    }
private:
    struct Urb {
        usbdevfs_urb* u = nullptr;
        uint8_t* data = nullptr;
        bool out = false;
        int64_t submittedNs = 0;
    };

    bool allocStream(std::vector<Urb>& v, int count, int packets, int ep, bool out,
                     int bytesPerPacket);
    void freeStream(std::vector<Urb>& v);
    bool submit(Urb& b);
    void fillOut(Urb& b);
    void fillIn(Urb& b, int packets, int bytesPerPacket);
    void completeIn(Urb& b);
    void completeFeedback(Urb& b);
    void completeOut(Urb& b);
    void reapLoop();
    int nextFrames();
    void setFatal(const std::string& why);

    Config mCfg;
    PullFn mPull = nullptr;
    void* mCtx = nullptr;

    int mUrbs = 4, mInUrbs = 4, mPackets = 2, mFramesPerUrb = 0;
    double mPeriodUs = 1000.0;
    int mFrameBytes = 6;

    std::vector<Urb> mOut, mIn;
    std::thread mThread;

    // ADPF for the REAP THREAD, which is the one with a hard deadline.
    //
    // Michiel's idea, and the gap was real: a hint session already existed but
    // only the render thread was in it. Nice values merely order threads
    // against one another -- they cannot ask the governor for clock, and they
    // cannot keep a thread off a little core. A hint session states a DEADLINE
    // and reports what the work actually cost, which is the supported
    // substitute for SCHED_FIFO that an ordinary app is allowed to use.
    //
    // This thread's deadline is exactly one URB: miss it and the endpoint has
    // nothing to transmit. It gets its own session rather than joining the
    // render one, because the two deadlines differ by an order of magnitude and
    // a session describes a single unit of work.
    bos::PerfHint mHint;
    std::atomic<bool> mRunning{false};
    mutable std::atomic<int> mFatalSet{0};
    std::string mFatal;

    // Packet sizing. Nominal: an exact integer accumulator, `rate` frames per
    // 1e6 us, so 44100 at 1 ms comes out as nine 44s and a 45 per ten.
    int64_t mNominalAcc = 0;
    // Explicit feedback: frames per period in 16.16, and its accumulator.
    uint32_t mFbQ16 = 0;
    uint32_t mFbAcc = 0;
    // Implicit feedback: packet sizes seen on the IN stream, oldest first.
    // SHALLOW ON PURPOSE, and it works with the deep IN URB queue rather than
    // against it. The two solve different halves of the same problem.
    //
    // The arithmetic first. The device asks for ~44,1 frames per millisecond
    // and one OUT packet can carry at most 45, so the headroom for catching up
    // is about 2%: a 140 ms backlog needs ~7 SECONDS to drain, and holes arrive
    // far more often than that. Frame debt is therefore permanent, and any
    // design that tries to answer every request eventually just overflows --
    // measured, fbDrop climbing 468 -> 1278 -> 2178 with a 512-deep queue.
    //
    // So the rule is not "answer every request". It is:
    //   never INVENT a size  -- that is what wedges the device;
    //   never use a STALE one -- a size from 512 ms ago describes a cadence the
    //                            device has long since moved on from.
    // The deep IN URB queue buys the first: packets are always caught, so a
    // recent size is always available and nextFrames() never has to guess. This
    // cap buys the second: once the backlog exceeds ~64 ms the oldest entries
    // are dropped, resynchronising to what the device is asking for NOW. The
    // frames in them are a debt that is acknowledged, bounded and counted in
    // fbDrop rather than pretended away.
    static constexpr int kSizeCap = 64;
    int mSizes[kSizeCap] = {};
    int mSizeHead = 0, mSizeCount = 0;


    std::atomic<int64_t> mFrames{0}, mOutBytes{0}, mOutErr{0}, mInErr{0};

    // The IN stream's packet sizes, summarised.
    //
    // THIS IS A LATCH PROBE, and the reasoning is worth keeping even if it
    // comes to nothing. With implicit feedback the device tells us how many
    // frames it wants by how many it DELIVERS on its own IN stream, and those
    // sizes come from the device's own audio clock: at 44100 Hz over 1 ms
    // frames the nominal is 44.1, so a device running a real converter has to
    // alternate 44 and 45 to make up the fraction. A device whose audio engine
    // has wedged might free-run at a constant size, or drift, and that would be
    // the only host-visible difference between "playing" and "acknowledging and
    // discarding" -- every other counter is identical across a latch, measured.
    //
    // Min/max rather than a histogram: it is the SPREAD that carries the
    // signal, and two ints cost nothing on the reap thread. Reset on read, so
    // each sample describes its own interval rather than all of history.
    // mutable: stats() is const and RESETS these, because each sample has to
    // describe its own interval rather than all of history.
    mutable std::atomic<int32_t> mInSizeMin{1 << 30};
    mutable std::atomic<int32_t> mInSizeMax{0};
    mutable std::atomic<int64_t> mInPackets{0};

    // IMPLICIT FEEDBACK IS A CONTRACT, and these count the places we break it.
    //
    // Michiel's hypothesis, and it survives every test so far: the latch is a
    // PROTOCOL error, not lateness. "The protocol requires to send message ABC
    // or nothing at all, but the implementation being late could send AC,
    // leaving out B." With implicit feedback the device states, packet by
    // packet, exactly how many frames it wants back; sending a different
    // number is that error, and the device has no way to complain about it.
    //
    // Three places do it, and none of them used to count:
    //   mFbDrop    -- the size queue overflowed and the OLDEST request was
    //                 discarded to keep the newest. A frame count the device
    //                 asked for that is never delivered.
    //   mFbClamp   -- a request larger than the endpoint's max packet, cut down.
    //   (mStarved) -- already counted: the queue ran dry and a NOMINAL size was
    //                 invented in place of one the device asked for.
    //
    // And the summary measurement that says whether the contract held at all:
    // cumulative IN frames against cumulative OUT frames. usbhost tracks these
    // and they match (221822 vs 222351 over a clean run). Any divergence here
    // is the host not honouring what the device asked for.
    // Cumulative IN packets, never reset -- mInPackets is reset on read for the
    // per-interval spread, and the loss estimate needs the running total.
    //
    // THE ONE COUNTER THAT SEES A BROKEN CONTRACT. The device emits one IN
    // packet per service interval on its own clock, so the number it has sent
    // since the stream started is simply the elapsed time in periods. Anything
    // that number exceeds what we actually received is packets that arrived
    // with no URB queued to catch them: the device's own statements of how many
    // frames it wants, destroyed before they were read. Unlike `starved`, which
    // only notices that the size queue ran dry, this counts requests that are
    // GONE and can never be answered.
    // Cumulative frames sent may never exceed cumulative frames asked. Zero,
    // not a margin: the device's own tolerance was measured at about 170 frames
    // and a ceiling should not be set at the edge of the cliff it guards.
    // How far above its own resting level the modelled FIFO may go. The resting
    // level is not zero and never was: it is the OUT ring, every frame of which
    // the device is holding -- measured at exactly 265 frames for a 6 ms ring,
    // stable to the frame. So the ceiling is computed from the ring at start(),
    // not written down here, and this is only the margin above it. 88 is two
    // packets: healthy operation excursions are one, and the cliff is ~170.
    static constexpr int64_t kFifoMarginFrames = 88;
    int64_t mFifoCeiling = 0;
    // OFF by default, and it stays off until it is shown to prevent something.
    // It does not: holding the modelled FIFO exactly at this ceiling (measured,
    // fifo=352 held=3852) did NOT stop a sustained overfeed from wedging the
    // device. So the model predicts the resting level correctly and the cliff
    // wrongly, and a fix that does not fix is a hack. Kept behind the GUARD
    // broadcast because it is the only handle on the one mechanism that IS
    // proven, and because the counters beside it are worth having.
    std::atomic<bool> mBalanceGuard{false};
    std::atomic<int64_t> mBalanceHeld{0};
    // mutable, like the other interval peaks: stats() is const and resets it.
    mutable std::atomic<int64_t> mBalPeak{std::numeric_limits<int64_t>::min()};
    mutable std::atomic<int64_t> mBalRise{0};
    // Reap thread only, so no atomic: the floor `balRise` is measured from.
    int64_t mBalMin = std::numeric_limits<int64_t>::max();
    // Reap thread only: fillOut adds, completeIn subtracts, floor at zero.
    int64_t mFifoFrames = 0;
    mutable std::atomic<int64_t> mFifoPeak{0};
    std::atomic<int64_t> mOutShort{0};
    std::atomic<int64_t> mOutOdd{0};
    std::atomic<int64_t> mHoldOutUs{0};
    std::atomic<int64_t> mHoldOutLeft{0};
    std::atomic<int64_t> mInjectStarve{0};
    std::atomic<int64_t> mInjectSizeLeft{0};
    std::atomic<int32_t> mInjectSizeFrames{0};
    std::atomic<int64_t> mInjectBiasLeft{0};
    std::atomic<int32_t> mInjectBiasDelta{0};
    std::atomic<int64_t> mShaped{0};
    std::atomic<int64_t> mInPacketsTotal{0};
    std::atomic<int64_t> mInFramesTotal{0};
    std::atomic<int64_t> mFbDrop{0};
    std::atomic<int64_t> mFbClamp{0};

    // Times a zero-length OUT packet was about to go out and was stopped. ONE
    // of those wedges the CP-88 permanently -- measured, 11 trials of 11, with
    // a full queue and no load. Anything but 0 here means the rig came within a
    // single packet of needing a USB reset.
    std::atomic<int64_t> mZeroAvoided{0};
    // TRIED AND REJECTED, 2026-09-23: measuring the LEVEL of the device's own
    // capture stream on endpoint 0x86, on the theory that a wedged audio
    // function might go silent in both directions, or that the CP-88's capture
    // might carry a mix including what we send it. It reads 0.0000 in BOTH
    // states -- the piano sends digital silence up the wire -- so it cannot
    // discriminate. Removed rather than left in: it scanned every sample of
    // every packet ON THE REAP THREAD, and a reap thread that runs late is the
    // one thing known to precede a latch. Do not add work here.
    std::atomic<int64_t> mStarved{0}, mLate{0};
    std::atomic<int64_t> mMaxGapUs{0};
    std::atomic<int64_t> mStartNs{0};
    std::atomic<float> mFbRate{0.0f};
};
