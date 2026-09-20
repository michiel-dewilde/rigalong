#include "PlaitsNode.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>

#include <android/log.h>

// Plaits and its host library, straight out of Surge's `libs/eurorack`. That
// target is already built and already linked into this binary, because Surge's
// Twist oscillator is a plaits::Voice -- so none of this is a new dependency.
#include "plaits/dsp/voice.h"
#include "stmlib/utils/buffer_allocator.h"

#include "sst/basic-blocks/dsp/LanczosResampler.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "bos-plaits", __VA_ARGS__)

namespace {
inline double nowSec() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}

const char* const kParamNames[] = {
    "Level", "Engine", "Harmonics", "Timbre", "Morph",
    "Decay", "Colour", "Aux Mix", "Transpose", "Bend Range", "Mod Depth",
};
static_assert(sizeof(kParamNames) / sizeof(kParamNames[0]) ==
                      static_cast<size_t>(bagosynths::PlaitsNode::kNumParams),
              "parameter names and the Param enum have drifted apart");

// The order is the one Voice::Init registers the engines in, which is the order
// the hardware model knob sweeps through. Renaming them would only make the
// Plaits manual harder to follow.
const char* const kEngineNames[] = {
    "VIRTUAL ANALOG",   //  0  a pair of classic waveforms
    "WAVESHAPING",      //  1  waveshaping oscillator
    "2-OP FM",          //  2
    "GRAIN",            //  3  formant / granular
    "HARMONIC",         //  4  additive
    "WAVETABLE",        //  5
    "CHORD",            //  6
    "SPEECH",           //  7  vowels, words and numbers
    "SWARM",            //  8  granular cloud
    "NOISE",            //  9  filtered / dust
    "PARTICLE",         // 10
    "STRING",           // 11  Karplus-Strong
    "MODAL",            // 12  modal resonator
    "BASS DRUM",        // 13
    "SNARE DRUM",       // 14
    "HI-HAT",           // 15
};
static_assert(sizeof(kEngineNames) / sizeof(kEngineNames[0]) ==
                      static_cast<size_t>(bagosynths::PlaitsNode::kNumEngines),
              "engine names and the plaits registry have drifted apart");

// What the three knobs mean in each model.
//
// Taken from Surge XT's TwistOscillator (`EngineDynamicName`), which is the same
// sixteen engines in the same order and had already done this work. Surge XT is
// GPL-3-or-later, as is this project, so it combines -- recorded here because
// copying a table is still copying, and LICENSING.md would rather the question
// were answered than skipped.
//
// The order within each row is HARMONICS, TIMBRE, MORPH, AUX.
const char* const kParamLabels[][4] = {
    {"Detune",       "Square Shape",    "Saw Shape",      "Sync"},        //  0 virtual analog
    {"Waveshaper",   "Fold",            "Asymmetry",      "Variation"},   //  1 waveshaping
    {"Ratio",        "Amount",          "Feedback",       "Sub"},         //  2 2-op FM
    {"Ratio/Type",   "Formant",         "Shape",          "PD"},          //  3 grain
    {"Bump",         "Peak",            "Shape",          "Organ"},       //  4 harmonic
    {"Bank",         "Morph X",         "Morph Y",        "Lo-Fi"},       //  5 wavetable
    {"Type",         "Inversion",       "Shape",          "Root"},        //  6 chord
    {"Speak",        "Species",         "Segment",        "Raw"},         //  7 speech
    {"Pitch Random", "Grain Density",   "Grain Duration", "Sine"},        //  8 swarm
    {"Type",         "Clock Frequency", "Resonance",      "Dual Peak"},   //  9 noise
    {"Freq Random",  "Density",         "Filter Type",    "Raw"},         // 10 particle
    {"Inharmonicity","Brightness",      "Decay Time",     "Exciter"},     // 11 string
    {"Material",     "Brightness",      "Decay Time",     "Exciter"},     // 12 modal
    {"Sharpness",    "Brightness",      "Decay Time",     "Variation"},   // 13 bass drum
    {"Tone<>Noise",  "Model",           "Decay Time",     "Variation"},   // 14 snare drum
    {"Tone<>Noise",  "Low Cut",         "Decay Time",     "Variation"},   // 15 hi-hat
};
static_assert(sizeof(kParamLabels) / sizeof(kParamLabels[0]) ==
                      static_cast<size_t>(bagosynths::PlaitsNode::kNumEngines),
              "the per-engine label table and the plaits registry have drifted apart");

// The native rate, from plaits/dsp/dsp.h. A compile-time constant there, so a
// compile-time constant here: if the two ever disagree, everything is detuned.
constexpr float kPlaitsRate = 48000.0f;

// All sixteen engines share one arena per voice -- Voice::Init frees it before
// initialising each of them. 16 kB is what Surge gives it, and the largest
// engine fits well inside that.
constexpr int kArenaBytes = 16384;

// A voice is retired when it has been released AND has rendered nothing audible
// for this long. The gate alone is not enough: the low-pass gate rings on after
// the gate falls, and the drum engines are one-shots that never had one.
// -84 dB for 10 ms is below anything the limiter or the ear will find.
constexpr float kSilenceFloor = 6e-5f;
constexpr int kSilentSubBlocksToRetire = 40;   // 40 * 12 / 48000 = 10 ms

// Plaits is a MONOPHONIC module and its post-processor is scaled accordingly:
// one voice arrives at very nearly full scale (the engine out_gain is 0.8 for
// the virtual analog model, and the low-pass gate reaches 1.0 at full velocity).
// Sixteen of those summed is sixteen times too loud, which is not a limiter's
// job to fix -- measured as a flat `out 1.000 CLIPPING` on the first chord.
//
// A quarter puts a single note at about 0.2, which is where the organ sits, and
// a four-note chord at about 0.8. Deliberately NOT a divide by the voice count:
// normalising by polyphony makes an instrument duck every time a note is added,
// which is far more noticeable than the limiter catching a ten-note cluster.
constexpr float kVoiceHeadroom = 0.25f;
}  // namespace

namespace bagosynths {

// Everything that would otherwise drag the eurorack and SIMD headers into
// PlaitsNode.h.
struct PlaitsImpl {
    struct Voice {
        plaits::Voice engine;
        plaits::Patch patch{};
        plaits::Modulations mod{};
        stmlib::BufferAllocator alloc;
        char arena[kArenaBytes] = {};

        int midiNote = -1;
        float velocity = 0.0f;
        bool live = false;
        bool keyDown = false;
        bool sustained = false;
        bool gate = false;
        // One sub-block of trigger-low, so that stealing a voice whose gate is
        // still high produces a real rising edge rather than none at all. The
        // struck and plucked models fire on that edge and on nothing else.
        bool forceTriggerLow = false;
        int silentSubBlocks = 0;
        uint32_t startedAt = 0;
    };

    Voice voices[16];
    std::unique_ptr<sst::basic_blocks::dsp::LanczosResampler<128>> resampler;

    // The 48 kHz mix, one sub-block at a time.
    float subOut[12] = {};
    float subAux[12] = {};

    // Resampled output, waiting to be handed to the graph.
    std::vector<float> tmpL, tmpR;
};

PlaitsNode::PlaitsNode() : mImpl(std::make_unique<PlaitsImpl>()) {
    mParams[kLevel] = 1.0f;
    mParams[kEngine] = 0.0f;
    mParams[kHarmonics] = 0.3f;
    mParams[kTimbre] = 0.5f;
    mParams[kMorph] = 0.5f;
    mParams[kDecay] = 0.5f;
    mParams[kColour] = 0.5f;
    mParams[kAuxMix] = 0.0f;
    mParams[kTranspose] = 0.0f;
    mParams[kBendRange] = 2.0f;
    mParams[kModDepth] = 0.0f;
}

PlaitsNode::~PlaitsNode() = default;

const char* PlaitsNode::engineName(int index) {
    if (index < 0 || index >= kNumEngines) return "";
    return kEngineNames[index];
}

const char* PlaitsNode::paramLabel(int engineIndex, int which) {
    if (engineIndex < 0 || engineIndex >= kNumEngines) return "";
    if (which < 0 || which >= kNumLabelled) return "";
    return kParamLabels[engineIndex][which];
}

const char* PlaitsNode::paramName(int index) const {
    if (index < 0 || index >= kNumParams) return "";
    return kParamNames[index];
}

void PlaitsNode::prepare(double sampleRate, int maxFrames) {
    mRate = sampleRate > 0 ? sampleRate : 44100.0;

    // prepare() allocates; process() never does.
    for (auto& v : mImpl->voices) {
        v.alloc.Init(v.arena, kArenaBytes);
        v.engine.Init(&v.alloc);
        v.live = false;
        v.midiNote = -1;
        v.gate = false;
        std::memset(&v.patch, 0, sizeof(v.patch));
        std::memset(&v.mod, 0, sizeof(v.mod));
    }

    mImpl->resampler = std::make_unique<sst::basic_blocks::dsp::LanczosResampler<128>>(
            kPlaitsRate, static_cast<float>(mRate));

    const int cap = std::max(maxFrames, 128);
    mImpl->tmpL.assign(static_cast<size_t>(cap), 0.0f);
    mImpl->tmpR.assign(static_cast<size_t>(cap), 0.0f);

    mInitialised = true;
    LOGI("plaits ready: %d voices, %.0f Hz -> %.0f Hz, %zu bytes of voice state",
         kMaxVoices, kPlaitsRate, mRate,
         sizeof(PlaitsImpl::Voice) * static_cast<size_t>(kMaxVoices));
}

// --------------------------------------------------------------------- params

void PlaitsNode::pushParam(int index, float value) {
    mQueue.push(index, value);
}

void PlaitsNode::setParam(int index, float value) {
    if (index < 0 || index >= kNumParams) return;
    mParams[index] = value;      // so the UI reads back what it just wrote
    pushParam(index, value);
}

float PlaitsNode::getParam(int index) const {
    if (index < 0 || index >= kNumParams) return 0.0f;
    return mParams[index];
}

void PlaitsNode::applyParam(int index, float value) {
    if (index < 0 || index >= kNumParams) return;
    mParams[index] = value;
}

// --------------------------------------------------------------------- voices

int PlaitsNode::activeVoices() const {
    int n = 0;
    for (const auto& v : mImpl->voices) if (v.live) ++n;
    return n;
}

bool PlaitsNode::anyVoiceLive() const {
    for (const auto& v : mImpl->voices) if (v.live) return true;
    return false;
}

int PlaitsNode::chooseVoice(int midiNote) {
    // The same note again reuses its own voice, which is what a retrigger means.
    for (int i = 0; i < kMaxVoices; ++i) {
        if (mImpl->voices[i].live && mImpl->voices[i].midiNote == midiNote) return i;
    }
    for (int i = 0; i < kMaxVoices; ++i) {
        if (!mImpl->voices[i].live) return i;
    }
    // Steal the oldest released voice before touching anything still held down.
    int best = -1;
    uint32_t oldest = UINT32_MAX;
    for (int i = 0; i < kMaxVoices; ++i) {
        const auto& v = mImpl->voices[i];
        if (v.keyDown || v.sustained) continue;
        if (v.startedAt < oldest) { oldest = v.startedAt; best = i; }
    }
    if (best >= 0) return best;
    for (int i = 0; i < kMaxVoices; ++i) {
        if (mImpl->voices[i].startedAt < oldest) {
            oldest = mImpl->voices[i].startedAt;
            best = i;
        }
    }
    return best < 0 ? 0 : best;
}

void PlaitsNode::noteOn(int midiNote, int velocity) {
    const int i = chooseVoice(midiNote);
    auto& v = mImpl->voices[i];

    // If the gate is still up -- a restruck key, or a stolen voice -- Plaits
    // will not see an edge unless we hand it one.
    v.forceTriggerLow = v.gate;

    v.midiNote = midiNote;
    v.velocity = static_cast<float>(velocity) / 127.0f;
    v.live = true;
    v.keyDown = true;
    v.sustained = false;
    v.gate = true;
    v.silentSubBlocks = 0;
    v.startedAt = mVoiceTick++;
}

void PlaitsNode::noteOff(int midiNote) {
    for (auto& v : mImpl->voices) {
        if (!v.live || v.midiNote != midiNote || !v.keyDown) continue;
        v.keyDown = false;
        if (mSustain) {
            v.sustained = true;
        } else {
            v.gate = false;     // the low-pass gate takes it from here
        }
    }
}

void PlaitsNode::releaseSustained() {
    for (auto& v : mImpl->voices) {
        if (v.live && v.sustained) {
            v.sustained = false;
            v.gate = false;
        }
    }
}

void PlaitsNode::panic() {
    for (auto& v : mImpl->voices) {
        v.live = false;
        v.keyDown = false;
        v.sustained = false;
        v.gate = false;
        v.midiNote = -1;
    }
    mSustain = false;
}

// ---------------------------------------------------------------- the 48k mix

void PlaitsNode::renderSubBlock() {
    std::fill_n(mImpl->subOut, kSubBlock, 0.0f);
    std::fill_n(mImpl->subAux, kSubBlock, 0.0f);

    const int engine =
            std::clamp(static_cast<int>(mParams[kEngine] + 0.5f), 0, kNumEngines - 1);
    const float transpose = mParams[kTranspose];
    const float bend = mBend * mParams[kBendRange];
    const float modDepth = mParams[kModDepth];

    plaits::Voice::Frame frames[kSubBlock];

    for (auto& v : mImpl->voices) {
        if (!v.live) continue;

        v.patch.engine = engine;
        v.patch.note = static_cast<float>(v.midiNote) + transpose + bend;
        v.patch.harmonics = mParams[kHarmonics];
        v.patch.timbre = mParams[kTimbre];
        v.patch.morph = mParams[kMorph];
        v.patch.decay = mParams[kDecay];
        v.patch.lpg_colour = mParams[kColour];
        v.patch.frequency_modulation_amount = 0.0f;
        v.patch.morph_modulation_amount = 0.0f;

        // Only claim the TIMBRE input when the wheel is actually assigned to it:
        // patching it also takes timbre away from the internal decay envelope,
        // which is half of what the percussion models are.
        v.patch.timbre_modulation_amount = modDepth;
        v.mod.timbre = mModWheel;
        v.mod.timbre_patched = modDepth > 0.0f;

        v.mod.note = 0.0f;
        v.mod.engine = 0.0f;
        v.mod.harmonics = 0.0f;
        v.mod.morph = 0.0f;
        v.mod.frequency_patched = false;
        v.mod.morph_patched = false;

        // LEVEL patched is what makes the low-pass gate sustain instead of ping,
        // and it is also what Plaits reads as accent -- so velocity lands on the
        // loudness and the brightness together, as it does on the hardware.
        v.mod.level = v.gate ? v.velocity : 0.0f;
        v.mod.level_patched = true;

        v.mod.trigger = (v.gate && !v.forceTriggerLow) ? 1.0f : 0.0f;
        v.mod.trigger_patched = true;
        v.forceTriggerLow = false;

        v.engine.Render(v.patch, v.mod, frames, kSubBlock);

        float peak = 0.0f;
        for (int j = 0; j < kSubBlock; ++j) {
            const float o = static_cast<float>(frames[j].out) * (1.0f / 32768.0f);
            const float a = static_cast<float>(frames[j].aux) * (1.0f / 32768.0f);
            mImpl->subOut[j] += o;
            mImpl->subAux[j] += a;
            peak = std::max(peak, std::max(std::fabs(o), std::fabs(a)));
        }

        // Retire on silence rather than on the gate. A released voice may still
        // be ringing, and a drum engine never had a gate to fall.
        if (!v.gate && peak < kSilenceFloor) {
            if (++v.silentSubBlocks >= kSilentSubBlocksToRetire) {
                v.live = false;
                v.midiNote = -1;
                v.silentSubBlocks = 0;
            }
        } else {
            v.silentSubBlocks = 0;
        }
    }

    for (int j = 0; j < kSubBlock; ++j) {
        mImpl->resampler->push(mImpl->subOut[j], mImpl->subAux[j]);
    }
}

// --------------------------------------------------------------------- render

void PlaitsNode::process(const ProcessArgs& args) {
    if (args.numAudioOut < 1) return;
    const AudioPort& out = args.audioOut[0];
    float* outL = out.channel(0);
    float* outR = out.width > 1 ? out.channel(1) : out.channel(0);

    if (!mInitialised) {
        std::fill_n(outL, args.numFrames, 0.0f);
        if (out.width > 1) std::fill_n(outR, args.numFrames, 0.0f);
        return;
    }

    const double t0 = nowSec();

    {
        mQueue.drain([this](int index, float value) {
            applyParam(index, value);
        });
    }

    // Skip the resampler entirely while there is provably nothing to resample.
    // "Provably" is the whole point: voices stay live until they have rendered
    // silence for 10 ms, so a release tail is never cut, and a block carrying any
    // MIDI at all takes the full path so a note-on is never missed.
    const bool haveMidi = args.numMidiIn > 0 && args.midiIn[0] != nullptr &&
                          args.midiIn[0]->size() > 0;
    if (!anyVoiceLive() && !haveMidi) {
        if (mSilentBlocks >= kBlocksBeforeIdle) {
            std::fill_n(outL, args.numFrames, 0.0f);
            if (out.width > 1) std::fill_n(outR, args.numFrames, 0.0f);
            mResamplerIdle = true;
            const auto idleMs = static_cast<float>((nowSec() - t0) * 1000.0);
            if (idleMs > mMaxRenderMs) mMaxRenderMs = idleMs;
            return;
        }
        ++mSilentBlocks;
    } else {
        mSilentBlocks = 0;
    }

    // Coming back: the input ring holds nothing but the silence we stopped
    // feeding, so realigning the phases loses nothing and keeps them small.
    if (mResamplerIdle) {
        mImpl->resampler->snapOutToIn();
        mResamplerIdle = false;
    }

    const float auxMix = std::clamp(mParams[kAuxMix], 0.0f, 1.0f);
    int written = 0;

    auto renderTo = [&](int upto) {
        while (written < upto) {
            const size_t need = static_cast<size_t>(upto - written);
            // Top the 48 kHz side up until the resampler can satisfy the
            // request, then take everything it will give in one go.
            while (mImpl->resampler->inputsRequiredToGenerateOutputs(need) > 0) {
                renderSubBlock();
            }
            const size_t got = mImpl->resampler->populateNext(
                    mImpl->tmpL.data(), mImpl->tmpR.data(), need);
            if (got == 0) {
                // Should not happen given the loop above, but a render thread
                // that spins is a dead instrument -- make progress regardless.
                renderSubBlock();
                continue;
            }
            for (size_t i = 0; i < got; ++i) {
                outL[written + static_cast<int>(i)] =
                        (1.0f - auxMix) * mImpl->tmpL[i] + auxMix * mImpl->tmpR[i];
            }
            written += static_cast<int>(got);
        }
    };

    if (args.numMidiIn > 0 && args.midiIn[0] != nullptr) {
        const MidiBuffer& midi = *args.midiIn[0];
        for (int i = 0; i < midi.size(); ++i) {
            const MidiEvent& e = midi[i];
            renderTo(std::min(static_cast<int>(e.frame), args.numFrames));

            switch (e.status & 0xf0) {
                case 0x80:
                    noteOff(e.data1);
                    break;
                case 0x90:
                    if (e.data2 == 0) noteOff(e.data1); else noteOn(e.data1, e.data2);
                    break;
                case 0xB0:
                    switch (e.data1) {
                        case 1:
                            mModWheel = static_cast<float>(e.data2) / 127.0f;
                            break;
                        case 64:
                            mSustain = e.data2 >= 64;
                            if (!mSustain) releaseSustained();
                            break;
                        case 120:
                        case 123:
                            panic();
                            break;
                        default:
                            break;
                    }
                    break;
                case 0xC0:
                    // Dropped, as everywhere else here: the CP-88 sends a program
                    // change on every voice change of its own, and letting it
                    // through would swap the model mid-set.
                    break;
                case 0xE0:
                    mBend = (static_cast<float>((static_cast<int>(e.data2) << 7) | e.data1)
                             - 8192.0f) / 8192.0f;
                    break;
                default:
                    break;
            }
        }
    }
    renderTo(args.numFrames);

    mImpl->resampler->renormalizePhases();

    const float level = mParams[kLevel] * kVoiceHeadroom;
    for (int i = 0; i < args.numFrames; ++i) outL[i] *= level;
    // Plaits is mono, and saying so is better than faking width here: that
    // belongs to an effect, not to the instrument.
    if (out.width > 1) {
        std::memcpy(outR, outL, static_cast<size_t>(args.numFrames) * sizeof(float));
    }

    const auto ms = static_cast<float>((nowSec() - t0) * 1000.0);
    if (ms > mMaxRenderMs) mMaxRenderMs = ms;
}

}  // namespace bagosynths
