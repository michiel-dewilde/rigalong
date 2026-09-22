#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <bagosynths/Gain.h>
#include <bagosynths/Graph.h>
#include <bagosynths/ParamQueue.h>

// Mutable Instruments Plaits as a graph node: sixteen synthesis models, from a
// two-oscillator virtual analog through granular, speech, physical modelling
// and a drum section.
//
// Why this engine, and why it is nearly free. Surge XT already vendors
// `libs/eurorack` and already links it -- Surge's Twist oscillator *is*
// `plaits::Voice` and its Nimbus effect *is* `clouds::GranularProcessor`. So
// every one of these sixteen models is already compiled into the binary and
// tested by Surge's own use of it. What was missing was a keyboard in front of
// it. That is this file: voice allocation, MIDI, and the sample rate.
//
// It is MIT, which is the loosest licence in the rig.
//
// THE SAMPLE RATE IS THE ONE REAL PROBLEM. Plaits is firmware for a Cortex-M4
// and its rate is a compile-time constant -- `kSampleRate` in plaits/dsp/dsp.h,
// 48000, baked into every oscillator's phase increment and every wavetable.
// There is no `setSampleRate`. We run at 44100, so the only honest answer is to
// render at 48k and resample, which is what Surge does too.
//
// Resampling once for the whole node rather than once per voice is the
// difference that matters: Plaits voices are summed at 48 kHz and ONE Lanczos
// resampler converts the mix. Sixteen resamplers would have cost sixteen times
// the filtering and 64 kB of ring buffer each, to produce a result that is
// arithmetically identical because resampling is linear.
//
// Plaits' `out` and `aux` are two different signals, not a stereo pair -- so
// they ride the resampler's two channels and are blended afterwards by the AUX
// fader, which costs nothing and keeps both signals band-limited.
//
// The envelope is Plaits' own low-pass gate, driven the way the hardware drives
// it: LEVEL patched from our gate, TRIGGER patched from note-on. The LPG then
// sustains under a held key, and DECAY becomes the release. That is one vactrol
// model rather than an ADSR of ours bolted on top, and it is the reason the
// plucked and struck models sound like themselves.

namespace bagosynths {

struct PlaitsImpl;   // plaits::Voice, the arenas and the resampler

class PlaitsNode : public Node {
public:
    PlaitsNode();
    ~PlaitsNode() override;

    const char* typeName() const override { return "Plaits"; }

    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Midi, 1, "midi"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    enum Param {
        kLevel = 0,
        kEngine = 1,       // 0..15, the model
        kHarmonics = 2,    // 0..1, the HARMONICS knob
        kTimbre = 3,       // 0..1, the TIMBRE knob
        kMorph = 4,        // 0..1, the MORPH knob
        kDecay = 5,        // 0..1, LPG decay -- the release control
        kColour = 6,       // 0..1, LPG colour: how much high end survives the tail
        kAuxMix = 7,       // 0..1, blend of the OUT and AUX signals
        kTranspose = 8,    // semitones, -24..24
        kBendRange = 9,    // semitones, 0..12
        kModDepth = 10,    // mod wheel -> TIMBRE, 0..1
        kNumParams = 11,
    };

    static constexpr int kNumEngines = 16;
    static const char* engineName(int index);

    // What HARMONICS, TIMBRE, MORPH and AUX actually DO in a given model.
    //
    // On the hardware these three knobs are deliberately vague, and that
    // vagueness is the instrument -- but it makes the panel unusable on stage,
    // where "Inharmonicity" and "Grain Density" are the difference between
    // reaching for the right control and hunting. `which` is 0..3 in the order
    // HARMONICS, TIMBRE, MORPH, AUX.
    static const char* paramLabel(int engineIndex, int which);
    static constexpr int kNumLabelled = 4;

    int numParams() const override { return kNumParams; }
    void setParam(int index, float value) override;
    float getParam(int index) const override;
    const char* paramName(int index) const override;

    float maxRenderMs() const { return mMaxRenderMs; }
    void resetProfile() { mMaxRenderMs = 0.0f; }
    int activeVoices() const;

private:
    // Plaits is monophonic firmware, so polyphony is N instances. It is cheap to
    // be generous: a plaits::Voice is 4792 bytes plus a 16 kB arena shared by all
    // sixteen models, so sixteen voices cost about 340 kB. CPU is the real limit
    // and is measured, not assumed.
    static constexpr int kMaxVoices = 16;

    // plaits::kBlockSize. Not ours to choose: the LPG and the decay envelope
    // advance once per call and their coefficients are scaled by this constant.
    static constexpr int kSubBlock = 12;

    void noteOn(int midiNote, int velocity);
    void noteOff(int midiNote);
    void releaseSustained();
    void panic();
    void allNotesOff() override { panic(); }
    float lastRenderPeak() const override { return mLastPeak; }
    int chooseVoice(int midiNote);
    void applyParam(int index, float value);
    void pushParam(int index, float value);
    void renderSubBlock();
    bool anyVoiceLive() const;

    std::unique_ptr<PlaitsImpl> mImpl;

    double mRate = 44100.0;
    bool mInitialised = false;
    float mParams[kNumParams] = {};
    bool mSustain = false;

    // Resampling silence is not free -- measured at 0.494 ms per block with no
    // voices at all, which was the second most expensive node in the graph while
    // doing nothing. So when the node has been quiet for a while it stops
    // touching the resampler and writes zeros instead, and re-syncs the phases on
    // the way back in. Waiting a few blocks first is what makes that safe: it
    // lets the 8-tap window flush the real tail before anything is skipped, so
    // the only thing ever truncated is silence.
    static constexpr int kBlocksBeforeIdle = 8;   // ~23 ms at 128 frames
    int mSilentBlocks = 0;
    bool mResamplerIdle = false;
    float mBend = 0.0f;       // -1..1
    float mModWheel = 0.0f;   // 0..1
    uint32_t mVoiceTick = 0;

    ParamQueue<256> mQueue;

    float mMaxRenderMs = 0.0f;

    // Peak of the last rendered block, taken before this node applies its own
    // level: see Node::lastRenderPeak. Render thread only.
    float mLastPeak = 0.0f;

    // The output level, ramped rather than stepped: see SmoothedGain.
    SmoothedGain mGain;
};

}  // namespace bagosynths
