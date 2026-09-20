#include "HammondNode.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>

// setBfree's headers are plain C without extern "C" guards of their own, so the
// wrapping happens here. System headers are included first, outside the block,
// because dragging libc declarations into a C linkage block is asking for
// trouble on some toolchains.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#include "global_inst.h"   // struct b_instance, and every engine header
#include "midi.h"
#include "program.h"
#include "state.h"
#include "tonegen.h"
#include "vibrato.h"
#include "whirl.h"

extern double SampleRateD;   // beatrix_glue.c; read by tonegen.c and vibrato.c
}

namespace {
inline double nowSec() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}

constexpr int kDrawbarsPerManual = 9;

// The registrations every organist has in their hands, in the classic drawbar
// notation: nine digits, 0-8, for 16' 5 1/3' 8' 4' 2 2/3' 2' 1 3/5' 1 1/3' 1'.
struct Preset {
    const char* name;
    unsigned int bars[kDrawbarsPerManual];
};

const Preset kPresets[] = {
    {"Full Organ",   {8, 8, 8, 8, 8, 8, 8, 8, 8}},
    {"Jimmy Smith",  {8, 8, 8, 8, 0, 0, 0, 0, 0}},
    {"Gospel",       {8, 8, 8, 0, 0, 0, 0, 0, 8}},
    {"Blues",        {8, 8, 8, 8, 0, 0, 0, 0, 8}},
    {"Booker T",     {8, 8, 8, 0, 0, 0, 0, 0, 0}},
    {"Procol",       {8, 8, 8, 8, 8, 8, 0, 0, 0}},
    {"Ballad",       {0, 0, 8, 8, 0, 0, 0, 0, 0}},
    {"Flute 8",      {0, 0, 8, 0, 0, 0, 0, 0, 0}},
    {"Bright Comp",  {8, 6, 8, 6, 4, 4, 5, 6, 8}},
};
constexpr int kNumPresets = static_cast<int>(sizeof(kPresets) / sizeof(kPresets[0]));

const char* const kParamNames[] = {
    "Upper 16", "Upper 5 1/3", "Upper 8", "Upper 4", "Upper 2 2/3",
    "Upper 2", "Upper 1 3/5", "Upper 1 1/3", "Upper 1",
    "Lower 16", "Lower 5 1/3", "Lower 8", "Lower 4", "Lower 2 2/3",
    "Lower 2", "Lower 1 3/5", "Lower 1 1/3", "Lower 1",
    "Pedal 16", "Pedal 5 1/3", "Pedal 8", "Pedal 4", "Pedal 2 2/3",
    "Pedal 2", "Pedal 1 3/5", "Pedal 1 1/3", "Pedal 1",
    "Percussion", "Perc Fast", "Perc 3rd", "Perc Soft",
    "Vibrato/Chorus", "Vibrato Upper", "Vibrato Lower",
    "Overdrive", "Drive Character", "Drive Gain",
    "Leslie", "Reverb", "Swell",
    "Pedal Split", "Lower Split", "Transpose",
    "Level",
};
static_assert(sizeof(kParamNames) / sizeof(kParamNames[0]) ==
                      static_cast<size_t>(bagosynths::HammondNode::kNumParams),
              "parameter name table out of step with the enum");
}  // namespace

namespace bagosynths {

HammondNode::HammondNode() {
    // Defaults: a usable organ the moment the node exists, so a fresh patch
    // makes a sound rather than silence. 88 8000 000 is the Jimmy Smith
    // registration and the most recognisable Hammond sound there is.
    static const unsigned int kUpperDefault[9] = {8, 8, 8, 8, 0, 0, 0, 0, 0};
    static const unsigned int kLowerDefault[9] = {8, 6, 8, 0, 0, 0, 0, 0, 0};
    static const unsigned int kPedalDefault[9] = {8, 0, 6, 0, 0, 0, 0, 0, 0};
    for (int i = 0; i < kDrawbarsPerManual; ++i) {
        mParams[kUpper1 + i] = static_cast<float>(kUpperDefault[i]);
        mParams[kLower1 + i] = static_cast<float>(kLowerDefault[i]);
        mParams[kPedal1 + i] = static_cast<float>(kPedalDefault[i]);
    }
    mParams[kPercEnable] = 1.0f;
    mParams[kPercFast] = 1.0f;
    mParams[kPercThird] = 1.0f;
    mParams[kPercSoft] = 0.0f;
    mParams[kVibratoKnob] = 3.0f;      // C2, the chorus most people mean
    mParams[kVibratoUpper] = 0.0f;
    mParams[kVibratoLower] = 0.0f;
    mParams[kOverdrive] = 0.0f;
    mParams[kOverdriveChar] = 0.5f;
    mParams[kOverdriveGain] = 0.5f;
    mParams[kRotary] = 1.0f;           // chorale: slow, the resting position
    mParams[kReverbMix] = 0.15f;
    mParams[kSwell] = 0.8f;
    mParams[kSplitPedal] = 0.0f;       // no split: the whole keyboard is upper
    mParams[kSplitLower] = 0.0f;
    mParams[kTranspose] = 0.0f;
    mParams[kLevel] = 1.0f;
}

HammondNode::~HammondNode() {
    if (!mInst) return;
    freeReverb(mInst->reverb);
    freeWhirl(mInst->whirl);
    freeToneGenerator(mInst->synth);
    freeMidiCfg(mInst->midicfg);
    freePreamp(mInst->preamp);
    freeProgs(mInst->progs);
    freeRunningConfig(mInst->state);
    delete mInst;
}

void HammondNode::prepare(double sampleRate, int maxFrames) {
    // prepare() runs on every graph compile. Building the tone generator means
    // computing 91 wheels' worth of wavetables, which takes real time and must
    // happen exactly once.
    if (mInitialised) return;

    mRate = sampleRate;
    SampleRateD = sampleRate;

    mInst = new b_instance();
    // Order matters and is upstream's: allocate, wire the controller table, then
    // initialise each part against the shared midicfg. Taken from
    // b_synth/lv2.c's allocSynth() and initSynth().
    mInst->state = allocRunningConfig();
    mInst->progs = allocProgs();
    mInst->reverb = allocReverb();
    mInst->whirl = allocWhirl();
    mInst->midicfg = allocMidiCfg(mInst->state);
    mInst->synth = allocTonegen();
    mInst->preamp = allocPreamp();

    initControllerTable(mInst->midicfg);
    midiPrimeControllerMapping(mInst->midicfg);

    initToneGenerator(mInst->synth, mInst->midicfg);
    initVibrato(mInst->synth, mInst->midicfg);
    initPreamp(mInst->preamp, mInst->midicfg);
    initReverb(mInst->reverb, mInst->midicfg, sampleRate);
    initWhirl(mInst->whirl, mInst->midicfg, sampleRate);
    initRunningConfig(mInst->state, mInst->midicfg);
    initMidiTables(mInst->midicfg);

    mInitialised = true;

    // Push our defaults into the freshly built engine. Direct, not queued: the
    // render thread cannot be running a node that has only just been prepared.
    for (int i = 0; i < kNumParams; ++i) applyParam(i, mParams[i]);
}

// ---------------------------------------------------------------- parameters

void HammondNode::pushParam(int index, float value) {
    const uint32_t w = mQueueWrite.load(std::memory_order_relaxed);
    const uint32_t r = mQueueRead.load(std::memory_order_acquire);
    if (w - r >= kQueueCapacity) return;   // full: drop, the UI will resend
    mQueue[w % kQueueCapacity] = {index, value};
    mQueueWrite.store(w + 1, std::memory_order_release);
}

void HammondNode::setParam(int index, float value) {
    if (index < 0 || index >= kNumParams) return;
    mParams[index] = value;
    if (!mInitialised) return;   // prepare() will apply it
    pushParam(index, value);
}

float HammondNode::getParam(int index) const {
    if (index < 0 || index >= kNumParams) return 0.0f;
    return mParams[index];
}

const char* HammondNode::paramName(int index) const {
    if (index < 0 || index >= kNumParams) return "";
    return kParamNames[index];
}

int HammondNode::presetCount() { return kNumPresets; }

const char* HammondNode::presetName(int index) {
    if (index < 0 || index >= kNumPresets) return "";
    return kPresets[index].name;
}

void HammondNode::applyPreset(int index, int manual) {
    if (index < 0 || index >= kNumPresets) return;
    if (manual < 0 || manual > 2) return;
    const int base = kUpper1 + manual * kDrawbarsPerManual;
    for (int i = 0; i < kDrawbarsPerManual; ++i) {
        setParam(base + i, static_cast<float>(kPresets[index].bars[i]));
    }
}

void HammondNode::applyParam(int index, float value) {
    if (!mInst) return;
    void* mcfg = mInst->midicfg;

    // Drawbars go in as a set of nine, because that is the API upstream
    // documents and because setting them one at a time would recompute the bus
    // gains nine times over.
    if (index >= kUpper1 && index < kPercEnable) {
        const int manual = (index - kUpper1) / kDrawbarsPerManual;   // 0, 1, 2
        const int base = kUpper1 + manual * kDrawbarsPerManual;
        unsigned int bars[kDrawbarsPerManual];
        for (int i = 0; i < kDrawbarsPerManual; ++i) {
            bars[i] = static_cast<unsigned int>(
                    std::clamp(static_cast<int>(std::lround(mParams[base + i])), 0, 8));
        }
        setDrawBars(mInst, static_cast<unsigned int>(manual), bars);
        return;
    }

    const auto on = [](float v) { return v >= 0.5f; };
    const auto cc = [](float v) {   // 0..1 -> 0..127
        return static_cast<unsigned char>(std::clamp(v, 0.0f, 1.0f) * 127.0f + 0.5f);
    };

    switch (index) {
        case kPercEnable:
            setPercussionEnabled(mInst->synth, on(value));
            break;
        case kPercFast:
            setPercussionFast(mInst->synth, on(value));
            break;
        case kPercThird:
            // Upstream's flag asks whether percussion sits on the FIRST
            // harmonic bus, which is the 2nd harmonic -- so it is the inverse.
            setPercussionFirst(mInst->synth, on(value) ? 0 : 1);
            break;
        case kPercSoft:
            setPercussionVolume(mInst->synth, on(value));
            break;

        case kVibratoKnob: {
            // Six positions: V1 C1 V2 C2 V3 C3. Upstream selects by dividing the
            // MIDI value by 23, so land in the middle of a bucket rather than on
            // its edge.
            const int pos = std::clamp(static_cast<int>(std::lround(value)), 0, 5);
            callMIDIControlFunction(mcfg, "vibrato.knob",
                                    static_cast<unsigned char>(pos * 23 + 11));
            break;
        }
        case kVibratoUpper:
            setVibratoUpper(mInst->synth, on(value));
            break;
        case kVibratoLower:
            setVibratoLower(mInst->synth, on(value));
            break;

        case kOverdrive:
            // setClean() takes the INVERSE: 1 means bypass the valve stage.
            setClean(mInst->preamp, on(value) ? 0 : 1);
            break;
        case kOverdriveChar:
            callMIDIControlFunction(mcfg, "overdrive.character", cc(value));
            break;
        case kOverdriveGain:
            callMIDIControlFunction(mcfg, "overdrive.inputgain", cc(value));
            break;

        case kRotary: {
            // WHIRL_SLOW is 0, WHIRL_STOP is 1, WHIRL_FAST is 2 -- not in speed
            // order, so the UI's 0/1/2 (stop, slow, fast) is mapped, not passed.
            static const int kWhirl[3] = {WHIRL_STOP, WHIRL_SLOW, WHIRL_FAST};
            const int sel = std::clamp(static_cast<int>(std::lround(value)), 0, 2);
            setRevSelect(mInst->whirl, kWhirl[sel]);
            break;
        }
        case kReverbMix:
            setReverbMix(mInst->reverb, std::clamp(value, 0.0f, 1.0f));
            break;
        case kSwell:
            callMIDIControlFunction(mcfg, "swellpedal1", cc(value));
            break;

        case kSplitPedal:
        case kSplitLower: {
            // One keyboard, three manuals. Flags 1 and 2 set the two split
            // points; the per-zone transpose fields are left alone.
            const int pedal =
                    std::clamp(static_cast<int>(std::lround(mParams[kSplitPedal])), 0, 127);
            const int lower =
                    std::clamp(static_cast<int>(std::lround(mParams[kSplitLower])), 0, 127);
            setKeyboardSplitMulti(mcfg, 1 | 2, pedal, lower, 0, 0, 0);
            break;
        }
        case kTranspose:
            setKeyboardTranspose(mcfg,
                                 std::clamp(static_cast<int>(std::lround(value)), -12, 12));
            break;

        case kLevel:
            break;   // applied in process(), not by the engine

        default:
            break;
    }
}

// ------------------------------------------------------------------ process

void HammondNode::process(const ProcessArgs& args) {
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

    // Drain the parameter queue before any audio is generated, so a change
    // always lands on a fragment boundary rather than halfway through one.
    {
        const uint32_t w = mQueueWrite.load(std::memory_order_acquire);
        uint32_t r = mQueueRead.load(std::memory_order_relaxed);
        while (r != w) {
            const Change& c = mQueue[r % kQueueCapacity];
            applyParam(c.index, c.value);
            ++r;
        }
        mQueueRead.store(r, std::memory_order_release);
    }

    // Render `upto` frames, pulling fresh 128-sample fragments as needed. This
    // is upstream's synthSound() with our buffers: tone generator, valve preamp,
    // spring reverb, rotating speaker, in that order.
    int written = 0;
    auto renderTo = [&](int upto) {
        while (written < upto) {
            if (mOffset >= kFragment) {
                mOffset = 0;
                oscGenerateFragment(mInst->synth, mBufA, kFragment);
                preamp(mInst->preamp, mBufA, mBufB, kFragment);
                reverb(mInst->reverb, mBufB, mBufC, kFragment);
                whirlProc3(mInst->whirl, mBufC, mBufL[0], mBufL[1],
                           mBufD[0], mBufD[1], kFragment);
            }
            const int n = std::min(upto - written, kFragment - mOffset);
            std::memcpy(outL + written, mBufL[0] + mOffset,
                        static_cast<size_t>(n) * sizeof(float));
            std::memcpy(outR + written, mBufL[1] + mOffset,
                        static_cast<size_t>(n) * sizeof(float));
            written += n;
            mOffset += n;
        }
    };

    // MIDI is applied at its own frame position, so a chord straddling a
    // fragment boundary keeps its timing instead of being quantised to 2.9 ms.
    if (args.numMidiIn > 0 && args.midiIn[0]) {
        const MidiBuffer& midi = *args.midiIn[0];
        for (int i = 0; i < midi.size(); ++i) {
            const MidiEvent& e = midi[i];
            const int at = std::min(static_cast<int>(e.frame), args.numFrames);
            renderTo(at);

            // Program change is dropped on purpose. The CP-88 sends one when its
            // voice is changed, and letting it reach installProgram() would
            // silently rewrite the drawbars and the Leslie setting mid-set.
            if ((e.status & 0xf0) == 0xC0) continue;

            // All-notes-off and all-sound-off, handled here because setBfree's
            // controller table does not carry them: CC 120 and 123 would simply
            // be ignored, and a stuck organ note does not decay -- it drones
            // until the app is killed. Panic has to actually work.
            if ((e.status & 0xf0) == 0xB0 && (e.data1 == 120 || e.data1 == 123)) {
                midi_panic(mInst);
                continue;
            }

            uint8_t msg[3] = {e.status, e.data1, e.data2};
            parse_raw_midi_data(mInst, msg, 3);
        }
    }
    renderTo(args.numFrames);

    const float level = mParams[kLevel];
    if (level != 1.0f) {
        for (int i = 0; i < args.numFrames; ++i) {
            outL[i] *= level;
            outR[i] *= level;
        }
    }

    const auto ms = static_cast<float>((nowSec() - t0) * 1000.0);
    if (ms > mMaxRenderMs) mMaxRenderMs = ms;
}

}  // namespace bagosynths
