#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Graph.h"

// SF2 and SF3 SoundFonts, as a graph node: FluidSynth.
//
// Why this engine. It is the format, more than the synthesis: a SoundFont is
// how "any General MIDI sound" is distributed, there are decades of free ones,
// and a 30 MB GM font covers the orchestral and band sounds that nothing else
// in this rig does at all. FluidSynth is the reference implementation, it is
// LGPL-2.1, and it is already the engine on half the Linux music boxes in
// existence.
//
// It needed no patch at all to build for Android, which none of the other
// engines managed. Two upstream decisions are why: as of 2.6 it has no glib
// dependency (GLib's hash table is vendored as `fluid_hash.c`), and its OS
// abstraction is C++11 threads rather than pthreads.
//
// **Every driver is compiled out.** FluidSynth is perfectly capable of opening
// its own audio device and its own MIDI port, and it must do neither here --
// the one thing allowed to touch the device is AudioEngine. What is used is
// `fluid_synth_write_float` and the MIDI entry points, and nothing else.
//
// SF3 is SF2 with Ogg Vorbis compressed samples, and upstream decodes those
// through libsndfile. A patch routes that through stb_vorbis instead: one
// public-domain file rather than libsndfile + libogg + libvorbis. See
// `cpp/fluid/fluid_glue.c`.
//
// Unlike the other four instruments, this node does NOT allocate voices
// itself. FluidSynth owns its polyphony, its channels and its SoundFont; the
// node is a MIDI adapter and a level. That is why there is no voice array here.

// FluidSynth's own typedefs rather than forward declarations of our own.
// `fluid_settings_t` is a typedef for `struct _fluid_hashtable_t`, not for a
// `_fluid_settings_t` that does not exist -- so guessing at it is a typedef
// redefinition, not a forward declaration. types.h is a few dozen lines of
// opaque handles and pulls in nothing.
#include <fluidsynth/types.h>

namespace bagosynths {

class FluidNode : public Node {
public:
    FluidNode();
    ~FluidNode() override;

    const char* typeName() const override { return "FluidSynth"; }

    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Midi, 1, "midi"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    enum Param {
        kLevel = 0,
        kReverb = 1,      // FluidSynth's own reverb send, 0..1
        kChorus = 2,      // its chorus send, 0..1
        kTranspose = 3,   // semitones, -24..24
        kBendRange = 4,   // semitones, 0..12
        kNumParams = 5,
    };

    int numParams() const override { return kNumParams; }
    void setParam(int index, float value) override;
    float getParam(int index) const override;
    const char* paramName(int index) const override;

    // ------------------------------------------------------- off the audio thread

    // Builds the synth. Cheap -- it allocates voices and buffers but reads no
    // files -- yet still not something to do on the audio thread.
    bool init(double sampleRate);
    bool ready() const { return mReady.load(std::memory_order_acquire); }

    // Scans `dirs` for .sf2/.sf3 files. No file is opened: this is a directory
    // listing, and which font is LOADED is a separate and much slower step.
    int scanSoundFonts(const std::vector<std::string>& dirs);
    int soundFontCount() const;
    std::string soundFontName(int index) const;     // the file's stem
    std::string currentSoundFontName() const;

    // Opens a SoundFont. Megabytes of file IO, and for SF3 a Vorbis decode of
    // every sample, so it parks the render thread exactly as sfizz and Surge do.
    bool loadSoundFont(int index);
    bool loadSoundFontNamed(const std::string& name);

    // Records intent without caring whether the scan has happened yet. A saved
    // patch is restored during onResume, BEFORE the loader has listed anything
    // -- see FINDINGS 5b, which is a bug this project has already had twice.
    void requestSoundFontNamed(const std::string& name);
    void applyRequestedSoundFont();

    // Presets within the loaded font, as "bank:prog NAME". A GM font has 128 of
    // them plus a drum bank, and picking one by name is the whole point.
    int presetCount() const;
    std::string presetName(int index) const;
    int currentPreset() const { return mCurrentPreset.load(std::memory_order_relaxed); }
    std::string currentPresetName() const;
    bool selectPreset(int index);
    bool selectPresetNamed(const std::string& name);
    void requestPresetNamed(const std::string& name);

    float maxRenderMs() const { return mMaxRenderMs; }
    void resetProfile() { mMaxRenderMs = 0.0f; }
    int activeVoices() const;

private:
    struct Preset {
        int bank = 0;
        int program = 0;
        std::string name;
        std::string label;     // "bank:prog NAME", the stable address
    };

    void applyParam(int index, float value);
    void pushParam(int index, float value);
    void refreshPresets();     // off the audio thread, after a font loads
    void panic();
    void silence(const ProcessArgs& args);

    std::atomic<fluid_synth_t*> mSynth{nullptr};
    fluid_settings_t* mSettings = nullptr;
    int mSoundFontId = -1;

    std::atomic<bool> mReady{false};
    std::atomic<int> mCurrentPreset{-1};

    // Font list and preset list. Guarded by a mutex that the render thread
    // never takes -- it reads neither.
    mutable std::mutex mLock;
    std::vector<std::string> mFontPaths;
    std::vector<std::string> mFontNames;
    std::vector<Preset> mPresets;
    std::string mCurrentFont;
    std::string mWantedFont;
    std::string mWantedPreset;

    // The same handshake as SfizzNode and SurgeNode: the loader raises
    // mLoadRequested and waits for the render thread to park itself and emit
    // silence. Only this node goes quiet; everything else plays through.
    std::atomic<bool> mLoadRequested{false};
    std::atomic<bool> mRenderParked{false};

    // Pitch bend range is per channel in the MIDI spec and FluidSynth
    // implements it as an RPN, so a change has to be pushed to all 16.
    void applyBendRange(int semitones);

    double mRate = 44100.0;
    float mParams[kNumParams] = {};
    float mTransposeApplied = 0.0f;

    // FluidSynth wants separate left and right buffers; the graph hands us a
    // channel-major block, so these exist only when the output pin is mono and
    // the two would otherwise alias.
    std::vector<float> mScratchR;

    struct Change { int index; float value; };
    static constexpr uint32_t kQueueCapacity = 256;
    Change mQueue[kQueueCapacity] = {};
    std::atomic<uint32_t> mQueueWrite{0};
    std::atomic<uint32_t> mQueueRead{0};

    float mMaxRenderMs = 0.0f;
};

}  // namespace bagosynths
