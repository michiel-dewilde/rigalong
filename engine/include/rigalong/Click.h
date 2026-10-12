#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>

// The metronome click (REQUIREMENTS S12.2, I12.2): a short sine burst on every
// beat, higher on the first beat of the bar, mixed into an interleaved stereo
// block.
//
// Placed on the sample, not on the block: a click that may land anywhere in a
// 128-frame block is 2.9 ms of jitter, which a drummer hears. The beat phase is
// accumulated per frame, so a tempo change mid-run bends the next beat rather
// than restarting the count, and the output is the same whatever the block size.
//
// A count-in plays a fixed number of beats and stops on its own; continuous
// runs until stop(). Either way the last click rings out -- stop() never cuts a
// burst in half, which would be a click of its own.
//
// Control (start, stop, tempo, level) from any thread, through atomics; render()
// on the render thread only. Allocation-free and header-only.
namespace rigalong {

class Click {
public:
    static constexpr double kAccentHz = 1568.0;   // G6
    static constexpr double kBeatHz = 1046.5;     // C6
    static constexpr double kAttackSeconds = 0.0005;
    static constexpr double kDecaySeconds = 0.012; // time constant
    static constexpr double kLengthSeconds = 0.06;
    static constexpr float kBeatLevel = 0.6f;     // relative to the accent

    // `beats` <= 0 runs until stop(); otherwise that many beats, then stops.
    void start(double bpm, int beatsPerBar, int beats) {
        mBpm.store(bpm, std::memory_order_relaxed);
        mBeatsPerBar.store(beatsPerBar < 1 ? 1 : beatsPerBar, std::memory_order_relaxed);
        mBeats.store(beats, std::memory_order_relaxed);
        mStartGen.fetch_add(1, std::memory_order_release);
        // Visible at once, so a caller polling running() right after start()
        // does not see the previous run's false.
        mRunning.store(true, std::memory_order_relaxed);
    }

    void stop() {
        mStopGen.fetch_add(1, std::memory_order_release);
        mRunning.store(false, std::memory_order_relaxed);
    }

    // A tempo change while running bends the next beat; the count goes on.
    void setBpm(double bpm) { mBpm.store(bpm, std::memory_order_relaxed); }
    void setGain(float gain) { mGain.store(gain, std::memory_order_relaxed); }
    float gain() const { return mGain.load(std::memory_order_relaxed); }

    bool running() const { return mRunning.load(std::memory_order_relaxed); }
    // Beats rendered since the last start (render time, which runs ahead of
    // what is heard by the output latency).
    int64_t beatsRendered() const { return mBeatsDone.load(std::memory_order_relaxed); }

    // Adds the click into `stereo` (interleaved L R), `frames` frames.
    void render(float* stereo, int frames, double sampleRate) {
        const uint32_t startGen = mStartGen.load(std::memory_order_acquire);
        const uint32_t stopGen = mStopGen.load(std::memory_order_acquire);
        if (startGen != mSeenStart) {
            mSeenStart = startGen;
            mSeenStop = stopGen;  // a start supersedes any stop before it
            mActive = true;
            mPhase = 0.0;
            mNextBeat = 0;
            mLimit = mBeats.load(std::memory_order_relaxed);
            mBar = mBeatsPerBar.load(std::memory_order_relaxed);
            mBeatsDone.store(0, std::memory_order_relaxed);
        } else if (stopGen != mSeenStop) {
            mSeenStop = stopGen;
            mActive = false;
        }
        if (!mActive && mVoiceLeft <= 0) return;

        const double bpm = mBpm.load(std::memory_order_relaxed);
        const double perFrame = (bpm > 0.0 ? bpm : 120.0) / 60.0 / sampleRate;
        const float gain = mGain.load(std::memory_order_relaxed);
        const double twoPi = 6.283185307179586;
        const double attack = kAttackSeconds * sampleRate;
        const double decay = std::exp(-1.0 / (kDecaySeconds * sampleRate));

        for (int i = 0; i < frames; ++i) {
            if (mActive && mPhase >= static_cast<double>(mNextBeat)) {
                const bool accent = mNextBeat % mBar == 0;
                mStep = twoPi * (accent ? kAccentHz : kBeatHz) / sampleRate;
                mOsc = 0.0;
                mEnv = accent ? 1.0 : kBeatLevel;
                mAge = 0;
                mVoiceLeft = static_cast<int>(kLengthSeconds * sampleRate);
                ++mNextBeat;
                mBeatsDone.store(mNextBeat, std::memory_order_relaxed);
                if (mLimit > 0 && mNextBeat >= mLimit) {
                    mActive = false;
                    mRunning.store(false, std::memory_order_relaxed);
                }
            }
            if (mActive) mPhase += perFrame;
            if (mVoiceLeft > 0) {
                const double ramp = mAge < attack ? mAge / attack : 1.0;
                const float s = static_cast<float>(std::sin(mOsc) * mEnv * ramp) * gain;
                stereo[i * 2] += s;
                stereo[i * 2 + 1] += s;
                mOsc += mStep;
                if (mOsc > twoPi) mOsc -= twoPi;
                mEnv *= decay;
                ++mAge;
                --mVoiceLeft;
            }
        }
    }

private:
    // control
    std::atomic<uint32_t> mStartGen{0};
    std::atomic<uint32_t> mStopGen{0};
    std::atomic<double> mBpm{120.0};
    std::atomic<int> mBeatsPerBar{4};
    std::atomic<int> mBeats{0};
    std::atomic<float> mGain{0.5f};
    std::atomic<bool> mRunning{false};
    std::atomic<int64_t> mBeatsDone{0};

    // render thread
    uint32_t mSeenStart = 0;
    uint32_t mSeenStop = 0;
    bool mActive = false;
    double mPhase = 0.0;   // in beats since start
    int64_t mNextBeat = 0;
    int mLimit = 0;
    int mBar = 4;
    double mOsc = 0.0;
    double mStep = 0.0;
    double mEnv = 0.0;
    int mAge = 0;
    int mVoiceLeft = 0;
};

}  // namespace rigalong
