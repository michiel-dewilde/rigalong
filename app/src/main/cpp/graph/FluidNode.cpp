#include "FluidNode.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <thread>

#include <android/log.h>

#include <fluidsynth.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "bos-fluid", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "bos-fluid", __VA_ARGS__)

namespace {
inline double nowSec() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}

const char* const kParamNames[] = {
    "Level", "Reverb", "Chorus", "Transpose", "Bend Range",
};
static_assert(sizeof(kParamNames) / sizeof(kParamNames[0]) ==
                      static_cast<size_t>(bagosynths::FluidNode::kNumParams),
              "parameter names and the Param enum have drifted apart");

// One channel, not sixteen. A SoundFont is a General MIDI bank and FluidSynth
// is built to be a 16-part multitimbral module, but the CP-88 is one keyboard
// sending on one channel, and layering here is what the graph is for -- a
// second FluidNode, not a second channel inside this one. Everything therefore
// goes to channel 0, and the other fifteen exist only because FluidSynth
// allocates them.
constexpr int kChannel = 0;

// FluidSynth's own default is 256, which is far more than a stage instrument
// needs and costs memory per voice. 128 is still more than the sustain pedal
// will ever find.
constexpr int kPolyphony = 128;

bool hasSoundFontExtension(const std::string& name) {
    const size_t dot = name.rfind('.');
    if (dot == std::string::npos) return false;
    std::string ext = name.substr(dot + 1);
    for (char& c : ext) c = static_cast<char>(tolower(c));
    return ext == "sf2" || ext == "sf3";
}

// The FILE NAME, extension included -- not the stem.
//
// The stem is not unique: the same font routinely ships as both .sf2 and .sf3,
// and FluidSynth's own test font does exactly that. Two entries called
// "VintageDreamsWaves-v2" would be indistinguishable in the browser, and
// loadSoundFontNamed() would always return the first of them, so a saved patch
// naming the SF3 would silently load the SF2. Keeping the extension makes the
// name unique AND says which format it is, which is worth seeing anyway.
std::string fileNameOf(const std::string& path) {
    const size_t slash = path.find_last_of("/\\");
    return (slash == std::string::npos) ? path : path.substr(slash + 1);
}
}  // namespace

namespace bagosynths {

FluidNode::FluidNode() {
    mParams[kLevel] = 1.0f;
    mParams[kReverb] = 0.0f;
    mParams[kChorus] = 0.0f;
    mParams[kTranspose] = 0.0f;
    mParams[kBendRange] = 2.0f;
}

FluidNode::~FluidNode() {
    fluid_synth_t* s = mSynth.exchange(nullptr, std::memory_order_acq_rel);
    if (s != nullptr) delete_fluid_synth(s);
    if (mSettings != nullptr) delete_fluid_settings(mSettings);
}

const char* FluidNode::paramName(int index) const {
    if (index < 0 || index >= kNumParams) return "";
    return kParamNames[index];
}

void FluidNode::prepare(double sampleRate, int maxFrames) {
    const double rate = sampleRate > 0 ? sampleRate : 44100.0;
    // Start AT the current level, so a graph recompile does not fade the
    // instrument in from silence.
    mGain.prepare(rate, mParams[kLevel]);

    // The rate reaches FluidSynth at init() and, before this, never again --
    // so a synth built at 44100 kept rendering as if it were at 44100 after the
    // output moved to a 48000 device, and played 8,8 % sharp. Reported
    // 2026-09-21 together with the organ, which had the same bug for the same
    // reason: both are built once, asynchronously, while sfizz takes
    // setSampleRate() on every prepare and was therefore always right.
    if (fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
        s != nullptr && std::abs(mRate - rate) > 0.5) {
        LOGI("fluid: sample rate %.0f -> %.0f", mRate, rate);
        fluid_synth_set_sample_rate(s, static_cast<float>(rate));
    }

    mRate = rate;
    // Only the scratch buffer is allocated here. Building the synth reads
    // settings and allocates voices, which is cheap but still belongs on the
    // loader thread with the SoundFont scan -- see AudioEngine::initFluidAsync.
    mScratchR.assign(static_cast<size_t>(std::max(maxFrames, 1)), 0.0f);
}

// ----------------------------------------------------------------- lifecycle

bool FluidNode::init(double sampleRate) {
    if (mSynth.load(std::memory_order_acquire) != nullptr) return true;

    mRate = sampleRate > 0 ? sampleRate : mRate;

    mSettings = new_fluid_settings();
    if (mSettings == nullptr) {
        LOGE("new_fluid_settings failed");
        return false;
    }

    fluid_settings_setnum(mSettings, "synth.sample-rate", mRate);
    fluid_settings_setint(mSettings, "synth.polyphony", kPolyphony);
    fluid_settings_setint(mSettings, "synth.midi-channels", 16);
    fluid_settings_setint(mSettings, "synth.cpu-cores", 1);

    // FluidSynth's gain default is 0.2, which is quiet enough to look broken
    // next to the other four instruments. 1.0 here and balance with the fader,
    // which is the same rule the rest of the rig follows.
    fluid_settings_setnum(mSettings, "synth.gain", 1.0);

    // Its reverb and chorus are off by default here because the graph already
    // has a reverb and a limiter downstream. They stay reachable as parameters
    // because a GM font is often voiced expecting them.
    fluid_settings_setint(mSettings, "synth.reverb.active", 0);
    fluid_settings_setint(mSettings, "synth.chorus.active", 0);

    fluid_synth_t* s = new_fluid_synth(mSettings);
    if (s == nullptr) {
        LOGE("new_fluid_synth failed");
        delete_fluid_settings(mSettings);
        mSettings = nullptr;
        return false;
    }

    mSynth.store(s, std::memory_order_release);
    mReady.store(true, std::memory_order_release);
    LOGI("FluidSynth up: %s, %d voices, %.0f Hz",
         fluid_version_str(), kPolyphony, mRate);
    return true;
}

// -------------------------------------------------------------- soundfonts

int FluidNode::scanSoundFonts(const std::vector<std::string>& dirs) {
    std::vector<std::string> paths, names;

    for (const std::string& dir : dirs) {
        DIR* d = opendir(dir.c_str());
        if (d == nullptr) continue;
        while (dirent* e = readdir(d)) {
            const std::string name = e->d_name;
            if (name == "." || name == "..") continue;
            if (!hasSoundFontExtension(name)) continue;
            paths.push_back(dir + "/" + name);
            names.push_back(fileNameOf(name));
        }
        closedir(d);
    }

    // Stable order, so that "the third font" means the same thing twice and a
    // saved index would at least be reproducible. Names are what get saved.
    std::vector<size_t> order(paths.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(),
              [&](size_t a, size_t b) { return names[a] < names[b]; });

    {
        std::lock_guard<std::mutex> lock(mLock);
        mFontPaths.clear();
        mFontNames.clear();
        for (size_t i : order) {
            mFontPaths.push_back(paths[i]);
            mFontNames.push_back(names[i]);
        }
    }
    LOGI("SoundFonts found: %zu", paths.size());
    return static_cast<int>(paths.size());
}

int FluidNode::soundFontCount() const {
    std::lock_guard<std::mutex> lock(mLock);
    return static_cast<int>(mFontPaths.size());
}

std::string FluidNode::soundFontName(int index) const {
    std::lock_guard<std::mutex> lock(mLock);
    if (index < 0 || index >= static_cast<int>(mFontNames.size())) return {};
    return mFontNames[index];
}

std::string FluidNode::currentSoundFontName() const {
    std::lock_guard<std::mutex> lock(mLock);
    return mCurrentFont;
}

bool FluidNode::loadSoundFont(int index) {
    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr) return false;

    std::string path, name;
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (index < 0 || index >= static_cast<int>(mFontPaths.size())) return false;
        path = mFontPaths[index];
        name = mFontNames[index];
    }

    // Megabytes of file IO, and for an SF3 a Vorbis decode of every sample --
    // hundreds of milliseconds, which is hundreds of render blocks. So the
    // render thread is parked first, exactly as sfizz and Surge park it. Only
    // this node falls silent; the piano and the organ play straight through.
    mLoadRequested.store(true, std::memory_order_release);
    for (int i = 0; i < 2000 && !mRenderParked.load(std::memory_order_acquire); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    const double t0 = nowSec();
    if (mSoundFontId >= 0) {
        fluid_synth_sfunload(s, mSoundFontId, 1);
        mSoundFontId = -1;
    }
    const int id = fluid_synth_sfload(s, path.c_str(), 1);
    const double ms = (nowSec() - t0) * 1000.0;

    mLoadRequested.store(false, std::memory_order_release);

    if (id == FLUID_FAILED) {
        LOGE("failed to load SoundFont %s", path.c_str());
        return false;
    }
    mSoundFontId = id;

    {
        std::lock_guard<std::mutex> lock(mLock);
        mCurrentFont = name;
        mWantedFont.clear();   // a deliberate choice beats a restore in flight
    }

    refreshPresets();

    // A font brings its own presets, so the previous selection is meaningless.
    // Land on the first one rather than on whatever index happened to survive.
    mCurrentPreset.store(-1, std::memory_order_relaxed);

    std::string wanted;
    {
        std::lock_guard<std::mutex> lock(mLock);
        wanted = mWantedPreset;
    }
    if (!wanted.empty() && selectPresetNamed(wanted)) {
        std::lock_guard<std::mutex> lock(mLock);
        mWantedPreset.clear();
    } else {
        selectPreset(0);
    }

    LOGI("SoundFont loaded in %.0f ms: %s (%d presets)",
         ms, name.c_str(), presetCount());
    return true;
}

bool FluidNode::loadSoundFontNamed(const std::string& name) {
    int index = -1;
    {
        std::lock_guard<std::mutex> lock(mLock);
        for (size_t i = 0; i < mFontNames.size(); ++i) {
            if (mFontNames[i] == name) { index = static_cast<int>(i); break; }
        }
    }
    if (index < 0) return false;
    return loadSoundFont(index);
}

void FluidNode::requestSoundFontNamed(const std::string& name) {
    std::lock_guard<std::mutex> lock(mLock);
    mWantedFont = name;
}

void FluidNode::applyRequestedSoundFont() {
    std::string wanted;
    {
        std::lock_guard<std::mutex> lock(mLock);
        wanted = mWantedFont;
    }
    if (wanted.empty()) return;
    if (loadSoundFontNamed(wanted)) {
        std::lock_guard<std::mutex> lock(mLock);
        mWantedFont.clear();
    }
}

// ------------------------------------------------------------------ presets

void FluidNode::refreshPresets() {
    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    std::vector<Preset> presets;

    if (s != nullptr && mSoundFontId >= 0) {
        fluid_sfont_t* font = fluid_synth_get_sfont_by_id(s, mSoundFontId);
        if (font != nullptr) {
            fluid_sfont_iteration_start(font);
            while (fluid_preset_t* p = fluid_sfont_iteration_next(font)) {
                Preset entry;
                entry.bank = fluid_preset_get_banknum(p);
                entry.program = fluid_preset_get_num(p);
                const char* n = fluid_preset_get_name(p);
                entry.name = (n != nullptr) ? n : "";

                char label[160];
                std::snprintf(label, sizeof(label), "%d:%03d %s",
                              entry.bank, entry.program, entry.name.c_str());
                entry.label = label;
                presets.push_back(std::move(entry));
            }
        }
    }

    // SoundFonts do not promise any order, and a GM font that lists its presets
    // backwards is not a broken font. Sorting by bank then program is what makes
    // the browser read as General MIDI does.
    std::sort(presets.begin(), presets.end(), [](const Preset& a, const Preset& b) {
        if (a.bank != b.bank) return a.bank < b.bank;
        return a.program < b.program;
    });

    std::lock_guard<std::mutex> lock(mLock);
    mPresets = std::move(presets);
}

int FluidNode::presetCount() const {
    std::lock_guard<std::mutex> lock(mLock);
    return static_cast<int>(mPresets.size());
}

std::string FluidNode::presetName(int index) const {
    std::lock_guard<std::mutex> lock(mLock);
    if (index < 0 || index >= static_cast<int>(mPresets.size())) return {};
    return mPresets[index].label;
}

std::string FluidNode::currentPresetName() const {
    const int i = mCurrentPreset.load(std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(mLock);
    if (i < 0 || i >= static_cast<int>(mPresets.size())) return {};
    return mPresets[i].label;
}

bool FluidNode::selectPreset(int index) {
    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr || mSoundFontId < 0) return false;

    int bank = 0, program = 0;
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (index < 0 || index >= static_cast<int>(mPresets.size())) return false;
        bank = mPresets[index].bank;
        program = mPresets[index].program;
    }

    // Cheap: it looks the preset up in memory and swaps a pointer. No file is
    // touched, so unlike the font load this needs no park.
    if (fluid_synth_program_select(s, kChannel, mSoundFontId, bank, program) == FLUID_FAILED) {
        return false;
    }
    mCurrentPreset.store(index, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(mLock);
        mWantedPreset.clear();
    }
    return true;
}

bool FluidNode::selectPresetNamed(const std::string& label) {
    int index = -1;
    {
        std::lock_guard<std::mutex> lock(mLock);
        for (size_t i = 0; i < mPresets.size(); ++i) {
            if (mPresets[i].label == label) { index = static_cast<int>(i); break; }
        }
    }
    if (index < 0) return false;
    return selectPreset(index);
}

void FluidNode::requestPresetNamed(const std::string& label) {
    std::lock_guard<std::mutex> lock(mLock);
    mWantedPreset = label;
}

// --------------------------------------------------------------------- params

void FluidNode::pushParam(int index, float value) {
    mQueue.push(index, value);
}

void FluidNode::setParam(int index, float value) {
    if (index < 0 || index >= kNumParams) return;
    mParams[index] = value;
    pushParam(index, value);
}

float FluidNode::getParam(int index) const {
    if (index < 0 || index >= kNumParams) return 0.0f;
    return mParams[index];
}

void FluidNode::applyBendRange(int semitones) {
    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr) return;
    // Per channel in the MIDI spec, so it has to go to all sixteen even though
    // only one is played: a stray channel change must not find a stale range.
    for (int ch = 0; ch < 16; ++ch) {
        fluid_synth_pitch_wheel_sens(s, ch, semitones);
    }
}

void FluidNode::applyParam(int index, float value) {
    if (index < 0 || index >= kNumParams) return;
    mParams[index] = value;

    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr) return;

    switch (index) {
        case kReverb: {
            const int on = value > 0.001f ? 1 : 0;
            fluid_synth_reverb_on(s, -1, on);
            if (on) {
                fluid_synth_cc(s, kChannel, 91, static_cast<int>(value * 127.0f + 0.5f));
            }
            break;
        }
        case kChorus: {
            const int on = value > 0.001f ? 1 : 0;
            fluid_synth_chorus_on(s, -1, on);
            if (on) {
                fluid_synth_cc(s, kChannel, 93, static_cast<int>(value * 127.0f + 0.5f));
            }
            break;
        }
        case kBendRange:
            applyBendRange(std::clamp(static_cast<int>(value + 0.5f), 0, 24));
            break;
        default:
            break;
    }
}

// --------------------------------------------------------------------- render

int FluidNode::activeVoices() const {
    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    return s != nullptr ? fluid_synth_get_active_voice_count(s) : 0;
}

void FluidNode::panic() {
    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr) return;
    for (int ch = 0; ch < 16; ++ch) {
        fluid_synth_all_notes_off(s, ch);
    }
}

void FluidNode::allNotesOff() {
    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr) return;
    for (int ch = 0; ch < 16; ++ch) {
        // SOUNDS off, not NOTES off: the latter releases and leaves a tail
        // this node will not be running to finish.
        fluid_synth_all_sounds_off(s, ch);
    }
}

void FluidNode::silence(const ProcessArgs& args) {
    const AudioPort& out = args.audioOut[0];
    std::fill_n(out.channel(0), args.numFrames, 0.0f);
    if (out.width > 1) std::fill_n(out.channel(1), args.numFrames, 0.0f);
    mLastPeak = 0.0f;
}

void FluidNode::process(const ProcessArgs& args) {
    if (args.numAudioOut < 1) return;
    const AudioPort& out = args.audioOut[0];

    fluid_synth_t* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr) {          // not built yet
        silence(args);
        return;
    }

    // Park while a SoundFont is being opened. A few hundred milliseconds of
    // silence from this one node beats racing FluidSynth's sample loader.
    if (mLoadRequested.load(std::memory_order_acquire)) {
        mRenderParked.store(true, std::memory_order_release);
        silence(args);
        return;
    }
    mRenderParked.store(false, std::memory_order_release);

    const double t0 = nowSec();

    {
        mQueue.drain([this](int index, float value) {
            applyParam(index, value);
        });
    }

    // MIDI first, then one render call.
    //
    // This node does NOT split the block at each event, as the other four do.
    // FluidSynth queues note events to its own rvoice mixer and starts them at
    // its internal 64-frame boundary regardless of when they were handed over,
    // so splitting would cost four extra calls to buy a precision the engine
    // does not offer. The quantisation is 64 frames -- 1.45 ms, under half a
    // render block, and below what the 89-frame USB burst already imposes.
    const int transpose = std::clamp(static_cast<int>(mParams[kTranspose] + 0.5f), -24, 24);
    if (args.numMidiIn > 0 && args.midiIn[0] != nullptr) {
        const MidiBuffer& midi = *args.midiIn[0];
        for (int i = 0; i < midi.size(); ++i) {
            const MidiEvent& e = midi[i];
            switch (e.status & 0xf0) {
                case 0x80:
                    fluid_synth_noteoff(s, kChannel, std::clamp(e.data1 + transpose, 0, 127));
                    break;
                case 0x90:
                    if (e.data2 == 0) {
                        fluid_synth_noteoff(s, kChannel, std::clamp(e.data1 + transpose, 0, 127));
                    } else {
                        fluid_synth_noteon(s, kChannel,
                                           std::clamp(e.data1 + transpose, 0, 127), e.data2);
                    }
                    break;
                case 0xB0:
                    switch (e.data1) {
                        case 120:
                        case 123:
                            panic();
                            break;
                        // 91 and 93 are the reverb and chorus sends, and they
                        // belong to the panel here, not to the piano: letting
                        // the CP-88 write them would fight the faders.
                        case 91:
                        case 93:
                            break;
                        default:
                            fluid_synth_cc(s, kChannel, e.data1, e.data2);
                            break;
                    }
                    break;
                case 0xC0:
                    // Dropped, as everywhere else here: the CP-88 sends a
                    // program change on every voice change of its own, and
                    // letting it through would swap the preset mid-set.
                    break;
                case 0xD0:
                    fluid_synth_channel_pressure(s, kChannel, e.data1);
                    break;
                case 0xE0:
                    fluid_synth_pitch_bend(s, kChannel,
                                           (static_cast<int>(e.data2) << 7) | e.data1);
                    break;
                default:
                    break;
            }
        }
    }

    float* outL = out.channel(0);
    float* outR = out.width > 1 ? out.channel(1) : mScratchR.data();
    if (out.width <= 1 &&
        static_cast<int>(mScratchR.size()) < args.numFrames) {
        // prepare() sized this; a larger block than promised means something is
        // wrong upstream, and silence beats writing past the end of a buffer.
        silence(args);
        return;
    }

    fluid_synth_write_float(s, args.numFrames, outL, 0, 1, outR, 0, 1);

    // Before the level, so the gate can see FluidSynth's reverb and chorus
    // decay rather than the zeros the level would write.
    mLastPeak = blockPeak(outR, args.numFrames, blockPeak(outL, args.numFrames));

    mGain.apply(outL, out.width > 1 ? outR : nullptr, args.numFrames, mParams[kLevel]);

    const auto ms = static_cast<float>((nowSec() - t0) * 1000.0);
    if (ms > mMaxRenderMs) mMaxRenderMs = ms;
}

}  // namespace bagosynths
