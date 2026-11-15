#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

// Round-trip latency probe (MEASURE, S20.3): write a click into the output,
// count how many input frames pass before it comes back. The same technique
// OboeTester uses with a loopback cable, except that the loop here is the room
// and a microphone, so the figure includes both.
//
// The input stream is entirely optional: if it does not open, or permission is
// denied, the instrument works exactly as before and the probe reports
// Unavailable.
//
// Threads: open(), close() and start() from a control thread; run() from
// whichever thread renders the block (one at a time, under the engine's render
// lock); the readings from any thread.
class LatencyProbe {
public:
    enum class State : int32_t {
        Unavailable = 0,   // no input stream (not opened, or permission denied)
        Idle = 1,
        Armed = 2,         // the click goes out with the next block
        Waiting = 3,       // click emitted, listening for its return
        Done = 4,
        Failed = 5,        // nothing came back within the timeout
    };

    // Opens the input on `deviceId` (oboe::kUnspecified: the default input).
    // Already up on that device: left alone. False when no configuration opens,
    // or when AAudio answers with another device than the one asked for.
    bool open(int32_t deviceId);
    void close();

    // Arms a measurement; the result arrives in state() and roundTripMs().
    void start();

    // After a block has been rendered into `out` (interleaved stereo): drains
    // the input, and overwrites the block with the click while one is going out.
    void run(float* out, int32_t numFrames);

    State state() const { return mState.load(std::memory_order_acquire); }
    float roundTripMs() const { return mRoundTripMs.load(std::memory_order_relaxed); }
    // 0..1, so one can see whether anything returns at all.
    float inputPeak() const { return mInputPeak.load(std::memory_order_relaxed); }
    // What the input stream reports of itself; 0 without one.
    float inputLatencyMs() const;

private:
    // ~10 ms of buzz. Longer than strictly needed, because the click has to
    // survive a trip through the room and a tablet microphone, not a cable.
    static constexpr int32_t kClickFrames = 441;
    static constexpr int64_t kTimeoutFrames = 44100 / 2;   // 500 ms
    static constexpr int32_t kBufferFrames = 4096;

    void emitClick(float* out, int32_t frames);
    // Unavailable, and run() out of the stream, before the stream is touched.
    void retire();

    std::shared_ptr<oboe::AudioStream> mStream;
    // Which device mStream is on, so open() can tell "already up on the right
    // device" from "up on the wrong one".
    int32_t mDeviceId = -1;
    int32_t mChannels = 2;
    std::vector<int16_t> mBuffer;

    std::atomic<State> mState{State::Unavailable};
    // run() is inside the stream. With mState this is the handshake that lets a
    // control thread replace or close the stream under a running render thread.
    std::atomic<bool> mInRun{false};
    std::atomic<float> mRoundTripMs{0.0f};
    std::atomic<float> mInputPeak{0.0f};

    // The detector. Written by start() while Idle/Done/Failed, by run() after.
    int64_t mFramesSinceClick = 0;
    int32_t mClickRemaining = 0;
    float mNoiseFloor = 0.0f;
    // The level must STAY up for a few ms before an onset is believed, which
    // rejects a transient that is not the click.
    int64_t mCandidateFrame = -1;
    int32_t mAboveCount = 0;
    float mMaxPeakSinceClick = 0.0f;
    float mEnvelope = 0.0f;
};
