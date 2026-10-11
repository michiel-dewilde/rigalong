#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include <bagosynths/Gain.h>
#include <bagosynths/Graph.h>

namespace sfz { class Sfizz; }

namespace bagosynths {

// Entry point for hardware MIDI. The engine writes into this node's output
// buffer before running the schedule.
class MidiInNode : public Node {
public:
    const char* typeName() const override { return "MIDI In"; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Midi, 1, "midi"}; }
    void process(const ProcessArgs&) override {}   // filled by the engine
};

// Exit point. The engine reads this node's input buffer after the schedule runs.
class AudioOutNode : public Node {
public:
    const char* typeName() const override { return "Audio Out"; }
    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Audio, 2, "in"}; }
    void process(const ProcessArgs&) override {}   // read by the engine
};

// ------------------------------------------------------------------- sfizz

// sfizz, the SFZ sampler. MIDI in, stereo out.
//
// Loading is explicitly NOT done here: loadFile() runs on the loader thread and
// publishes the result. The audio thread only ever renders.
class SfizzNode : public Node {
public:
    SfizzNode();
    ~SfizzNode() override;

    const char* typeName() const override { return "sfizz"; }

    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Midi, 1, "midi"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    // Released when the part is switched away from: see Node::allNotesOff.
    void allNotesOff() override;
    float lastRenderPeak() const override { return mLastPeak; }

    // Off the audio thread. `id` is the library id a patch refers to; `path` is
    // where it happens to live on this device. The node remembers both, and only
    // the id is ever serialized.
    bool loadFile(const std::string& id, const std::string& path);
    std::string instrumentId() const { return mInstrumentId; }
    std::string loadedName() const { return mLoadedName; }
    int activeVoices() const;

    // One parameter so far: the level this part sits at in the mix. It exists
    // because the graph can now run two instruments into the same effects chain,
    // and "how loud is sfizz against setBfree" is the first thing anyone
    // reaches for.
    enum Param { kLevel, kNumParams };
    int numParams() const override { return kNumParams; }
    void setParam(int index, float value) override;
    float getParam(int index) const override;
    const char* paramName(int index) const override;

    // Starting a note and rendering it are very different costs: note-on may
    // touch a cold sample (page fault, file handle, streamer wake-up) while
    // rendering is steady DSP. Timing them apart is the only way to tell a
    // streaming problem from a CPU problem.
    float maxNoteMs() const { return mMaxNoteMs; }
    float maxRenderMs() const { return mMaxRenderMs; }
    int lastNoteOns() const { return mLastNoteOns; }
    void resetProfile() { mMaxNoteMs = 0.0f; mMaxRenderMs = 0.0f; }

private:
    // sfizz's loadSfzFile() may NOT run concurrently with renderBlock(). Loading
    // happens on the loader thread while the render thread is synthesising, so
    // the two must be handshaken -- this was a latent crash from the moment
    // loading became asynchronous, and it surfaced as a SIGSEGV inside
    // SfizzNode::process on the render thread.
    //
    // The handshake, rather than a lock: a lock held across a multi-second load
    // would stall rendering for that whole time. Instead the loader raises
    // mLoadRequested and waits for the render thread to acknowledge by parking
    // itself (mRenderParked) and emitting silence. Only then does it touch sfizz.
    std::atomic<bool> mLoadRequested{false};
    std::atomic<bool> mRenderParked{false};

    std::unique_ptr<sfz::Sfizz> mSynth;
    std::string mInstrumentId;
    std::string mLoadedName;
    std::vector<float> mLeft, mRight;
    std::atomic<float> mLevel{1.0f};
    float mMaxNoteMs = 0.0f;
    float mMaxRenderMs = 0.0f;

    // Peak of the last rendered block, taken before this node applies its own
    // level: see Node::lastRenderPeak. Render thread only.
    float mLastPeak = 0.0f;

    // The output level, ramped rather than stepped: see SmoothedGain.
    SmoothedGain mGain;
    int mLastNoteOns = 0;

    // Note-ons are budgeted by TIME, not by count.
    //
    // Starting a note on a cold sample costs ~50 us; on a warm one, ~1 us. Thirty
    // cold note-ons in a single 2 ms block is 1.5 ms spent before a sample is
    // rendered, which blows the deadline outright. So note-ons are started until
    // this block's allowance is spent, and the remainder queue for the next block.
    //
    // A time budget beats a fixed count precisely because the cost varies by 50x:
    // a warm cluster starts instantly with no delay at all, and only genuinely
    // expensive notes are spread. The principle: a note arriving a few ms late is
    // inaudible, a dropout is not -- so trade onset delay for glitch-freedom, but
    // only when it is actually needed.
    //
    // Note-offs, CCs and pitch bend are NEVER delayed: they are cheap, and
    // holding them back would sound worse than the problem being solved.
    // Calibration trim, so that "level 1" means roughly the same loudness here
    // as it does on the other five engines.
    //
    // MEASURED, 2026-09-22, one A4 per engine, peak of each engine's own output
    // before its level (PROFILE ... pre-level):
    //
    //   Plaits  +0,2 dBFS (x0,25 headroom inside its own level: -11,8 net)
    //   Fluid   -7,1      setBfree -8,2     Surge  -10,7
    //   Dexed  -18,1      sfizz   -20,5
    //
    // and sfizz across velocity, which is the part that decides it:
    //
    //   vel  40   60   80  100  110  127
    //   dBFS -36 -29  -25 -20,5 -17,8 -13,0
    //
    // At FULL velocity sfizz reached only -13 dBFS, while A4v16.wav peaks
    // at -1,5 dBFS in the file. So eleven decibels of headroom sat unused even
    // at fortissimo -- this is not "a dynamic instrument is quieter at mezzo",
    // which would be correct and would be left alone; it is the whole range
    // sitting low. Michiel's instruction was to adjust only if the staging is
    // really off, and twelve decibels below setBfree at every velocity is off.
    //
    // 2,7 (+8,6 dB) puts full velocity at about -4,4 dBFS -- in the same band
    // as the other engines, with real headroom left, and it CANNOT clip on its
    // own: the measured maximum times 2,7 is 0,60.
    //
    // Applied here rather than by editing the SFZ, so third-party content is
    // untouched and the fader keeps meaning 0..1.
    static constexpr float kCalibration = 2.7f;

    static constexpr double kNoteOnBudgetMs = 0.35;
    static constexpr int kMaxNoteOnsPerBlock = 24;   // hard ceiling as well
    static constexpr int kPendingCapacity = 256;
    MidiEvent mPending[kPendingCapacity];
    int mPendingHead = 0;
    int mPendingTail = 0;
    int mPendingDropped = 0;

public:
    int pendingNoteOns() const {
        return (mPendingTail - mPendingHead + kPendingCapacity) % kPendingCapacity;
    }
    int pendingDropped() const { return mPendingDropped; }

private:
};

// ------------------------------------------------------------------ effects

// Freeverb-style reverb: a small bank of comb filters into allpasses. Public
// domain algorithm, tiny, and good enough to make an instrument sound like it is
// in a room. Dragonfly-class quality is an M6 replacement, not an M3 blocker.
class ReverbNode : public Node {
public:
    const char* typeName() const override { return "Reverb"; }

    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Audio, 2, "in"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    enum Param { kRoomSize, kDamping, kWidth, kMix, kNumParams };
    int numParams() const override { return kNumParams; }
    void setParam(int index, float value) override;
    float getParam(int index) const override;
    const char* paramName(int index) const override;

private:
    static constexpr int kCombs = 8;
    static constexpr int kAllpasses = 4;

    struct Comb {
        std::vector<float> buf;
        int index = 0;
        float store = 0.0f;
        float process(float in, float feedback, float damp);
    };
    struct Allpass {
        std::vector<float> buf;
        int index = 0;
        float process(float in);
    };

    Comb mComb[2][kCombs];
    Allpass mAllpass[2][kAllpasses];

    float mRoomSize = 0.72f, mDamping = 0.35f, mWidth = 1.0f, mMix = 0.25f;
};

// Master limiter. Not a lookahead design yet -- this is a fast-attack soft-knee
// compressor with a hard ceiling, whose job is to guarantee the signal handed to
// the dither stage is inside [-1, 1]. A proper lookahead limiter is M6.
class LimiterNode : public Node {
public:
    const char* typeName() const override { return "Limiter"; }

    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Audio, 2, "in"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    enum Param { kCeiling, kGain, kNumParams };
    int numParams() const override { return kNumParams; }
    void setParam(int index, float value) override;
    float getParam(int index) const override;
    const char* paramName(int index) const override;

private:
    float mCeiling = 0.95f;
    float mGain = 1.0f;
    float mEnvelope = 0.0f;
    float mAttack = 0.0f, mRelease = 0.0f;
};

}  // namespace bagosynths
