#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <bagosynths/Graph.h>

// The DX7 as a graph node: Dexed's `msfa` engine.
//
// Why this engine. The DX7 is not a sound you can sample your way to -- the
// point of six-operator FM is that the timbre moves under the key, with
// velocity and with the envelopes, and a sampler freezes exactly that. msfa is
// the reference open implementation, it is Apache-2.0, and it was written for
// Android in the first place, which shows: no dependencies, fixed-point
// throughout, and a 64-sample block.
//
// What is linked is msfa ONLY -- not the Dexed plugin, which is GPL-3 and JUCE
// bound. Voice allocation, cartridge parsing and MIDI handling are ours; that is
// a few hundred lines and it is the part that has to fit this rig rather than a
// plugin host.
//
// Three FM cores are built and selectable, because they are the difference
// between "a DX7" and "a DX7 that sounds right":
//   0  Modern   -- msfa's own FmCore, cleaner and more accurate arithmetic
//   1  Mark I   -- models the original hardware's quirks; the authentic one
//   2  OPL      -- the cheaper Yamaha OPL series voicing
//
// Cartridges are DX7 SysEx banks: 4104 bytes, 32 voices of 128 packed bytes.
// 33 of them ship in the APK (1056 voices, 132 KB); more can be dropped into
// the external `dx7/` folder beside the instrument library.
//
// Threading, as everywhere else here: parameters cross from the UI thread
// through a lock-free ring drained at the top of each block, and a voice change
// is published through a single-slot handoff rather than by writing the live
// patch out from under the render thread.

class Dx7Note;
class Lfo;
class Controllers;
class FmCore;
class TuningState;

namespace bagosynths {

class DexedNode : public Node {
public:
    DexedNode();
    ~DexedNode() override;

    const char* typeName() const override { return "Dexed"; }

    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Midi, 1, "midi"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    enum Param {
        kLevel = 0,
        kEngine = 1,        // 0 Modern, 1 Mark I, 2 OPL
        kTranspose = 2,     // semitones, -24..24
        kBendRange = 3,     // semitones, 0..12
        kModDepth = 4,      // mod wheel -> LFO pitch depth, 0..1
        kNumParams = 5,
    };
    static constexpr int kNumEngines = 3;
    static const char* engineName(int index);

    int numParams() const override { return kNumParams; }
    void setParam(int index, float value) override;
    float getParam(int index) const override;
    const char* paramName(int index) const override;

    // ------------------------------------------------------- off the audio thread

    // Reads every `.syx` in `dirs`, in order, skipping anything that is not a
    // 32-voice DX7 bank. Safe to call with directories that do not exist.
    int loadBanks(const std::vector<std::string>& dirs);
    bool ready() const { return mReady.load(std::memory_order_acquire); }

    int voiceCount() const;
    std::string voiceName(int index) const;      // "Bank/NAME"
    std::string voiceShortName(int index) const; // "NAME"

    bool selectVoice(int index);
    bool selectVoiceNamed(const std::string& name);

    // Records a voice to select without caring whether the banks are loaded.
    //
    // A saved patch is restored during onResume, which is BEFORE the loader
    // thread has read the cartridges -- so asking for a voice by name at that
    // moment looks it up in an empty library and quietly gives up. Intent is
    // recorded here and honoured at the end of loadBanks(). Choosing a voice
    // explicitly clears it, so a restore in flight cannot overwrite a
    // deliberate choice.
    void requestVoiceNamed(const std::string& name);
    void applyRequestedVoice();
    int currentVoice() const { return mCurrentVoice.load(std::memory_order_relaxed); }
    std::string currentVoiceName() const;

    float maxRenderMs() const { return mMaxRenderMs; }
    void resetProfile() { mMaxRenderMs = 0.0f; }
    int activeVoices() const;

private:
    // A DX7 voice, unpacked: msfa wants the 155-byte edit-buffer form, not the
    // 128-byte packed form a cartridge stores.
    static constexpr int kPatchBytes = 156;
    static constexpr int kVoicesPerBank = 32;
    static constexpr int kPackedVoiceBytes = 128;

    struct Bank {
        std::string name;
        std::vector<uint8_t> packed;   // 32 * 128
        std::string voiceNames[kVoicesPerBank];
    };

    // Polyphony. The DX7 itself had 16; 32 costs little here and a stage piano's
    // sustain pedal will find the limit of 16 quickly.
    static constexpr int kMaxVoices = 32;
    struct Voice {
        std::unique_ptr<Dx7Note> note;
        int midiNote = -1;
        bool keyDown = false;
        bool sustained = false;
        bool live = false;
        uint32_t startedAt = 0;
    };

    void noteOn(int midiNote, int velocity);
    void noteOff(int midiNote);
    void releaseSustained();
    void panic();
    int chooseVoice(int midiNote);
    void applyParam(int index, float value);
    void pushParam(int index, float value);
    void takePendingVoice();     // render thread

    static void unpackVoice(const uint8_t* packed, uint8_t* out);

    std::vector<Bank> mBanks;
    mutable std::mutex mBankLock;      // guards mBanks and mCurrentName, never held by the render thread
    std::atomic<bool> mReady{false};
    std::atomic<int> mCurrentVoice{-1};
    std::string mCurrentName;
    std::string mWantedName;

    // The live patch, owned by the render thread. A voice change is staged here
    // and taken at the top of a block, so the render thread never reads a patch
    // that is halfway rewritten.
    uint8_t mPatch[kPatchBytes] = {};
    uint8_t mPendingPatch[kPatchBytes] = {};
    std::atomic<bool> mPendingValid{false};

    Voice mVoices[kMaxVoices];
    uint32_t mVoiceTick = 0;

    std::unique_ptr<Lfo> mLfo;
    std::unique_ptr<Controllers> mControllers;
    std::unique_ptr<FmCore> mCoreModern;
    std::unique_ptr<FmCore> mCoreMkI;    // EngineMkI, which derives from FmCore
    std::unique_ptr<FmCore> mCoreOpl;

    std::shared_ptr<TuningState> mTuning;

    double mRate = 44100.0;
    bool mInitialised = false;
    float mParams[kNumParams] = {};
    bool mSustain = false;

    // msfa renders 64 samples at a time; mOffset carries the remainder across
    // calls whose length is not a multiple of it.
    static constexpr int kChunk = 64;
    float mChunkBuf[kChunk] = {};
    int mOffset = kChunk;

    struct Change { int index; float value; };
    static constexpr uint32_t kQueueCapacity = 256;
    Change mQueue[kQueueCapacity] = {};
    std::atomic<uint32_t> mQueueWrite{0};
    std::atomic<uint32_t> mQueueRead{0};

    float mMaxRenderMs = 0.0f;
};

}  // namespace bagosynths
