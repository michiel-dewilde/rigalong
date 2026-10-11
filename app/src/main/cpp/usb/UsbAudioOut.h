#pragma once

// Direct-USB audio output: this process drives the device's isochronous OUT
// endpoint itself, through usbfs, instead of handing samples to AAudio.
//
// WHY. On the Galaxy Tab S9 Ultra, Android's USB audio path runs through
// Qualcomm's DSP offload: the xHCI ring is handed to the ADSP, and neither the
// app nor the kernel can see or influence what reaches the device. Driving the
// endpoint from here takes the DSP, the HAL and AudioFlinger out of the data
// path, and makes the queue depth the whole of this stage's latency.
//
// THE TRANSPORT IS fbx (tools/fbx in the workspace), ported without its test
// instrumentation. It was built so that no delay of this thread, however long
// and wherever it falls, can make the device latch; the device story behind
// every rule is CP88-USB-AUDIO.md. Implicit feedback only: every packet size
// is one the device asked for on its IN stream.
//
// THE PROTOCOL
//
//   1. Echo exactly. Each OUT packet carries one IN ask: never a guess, never
//      less (except the descent of rule 6), never zero, never above the
//      declared maximum. The device's FIFO then only moves when packets are
//      absent.
//   2. Prove every append. After each submit, the URBs queued before it that
//      are still not given back must, by what usbfs shows, have held enough
//      frames that the new one cannot have landed after a gap. Else HOLD.
//   3. Verify every completion. Between two contiguous OUT completions exactly
//      one IN packet is given back. More means a gap got through: HOLD.
//   4. HOLD: append nothing more; let what is queued go out whole (never
//      cancel a URB on the wire: behind a hub that can deliver a cut packet,
//      and a cut packet latches the CP-88); then empty the device's FIFO by
//      stopping its stream -- the OUT interface to alt 0 and back. The device
//      re-primes; the next start is known.
//   5. Restart with one burst of the whole depth. A burst submitted within the
//      controller's IST cannot have started before it ended: contiguous.
//   6. Operating point (only with a measured profile). The device resumes at
//      its start fill, which may sit where a few missing packets land on a
//      deadly level. After the refill, short packets take the FIFO down to the
//      middle of the deepest safe zone, passing each zone above at its middle.
//
// A hold costs a short silence (~10 ms on the tablet plugged in directly) and
// is the transport working, not failing: the device never sees a gap it could
// misread, and never a packet that is not whole.
//
// THE SPLIT BETWEEN THE RENDER THREAD AND THE REAP THREAD
//
//   The reap thread (here) owns protocol timing and nothing else: reap, push
//   the asks, prove, submit, with one memcpy of already-formatted bytes per
//   packet. No conversion, no allocation, no logging. It is an AAudio callback
//   thread borrowed for the purpose (Config::hostDeviceId), because that is
//   the one way an app gets SCHED_FIFO; it preempts every ordinary thread.
//
//   The render thread (AudioEngine) owns every sample operation and fills the
//   ring ON DEMAND -- while it holds less than its target. So the frames the
//   descent leaves out are simply never rendered, and a hold adds no lasting
//   latency: the stream the device plays stays continuous, only shallower.

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include "../RealtimePriority.h"

struct usbdevfs_urb;

class UsbAudioOut {
public:
    // What the device's FIFO tolerates, measured (tools/fbx/PROFILING.md).
    // Levels are frames BELOW the stream-start fill. All zero: not measured,
    // and the transport then runs at the start fill, with no descent.
    struct Profile {
        int rate = 0;                   // the rate it was measured at
        int startFill = 0;              // frames above empty when it resumes
        int refill = 0;                 // packets from empty after which it resumes
        double dangerLo = 0, dangerHi = 0;  // the band where a landing packet kills
        double jitter = 0;              // echo jitter, frames peak to peak
    };
    // By USB ID. One CP-88 (and, by USB ID, CP73), measured 2026-10-03/04.
    static Profile profileFor(uint16_t vendorId, uint16_t productId);

    struct Config {
        int fd = -1;                    // usbfs descriptor, interfaces claimed
        bool highSpeed = false;         // period unit: 125 us microframes, else 1 ms

        int outIface = -1, outAlt = -1, outEp = 0;
        int outMaxPacket = 0;           // bytes per service interval
        int outInterval = 1;            // bInterval
        int inIface = -1, inAlt = -1, inEp = 0;   // the implicit-feedback stream
        int inMaxPacket = 0;
        int inFrameBytes = 0;           // bytes per frame on the IN stream

        int channels = 2;
        int subslotBytes = 3;           // 2 = S16, 3 = S24_3LE, 4 = S32
        int rate = 44100;

        // OUT packets kept queued: this IS the added latency. 6 on the tablet
        // plugged in directly: IN givebacks come up to two packets late there,
        // and at 5 that cost ~7 holds a minute at rest, at 6 ~1.
        int depth = 6;

        Profile profile;

        // THE THREAD. An app is refused SCHED_FIFO for its own threads
        // (RLIMIT_RTPRIO 0), but the audio server grants it -- priority 2 on
        // the tablet -- to an AAudio callback thread. So when this names an
        // output device (the built-in speaker, never the device driven here),
        // a silent low-latency stream is opened on it, and its FIRST callback
        // writes silence and then runs the reap loop itself -- blocking in
        // usbfs between completions, exactly as an own thread would -- until
        // stop(), when it returns STOP. That stream plays nothing meanwhile.
        //
        // This keeps the callback on purpose, which AAudio's contract says not
        // to do, because the canonical alternative -- one non-blocking pass per
        // callback -- runs only as often as the host's burst: 4 ms on the
        // tablet's speaker, and a 6-packet queue cannot be proven at that
        // pace (52 holds in its first second). Measured 2026-10-04 against an
        // own thread at nice -19, same load, judged at the CP-88's line out:
        // at rest 0 dropouts a minute against 13; 64-voice sweeps 1,9 against
        // 80; 16 CPU spinners 0 against 43.
        //
        // 0, or a stream whose callback never comes: an own thread at nice -19.
        int hostDeviceId = 0;
    };

    // Fills `frames` frames ALREADY IN THE DEVICE'S FORMAT. Called on the reap
    // thread; must fill all of them (concealing any shortfall itself).
    using PullFn = int (*)(void* ctx, uint8_t* dst, int frames);

    ~UsbAudioOut() { stop(); }

    // The streaming interfaces must already be at their streaming alts.
    bool start(const Config& config, PullFn pull, void* ctx, std::string* error);
    // Lets the OUT queue go out whole, then stops; leaves both interfaces at alt 0.
    void stop();

    bool running() const { return mRunning.load(std::memory_order_acquire); }
    std::string fatal() const;

    int depth() const { return mDepth; }
    double periodUs() const { return mPeriodNs / 1000.0; }
    double nominalFrames() const { return mNominal; }   // frames per packet, average
    int opPoint() const { return mPlan.opPoint; }       // frames below the start fill

    enum HoldReason {
        kHoldProof = 1,   // an append could not be proven contiguous
        kHoldGap,         // verification saw a gap after the fact
        kHoldLost,        // an OUT packet did not go out whole
        kHoldBadAsk,      // an IN packet failed: no size to echo
        kHoldSlowBurst,   // the restart burst took longer than the IST
        kHoldStarve,      // the IN queue ran dry: packets went by unseen
        kHoldReasons
    };

    struct Stats {
        int64_t frames = 0;             // frames handed to OUT URBs
        int64_t outBytes = 0;           // bytes the controller reports it sent
        int64_t holds = 0;
        int64_t holdsBy[kHoldReasons] = {};
        int64_t restarts = 0;
        int64_t altErrors = 0;          // alt cycles refused: a FIFO NOT emptied
        int64_t outLost = 0;            // OUT packets not sent whole
        int64_t inBad = 0;              // IN packets that carried no ask
        int64_t descentFrames = 0;      // frames left out to reach the op point
        int32_t queued = 0;             // OUT packets queued now
        float deficitMs = 0.0f;         // wall time minus wire time
        float maxGapMs = 0.0f;          // worst reap interval since resetPeaks()
        float feedbackRate = 0.0f;      // frames the device asked for, per second
    };
    Stats stats() const;
    void resetPeaks() { mMaxGapNs.store(0, std::memory_order_relaxed); }

    // A URB ON THIS DESCRIPTOR THAT IS NOT OURS. usbfs completions are per file
    // descriptor, so this loop reaps every async URB the process submits on the
    // device -- MIDI's included. A URB whose context is none of ours goes to the
    // registered handler, which resubmits it. Not cleared by start(), so an
    // audio restart does not unhook the MIDI stream.
    using ForeignFn = bool (*)(void* ctx, usbdevfs_urb* u);
    void setForeignReaper(ForeignFn fn, void* ctx) {
        mForeignCtx.store(ctx, std::memory_order_relaxed);
        mForeignFn.store(fn, std::memory_order_release);
    }
    bool runningOn(int fd) const { return running() && mCfg.fd == fd; }

private:
    static constexpr int kMaxZones = 8;
    struct Plan {
        int opPoint = 0;                // frames below the start fill (0: at it)
        int nZones = 0;                 // safe zones from the deepest (0) upward
        double zoneShallow[kMaxZones] = {}, zoneDeep[kMaxZones] = {};
        int zoneMid[kMaxZones] = {};
    };
    static Plan derive(const Profile& d, double nominal);

    struct Urb {
        usbdevfs_urb* u = nullptr;
        bool out = false;
        bool queued = false;
        Urb* next = nullptr;            // OUT queue, submit order
    };
    enum State { kAbsent, kRun, kDrain, kStopping };

    int reap(bool block);               // 1 handled, 0 nothing ready, <0 -errno
    void reapAll();
    void onIn(Urb& w, int64_t t);
    void onOut(Urb& w);
    void submitIn(Urb& w);
    void unqueue(Urb& w);
    void hold(int reason);
    void resetOut();
    void clockObserve(int64_t t);
    int64_t lag() const;
    int zoneOf(int depth) const;
    int descend(int ask);
    Urb* send();
    bool proven(const Urb& w) const;
    void topUp();
    void restart();
    void step();
    void reapLoop();

    bool ownsContext(const void* p) const;
    void setFatal(const std::string& why);
    void freeUrbs();
    bool openHost();
    void closeHost();
    static int hostData(void* stream, void* self, void* audio, int32_t frames);
    static void hostError(void* stream, void* self, int32_t error);

    Config mCfg;
    PullFn mPull = nullptr;
    void* mCtx = nullptr;
    Plan mPlan;

    int mDepth = 6;
    int64_t mPeriodNs = 1000000;
    double mNominal = 44.1;
    int mOutFrameBytes = 6, mMaxFrames = 45;

    std::vector<Urb> mOut, mIn;         // pools; never resized while running
    Urb* mQ = nullptr;                  // OUT URBs not yet given back
    Urb* mQTail = nullptr;
    int mQueued = 0;
    State mState = kAbsent;
    uint64_t mInPackets = 0;            // IN packets given back: the frame clock
    uint64_t mAbsentUntil = 0;
    // Set when the loop was away longer than the IN queue spans (kHoldStarve):
    // IN packets went by with no URB queued to see them, so mInPackets no
    // longer counts the device's frames and the anchor is wrong for good.
    // The next absence starts the anchor afresh. Loop thread only.
    bool mReanchor = false;
    uint64_t mVerifyMark = 0;           // mInPackets at the previous OUT completion
    bool mVerifyArmed = false;

    // The asks: IN sizes in frames, 0 for a packet that failed. Lag is free, so
    // only the newest kAskCap are kept.
    static constexpr unsigned kAskRing = 4096;   // power of two
    static constexpr unsigned kAskCap = 64;
    uint16_t mAsks[kAskRing] = {};
    unsigned mAskR = 0, mAskW = 0;
    unsigned asks() const { return mAskW - mAskR; }

    // The anchor maps CLOCK_MONOTONIC onto the IN packet count: it falls at
    // once to any earlier observation and rises no faster than the frame clock
    // can drift, so a thread that has been late for long cannot teach it that
    // late is normal.
    int64_t mAnchor = 0, mAnchorT = 0;
    int mSinceRestart = 0;              // packets sent since the FIFO was empty
    int mRefillSum = 0;                 // frames in the first `refill` of them
    int mLevel = 0;                     // frames below the start fill, once resumed

    std::thread mThread;
    void* mHost = nullptr;              // AAudioStream* whose callback runs the loop
    std::atomic<int> mHostState{0};     // 0 none yet, 1 hosted, 2 own thread
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mStopping{false};
    // TEST: stall the loop once, for this many ms, to reproduce a starved IN
    // queue on demand (PROVISIONAL.md).
    std::atomic<int> mTestStallMs{0};
public:
    void testStall(int ms) { mTestStallMs.store(ms, std::memory_order_relaxed); }
private:
    std::atomic<ForeignFn> mForeignFn{nullptr};
    std::atomic<void*> mForeignCtx{nullptr};
    mutable std::atomic<int> mFatalSet{0};
    std::string mFatal;

    // ADPF for this thread: a session stating its deadline (one packet) and
    // what the work cost, so the governor keeps it off a little core.
    bos::PerfHint mHint;

    // Written by the reap thread, read by stats().
    std::atomic<int64_t> mFrames{0}, mOutBytes{0}, mHolds{0}, mRestarts{0},
            mAltErrors{0}, mOutLost{0}, mInBad{0}, mDescent{0}, mAskedFrames{0};
    std::atomic<int64_t> mHoldsBy[kHoldReasons] = {};
    std::atomic<int32_t> mQueuedNow{0};
    std::atomic<int64_t> mMaxGapNs{0};
    std::atomic<int64_t> mStartNs{0};
    mutable std::atomic<int64_t> mRateAsked{0}, mRateNs{0};   // stats() only
    mutable std::atomic<float> mRateLast{0.0f};                // the last full-second measurement
};
