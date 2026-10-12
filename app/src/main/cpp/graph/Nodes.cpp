#include "Nodes.h"

#include <android/log.h>
#include <sfizz.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <ctime>
#include <thread>

namespace {
inline double nowSec() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}
}  // namespace

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "rigalong-graph", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "rigalong-graph", __VA_ARGS__)

namespace rigalong {

// ------------------------------------------------------------------- sfizz

SfizzNode::SfizzNode() : mSynth(std::make_unique<sfz::Sfizz>()) {}
SfizzNode::~SfizzNode() = default;

void SfizzNode::prepare(double sampleRate, int maxFrames) {
    mSynth->setSampleRate(static_cast<float>(sampleRate));
    mSynth->setSamplesPerBlock(maxFrames);

    // Voice count is a HARD CPU budget, not a generosity dial. Measured on this
    // rig: sfizz costs ~2.2 ms per block at 128 voices against a 2.018 ms
    // deadline -- i.e. permanently over budget, and the xrun counter climbs
    // continuously no matter how much buffer slack exists. 80 voices keeps the
    // sustained cost near 1.4 ms (~70% of the deadline), which leaves room for
    // a disk fetch or a clock dip without a dropout.
    //
    // 80 is also musically sufficient: two forearms is ~30 notes, and even with
    // the sustain pedal down the older voices have decayed enough to be stolen
    // inaudibly.
    // 64 -- the canonical polyphony figure, and chosen on reasoning rather than
    // measurement, because measurement could not settle it:
    //
    //   56 voices -> render peak 1.03 ms
    //   96 voices -> render peak 1.04 ms     <- identical
    //  128 voices -> render peak 2.94 ms     <- other conditions, not voice count
    //
    // The LIMIT costs nothing until the voices actually sound, and the stress
    // sweep never creates more than ~30 simultaneous voices, so it cannot tell
    // these apart. Raising the ceiling would buy nothing measurable.
    //
    // What decides it: the remaining headroom is wanted for EFFECTS. The current
    // Freeverb costs 0.02-0.4 ms; a Dragonfly-class reverb, convolution, and the
    // Leslie will cost real time. Pre-committing that budget to voices that never
    // sound would be a poor trade.
    //
    // Revisit only with a test that actually holds 64+ voices at once.
    //
    // REVISITED, 2026-09-22, with exactly that test: Michiel watched the voice
    // count while playing and reported it "accumulating quickly to 64" when
    // hammering the keyboard. At 64 sfizz steals, and a stolen voice is a note
    // cut short or never heard.
    //
    // The reasoning above missed a multiplier that is specific to this
    // instrument rather than to polyphony in general. Salamander does not use
    // one voice per key: `ampeg_release=1` (5 for the top groups) keeps a voice
    // alive for a second after the key is up, and EIGHT `trigger=release`
    // groups fire damper and action samples on every note-off, with overlapping
    // key ranges. One note played and released is therefore several voices for
    // several seconds. Thirty notes is nowhere near thirty voices.
    //
    // LEFT AT 64 all the same, because Michiel, told the arithmetic, said the
    // ceiling is not what bothers him. It is reached by design, not by fault,
    // and the headroom above it is still wanted for effects. 96 is the raise
    // this rig has evidence for -- the earlier sweep measured it at the SAME
    // render peak as 56, 1,04 ms against 1,03 ms -- so it is one number to
    // change if hammered chords ever start audibly losing notes to stealing.
    mSynth->setNumVoices(64);

    // Preload far more of each sample than the default 8192 frames. sfizz keeps
    // the head of every sample in memory and streams the rest from disk; a dense
    // cluster starts dozens of voices at once, and if the streamer cannot keep
    // up the audio thread pays for it. At ~1.5 s per sample this costs roughly
    // 80 MB for Salamander's 641 samples, which on a 12 GB tablet is nothing
    // next to a dropout.
    mSynth->setPreloadSize(65536);
    mGain.prepare(sampleRate, mLevel.load(std::memory_order_relaxed) * kCalibration);
    mLeft.assign(maxFrames, 0.0f);
    mRight.assign(maxFrames, 0.0f);
}

bool SfizzNode::loadFile(const std::string& id, const std::string& path) {
    // Loader thread only. Park the render thread first: sfizz cannot parse an SFZ
    // while renderBlock() is running, and the instrument falls silent for the
    // duration rather than crashing.
    mLoadRequested.store(true, std::memory_order_release);
    for (int i = 0; i < 2000 && !mRenderParked.load(std::memory_order_acquire); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    // NOT loadSfzFile(). sfizz's default voice-stealing algorithm is Oldest
    // (VoiceManager.cpp: setStealingAlgorithm(StealingAlgorithm::Oldest) in the
    // constructor), and Oldest is exactly wrong for a piano: the oldest sounding
    // voice IS the bass note being deliberately held under a passage, so it is
    // the first thing stolen when the right hand fills the voice pool. The
    // symptom is a pedal note that vanishes mid-bar.
    //
    // Checked before changing anything, because a setting already present in the
    // content would make this the content's business and not the app's: neither
    // SalamanderGrandPianoV3.sfz nor salamander-tight.sfz contains hint_stealing
    // at all. Their only <group> headers are the sustain-pedal noise groups. So
    // the instrument is running on sfizz's default, and improving the default is
    // ours to do.
    //
    // Applied by WRAPPING rather than by editing the SFZ, so that third-party
    // content keeps working untouched and an instrument that DOES specify
    // hint_stealing still wins -- its own <control> block is parsed after this
    // one and overrides it.
    //
    // Note the cost: EnvelopeAndAge calls enablePowerFollower() on every voice,
    // which Oldest does not. That is a per-voice envelope tracker running
    // whether or not stealing ever happens, so it is a real CPU charge and is
    // measured, not assumed -- see MEASUREMENTS.md.
    bool ok = false;
    {
        const auto slash = path.find_last_of("/\\");
        const std::string file = slash == std::string::npos ? path : path.substr(slash + 1);
        const std::string wrapper =
            "<control>\n"
            "hint_stealing=envelope_and_age\n"
            "#include \"" + file + "\"\n";
        ok = mSynth->loadSfzString(path, wrapper);
        if (!ok) {
            // An instrument that will not parse through the wrapper must still
            // load. Better a piano with the wrong stealing than no piano.
            LOGE("sfizz: the hint_stealing wrapper failed for %s, loading it plain",
                 path.c_str());
            ok = mSynth->loadSfzFile(path);
        }
    }

    mLoadRequested.store(false, std::memory_order_release);
    if (ok) {
        const auto slash = path.find_last_of("/\\");
        mLoadedName = slash == std::string::npos ? path : path.substr(slash + 1);
        mInstrumentId = id;
        LOGI("sfizz loaded id=%s file=%s (%d regions)", id.c_str(), mLoadedName.c_str(),
             mSynth->getNumRegions());
    } else {
        LOGE("sfizz failed to load id=%s at %s", id.c_str(), path.c_str());
    }
    return ok;
}

int SfizzNode::activeVoices() const {
    return mSynth ? mSynth->getNumActiveVoices() : 0;
}

// sfizz's own panic, plus the queue of note-ons waiting to be started: a note
// that has not sounded yet must not sound after the part has been switched
// away from.
void SfizzNode::allNotesOff() {
    mPendingHead = mPendingTail = 0;
    if (mSynth) mSynth->allSoundOff();
}

void SfizzNode::process(const ProcessArgs& args) {
    // Park while a load is in progress. Emitting silence for a second is a far
    // better outcome than racing sfizz's parser.
    if (mLoadRequested.load(std::memory_order_acquire)) {
        mRenderParked.store(true, std::memory_order_release);
        mLastPeak = 0.0f;
        if (args.numAudioOut > 0) {
            const AudioPort& out = args.audioOut[0];
            for (int ch = 0; ch < out.width; ++ch) {
                std::fill_n(out.channel(ch), args.numFrames, 0.0f);
            }
        }
        return;
    }
    mRenderParked.store(false, std::memory_order_release);

    const double tNote0 = nowSec();
    int noteOns = 0;

    // 1. Everything that is NOT a note-on is applied immediately: note-offs, CCs
    //    and pitch bend are cheap, and delaying them would be audible.
    //    Note-ons are queued, so that a cluster cannot blow the deadline.
    if (args.numMidiIn > 0 && args.midiIn[0] != nullptr) {
        const MidiBuffer& midi = *args.midiIn[0];
        for (int i = 0; i < midi.size(); ++i) {
            const MidiEvent& e = midi[i];
            const int delay = static_cast<int>(e.frame);
            const uint8_t type = e.status & 0xF0u;

            // BOTH note-ons and note-offs go through the queue, and that is the
            // point of it being a queue.
            //
            // Applying note-offs immediately while note-ons waited was a bug
            // with a nasty signature: if a key's on and off landed in the same
            // block -- or the on was still queued when the off arrived -- the
            // off was applied to a note that had not started, so it did
            // nothing, and then the on started a note that NOTHING WOULD EVER
            // STOP. A hung voice, holding CPU and polyphony until a panic.
            //
            // Ordering is not a refinement here; it is the difference between a
            // late note and a stuck one. CCs and pitch bend still go straight
            // through: they are cheap, they are not paired, and delaying them
            // would be audible.
            if (type == 0x90 || type == 0x80) {
                const int next = (mPendingTail + 1) % kPendingCapacity;
                if (next != mPendingHead) {
                    mPending[mPendingTail] = e;
                    mPendingTail = next;
                } else {
                    ++mPendingDropped;   // 256 queued notes is not music
                }
                continue;
            }

            switch (type) {
                case 0xB0: mSynth->cc(delay, e.data1, e.data2); break;
                case 0xE0: {
                    const int raw = (static_cast<int>(e.data2) << 7) | e.data1;
                    mSynth->pitchWheel(delay, raw - 8192);
                    break;
                }
                default: break;
            }
        }
    }

    // 2. Start queued notes until this block's time allowance is spent. Always
    //    start at least one, so progress is guaranteed no matter how slow a
    //    single note turns out to be.
    // Only note-ONS are rationed. A note-off costs nothing to apply and
    // delaying one holds a voice that should be releasing, so the loop walks
    // past them freely and stops only when the next thing to do is a note-on it
    // cannot afford. At least one note-on always starts, so progress is
    // guaranteed however slow a single note turns out to be.
    const double tStart = nowSec();
    while (mPendingHead != mPendingTail) {
        const MidiEvent& e = mPending[mPendingHead];
        const bool isOn = (e.status & 0xF0u) == 0x90 && e.data2 > 0;
        if (isOn && noteOns > 0 &&
            (noteOns >= kMaxNoteOnsPerBlock ||
             (nowSec() - tStart) * 1000.0 > kNoteOnBudgetMs)) {
            break;
        }
        mPendingHead = (mPendingHead + 1) % kPendingCapacity;
        if (isOn) {
            mSynth->noteOn(0, e.data1, e.data2);
            ++noteOns;
        } else {
            // Frame offset is not carried: MidiInput timestamps everything at
            // frame 0 today, so there is none to lose.
            mSynth->noteOff(0, e.data1, (e.status & 0xF0u) == 0x80 ? e.data2 : 0);
        }
    }

    const auto noteMs = static_cast<float>((nowSec() - tNote0) * 1000.0);
    if (noteMs > mMaxNoteMs) mMaxNoteMs = noteMs;
    if (noteOns > 0) mLastNoteOns = noteOns;

    const double tRender0 = nowSec();

    // numOutputs counts STEREO PAIRS, not channels. Passing 2 here makes sfizz
    // write to buffers[2] and buffers[3], which do not exist -- an immediate
    // segfault inside its memset. One stereo output = 1.
    float* buffers[2] = {mLeft.data(), mRight.data()};
    mSynth->renderBlock(buffers, static_cast<size_t>(args.numFrames), 1);

    // Before the level: the gate watches this to know when the release tails of
    // the last notes have actually died away.
    mLastPeak = blockPeak(mRight.data(), args.numFrames,
                          blockPeak(mLeft.data(), args.numFrames));

    if (args.numAudioOut > 0) {
        const AudioPort& out = args.audioOut[0];
        const float level = mLevel.load(std::memory_order_relaxed) * kCalibration;
        float* l = out.channel(0);
        float* r = out.width > 1 ? out.channel(1) : nullptr;
        // Copy, then apply the level as a RAMP. Fusing the two was cheaper and
        // put a step into the waveform every time the level moved.
        for (int i = 0; i < args.numFrames; ++i) l[i] = mLeft[i];
        if (r) {
            for (int i = 0; i < args.numFrames; ++i) r[i] = mRight[i];
        }
        mGain.apply(l, r, args.numFrames, level);
    }

    const auto renderMs = static_cast<float>((nowSec() - tRender0) * 1000.0);
    if (renderMs > mMaxRenderMs) mMaxRenderMs = renderMs;
}

void SfizzNode::setParam(int index, float value) {
    if (index == kLevel) mLevel.store(value, std::memory_order_relaxed);
}

float SfizzNode::getParam(int index) const {
    return index == kLevel ? mLevel.load(std::memory_order_relaxed) : 0.0f;
}

const char* SfizzNode::paramName(int index) const {
    return index == kLevel ? "Level" : "";
}

// ------------------------------------------------------------------ Reverb

namespace {
// Freeverb's original tunings, at 44.1 kHz.
constexpr int kCombTuning[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassTuning[4] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;
}  // namespace

float ReverbNode::Comb::process(float in, float feedback, float damp) {
    const float out = buf[index];
    store = out * (1.0f - damp) + store * damp;
    buf[index] = in + store * feedback;
    if (++index >= static_cast<int>(buf.size())) index = 0;
    return out;
}

float ReverbNode::Allpass::process(float in) {
    const float bufout = buf[index];
    const float out = -in + bufout;
    buf[index] = in + bufout * 0.5f;
    if (++index >= static_cast<int>(buf.size())) index = 0;
    return out;
}

void ReverbNode::prepare(double sampleRate, int) {
    // Delay lines scale with sample rate so the room stays the same size.
    const double scale = sampleRate / 44100.0;
    for (int ch = 0; ch < 2; ++ch) {
        const int spread = ch == 0 ? 0 : kStereoSpread;
        for (int i = 0; i < kCombs; ++i) {
            const auto len = static_cast<size_t>((kCombTuning[i] + spread) * scale);
            mComb[ch][i].buf.assign(std::max<size_t>(len, 1), 0.0f);
            mComb[ch][i].index = 0;
            mComb[ch][i].store = 0.0f;
        }
        for (int i = 0; i < kAllpasses; ++i) {
            const auto len = static_cast<size_t>((kAllpassTuning[i] + spread) * scale);
            mAllpass[ch][i].buf.assign(std::max<size_t>(len, 1), 0.0f);
            mAllpass[ch][i].index = 0;
        }
    }
}

void ReverbNode::process(const ProcessArgs& args) {
    if (args.numAudioIn == 0 || args.numAudioOut == 0) return;

    const AudioPort& in = args.audioIn[0];
    const AudioPort& out = args.audioOut[0];
    const int nf = args.numFrames;

    const float feedback = 0.28f + mRoomSize * 0.7f;
    const float damp = mDamping * 0.4f;
    const float wet = mMix;
    const float dry = 1.0f - mMix;

    for (int ch = 0; ch < 2; ++ch) {
        const float* src = in.channel(std::min(ch, in.width - 1));
        float* dst = out.channel(std::min(ch, out.width - 1));

        for (int i = 0; i < nf; ++i) {
            const float input = src[i] * 0.015f;
            float acc = 0.0f;
            for (int c = 0; c < kCombs; ++c) {
                acc += mComb[ch][c].process(input, feedback, damp);
            }
            for (int a = 0; a < kAllpasses; ++a) {
                acc = mAllpass[ch][a].process(acc);
            }
            dst[i] = src[i] * dry + acc * wet;
        }
    }

    // Stereo width: blend towards mono as width falls.
    if (out.width > 1 && mWidth < 1.0f) {
        float* l = out.channel(0);
        float* r = out.channel(1);
        const float side = mWidth;
        for (int i = 0; i < nf; ++i) {
            const float mid = (l[i] + r[i]) * 0.5f;
            l[i] = mid + (l[i] - mid) * side;
            r[i] = mid + (r[i] - mid) * side;
        }
    }
}

void ReverbNode::setParam(int index, float v) {
    switch (index) {
        case kRoomSize: mRoomSize = std::clamp(v, 0.0f, 1.0f); break;
        case kDamping:  mDamping = std::clamp(v, 0.0f, 1.0f); break;
        case kWidth:    mWidth = std::clamp(v, 0.0f, 1.0f); break;
        case kMix:      mMix = std::clamp(v, 0.0f, 1.0f); break;
        default: break;
    }
}

float ReverbNode::getParam(int index) const {
    switch (index) {
        case kRoomSize: return mRoomSize;
        case kDamping:  return mDamping;
        case kWidth:    return mWidth;
        case kMix:      return mMix;
        default: return 0.0f;
    }
}

const char* ReverbNode::paramName(int index) const {
    switch (index) {
        case kRoomSize: return "Room size";
        case kDamping:  return "Damping";
        case kWidth:    return "Width";
        case kMix:      return "Mix";
        default: return "";
    }
}

// ----------------------------------------------------------------- Limiter

void LimiterNode::prepare(double sampleRate, int) {
    mAttack = std::exp(-1.0f / static_cast<float>(0.0005 * sampleRate));   // 0.5 ms
    mRelease = std::exp(-1.0f / static_cast<float>(0.100 * sampleRate));   // 100 ms
    mEnvelope = 0.0f;
}

void LimiterNode::process(const ProcessArgs& args) {
    if (args.numAudioIn == 0 || args.numAudioOut == 0) return;

    const AudioPort& in = args.audioIn[0];
    const AudioPort& out = args.audioOut[0];
    const int nf = args.numFrames;
    const int channels = std::min(in.width, out.width);

    for (int i = 0; i < nf; ++i) {
        // Link the channels: gain reduction is driven by the loudest, so the
        // stereo image does not shift when only one side is loud.
        float peak = 0.0f;
        for (int ch = 0; ch < channels; ++ch) {
            peak = std::max(peak, std::fabs(in.channel(ch)[i] * mGain));
        }

        const float coeff = peak > mEnvelope ? mAttack : mRelease;
        mEnvelope = peak + coeff * (mEnvelope - peak);

        float gain = 1.0f;
        if (mEnvelope > mCeiling) gain = mCeiling / mEnvelope;

        for (int ch = 0; ch < channels; ++ch) {
            // Hard clamp behind the smooth gain: the dither stage downstream
            // depends on [-1, 1] as a contract, not an aspiration.
            out.channel(ch)[i] = std::clamp(in.channel(ch)[i] * mGain * gain, -1.0f, 1.0f);
        }
    }
}

void LimiterNode::setParam(int index, float v) {
    switch (index) {
        case kCeiling: mCeiling = std::clamp(v, 0.05f, 1.0f); break;
        case kGain:    mGain = std::clamp(v, 0.0f, 8.0f); break;
        default: break;
    }
}

float LimiterNode::getParam(int index) const {
    switch (index) {
        case kCeiling: return mCeiling;
        case kGain:    return mGain;
        default: return 0.0f;
    }
}

const char* LimiterNode::paramName(int index) const {
    switch (index) {
        case kCeiling: return "Ceiling";
        case kGain:    return "Gain";
        default: return "";
    }
}

}  // namespace rigalong
