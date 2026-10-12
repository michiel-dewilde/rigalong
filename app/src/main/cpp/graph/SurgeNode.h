#pragma once

#include <atomic>
#include <mutex>
#include <memory>
#include <string>
#include <vector>

#include <bagosynths/Gain.h>
#include <bagosynths/Graph.h>
#include <bagosynths/ParamQueue.h>

// Surge XT's synthesis engine as a graph node.
//
// Why this engine. Michiel's first message asked for "real synth like things".
// Surge is three oscillators a voice across a dozen oscillator types including
// wavetable and FM, two filter units with a deep filter set, dual FX chains,
// twelve modulation sources -- and **641 factory patches**. Nothing else
// available changes what this rig can do by nearly as much. GPL-3-or-later,
// which this project already is.
//
// What this node owns. One `SurgeSynthesizer` and its `SurgeStorage`. Surge's
// DSP comes apart from its JUCE plugin layer because upstream made it come
// apart -- see the CMakeLists comment -- so what is linked here is the engine
// and nothing else. `PluginLayer` is an abstract base with two methods, both of
// which a host may legitimately ignore, and we do.
//
// Construction is NOT done in prepare(). Building the synthesiser scans the
// patch library -- 641 patches and 188 wavetables, 41 ms measured -- and that
// belongs on the loader thread, not on whatever thread happens to compile the
// graph. Until it finishes, process() emits silence. See initAsync().
//
// Block size. Surge's `BLOCK_SIZE` is a compile-time constant of 32 frames, so
// our 128-frame render block is exactly four `process()` calls. The carry in
// mOffset exists only for the case where the host hands us a length that is not
// a multiple of 32.
//
// Threading, the same rule as everywhere else here: parameters cross from the
// UI thread through a lock-free ring drained at the top of each block, and
// patch loading -- 2.8 ms typical, 63.8 ms worst measured -- parks the render
// with the same handshake SfizzNode uses for sample loading.

class SurgeSynthesizer;

namespace bagosynths {

class SurgeNode : public Node {
public:
    SurgeNode();
    ~SurgeNode() override;

    const char* typeName() const override { return "Surge XT"; }

    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Midi, 1, "midi"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    // Released when the part is switched away from: see Node::allNotesOff.
    void allNotesOff() override;
    float lastRenderPeak() const override { return mLastPeak; }

    // Parameters. Deliberately NOT a synth editor: Surge has around 2000
    // parameters and a UI for a synth this deep is its own project. What is
    // exposed is the level, and the eight **macros** -- which are the knobs the
    // patch author themselves decided were worth reaching for, so they are the
    // right performance controls by construction.
    enum Param {
        kLevel = 0,
        kMacro1 = 1,   // .. kMacro8 at 8
        kNumParams = 9,
    };
    static constexpr int kNumMacros = 8;

    int numParams() const override { return kNumParams; }
    void setParam(int index, float value) override;
    void setParamRealtime(int index, float value) override;
    float getParam(int index) const override;
    const char* paramName(int index) const override;

    // ------------------------------------------------------- off the audio thread

    // Builds the synthesiser and scans `dataPath` for patches and wavetables.
    // `homePath` must be a writable directory: sst-plugininfra's homePath()
    // THROWS if HOME is unset, and Android does not guarantee it. Safe to call
    // when the data path does not exist -- Surge then runs on its init patch,
    // which still makes a sound.
    bool init(const std::string& dataPath, const std::string& homePath);
    bool ready() const { return mSynth.load(std::memory_order_acquire) != nullptr; }

    int patchCount() const;
    std::string patchName(int index) const;       // "Category/Name"

    // Where Surge keeps the player's own patches (its user data folder's
    // Patches), as it worked that folder out; empty before init.
    std::string userPatchesPath() const;
    // Scans the patch folders again (a patch was added), keeping the current
    // patch by name: Surge numbers patches by their place in the list.
    void refreshPatches();

    // What the PATCH AUTHOR called this macro. Surge stores eight labels with
    // every patch, and they are the difference between eight faders called
    // "M1".."M8" and eight faders called "Drive", "Cutoff", "Air". Empty when
    // the author left it at the default, which is the caller's cue to fall
    // back to the number.
    std::string macroName(int index) const;
    std::string patchDisplayName(int index) const;  // just the name

    // Loads by index into the scanned list. Parks the render thread first.
    bool loadPatch(int index);
    // Loads the patch whose "Category/Name" matches, or returns false. Patches
    // are addressed by NAME, never by index, for exactly the reason instruments
    // are addressed by id: an index means something different the moment the
    // library changes underneath it.
    bool loadPatchNamed(const std::string& name);

    // Records a patch to load without caring whether the engine is up yet.
    //
    // This exists because a saved patch is restored during onResume, which is
    // BEFORE the loader thread has finished scanning 641 patches -- so asking
    // for one by name at that moment looks up a name in an empty library and
    // quietly gives up. Intent is recorded here and honoured by whoever
    // finishes loading. Selecting a patch explicitly clears it, so a restore in
    // flight can never overwrite a deliberate choice.
    void requestPatchNamed(const std::string& name);
    void applyRequestedPatch();

    int currentPatch() const { return mCurrentPatch.load(std::memory_order_relaxed); }
    std::string currentPatchName() const;

    float maxRenderMs() const { return mMaxRenderMs; }
    void resetProfile() { mMaxRenderMs = 0.0f; }

private:
    void applyParam(int index, float value);   // render thread only
    void pushParam(int index, float value);    // any thread

    // Owned through an atomic pointer because it is published from the loader
    // thread while the render thread may already be running this node. It is
    // written exactly once and never replaced, so an acquire load is enough.
    std::atomic<SurgeSynthesizer*> mSynth{nullptr};
    struct Layer;
    std::unique_ptr<Layer> mLayer;

    double mRate = 44100.0;
    float mParams[kNumParams] = {};
    std::atomic<int> mCurrentPatch{-1};
    std::string mCurrentName;      // guarded by mNameLock
    std::string mWantedName;       // guarded by mNameLock
    mutable std::mutex mNameLock;
    // The patch list and categories: read from the UI thread (names, counts),
    // rebuilt by refreshPatches(). Recursive: loadPatch() reads names too.
    mutable std::recursive_mutex mListLock;

    // Surge's loadPatch() opens and parses a file on the calling thread. Same
    // handshake as SfizzNode: the loader raises mLoadRequested and waits for the
    // render thread to acknowledge by parking itself and emitting silence.
    std::atomic<bool> mLoadRequested{false};
    std::atomic<bool> mRenderParked{false};

    // Surge renders BLOCK_SIZE frames at a time; mOffset carries the remainder
    // across calls whose length is not a multiple of it.
    int mOffset = 0;   // == blockSize means "nothing buffered"

    ParamQueue<256> mQueue;
    // Filled and drained by the render thread alone (setParamRealtime).
    ParamQueue<64> mRtQueue;

    float mMaxRenderMs = 0.0f;

    // Peak of the last rendered block, taken before this node applies its own
    // level: see Node::lastRenderPeak. Render thread only.
    float mLastPeak = 0.0f;

    // The output level, ramped rather than stepped: see SmoothedGain.
    SmoothedGain mGain;
};

}  // namespace bagosynths
