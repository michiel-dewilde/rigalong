#pragma once

#include <array>
#include <atomic>
#include <cstdint>

// M2 test instrument: two detuned polyBLEP saws -> state-variable lowpass with
// envelope-modulated cutoff -> ADSR amplitude. Deliberately a real synth voice
// rather than a sine, so the CPU load, the voice allocator and the note handling
// all get exercised under something resembling a musical workload.
//
// Everything here is preallocated and bounded. No allocation, no locks, no
// syscalls: this all runs inside the 2 ms audio callback.

class Envelope {
public:
    void setSampleRate(float sr) { mSampleRate = sr; }
    void configure(float attackSec, float decaySec, float sustain, float releaseSec);

    void noteOn()  { mStage = Stage::Attack; }
    void noteOff() { if (mStage != Stage::Idle) mStage = Stage::Release; }
    void reset()   { mStage = Stage::Idle; mLevel = 0.0f; }

    bool isActive() const { return mStage != Stage::Idle; }
    float nextSample();

private:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };

    Stage mStage = Stage::Idle;
    float mSampleRate = 44100.0f;
    float mLevel = 0.0f;
    float mAttackRate = 0.01f;
    float mDecayCoeff = 0.999f;
    float mSustain = 0.7f;
    float mReleaseCoeff = 0.999f;
};

// Topology-preserving-transform state variable filter (Zavalishin / Simper form).
// Stable when the cutoff is modulated hard, which is exactly what we do here.
class StateVariableFilter {
public:
    void setSampleRate(float sr) { mSampleRate = sr; }
    void reset() { mIc1eq = 0.0f; mIc2eq = 0.0f; }
    void setCutoff(float hz, float q);
    float processLowpass(float x);

private:
    float mSampleRate = 44100.0f;
    float mG = 0.1f, mK = 1.0f, mA1 = 0.0f, mA2 = 0.0f, mA3 = 0.0f;
    float mIc1eq = 0.0f, mIc2eq = 0.0f;
};

class Voice {
public:
    void setSampleRate(float sr);
    void noteOn(int note, float velocity);
    void noteOff();
    void kill();

    bool isActive() const { return mAmpEnv.isActive(); }
    bool isReleasing() const { return mReleasing; }
    int note() const { return mNote; }
    uint64_t startOrder() const { return mStartOrder; }
    void setStartOrder(uint64_t o) { mStartOrder = o; }

    // Renders into a stereo interleaved buffer, ADDING to what is there.
    void renderAdd(float* stereoOut, int numFrames, float pitchBendRatio, float cutoffMod);

private:
    static float midiNoteToHz(float note);

    float mSampleRate = 44100.0f;
    int mNote = -1;
    float mBaseFreq = 440.0f;
    float mVelocity = 0.0f;
    bool mReleasing = false;
    uint64_t mStartOrder = 0;

    float mPhase1 = 0.0f, mPhase2 = 0.5f;
    float mPan = 0.5f;

    Envelope mAmpEnv;
    Envelope mFilterEnv;
    StateVariableFilter mFilter;
};

class Synth {
public:
    static constexpr int kMaxVoices = 64;

    void setSampleRate(float sr);
    void reset();

    // MIDI entry points. Called from the audio thread, at block boundaries.
    void noteOn(int note, int velocity);
    void noteOff(int note);
    void controlChange(int cc, int value);
    void pitchBend(int lsb, int msb);
    void allNotesOff();
    void panic();

    // Renders a stereo interleaved block, overwriting the buffer.
    void render(float* stereoOut, int numFrames);

    int activeVoiceCount() const { return mActiveVoices.load(std::memory_order_relaxed); }

    // Counts voices right now rather than as of the last render. Used to report
    // the workload to ADPF *before* doing it, which is the whole point -- a big
    // chord that just landed must raise the estimate for this block, not the next.
    int countActiveVoicesNow() const;

private:
    Voice* allocateVoice(int note);

    std::array<Voice, kMaxVoices> mVoices{};
    std::array<bool, 128> mHeldBySustain{};

    float mSampleRate = 44100.0f;
    uint64_t mVoiceCounter = 0;

    bool mSustainPedal = false;
    float mPitchBendRatio = 1.0f;
    float mCutoffMod = 0.0f;      // mod wheel, 0..1
    float mMasterGain = 0.25f;    // CC7, scaled with headroom for the limiter

    std::atomic<int> mActiveVoices{0};
};
