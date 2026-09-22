#include "SurgeNode.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <thread>

#include <android/log.h>

#include "SurgeSynthesizer.h"
#include "SurgeStorage.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "bos-surge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "bos-surge", __VA_ARGS__)

namespace {
inline double nowSec() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}

const char* const kParamNames[] = {
    "Level",
    "Macro 1", "Macro 2", "Macro 3", "Macro 4",
    "Macro 5", "Macro 6", "Macro 7", "Macro 8",
};
static_assert(sizeof(kParamNames) / sizeof(kParamNames[0]) ==
                      static_cast<size_t>(bagosynths::SurgeNode::kNumParams),
              "parameter name table out of step with the enum");
}  // namespace

namespace bagosynths {

// Surge's host interface. Two methods, both of which exist so that a plugin
// editor can follow parameter changes it did not itself make. There is no
// editor here, so both are empty -- and that is the whole of the plugin layer.
struct SurgeNode::Layer : public SurgeSynthesizer::PluginLayer {
    void surgeParameterUpdated(const SurgeSynthesizer::ID&, float) override {}
    void surgeMacroUpdated(long, float) override {}
};

SurgeNode::SurgeNode() : mLayer(std::make_unique<Layer>()) {
    mParams[kLevel] = 1.0f;
    for (int i = 0; i < kNumMacros; ++i) mParams[kMacro1 + i] = 0.5f;
    mOffset = BLOCK_SIZE;   // nothing buffered yet
}

SurgeNode::~SurgeNode() {
    delete mSynth.exchange(nullptr, std::memory_order_acq_rel);
}

void SurgeNode::prepare(double sampleRate, int maxFrames) {
    mRate = sampleRate;
    // Start AT the current level, so a graph recompile does not fade the
    // instrument in from silence.
    mGain.prepare(sampleRate, mParams[kLevel]);

    // If the engine is already up and the rate genuinely changed, it has to be
    // told -- and setSamplerate rebuilds internal tables, so park first.
    SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire);
    if (s != nullptr && std::abs(s->storage.samplerate - sampleRate) > 0.5) {
        mLoadRequested.store(true, std::memory_order_release);
        for (int i = 0; i < 500 && !mRenderParked.load(std::memory_order_acquire); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        s->setSamplerate(static_cast<float>(sampleRate));
        mLoadRequested.store(false, std::memory_order_release);
    }
}

// ----------------------------------------------------------------- lifecycle

bool SurgeNode::init(const std::string& dataPath, const std::string& homePath) {
    if (mSynth.load(std::memory_order_acquire) != nullptr) return true;

    // SurgeStorage reaches for HOME through sst-plugininfra, whose homePath()
    // THROWS std::runtime_error when it is unset or empty -- and Android makes
    // no promise about HOME. It is set here rather than upstream being patched:
    // we own this process, and an environment variable is a far smaller change
    // than a fork. XDG_* likewise, so nothing goes looking in /usr or ~/.local.
    setenv("HOME", homePath.c_str(), 1);
    setenv("XDG_DATA_HOME", homePath.c_str(), 1);
    setenv("XDG_CONFIG_HOME", homePath.c_str(), 1);

    const double t0 = nowSec();
    SurgeSynthesizer* s = nullptr;
    try {
        s = new SurgeSynthesizer(mLayer.get(), dataPath);
    } catch (const std::exception& e) {
        LOGE("Surge construction threw: %s", e.what());
        return false;
    } catch (...) {
        LOGE("Surge construction threw a non-std exception");
        return false;
    }

    s->setSamplerate(static_cast<float>(mRate));
    s->time_data.tempo = 120;
    s->time_data.ppqPos = 0;

    // Push our defaults in before the render thread can ever see the engine.
    for (int i = 0; i < kNumMacros; ++i) {
        s->setMacroParameter01(i, std::clamp(mParams[kMacro1 + i], 0.0f, 1.0f));
    }

    mSynth.store(s, std::memory_order_release);

    // Whatever a restored patch asked for before the library existed.
    applyRequestedPatch();

    LOGI("Surge up in %.1f ms: %zu patches, %zu wavetables, data=%s",
         (nowSec() - t0) * 1000.0, s->storage.patch_list.size(), s->storage.wt_list.size(),
         s->storage.datapath.u8string().c_str());
    if (s->storage.patch_list.empty()) {
        LOGE("no Surge patches found under %s -- running on the init patch only",
             dataPath.c_str());
    }
    return true;
}

// -------------------------------------------------------------------- patches

int SurgeNode::patchCount() const {
    SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire);
    return s ? static_cast<int>(s->storage.patch_list.size()) : 0;
}

std::string SurgeNode::patchDisplayName(int index) const {
    SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire);
    if (!s || index < 0 || index >= static_cast<int>(s->storage.patch_list.size())) return {};
    return s->storage.patch_list[index].name;
}

std::string SurgeNode::macroName(int index) const {
    if (index < 0 || index >= kNumMacros) return {};
    SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr) return {};

    // Fixed-size char arrays, not std::string, and not guaranteed terminated --
    // so bound the read by the field size rather than trusting a NUL.
    const char* label = s->storage.getPatch().CustomControllerLabel[index];
    std::string name(label, strnlen(label, CUSTOM_CONTROLLER_LABEL_SIZE));

    // "-" is Surge's own "unnamed", set in SurgePatch::init_default_values and
    // tested for exactly this way in ModulationSource.cpp -- so use its test
    // rather than inventing one. Reported as absent, so the UI can fall back.
    if (name.empty() || name == "-") return {};
    return name;
}

std::string SurgeNode::patchName(int index) const {
    SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire);
    if (!s || index < 0 || index >= static_cast<int>(s->storage.patch_list.size())) return {};
    const auto& p = s->storage.patch_list[index];
    if (p.category >= 0 && p.category < static_cast<int>(s->storage.patch_category.size())) {
        return s->storage.patch_category[p.category].name + "/" + p.name;
    }
    return p.name;
}

std::string SurgeNode::currentPatchName() const {
    std::lock_guard<std::mutex> lock(mNameLock);
    return mCurrentName;
}

void SurgeNode::requestPatchNamed(const std::string& name) {
    std::lock_guard<std::mutex> lock(mNameLock);
    mWantedName = name;
}

void SurgeNode::applyRequestedPatch() {
    std::string wanted;
    {
        std::lock_guard<std::mutex> lock(mNameLock);
        wanted.swap(mWantedName);
    }
    if (!wanted.empty()) loadPatchNamed(wanted);
}

bool SurgeNode::loadPatch(int index) {
    SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire);
    if (!s || s->storage.patch_list.empty()) return false;
    if (index < 0 || index >= static_cast<int>(s->storage.patch_list.size())) return false;

    // loadPatch() opens and parses a file on the calling thread -- 2.8 ms
    // typically, 63.8 ms for the worst factory patch, which is twenty-two render
    // blocks. So the render thread is parked first, exactly as sample loading
    // parks it in SfizzNode. Only this node falls silent; the piano and the
    // organ keep playing straight through the parked interval.
    mLoadRequested.store(true, std::memory_order_release);
    for (int i = 0; i < 2000 && !mRenderParked.load(std::memory_order_acquire); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    const double t0 = nowSec();
    s->loadPatch(index);
    const double ms = (nowSec() - t0) * 1000.0;

    // A patch brings its own macro values. Ours would otherwise silently
    // overwrite what the patch author chose, so they are read back instead.
    for (int i = 0; i < kNumMacros; ++i) {
        mParams[kMacro1 + i] = s->getMacroParameter01(i);
    }

    mLoadRequested.store(false, std::memory_order_release);

    mCurrentPatch.store(index, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(mNameLock);
        mCurrentName = patchName(index);
        // A deliberate choice wins over a restore that has not landed yet.
        mWantedName.clear();
    }
    LOGI("Surge patch %d loaded in %.1f ms: %s", index, ms, currentPatchName().c_str());
    return true;
}

bool SurgeNode::loadPatchNamed(const std::string& name) {
    SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire);
    if (!s || name.empty()) return false;
    const int n = static_cast<int>(s->storage.patch_list.size());
    for (int i = 0; i < n; ++i) {
        if (patchName(i) == name) return loadPatch(i);
    }
    // Fall back to matching the bare name, so a patch that moved category still
    // comes back rather than silently reverting to the init patch.
    for (int i = 0; i < n; ++i) {
        if (s->storage.patch_list[i].name == name) return loadPatch(i);
    }
    LOGE("Surge patch '%s' is not in this library; keeping the current one", name.c_str());
    return false;
}

// ---------------------------------------------------------------- parameters

void SurgeNode::pushParam(int index, float value) {
    mQueue.push(index, value);
}

void SurgeNode::setParam(int index, float value) {
    if (index < 0 || index >= kNumParams) return;
    mParams[index] = value;
    if (index == kLevel) return;   // read directly in process(); nothing to queue
    if (!ready()) return;          // init() will apply it
    pushParam(index, value);
}

float SurgeNode::getParam(int index) const {
    if (index < 0 || index >= kNumParams) return 0.0f;
    return mParams[index];
}

const char* SurgeNode::paramName(int index) const {
    if (index < 0 || index >= kNumParams) return "";
    return kParamNames[index];
}

void SurgeNode::applyParam(int index, float value) {
    SurgeSynthesizer* s = mSynth.load(std::memory_order_relaxed);
    if (!s) return;
    if (index >= kMacro1 && index < kMacro1 + kNumMacros) {
        s->setMacroParameter01(index - kMacro1, std::clamp(value, 0.0f, 1.0f));
    }
}

// ------------------------------------------------------------------- process

// Both, as the CC handling does: allNotesOff releases, allSoundOff cuts the
// tails too -- which is what switching parts away wants.
void SurgeNode::allNotesOff() {
    if (SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire)) {
        s->allNotesOff();
        s->allSoundOff();
    }
}

void SurgeNode::process(const ProcessArgs& args) {
    if (args.numAudioOut < 1) return;
    const AudioPort& out = args.audioOut[0];
    float* outL = out.channel(0);
    float* outR = out.width > 1 ? out.channel(1) : out.channel(0);

    const auto silence = [&] {
        std::fill_n(outL, args.numFrames, 0.0f);
        if (out.width > 1) std::fill_n(outR, args.numFrames, 0.0f);
    };

    SurgeSynthesizer* s = mSynth.load(std::memory_order_acquire);
    if (s == nullptr) {   // not built yet
        silence();
        return;
    }

    // Park while a patch is being loaded. A few milliseconds of silence from
    // this one node beats racing Surge's patch parser.
    if (mLoadRequested.load(std::memory_order_acquire)) {
        mRenderParked.store(true, std::memory_order_release);
        silence();
        mOffset = BLOCK_SIZE;   // whatever was buffered belongs to the old patch
        return;
    }
    mRenderParked.store(false, std::memory_order_release);

    const double t0 = nowSec();

    // Parameter changes land on a block boundary, never halfway through one.
    {
        mQueue.drain([this](int index, float value) {
            applyParam(index, value);
        });
    }

    int written = 0;
    auto renderTo = [&](int upto) {
        while (written < upto) {
            if (mOffset >= BLOCK_SIZE) {
                mOffset = 0;
                s->process();
            }
            const int n = std::min(upto - written, BLOCK_SIZE - mOffset);
            std::memcpy(outL + written, &s->output[0][mOffset],
                        static_cast<size_t>(n) * sizeof(float));
            std::memcpy(outR + written, &s->output[1][mOffset],
                        static_cast<size_t>(n) * sizeof(float));
            written += n;
            mOffset += n;
        }
    };

    // MIDI is applied at its own frame position. With BLOCK_SIZE 32 the timing
    // resolution here is 0.73 ms -- four times finer than the organ's fragment.
    if (args.numMidiIn > 0 && args.midiIn[0] != nullptr) {
        const MidiBuffer& midi = *args.midiIn[0];
        for (int i = 0; i < midi.size(); ++i) {
            const MidiEvent& e = midi[i];
            renderTo(std::min(static_cast<int>(e.frame), args.numFrames));

            const auto chan = static_cast<char>(e.status & 0x0f);
            switch (e.status & 0xf0) {
                case 0x80:
                    s->releaseNote(chan, static_cast<char>(e.data1),
                                   static_cast<char>(e.data2));
                    break;
                case 0x90:
                    if (e.data2 == 0) {
                        s->releaseNote(chan, static_cast<char>(e.data1), 0);
                    } else {
                        s->playNote(chan, static_cast<char>(e.data1),
                                    static_cast<char>(e.data2), 0);
                    }
                    break;
                case 0xA0:
                    s->polyAftertouch(chan, e.data1, e.data2);
                    break;
                case 0xB0:
                    // Panic has to actually work, so CC 120 and 123 are handled
                    // here rather than passed through as ordinary controllers.
                    // A stuck synth note is not something anyone can wait out
                    // on stage.
                    if (e.data1 == 123) {
                        s->allNotesOff();
                    } else if (e.data1 == 120) {
                        s->allSoundOff();
                    } else {
                        s->channelController(chan, e.data1, e.data2);
                    }
                    break;
                case 0xC0:
                    // Program change is dropped on purpose, exactly as the organ
                    // drops it: the CP-88 sends one whenever its own voice is
                    // changed, and letting that through would swap the Surge
                    // patch out from under the player mid-set.
                    break;
                case 0xD0:
                    s->channelAftertouch(chan, e.data1);
                    break;
                case 0xE0:
                    s->pitchBend(chan, (static_cast<int>(e.data2) << 7 | e.data1) - 8192);
                    break;
                default:
                    break;
            }
        }
    }
    renderTo(args.numFrames);

    // Before the level, so the gate can see Surge's own effects decay.
    mLastPeak = blockPeak(outR, args.numFrames, blockPeak(outL, args.numFrames));

    mGain.apply(outL, outR, args.numFrames, mParams[kLevel]);

    const auto ms = static_cast<float>((nowSec() - t0) * 1000.0);
    if (ms > mMaxRenderMs) mMaxRenderMs = ms;
}

}  // namespace bagosynths
