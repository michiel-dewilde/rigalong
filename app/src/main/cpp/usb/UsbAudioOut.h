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

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include <bagosynths/Dither.h>

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
        int inBufferMs = 256;        // feedback capture depth; NOT latency
    };

    // Fills `frames` interleaved stereo floats; returns how many were really
    // available. The engine's ring. Called on this class's reap thread.
    using PullFn = int (*)(void* ctx, float* dst, int frames);

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
        int64_t fbDrop = 0;          // requests discarded on queue overflow
        int64_t fbClamp = 0;         // requests cut down to the max packet
        float deficitMs = 0.0f;      // wall time minus wire time; GROWTH is a hole
        float maxGapMs = 0.0f;       // worst reap interval since resetPeaks()
        float feedbackRate = 0.0f;   // the device's rate as last measured, Hz
        int64_t outMinFlightUs = 0;  // shortest submit->reap seen, OUT, this interval
        int64_t inMinFlightUs = 0;   // and IN
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
    void injectStarve(int packets) {
        mInjectStarve.store(packets, std::memory_order_relaxed);
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

    // Scratch for one packet's worth of float and dithered int16.
    std::vector<float> mFloat;
    std::vector<float> mStereo;
    std::vector<int16_t> mI16;
    Dither mDither;

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
    std::atomic<int64_t> mInjectStarve{0};
    std::atomic<int64_t> mInPacketsTotal{0};
    std::atomic<int64_t> mInFramesTotal{0};
    std::atomic<int64_t> mFbDrop{0};
    std::atomic<int64_t> mFbClamp{0};

    // DID THE ENDPOINT QUEUE RUN SHALLOW? Measured by flight time, because
    // counting URBs cannot answer it.
    //
    // The question is real: usbhost runs 8 URBs per stream, this runs 4 at 8 ms
    // and 2 at the 4 ms default. That is not the depth argument again -- what
    // matters is how many URBs the endpoint still has to TRANSMIT while the
    // reap thread is off refilling one. usbhost leaves 7, this leaves 1. At
    // zero the endpoint goes quiet, and since every URB carries ISO_ASAP the
    // next submission does not resume the stream, it starts one at whatever
    // frame the controller has reached.
    //
    // The obvious instrument -- submitted minus reaped -- WAS BUILT AND IS
    // WRONG, and it is worth saying why so nobody builds it again. The kernel
    // completes URBs whether or not anyone reaps them; a completed URB simply
    // waits on the completion queue. So that counter measures URBs I have not
    // COLLECTED, not URBs the endpoint has yet to SEND, and the two move in
    // opposite directions: a reap thread that is 300 ms late has a completion
    // queue that is full, not empty. Measured, 2026-09-23: it sat at its
    // healthy floor (3 of 4 queued, zero drains) through a run that reached
    // maxGap 323,9 ms and 1337 ms of deficit. Reassuring and meaningless.
    //
    // Flight time answers it one-sidedly, which is enough. A URB cannot come
    // back before the ones queued ahead of it have gone out, so with a full
    // queue submit->reap is about (urbs-1) x packets x period -- roughly 6 ms
    // here, 14 ms in usbhost. Reaping can only ever be LATE, never early, so a
    // SHORT flight time proves the queue was shallow; a long one proves
    // nothing. Short is the direction that matters.
    //
    // Reset on read, so each sample describes its own interval.
    mutable std::atomic<int64_t> mOutMinFlightUs{INT64_MAX};
    mutable std::atomic<int64_t> mInMinFlightUs{INT64_MAX};

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
