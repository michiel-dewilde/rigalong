#include "DexedNode.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <sys/stat.h>

#include <android/log.h>

#include "EngineMkI.h"
#include "EngineOpl.h"
#include "msfa/controllers.h"
#include "msfa/dx7note.h"
#include "msfa/exp2.h"
#include "msfa/freqlut.h"
#include "msfa/lfo.h"
#include "msfa/pitchenv.h"
#include "msfa/porta.h"
#include "msfa/sin.h"
#include "msfa/synth.h"
#include "msfa/tuning.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "bos-dexed", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "bos-dexed", __VA_ARGS__)

namespace {
inline double nowSec() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}

const char* const kParamNames[] = {
    "Level", "Engine", "Transpose", "Bend Range", "Mod Depth",
};
static_assert(sizeof(kParamNames) / sizeof(kParamNames[0]) ==
                      static_cast<size_t>(bagosynths::DexedNode::kNumParams),
              "parameter name table out of step with the enum");

const char* const kEngineNames[] = {"MODERN", "MARK I", "OPL"};

// A DX7 bulk dump: F0 43 0n 09 20 00 <4096 bytes> <checksum> F7.
constexpr size_t kSysexBytes = 4104;
constexpr size_t kSysexHeader = 6;

// The DX7's own character set for voice names is ASCII-ish but pads with
// spaces and occasionally carries values a cartridge editor left behind.
std::string cleanName(const uint8_t* p, int n) {
    std::string s;
    for (int i = 0; i < n; ++i) {
        const char c = static_cast<char>(p[i] & 0x7f);
        s.push_back(c >= 32 && c < 127 ? c : ' ');
    }
    while (!s.empty() && s.back() == ' ') s.pop_back();
    return s;
}

// Values outside a parameter's range appear in cartridges found in the wild --
// a byte that was never a parameter, or random data past a short dump. Upstream
// rescales rather than clamping, on the reasoning that such a byte is arbitrary
// anyway; matching that keeps those cartridges sounding as they do in Dexed.
inline uint8_t normparm(uint8_t value, uint8_t max) {
    if (value <= max) return value;
    return static_cast<uint8_t>(static_cast<float>(value) / 255.0f * max);
}
}  // namespace

namespace bagosynths {

DexedNode::DexedNode() {
    mParams[kLevel] = 1.0f;
    mParams[kEngine] = 1.0f;      // Mark I: the one that sounds like a DX7
    mParams[kTranspose] = 0.0f;
    mParams[kBendRange] = 2.0f;   // the DX7's own default
    mParams[kModDepth] = 0.99f;   // mod wheel adds vibrato, as on the hardware

    // The DX7 init voice, so the node makes a sound before any cartridge is
    // loaded. This is the unpacked 155-byte form, straight from the front panel
    // of the original: six operators at full output with instant envelopes,
    // algorithm 1.
    static const uint8_t kInitVoice[] = {
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 99, 0, 1, 0, 7,
        99, 99, 99, 99, 50, 50, 50, 50, 0, 0, 1, 35, 0, 0, 0, 1, 0, 3, 24,
        'I', 'N', 'I', 'T', ' ', 'V', 'O', 'I', 'C', 'E'};
    // 155 bytes: six operators of 21, then 19 global, then a 10-character name.
    // Asserted because a single dropped byte shifts the algorithm, the LFO and
    // the transpose -- which is exactly what happened, and it presented as the
    // instrument being silent rather than as anything that looked like a typo.
    static_assert(sizeof(kInitVoice) == 6 * 21 + 19 + 10,
                  "the DX7 init voice is not 155 bytes");
    std::memcpy(mPatch, kInitVoice, std::min(sizeof(kInitVoice), sizeof(mPatch)));
    mCurrentName = "INIT VOICE";
}

DexedNode::~DexedNode() = default;

const char* DexedNode::engineName(int index) {
    if (index < 0 || index >= kNumEngines) return "";
    return kEngineNames[index];
}

void DexedNode::prepare(double sampleRate, int maxFrames) {
    // Start AT the current level, so a graph recompile does not fade the
    // instrument in from silence.
    mGain.prepare(sampleRate, mParams[kLevel]);

    // msfa's tables are STATIC and shared by every instance. One output device
    // means one rate, so this is survivable -- but a second Dexed node at a
    // different rate would silently detune both. Same shape of constraint as
    // setBfree's SampleRateD global.
    if (mInitialised && std::abs(mRate - sampleRate) < 0.5) return;
    mRate = sampleRate;

    Exp2::init();
    Sin::init();
    Freqlut::init(sampleRate);
    Lfo::init(sampleRate);
    PitchEnv::init(sampleRate);
    Env::init_sr(sampleRate);
    Porta::init_sr(sampleRate);

    if (!mInitialised) {
        mTuning = createStandardTuning();
        mCoreModern = std::make_unique<FmCore>();
        mCoreMkI.reset(static_cast<FmCore*>(new EngineMkI()));
        mCoreOpl.reset(static_cast<FmCore*>(new EngineOpl()));

        mControllers = std::make_unique<Controllers>();
        mControllers->values_[kControllerPitch] = 0x2000;
        mControllers->values_[kControllerPitchRangeUp] = 2;
        mControllers->values_[kControllerPitchRangeDn] = 2;
        mControllers->values_[kControllerPitchStep] = 0;
        mControllers->masterTune = 0;
        mControllers->modwheel_cc = 0;
        mControllers->breath_cc = 0;
        mControllers->foot_cc = 0;
        mControllers->aftertouch_cc = 0;
        mControllers->portamento_enable_cc = false;
        mControllers->portamento_cc = 0;
        // MPE off. It is on by default upstream, and with a single-channel
        // controller like the CP-88 it only gets in the way.
        mControllers->mpeEnabled = false;
        std::strcpy(mControllers->opSwitch, "111111");

        mLfo = std::make_unique<Lfo>();
        mLfo->reset(mPatch + 137);

        for (auto& v : mVoices) {
            v.note = std::make_unique<Dx7Note>(mTuning, nullptr);
        }
        mInitialised = true;
    }

    for (int i = 0; i < kNumParams; ++i) applyParam(i, mParams[i]);
    mControllers->refresh();
}

// ------------------------------------------------------------------ cartridges

void DexedNode::unpackVoice(const uint8_t* bulk, uint8_t* out) {
    // The DX7's packed cartridge format: six operators of 17 bytes, then 16
    // global bytes, then a 10-character name -- 128 bytes, which msfa wants as
    // the 155-byte edit-buffer form. Several fields are bit-packed pairs.
    // Transcribed from the SysEx specification; Dexed's PluginData.cpp does the
    // same job and was the reference for the don't-care masks, which the
    // specification leaves implicit and cartridges in the wild rely on.
    for (int op = 0; op < 6; ++op) {
        const uint8_t* in = bulk + op * 17;
        uint8_t* o = out + op * 21;

        for (int i = 0; i < 11; ++i) o[i] = in[i] & 0x7f;   // EG rates/levels, breakpoint, depths

        const uint8_t curves = in[11] & 0x0f;
        o[11] = curves & 3;                                  // left curve
        o[12] = (curves >> 2) & 3;                           // right curve

        const uint8_t detune_rs = in[12] & 0x7f;
        o[13] = detune_rs & 7;                               // rate scaling

        const uint8_t kvs_ams = in[13] & 0x1f;
        o[14] = kvs_ams & 3;                                 // amp mod sensitivity
        o[15] = (kvs_ams >> 2) & 7;                          // key velocity sensitivity

        o[16] = in[14] & 0x7f;                               // output level

        const uint8_t fcoarse_mode = in[15] & 0x3f;
        o[17] = fcoarse_mode & 1;                            // oscillator mode
        o[18] = (fcoarse_mode >> 1) & 0x1f;                  // coarse frequency

        o[19] = in[16] & 0x7f;                               // fine frequency
        o[20] = (detune_rs >> 3) & 0x7f;                     // detune
    }

    for (int i = 0; i < 8; ++i) out[126 + i] = normparm(bulk[102 + i] & 0x7f, 99);  // pitch EG
    out[134] = normparm(bulk[110] & 0x1f, 31);                                      // algorithm

    const uint8_t oks_fb = bulk[111] & 0x0f;
    out[135] = oks_fb & 7;          // feedback
    out[136] = oks_fb >> 3;         // oscillator key sync

    out[137] = bulk[112] & 0x7f;    // LFO speed
    out[138] = bulk[113] & 0x7f;    // LFO delay
    out[139] = bulk[114] & 0x7f;    // LFO pitch mod depth
    out[140] = bulk[115] & 0x7f;    // LFO amp mod depth

    const uint8_t lpms_lfw_lks = bulk[116] & 0x7f;
    out[141] = lpms_lfw_lks & 1;          // LFO key sync
    out[142] = (lpms_lfw_lks >> 1) & 7;   // LFO waveform
    out[143] = lpms_lfw_lks >> 4;         // pitch mod sensitivity

    out[144] = bulk[117] & 0x7f;          // transpose
    for (int i = 0; i < 10; ++i) out[145 + i] = bulk[118 + i] & 0x7f;   // name
}

int DexedNode::loadBanks(const std::vector<std::string>& dirs) {
    std::vector<Bank> found;

    for (const std::string& dir : dirs) {
        DIR* d = opendir(dir.c_str());
        if (d == nullptr) continue;

        std::vector<std::string> files;
        while (dirent* e = readdir(d)) {
            const std::string name = e->d_name;
            if (name.size() < 5) continue;
            std::string ext = name.substr(name.size() - 4);
            for (char& c : ext) c = static_cast<char>(tolower(c));
            if (ext != ".syx") continue;
            files.push_back(name);
        }
        closedir(d);
        // readdir order is the filesystem's, which is arbitrary. Sort, so the
        // bank a saved patch names is at a stable place and the list reads
        // sensibly on screen.
        std::sort(files.begin(), files.end());

        for (const std::string& name : files) {
            const std::string path = dir + "/" + name;
            FILE* f = fopen(path.c_str(), "rb");
            if (f == nullptr) continue;

            std::vector<uint8_t> raw(kSysexBytes);
            const size_t got = fread(raw.data(), 1, kSysexBytes, f);
            fclose(f);

            // Only 32-voice bulk dumps. Anything else -- a single-voice dump, a
            // truncated file, something that is not SysEx at all -- is skipped
            // rather than guessed at.
            if (got != kSysexBytes || raw[0] != 0xf0 || raw[1] != 0x43 ||
                raw[3] != 0x09) {
                LOGE("skipping %s: not a 32-voice DX7 bank (%zu bytes)", name.c_str(), got);
                continue;
            }

            Bank b;
            b.name = name.substr(0, name.size() - 4);
            b.packed.assign(raw.begin() + kSysexHeader,
                            raw.begin() + kSysexHeader + kVoicesPerBank * kPackedVoiceBytes);
            for (int i = 0; i < kVoicesPerBank; ++i) {
                b.voiceNames[i] = cleanName(b.packed.data() + i * kPackedVoiceBytes + 118, 10);
            }
            found.push_back(std::move(b));
        }
    }

    {
        std::lock_guard<std::mutex> lock(mBankLock);
        mBanks = std::move(found);
        // Loaded again (a bank was imported): the voice playing keeps its
        // name, and its number follows it to its new place in the list.
        int at = -1;
        for (size_t b = 0; b < mBanks.size() && at < 0; ++b) {
            for (int v = 0; v < kVoicesPerBank; ++v) {
                if (mBanks[b].name + "/" + mBanks[b].voiceNames[v] == mCurrentName) {
                    at = static_cast<int>(b) * kVoicesPerBank + v;
                    break;
                }
            }
        }
        if (mCurrentVoice.load(std::memory_order_relaxed) >= 0) mCurrentVoice.store(at, std::memory_order_relaxed);
    }
    mReady.store(true, std::memory_order_release);

    const int voices = voiceCount();
    LOGI("Dexed: %d banks, %d voices", static_cast<int>(mBanks.size()), voices);

    // Whatever a restored patch asked for before the cartridges existed.
    applyRequestedVoice();
    return voices;
}

int DexedNode::voiceCount() const {
    std::lock_guard<std::mutex> lock(mBankLock);
    return static_cast<int>(mBanks.size()) * kVoicesPerBank;
}

std::string DexedNode::voiceShortName(int index) const {
    std::lock_guard<std::mutex> lock(mBankLock);
    const int bank = index / kVoicesPerBank;
    const int slot = index % kVoicesPerBank;
    if (bank < 0 || bank >= static_cast<int>(mBanks.size())) return {};
    return mBanks[bank].voiceNames[slot];
}

std::string DexedNode::voiceName(int index) const {
    std::lock_guard<std::mutex> lock(mBankLock);
    const int bank = index / kVoicesPerBank;
    const int slot = index % kVoicesPerBank;
    if (bank < 0 || bank >= static_cast<int>(mBanks.size())) return {};
    return mBanks[bank].name + "/" + mBanks[bank].voiceNames[slot];
}

void DexedNode::requestVoiceNamed(const std::string& name) {
    std::lock_guard<std::mutex> lock(mBankLock);
    mWantedName = name;
}

void DexedNode::applyRequestedVoice() {
    std::string wanted;
    {
        std::lock_guard<std::mutex> lock(mBankLock);
        wanted.swap(mWantedName);
    }
    if (!wanted.empty()) selectVoiceNamed(wanted);
}

bool DexedNode::selectVoice(int index) {
    uint8_t unpacked[kPatchBytes] = {};
    std::string name;
    {
        std::lock_guard<std::mutex> lock(mBankLock);
        const int bank = index / kVoicesPerBank;
        const int slot = index % kVoicesPerBank;
        if (bank < 0 || bank >= static_cast<int>(mBanks.size())) return false;
        unpackVoice(mBanks[bank].packed.data() + slot * kPackedVoiceBytes, unpacked);
        name = mBanks[bank].name + "/" + mBanks[bank].voiceNames[slot];
    }

    // Hand the patch over in one piece. Writing mPatch directly would let the
    // render thread start a note on a patch that is half the old voice and half
    // the new one -- which does not crash, it just sounds wrong occasionally,
    // which is worse.
    std::memcpy(mPendingPatch, unpacked, sizeof(mPendingPatch));
    mPendingValid.store(true, std::memory_order_release);

    mCurrentVoice.store(index, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(mBankLock);
        mCurrentName = name;
        // A deliberate choice wins over a restore that has not landed yet.
        mWantedName.clear();
    }
    LOGI("Dexed voice %d: %s", index, name.c_str());
    return true;
}

bool DexedNode::selectVoiceNamed(const std::string& name) {
    if (name.empty()) return false;
    const int n = voiceCount();
    if (n == 0) {
        // The cartridges are not read yet. Remember, do not fail.
        requestVoiceNamed(name);
        return false;
    }
    for (int i = 0; i < n; ++i) {
        if (voiceName(i) == name) return selectVoice(i);
    }
    for (int i = 0; i < n; ++i) {
        if (voiceShortName(i) == name) return selectVoice(i);
    }
    LOGE("Dexed voice '%s' is not in this library; keeping the current one", name.c_str());
    return false;
}

std::string DexedNode::currentVoiceName() const {
    std::lock_guard<std::mutex> lock(mBankLock);
    return mCurrentName;
}

int DexedNode::activeVoices() const {
    int n = 0;
    for (const auto& v : mVoices) {
        if (v.live) ++n;
    }
    return n;
}

// ---------------------------------------------------------------- parameters

void DexedNode::pushParam(int index, float value) {
    mQueue.push(index, value);
}

void DexedNode::setParam(int index, float value) {
    if (index < 0 || index >= kNumParams) return;
    mParams[index] = value;
    if (index == kLevel) return;   // read directly in process()
    if (!mInitialised) return;     // prepare() will apply it
    pushParam(index, value);
}

void DexedNode::setParamRealtime(int index, float value) {
    if (index < 0 || index >= kNumParams) return;
    mParams[index] = value;
    if (index == kLevel) return;   // read directly in process()
    if (!mInitialised) return;     // prepare() will apply it
    mRtQueue.push(index, value);
}

float DexedNode::getParam(int index) const {
    if (index < 0 || index >= kNumParams) return 0.0f;
    return mParams[index];
}

const char* DexedNode::paramName(int index) const {
    if (index < 0 || index >= kNumParams) return "";
    return kParamNames[index];
}

void DexedNode::applyParam(int index, float value) {
    if (!mControllers) return;
    switch (index) {
        case kEngine: {
            const int sel = std::clamp(static_cast<int>(std::lround(value)), 0, kNumEngines - 1);
            mControllers->core = sel == 0   ? mCoreModern.get()
                                 : sel == 1 ? mCoreMkI.get()
                                            : mCoreOpl.get();
            break;
        }
        case kBendRange: {
            const int semis = std::clamp(static_cast<int>(std::lround(value)), 0, 12);
            mControllers->values_[kControllerPitchRangeUp] = semis;
            mControllers->values_[kControllerPitchRangeDn] = semis;
            break;
        }
        case kModDepth: {
            // The mod wheel's job on a DX7 is vibrato, and leaving it at
            // upstream's default of zero means the wheel does nothing at all --
            // which reads as a broken instrument rather than a design choice.
            mControllers->wheel.range =
                    std::clamp(static_cast<int>(std::lround(value * 99.0f)), 0, 99);
            mControllers->wheel.pitch = mControllers->wheel.range > 0;
            mControllers->wheel.amp = false;
            mControllers->wheel.eg = false;
            mControllers->refresh();
            break;
        }
        case kTranspose:
        case kLevel:
        default:
            break;
    }
}

// --------------------------------------------------------------------- voices

int DexedNode::chooseVoice(int midiNote) {
    // A free voice first.
    for (int i = 0; i < kMaxVoices; ++i) {
        if (!mVoices[i].live) return i;
    }
    // Then the same note, retriggered -- a repeated note should replace itself
    // rather than stealing somebody else's.
    for (int i = 0; i < kMaxVoices; ++i) {
        if (mVoices[i].midiNote == midiNote && !mVoices[i].keyDown) return i;
    }
    // Then the oldest released voice, and only then the oldest held one. Taking
    // a released tail is almost always inaudible; taking a held note is not.
    int best = -1;
    uint32_t oldest = UINT32_MAX;
    for (int i = 0; i < kMaxVoices; ++i) {
        if (!mVoices[i].keyDown && mVoices[i].startedAt < oldest) {
            oldest = mVoices[i].startedAt;
            best = i;
        }
    }
    if (best >= 0) return best;

    oldest = UINT32_MAX;
    best = 0;
    for (int i = 0; i < kMaxVoices; ++i) {
        if (mVoices[i].startedAt < oldest) {
            oldest = mVoices[i].startedAt;
            best = i;
        }
    }
    return best;
}

void DexedNode::noteOn(int midiNote, int velocity) {
    if (velocity <= 0) {
        noteOff(midiNote);
        return;
    }
    const int transposed =
            midiNote + std::clamp(static_cast<int>(std::lround(mParams[kTranspose])), -24, 24);
    if (transposed < 0 || transposed > 127) return;

    // The LFO restarts only when no key is already down, which is what "LFO key
    // sync" means on the hardware: a held chord keeps one shared LFO phase.
    bool anyDown = false;
    for (const auto& v : mVoices) {
        if (v.keyDown) { anyDown = true; break; }
    }
    if (!anyDown) mLfo->keydown();

    const int i = chooseVoice(transposed);
    Voice& v = mVoices[i];
    const bool stealing = v.live;

    v.midiNote = transposed;
    v.keyDown = true;
    v.sustained = mSustain;
    v.live = true;
    v.startedAt = mVoiceTick++;
    v.note->init(mPatch, transposed, velocity, 1, mControllers.get());
    // Oscillator key sync, but never while stealing: restarting the phase on a
    // voice that is still sounding is a step discontinuity, and a step is a click.
    if (mPatch[136] && !stealing) v.note->oscSync();
}

void DexedNode::noteOff(int midiNote) {
    const int transposed =
            midiNote + std::clamp(static_cast<int>(std::lround(mParams[kTranspose])), -24, 24);
    for (auto& v : mVoices) {
        if (v.live && v.keyDown && v.midiNote == transposed) {
            v.keyDown = false;
            if (mSustain) {
                v.sustained = true;
            } else {
                v.note->keyup();
            }
        }
    }
}

void DexedNode::releaseSustained() {
    for (auto& v : mVoices) {
        if (v.live && v.sustained && !v.keyDown) {
            v.sustained = false;
            v.note->keyup();
        }
    }
}

void DexedNode::panic() {
    for (auto& v : mVoices) {
        v.keyDown = false;
        v.sustained = false;
        if (v.live) v.note->keyup();
    }
    mSustain = false;
}

void DexedNode::allNotesOff() {
    // keyup() first so the engine's own bookkeeping is consistent, then take
    // the voices out of the render entirely.
    for (auto& v : mVoices) {
        v.keyDown = false;
        v.sustained = false;
        if (v.live) v.note->keyup();
        v.live = false;
        v.midiNote = -1;
    }
    mSustain = false;
}

void DexedNode::takePendingVoice() {
    if (!mPendingValid.load(std::memory_order_acquire)) return;
    std::memcpy(mPatch, mPendingPatch, sizeof(mPatch));
    mPendingValid.store(false, std::memory_order_release);
    mLfo->reset(mPatch + 137);
}

// ------------------------------------------------------------------- process

void DexedNode::process(const ProcessArgs& args) {
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
        mRtQueue.drain([this](int index, float value) {
            applyParam(index, value);
        });
    }
    takePendingVoice();

    int written = 0;
    auto renderTo = [&](int upto) {
        while (written < upto) {
            if (mOffset >= kChunk) {
                mOffset = 0;

                int32_t acc[kChunk];
                std::fill_n(acc, kChunk, 0);
                std::fill_n(mChunkBuf, kChunk, 0.0f);

                const int32_t lfoValue = mLfo->getsample();
                const int32_t lfoDelay = mLfo->getdelay();

                for (auto& v : mVoices) {
                    if (!v.live) continue;
                    v.note->compute(acc, lfoValue, lfoDelay, mControllers.get());
                    // msfa works in 24-bit fixed point and each voice ADDS into
                    // the buffer; upstream converts and clears per voice rather
                    // than summing in fixed point, which would overflow.
                    for (int j = 0; j < kChunk; ++j) {
                        int32_t val = acc[j] >> 4;
                        const int clipped = val < -(1 << 24)  ? -0x8000
                                            : val >= (1 << 24) ? 0x7fff
                                                               : val >> 9;
                        mChunkBuf[j] += static_cast<float>(clipped) / 32768.0f;
                        acc[j] = 0;
                    }
                    if (!v.note->isPlaying()) {
                        v.live = false;
                        v.keyDown = false;
                        v.sustained = false;
                        v.midiNote = -1;
                    }
                }
            }
            const int n = std::min(upto - written, kChunk - mOffset);
            std::memcpy(outL + written, mChunkBuf + mOffset,
                        static_cast<size_t>(n) * sizeof(float));
            written += n;
            mOffset += n;
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
                        case 1:   mControllers->modwheel_cc = e.data2; mControllers->refresh(); break;
                        case 2:   mControllers->breath_cc = e.data2;   mControllers->refresh(); break;
                        case 4:   mControllers->foot_cc = e.data2;     mControllers->refresh(); break;
                        case 64:
                            mSustain = e.data2 >= 64;
                            if (!mSustain) releaseSustained();
                            break;
                        case 120:
                        case 123:
                            // Panic has to actually work, here as everywhere.
                            panic();
                            break;
                        default: break;
                    }
                    break;
                case 0xC0:
                    // Dropped, as setBfree and Surge XT drop it: the CP-88 sends a
                    // program change on every voice change of its own, and
                    // letting it through would swap the DX7 voice mid-set.
                    break;
                case 0xD0:
                    mControllers->aftertouch_cc = e.data1;
                    mControllers->refresh();
                    break;
                case 0xE0:
                    mControllers->values_[kControllerPitch] =
                            (static_cast<int>(e.data2) << 7) | e.data1;
                    break;
                default:
                    break;
            }
        }
    }
    renderTo(args.numFrames);

    // Before the level. Mono, so one channel is the whole picture.
    mLastPeak = blockPeak(outL, args.numFrames);

    mGain.apply(outL, nullptr, args.numFrames, mParams[kLevel]);
    // The DX7 is mono. Copying rather than pretending otherwise keeps the node
    // honest; width belongs to an effect, not to the instrument.
    if (out.width > 1) {
        std::memcpy(outR, outL, static_cast<size_t>(args.numFrames) * sizeof(float));
    }

    const auto ms = static_cast<float>((nowSec() - t0) * 1000.0);
    if (ms > mMaxRenderMs) mMaxRenderMs = ms;
}

}  // namespace bagosynths
