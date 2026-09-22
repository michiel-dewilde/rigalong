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
        float deficitMs = 0.0f;      // wall time minus wire time; GROWTH is a hole
        float maxGapMs = 0.0f;       // worst reap interval since resetPeaks()
        float feedbackRate = 0.0f;   // the device's rate as last measured, Hz
    };
    Stats stats() const;
    void resetPeaks() { mMaxGapUs.store(0, std::memory_order_relaxed); }

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

    int mUrbs = 4, mPackets = 2, mFramesPerUrb = 0;
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
    static constexpr int kSizeCap = 512;
    int mSizes[kSizeCap] = {};
    int mSizeHead = 0, mSizeCount = 0;

    // Scratch for one packet's worth of float and dithered int16.
    std::vector<float> mFloat;
    std::vector<float> mStereo;
    std::vector<int16_t> mI16;
    Dither mDither;

    std::atomic<int64_t> mFrames{0}, mOutBytes{0}, mOutErr{0}, mInErr{0};
    std::atomic<int64_t> mStarved{0}, mLate{0};
    std::atomic<int64_t> mMaxGapUs{0};
    std::atomic<int64_t> mStartNs{0};
    std::atomic<float> mFbRate{0.0f};
};
