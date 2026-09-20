#pragma once

#include <atomic>
#include <string>
#include <vector>

#include "Graph.h"

// The tonewheel organ: setBfree's Beatrix engine as a graph node.
//
// Why this engine. A Hammond is not a sample set -- it is 91 tonewheels whose
// outputs are mixed by the drawbars, with crosstalk between wheels, a key click
// from the contact bounce, a scanner vibrato built from a delay line, a tube
// preamp and a rotating speaker. Beatrix models all of it, and it is the only
// free implementation of that quality. It is GPL-2-or-later, which is why this
// project is GPL (see LICENSING.md).
//
// What this node owns. A complete setBfree `b_instance`: tone generator,
// scanner vibrato, preamp/overdrive, spring reverb and the Leslie. The internal
// chain is upstream's own, in upstream's order:
//
//     oscGenerateFragment -> preamp -> reverb -> whirlProc3
//
// The Leslie is INSIDE the node rather than being its own graph node, because
// the organ and its speaker are one instrument. Pulling whirl out into a
// reusable RotaryNode -- a Rhodes through a Leslie is worth having -- is a
// separate, easy job: it needs its own midicfg and nothing else. See GRAPH.md.
//
// Threading. process() runs on the render thread and does no allocation. The UI
// changes parameters through a lock-free queue that is drained at the top of
// each block, because setBfree's control functions mutate engine state directly
// and must not run concurrently with oscGenerateFragment.

struct b_instance;

namespace bagosynths {

class HammondNode : public Node {
public:
    HammondNode();
    ~HammondNode() override;

    const char* typeName() const override { return "Hammond"; }

    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Midi, 1, "midi"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    // Parameter space. Deliberately a curated subset: Beatrix exposes over a
    // thousand settings and a stage instrument needs the ones a player reaches
    // for. Everything else keeps upstream's default, which is a well-voiced B-3.
    enum Param {
        // Nine drawbars per manual, 0 (silent) .. 8 (loudest), in the classic
        // order 16', 5 1/3', 8', 4', 2 2/3', 2', 1 3/5', 1 1/3', 1'.
        kUpper1 = 0,
        kLower1 = 9,
        kPedal1 = 18,

        kPercEnable = 27,     // 0/1
        kPercFast = 28,       // 0 = slow decay, 1 = fast
        kPercThird = 29,      // 0 = 2nd harmonic, 1 = 3rd
        kPercSoft = 30,       // 0 = normal volume, 1 = soft

        kVibratoKnob = 31,    // 0..5 = V1, C1, V2, C2, V3, C3
        kVibratoUpper = 32,   // 0/1
        kVibratoLower = 33,   // 0/1

        kOverdrive = 34,      // 0/1
        kOverdriveChar = 35,  // 0..1
        kOverdriveGain = 36,  // 0..1, input gain

        kRotary = 37,         // 0 = stop/brake, 1 = slow (chorale), 2 = fast (tremolo)
        kReverbMix = 38,      // 0..1
        kSwell = 39,          // 0..1, the expression pedal

        // One keyboard, three manuals. Notes below kSplitPedal go to the pedal
        // bus, notes below kSplitLower to the lower manual, the rest to the
        // upper. Both are MIDI note numbers; 0 disables that split.
        kSplitPedal = 40,
        kSplitLower = 41,
        kTranspose = 42,      // semitones, -12..12

        kLevel = 43,          // this node's output gain, linear

        kNumParams = 44,
    };

    int numParams() const override { return kNumParams; }
    void setParam(int index, float value) override;
    float getParam(int index) const override;
    const char* paramName(int index) const override;

    // Applies a named factory registration -- the nine drawbar presets every
    // organist knows -- to one manual (0 upper, 1 lower, 2 pedal). Off the audio
    // thread; goes through the same queue.
    void applyPreset(int index, int manual);
    static int presetCount();
    static const char* presetName(int index);

    float maxRenderMs() const { return mMaxRenderMs; }
    void resetProfile() { mMaxRenderMs = 0.0f; }

private:
    void applyParam(int index, float value);   // render thread only
    void pushParam(int index, float value);    // any thread

    b_instance* mInst = nullptr;
    bool mInitialised = false;
    double mRate = 44100.0;

    float mParams[kNumParams] = {};

    // Parameter changes cross from the UI thread to the render thread through a
    // single-producer, single-consumer ring. setBfree's control functions write
    // engine state directly -- recomputing wheel gains, reloading key tables --
    // so calling them from the UI thread while oscGenerateFragment runs would be
    // the same race that sfizz's loader turned out to be. Queue, drain, apply.
    struct Change { int index; float value; };
    static constexpr int kQueueCapacity = 256;
    Change mQueue[kQueueCapacity];
    std::atomic<uint32_t> mQueueWrite{0};
    std::atomic<uint32_t> mQueueRead{0};

    // setBfree generates in fixed 128-sample fragments (BUFFER_SIZE_SAMPLES).
    // That is not a coincidence with our 128-frame render block, but it is a
    // happy one: one fragment per block, no carry, no partial reads. The offset
    // is kept anyway so any block size still works.
    static constexpr int kFragment = 128;
    float mBufA[kFragment] = {};   // tonegen out
    float mBufB[kFragment] = {};   // after preamp
    float mBufC[kFragment] = {};   // after reverb
    float mBufD[2][kFragment] = {};  // whirl scratch
    float mBufL[2][kFragment] = {};  // stereo, after the Leslie
    int mOffset = kFragment;         // forces a fresh fragment on the first call

    float mMaxRenderMs = 0.0f;
};

}  // namespace bagosynths
