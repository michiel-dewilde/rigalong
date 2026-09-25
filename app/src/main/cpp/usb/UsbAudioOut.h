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

// THE TRANSPORT, 2026-09-25. Michiel's design, and the shape of the reap loop.
// The evidence for every line of it is in HANDOFF sections 31-36.
//
// ON EVERY WAKE-UP, IN THIS ORDER:
//   1. DRAIN THE IN SIDE TO EAGAIN, pushing every ask and resubmitting every
//      IN URB, BEFORE the OUT side is touched at all. Not optional: the kernel
//      holds 0-2 unreaped completions when idle but 18-19 after a 30 ms stall,
//      and at 8 packets per IN URB that is ~150 asks the FIFO does not know
//      about yet -- arriving exactly when a trim decision is being taken. The
//      FIFO depth is the only accounting instrument that exists (there is no
//      frame number to be had from usbfs, probed exhaustively), so it must be
//      read on a drained queue or it is not an instrument at all.
//   2. THEN evaluate the FIFO and resubmit the completed OUT URBs.
//
// PUSH: an IN packet with non-zero status or zero actual_length is not an ask.
//       Nothing is pushed for it.
// POP:  exactly one count per OUT packet, sent at exactly that size. If the
//       FIFO cannot fill the URB, the URB is not submitted. A size is NEVER
//       synthesised, in either direction.
// FULL: OUT has fallen behind IN. Under the RESYNC policy (the default since
//       2026-09-25) the FIFO is cleared and the stream drops into pre-roll, so
//       the device gets the gap that makes it discard its accumulated
//       deviation. Under the TRIM policy it is cut back to the pre-roll depth
//       and the stream stays contiguous. See mFifoResync for the argument.
// DRY:  submit NOTHING until the FIFO holds mFifoPreRoll again, then discard
//       the render backlog and send silence at the popped sizes while the
//       renderer rebuilds, then cut over to live audio.
//
// AND THE ONE THAT KILLS, stated on its own because everything else here is a
// matter of degree and this is not: SILENCE MEANS CORRECTLY-SIZED PACKETS OF
// DIGITAL ZEROES. It never means a zero-length packet. One zero-length OUT
// packet wedges the CP-88 permanently -- 11 trials of 11, with a full queue and
// no load -- and only a USB reset clears it. The push rule already makes a zero
// impossible to pop; the pre-roll silence path is the one place an
// implementation could put one back.

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

        int bufferMs = 6;            // total OUT depth in flight

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
        //
        // AND IT MAY NO LONGER HAVE A JOB, 2026-09-25. The depth was bought to
        // stop asks being LOST while this process was descheduled, because a
        // FIFO that ran dry then invented a nominal size, which is fatal. That
        // path is gone: a dry FIFO now withholds the OUT stream instead. A lost
        // ask is simply an ask never answered, which is a deficit, which is
        // free -- so under the resync policy below, 8 ms of IN is a deliberate
        // choice and not a regression. `inLost` will read in the hundreds under
        // load and that is expected, not a fault.
        int inBufferMs = 24;         // feedback capture depth; NOT latency

        // THE SIZE FIFO'S GEOMETRY, and the pre-roll depth is not free to
        // choose: IT MUST EXCEED THE OUT RING. Filling the ring consumes
        // `mUrbs x mPackets` asks the instant the pre-roll releases, so a
        // pre-roll of 4 against 4 OUT URBs leaves the FIFO at ZERO and every
        // scrap of jitter runs it dry again. Measured 2026-09-25: 8/4 idled at
        // `fifo=0..2` with eight pre-rolls before it settled, then thrashed
        // under load -- ~1400 pre-rolls and 6,8 s of injected silence per
        // trial -- and latched 4 of 4.
        //
        // The margin is monotone from there, all at 4 OUT URBs: 12/8
        // (margin 4) latched 3 of 20, 16/12 (margin 8) 1 of 10, 24/20
        // (margin 16) 0 of 10. Those three are NOT distinguishable from one
        // another at ten trials each -- only the ends of the range are -- so
        // 24/20 is the best point estimate rather than a proven optimum. It
        // is chosen because the margin costs only phase lag, and phase lag
        // is the free direction.
        //
        // And the BAND -- length minus pre-roll, how far the host may drift
        // before it resyncs -- is the other half: 24/8 is the same length
        // with a band of 16 and it latched 6 of 10, against 24/20's 0 of 10
        // at band 4. So it is not how STALE an ask may get that matters, it
        // is how far behind the host is allowed to run before it gives up.
        int fifoLength = 24;
        int fifoPreRoll = 20;

        // HOW LONG THE WIRE GOES QUIET ON A RESYNC, and it is a SEPARATE
        // number from the pre-roll depth on purpose.
        //
        // Those two were one thing until 2026-09-25, and they are two
        // requirements wearing one hat:
        //
        //   THE GAP repairs the device -- a discontinuity makes it discard the
        //   deviation it has accumulated -- and section 34 measured 2 ms as
        //   ENOUGH, with 2, 4, 10 and 24 ms all resetting it equally.
        //
        //   THE MARGIN (preRoll minus the OUT ring) stops the FIFO running dry
        //   afterwards, and section 39 measured that it wants to be LARGE:
        //   margin 0 latched 4 of 4, margin 16 latched 0 of 10.
        //
        // Clearing the FIFO and waiting for `fifoPreRoll` fresh asks satisfies
        // both, but it pays 20 ms of silence for a 2 ms job -- and that silence
        // is the click heard about once a minute in real playing. Timing the
        // gap directly buys the repair at a fifth of the cost and leaves the
        // margin exactly where the trials put it.
        int resyncGapUs = 4000;
        // true: a full FIFO clears it and forces a pre-roll, so the device
        // resyncs on a gap. false: a full FIFO is trimmed back to the pre-roll
        // depth and the stream stays contiguous. See mFifoResync.
        bool fifoResync = true;

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
        //
        // 1 since 2026-09-25, and the bench measured exactly what it buys.
        // SLACK -- how long this thread may be away before the wire goes
        // silent -- is `(urbs - 1) x packets` packets, exactly linear: at a
        // fixed 8 ms of latency, 8x1 / 4x2 / 2x4 give 7 / 6 / 4 ms. So
        // dividing the same depth into more, smaller URBs is free slack, and
        // one packet per URB is the most of it there is.
        int packetsPerUrb = 1;

        // AND THE IN SIDE'S, WHICH IS A DIFFERENT NUMBER FOR A DIFFERENT
        // REASON. These were one field until 2026-09-25, which tied the
        // feedback stream's URB size to the latency knob above.
        //
        // An IN URB is an empty receive buffer, so its packet count costs
        // nothing but a coarser wake-up: 8 packets means this thread is woken
        // once per 8 ms with 8 asks, instead of eight times with one each.
        // That is the point -- the asks are not late, they are BATCHED, and
        // the FIFO below absorbs the batch. 128 URBs x 8 packets is the same
        // 1024 ms of depth for a quarter of the reaps.
        int inPacketsPerUrb = 1;
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

    // DISCARD THE RENDER BACKLOG, and say how many frames of silence cover the
    // refill. Called on the reap thread, once, at the moment the OUT stream
    // restarts after the size FIFO ran dry.
    //
    // Whatever is in the render ring at that moment is audio that was due
    // during the gap. Carrying it would make every gap permanent added
    // latency -- the device absorbs about 110 frames, 2,7 ms, and no more --
    // so the backlog goes and the stream resumes at the live edge. The return
    // value is how far the producer wants to be ahead again; the transport
    // sends correctly-sized SILENCE for that many frames while the renderer
    // rebuilds, then cuts over to live audio.
    using FlushFn = int (*)(void* ctx);

    ~UsbAudioOut() { stop(); }

    bool start(const Config& config, PullFn pull, FlushFn flush, void* ctx,
               std::string* error);
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
        int64_t outShort = 0;    // OUT packets the controller cut short; MUST stay 0
        int64_t outOdd = 0;      // OUT packets not a whole number of frames; MUST stay 0
        int64_t outDry = 0;      // reap gaps longer than the OUT ring: stream RESTARTS
        int64_t preRolls = 0;    // times the OUT stream was withheld and restarted
        int64_t silenceFrames = 0;   // frames of pre-roll silence sent, cumulative
        int64_t outTrims = 0;        // times a full FIFO was cut back on an OUT reap
        int64_t resyncs = 0;         // times a full FIFO forced a gap to resync the device
        int32_t fifoMin = 0;         // size-FIFO depth, this interval
        int32_t fifoMax = 0;
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
    // And the one that now exercises the RECOVERY instead of the fault:
    // injectStarve(n) makes the next n packets skip the FIFO, which under this
    // transport is not an invented size but an absence. Each one drops the OUT
    // stream into pre-roll -- withheld packets, a discarded render backlog,
    // silence, then live audio -- so the whole dry-FIFO path can be fired on
    // demand instead of waited for.
    //
    // It used to mean the opposite: the skipped packet was sized from the
    // nominal rate, which is not a substitution but an ADDITION, because the
    // ask it stood in for was still in the queue and got honoured a packet
    // later. Every starved packet was ~44 frames of permanent surplus. That
    // path is gone; injectBias is the way to ask the device about a surplus now.

    // Delay the resubmission of OUT URBs only, `count` times, `us` each. IN
    // keeps being serviced from its own queue throughout. TEST ONLY.
    // `every` spaces the holds out: hold on every Nth OUT URB rather than on
    // consecutive ones, which is the difference between one long hole and many
    // short ones. Back-to-back holds merge into a single absence, which is the
    // shape already known to be survivable.
    void holdOut(int us, int count, int every) {
        mHoldOutUs.store(us, std::memory_order_relaxed);
        mHoldOutEvery.store(every, std::memory_order_relaxed);
        mHoldOutSeen = 0;
        mHoldOutLeft.store(count, std::memory_order_relaxed);
    }

    // Replace the payload with usbhost's tone while everything else -- threads,
    // priorities, packet sizes, the ring and the render load behind it --
    // stays exactly as it is. 0 turns it off. TEST ONLY.
    void setTone(float hz) { mToneHz.store(hz, std::memory_order_relaxed); }

    // THE WIRE, RECORDED. One entry per isochronous packet in either
    // direction, in a ring that holds about a minute.
    //
    // This exists because the latch rate drifts from 0 of 4 to 3 of 3 between
    // blocks minutes apart, which makes every A/B expensive and most of them
    // underpowered. A trace needs ONE latch, not sixteen trials: stress in
    // short bursts, probe after each, and dump the moment a probe comes back
    // silent -- the ring then covers the burst the device died in.
    //
    // An earlier wire trace was removed as answered. It was answering a
    // different question (the submitted cadence through a hole, which is
    // flawless) and it did answer it.
    void dumpTrace(const char* path) const;

    // A URB ON THIS DESCRIPTOR THAT IS NOT OURS.
    //
    // usbfs completions are per-FILE-DESCRIPTOR, not per-endpoint, so the reap
    // loop here sees every async URB anyone in this process submits on the
    // device -- which is why the MIDI reader used a synchronous ioctl and had
    // no queue at all. The answer is not for MIDI to avoid the queue but for
    // this loop to DISPATCH what it finds: a URB whose context is none of ours
    // goes to the registered handler, which resubmits it.
    //
    // Deliberately NOT cleared by start(), so an audio restart does not silently
    // unhook the MIDI stream.
    using ForeignFn = bool (*)(void* ctx, usbdevfs_urb* u);
    void setForeignReaper(ForeignFn fn, void* ctx) { mForeignFn = fn; mForeignCtx = ctx; }
    bool runningOn(int fd) const { return running() && mCfg.fd == fd; }

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
    int fillOut(Urb& b);
    void fillIn(Urb& b, int packets, int bytesPerPacket);
    void completeIn(Urb& b);
    void completeFeedback(Urb& b);
    void completeOut(Urb& b);
    bool dispatch(void* reaped);
    bool ownsContext(const void* p) const;
    bool serviceOut();
    void noteFifoDepth();
    void reapLoop();
    int nextFrames();
    void setFatal(const std::string& why);

    Config mCfg;
    PullFn mPull = nullptr;
    FlushFn mFlush = nullptr;
    void* mCtx = nullptr;

    int mUrbs = 4, mInUrbs = 128, mPackets = 1, mInPacketsPerUrb = 8,
        mFramesPerUrb = 0;
    double mPeriodUs = 1000.0;
    int mFrameBytes = 6;

    std::vector<Urb> mOut, mIn;
    ForeignFn mForeignFn = nullptr;
    void* mForeignCtx = nullptr;
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
    // THE PACKET-FRAMES FIFO. Integers, not audio: what the device asked for,
    // oldest first. It grows by one entry per IN packet received and shrinks by
    // one per OUT packet sent, and it is the only thing that decides a size.
    //
    // THE RULE THE WHOLE TRANSPORT IS BUILT ON, measured on the bench over
    // three days and written up in HANDOFF sections 31-35:
    //
    //   NEVER MANUFACTURE A SIZE. Absence is free -- 11,6 s of it, no ill
    //   effect. Phase lag is free -- 1000 packets behind, no ill effect. An
    //   INVENTED size is fatal and cumulative: the device integrates the
    //   deviation and wedges at about 100-200 frames, and nothing drains it.
    //   A nominal 44,1 against the device's true 44,09929 is enough on its
    //   own -- 43 frames out in a minute, dead in three and a half.
    //
    // So this FIFO is never bypassed, never topped up from a clock, and never
    // read when empty. What it costs is phase: sending the ask from
    // PRE_ROLL packets ago means running that far behind, which is a permanent
    // deficit, which is the free direction.
    //
    // A gap in the OUT stream DISCARDS whatever deviation the device had
    // accumulated -- measured, and 2 ms of gap is enough. That is why running
    // dry is not a disaster here but a reset, and why the pre-roll below is a
    // deliberate mechanism rather than an apology.
    // The array is the biggest the FIFO may be configured to; the working
    // length and the pre-roll depth are RUNTIME values, so the geometry can be
    // swept from a broadcast instead of a rebuild. The modulus below is always
    // the capacity, never the working length -- mixing the two is how a ring
    // buffer quietly starts returning the wrong entry.
    static constexpr int kFifoCap = 64;
    int mSizes[kFifoCap] = {};
    int mFifoLen = 24, mFifoPreRoll = 20;
    int mSizeHead = 0, mSizeCount = 0;

    // WHAT TO DO WHEN THE FIFO FILLS, and it is the whole experiment of
    // 2026-09-25 late. Michiel:
    //
    //   "whenever the fifo is full (all INs processed), you clean the fifo with
    //    a new pre-roll (disrupting OUT for a bit so the device resyncs)"
    //
    // TRIM (false) absorbs the backlog: discard the oldest asks, keep the
    // stream contiguous, carry on. It is the conservative choice and it is what
    // section 36 shipped -- and the rig still latched under it, with 924 gaps
    // and a wire that was perfect to the frame.
    //
    // RESYNC (true) does the opposite. A full FIFO means OUT has fallen behind
    // IN, and rather than quietly catching up it CLEARS the FIFO and drops into
    // pre-roll: the wire goes silent for a few milliseconds, and a gap is the
    // one thing measured to make the device discard whatever deviation it had
    // accumulated (section 34 -- 2 ms is enough). The cost is an audible seam
    // every time the host falls 8 ms behind, which is a real cost and the
    // reason this is a flag rather than a decision.
    bool mFifoResync = true;

    // PRE-ROLL: the OUT stream is WITHHELD -- no URB submitted at all, not one
    // of a nominal size -- until the FIFO has rebuilt to mFifoPreRoll. True at
    // start and whenever the FIFO runs dry.
    bool mPreRoll = true;
    // A DELIBERATE, TIMED GAP, which is not the same state as a dry FIFO.
    // pre-roll waits for a DEPTH (it has nothing to send); this waits for a
    // CLOCK (it has plenty to send and is deliberately not sending it).
    bool mInGap = false;
    int64_t mGapUntilNs = 0;
    int64_t mResyncGapUs = 4000;
    // Frames of silence still owed while the render ring rebuilds after the
    // flush. Correctly sized packets of digital zeroes; see fillOut.
    int mSilenceFrames = 0;
    // OUT URBs that have completed and not yet been resubmitted, in completion
    // order. Reserved at start(), so no allocation on the reap thread.
    std::vector<Urb*> mOutIdle;
    std::atomic<int64_t> mPreRolls{0};
    std::atomic<int64_t> mSilenceTotal{0};
    std::atomic<int64_t> mOutTrims{0};
    std::atomic<int64_t> mResyncs{0};
    mutable std::atomic<int32_t> mFifoMin{1 << 30};
    mutable std::atomic<int32_t> mFifoMax{0};


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
    // THE BALANCE CEILING IS GONE, 2026-09-25, and why is worth keeping.
    //
    // It modelled the device's playout FIFO and cut a packet down when the
    // model said the device was too full. It never fired in the field latch it
    // was written for (rise=45 against a ceiling of 88), and holding the model
    // exactly at the ceiling did NOT stop a sustained overfeed from wedging the
    // device -- so it predicted the resting level right and the cliff wrong.
    //
    // And then the bench settled the principle underneath it: a size the device
    // did not ask for is fatal in BOTH directions. Cutting a packet down is
    // manufacturing a size just as much as padding one up, and doing it packet
    // after packet is precisely the shape that kills in ~200 frames. The guard
    // would have been a slower way to wedge the device, not a defence. The
    // counters it came with -- bal, balPeak, balRise -- are kept: they measure,
    // they do not intervene.
    // mutable, like the other interval peaks: stats() is const and resets it.
    mutable std::atomic<int64_t> mBalPeak{std::numeric_limits<int64_t>::min()};
    mutable std::atomic<int64_t> mBalRise{0};
    // Reap thread only, so no atomic: the floor `balRise` is measured from.
    int64_t mBalMin = std::numeric_limits<int64_t>::max();
    std::atomic<int64_t> mOutShort{0};
    std::atomic<int64_t> mOutOdd{0};
    std::atomic<int64_t> mOutDry{0};
    // Reap thread only. Per direction, because the two queues are deliberately
    // different depths: measuring an IN URB against the OUT ring made `late`
    // meaningless the moment the feedback stream went deep.
    int64_t mOutRingUs = 0, mOutRingNs = 0, mInRingNs = 0;
    struct TraceEntry {
        int32_t tUs;      // since the stream started
        int16_t frames;   // packet size, or -1 for a packet the device lost
        int16_t kind;     // 0 = OUT submitted, 1 = IN completed
    };
    static constexpr int kTraceCap = 65536;   // ~65 s at 1000 packets/s
    std::vector<TraceEntry> mTrace;
    int64_t mTraceAt = 0;                     // reap thread only
    int64_t mTraceT0 = 0;
    std::atomic<float> mToneHz{0.0f};
    double mTonePhase = 0.0;
    std::atomic<int64_t> mHoldOutUs{0};
    std::atomic<int64_t> mHoldOutLeft{0};
    std::atomic<int64_t> mHoldOutEvery{1};
    int64_t mHoldOutSeen = 0;
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
