#include "FxNode.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>

#include "sst/basic-blocks/simd/setup.h"

#include "sst/effects/Delay.h"
#include "sst/effects/Flanger.h"
#include "sst/effects/Phaser.h"
#include "sst/effects/Reverb2.h"
#include "sst/effects/RotarySpeaker.h"

namespace rigalong {
namespace {

namespace sfx = sst::effects;
using PMD = sst::basic_blocks::params::ParamMetaData;

// sst-effects' ConcreteConfig, with what it leaves out: tempo sync per
// parameter (Surge's own ratio, tempo / 120), and a dbToLinear that converts.
struct BosFxConfig {
    struct BC {
        static constexpr uint16_t maxParamCount{FxNode::kMaxParams};
        float paramStorage[maxParamCount] = {};
        template <typename... Types> BC(Types...) {}
    };
    struct GS {
        double sampleRate;
        float ratio = 1.0f;     // tempo / 120: what a synced value is scaled by
        explicit GS(double sr) : sampleRate(sr) {}
    };
    struct ES {
        bool synced[FxNode::kMaxParams] = {};
    };

    using BaseClass = BC;
    using GlobalStorage = GS;
    using EffectStorage = ES;
    using ValueStorage = float*;
    using BiquadAdapter = BosFxConfig;

    static constexpr int blockSize{FxNode::kBlock};

    static inline float floatValueAt(const BaseClass* const e, const ValueStorage* const, int idx) {
        return e->paramStorage[idx];
    }
    static inline int intValueAt(const BaseClass* const e, const ValueStorage* const, int idx) {
        return static_cast<int>(std::round(e->paramStorage[idx]));
    }
    static inline float envelopeRateLinear(GlobalStorage* s, float f) {
        return 1.f * blockSize / static_cast<float>(s->sampleRate) * std::pow(2.f, -f);
    }
    static inline bool temposyncInitialized(GlobalStorage*) { return true; }
    static inline float temposyncRatio(GlobalStorage* s, EffectStorage* e, int idx) {
        return e->synced[idx] ? s->ratio : 1.f;
    }
    static inline float temposyncRatioInv(GlobalStorage* s, EffectStorage* e, int idx) {
        return e->synced[idx] ? 1.f / s->ratio : 1.f;
    }
    static inline bool isDeactivated(EffectStorage*, int) { return false; }
    static inline bool isTemposynced(EffectStorage* e, int idx) { return e->synced[idx]; }
    static inline bool isExtended(EffectStorage*, int) { return false; }
    static inline float rand01(GlobalStorage*) { return static_cast<float>(rand()) / static_cast<float>(RAND_MAX); }
    static inline double sampleRate(GlobalStorage* s) { return s->sampleRate; }
    static inline double sampleRateInv(GlobalStorage* s) { return 1.0 / s->sampleRate; }
    static inline float noteToPitch(GlobalStorage*, float p) { return std::pow(2.f, p / 12.f); }
    static inline float noteToPitchIgnoringTuning(GlobalStorage* s, float p) { return noteToPitch(s, p); }
    static inline float noteToPitchInv(GlobalStorage* s, float p) { return 1.f / noteToPitch(s, p); }
    static inline float dbToLinear(GlobalStorage*, float db) { return std::pow(10.f, db / 20.f); }
};

template <typename FX>
class Impl final : public FxNode::Effect {
public:
    explicit Impl(double rate) : mGs(rate) {
        mFx = std::make_unique<FX>(&mGs, &mEs, nullptr);
        mCount = std::min(FX::numParams, FxNode::kMaxParams);
        for (int i = 0; i < mCount; ++i) {
            mMeta[i] = mFx->paramAt(i);
            mFx->paramStorage[i] = mMeta[i].defaultVal;
        }
        mFx->initialize();
    }
    void processBlock(float* l, float* r) override { mFx->processBlock(l, r); }
    void setValue(int i, float v) override {
        if (i < 0 || i >= mCount) return;
        // A synced value sits on a note value, as in Surge.
        if (mEs.synced[i] && mMeta[i].canTemposync) v = mMeta[i].snapToTemposync(v);
        mFx->paramStorage[i] = std::clamp(v, mMeta[i].minVal, mMeta[i].maxVal);
    }
    float value(int i) const override { return i >= 0 && i < mCount ? mFx->paramStorage[i] : 0.f; }
    void setSync(int i, bool on) override {
        if (i < 0 || i >= mCount || !mMeta[i].canTemposync) return;
        mEs.synced[i] = on;
        if (on) mFx->paramStorage[i] = mMeta[i].snapToTemposync(mFx->paramStorage[i]);
    }
    bool sync(int i) const override { return i >= 0 && i < mCount && mEs.synced[i]; }
    void setTempo(double bpm) override { mGs.ratio = static_cast<float>(bpm > 0.0 ? bpm / 120.0 : 1.0); }
    int count() const override { return mCount; }
    const std::string& name(int i) const override {
        static const std::string none;
        return i >= 0 && i < mCount && mMeta[i].type != PMD::NONE ? mMeta[i].name : none;
    }
    ParamRange range(int i) const override {
        if (i < 0 || i >= mCount) return {};
        const PMD& m = mMeta[i];
        return {m.minVal, m.maxVal, m.type == PMD::INT || m.type == PMD::BOOL};
    }
    bool canSync(int i) const override { return i >= 0 && i < mCount && mMeta[i].canTemposync; }
    void reinit() override { mFx->initialize(); }
    bool display(int i, float v, char* out, int capacity) const override {
        if (i < 0 || i >= mCount) return false;
        auto s = mMeta[i].valueToString(v, PMD::FeatureState().withTemposync(mEs.synced[i]));
        if (!s) return false;
        std::snprintf(out, static_cast<size_t>(capacity), "%s", s->c_str());
        return true;
    }

private:
    BosFxConfig::GS mGs;
    BosFxConfig::ES mEs;
    std::unique_ptr<FX> mFx;
    PMD mMeta[FxNode::kMaxParams];
    int mCount = 0;
};

std::atomic<double> gHostTempo{120.0};

const char* const kTypeNames[FxNode::kNumTypes] = {"Off", "Delay", "Phaser", "Flanger", "Rotary", "Reverb"};

// Queue indices: the effect's parameter, tagged with the generation it was sent to.
constexpr int kTagStride = 64;
constexpr int kGenWrap = 1 << 20;   // kGenWrap * kTagStride stays inside an int

}  // namespace

FxNode::FxNode() = default;

void FxNode::setHostTempo(double bpm) { gHostTempo.store(bpm > 0.0 ? bpm : 120.0, std::memory_order_relaxed); }

FxNode::~FxNode() { delete mEffect.load(); }

void FxNode::prepare(double sampleRate, int maxFrames) {
    mAmountStep = static_cast<float>(1.0 / std::max(1.0, sampleRate * 0.015));
    if (sampleRate == mRate) return;
    mRate = sampleRate;
    // Off the render thread (the graph compiles there): an effect built for the
    // old rate is built again. Its values go back to the defaults; the song
    // sets them again when it is applied.
    const int type = mType.load();
    if (type != kOff) {
        mType.store(kOff);
        setType(type);
    }
}

FxNode::Effect* FxNode::make(int type) const {
    switch (type) {
        case kDelay:   return new Impl<sfx::delay::Delay<BosFxConfig>>(mRate);
        case kPhaser:  return new Impl<sfx::phaser::Phaser<BosFxConfig>>(mRate);
        case kFlanger: return new Impl<sfx::flanger::Flanger<BosFxConfig>>(mRate);
        case kRotary:  return new Impl<sfx::rotaryspeaker::RotarySpeaker<BosFxConfig>>(mRate);
        case kReverb:  return new Impl<sfx::reverb2::Reverb2<BosFxConfig>>(mRate);
        default:       return nullptr;
    }
}

void FxNode::setType(int type) {
    type = std::clamp(type, 0, kNumTypes - 1);
    if (type == mType.load()) return;
    Effect* fresh = make(type);   // allocation here, never on the render thread
    if (fresh) {
        fresh->type = type;
        fresh->setTempo(gHostTempo.load(std::memory_order_relaxed));
    }
    publish(fresh, type);
}

void FxNode::configure(int type, const std::vector<std::string>& names, const std::vector<float>& values) {
    type = std::clamp(type, 0, kNumTypes - 1);
    Effect* fresh = make(type);
    if (fresh) {
        fresh->type = type;
        fresh->setTempo(gHostTempo.load(std::memory_order_relaxed));
        const size_t n = std::min(names.size(), values.size());
        // Syncs before values: a synced value snaps to a note value.
        for (int pass = 0; pass < 2; ++pass) {
            for (size_t k = 0; k < n; ++k) {
                for (int i = 0; i < fresh->count(); ++i) {
                    const bool isSync = fresh->canSync(i) && names[k] == fresh->name(i) + " sync";
                    if (pass == 0 && isSync) fresh->setSync(i, values[k] > 0.5f);
                    if (pass == 1 && !isSync && names[k] == fresh->name(i)) fresh->setValue(i, values[k]);
                }
            }
        }
        fresh->reinit();   // starts AT these values: no glide from the defaults
    }
    publish(fresh, type);
}

void FxNode::publish(Effect* fresh, int type) {
    const int gen = (mGen.load() + 1) % kGenWrap;
    if (fresh) fresh->gen = gen;
    for (int i = 0; i < kMaxParams; ++i) {
        mSyncNames[i] = fresh && fresh->canSync(i) ? fresh->name(i) + " sync" : std::string();
    }
    mType.store(type);
    Effect* old = mEffect.exchange(fresh, std::memory_order_seq_cst);
    // After the exchange: a change tagged in between can only be dropped, never
    // applied to the wrong effect.
    mGen.store(gen);
    if (old == nullptr) return;
    // The render thread may have loaded `old` before the exchange; it bumps
    // mRenderSeq to odd before loading and back to even after its block. Once
    // the count is even, or has moved on, it holds `old` no longer.
    const uint32_t seq = mRenderSeq.load(std::memory_order_seq_cst);
    if (seq & 1u) {
        const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
        while (mRenderSeq.load(std::memory_order_seq_cst) == seq && std::chrono::steady_clock::now() < until) {
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
    }
    delete old;
}

void FxNode::setParam(int index, float value) {
    if (index == 0) {
        setType(static_cast<int>(std::lround(value)));
        return;
    }
    if (index == kAmount) {
        mAmountTarget.store(std::clamp(value, 0.0f, 1.0f), std::memory_order_relaxed);
        return;
    }
    // Tagged with the effect now in force: drained only into that one.
    mQueue.push(mGen.load() * kTagStride + index, value);
}

float FxNode::getParam(int index) const {
    if (index == 0) return static_cast<float>(mType.load());
    if (index == kAmount) return mAmountTarget.load(std::memory_order_relaxed);
    const Effect* e = mEffect.load();
    if (e == nullptr) return 0.f;
    if (index >= kSyncBase) return e->sync(index - kSyncBase) ? 1.f : 0.f;
    return e->value(index - 1);
}

const char* FxNode::paramName(int index) const {
    if (index == 0) return "Type";
    if (index == kAmount) return "Amount";
    const Effect* e = mEffect.load();
    if (e == nullptr) return "";
    if (index >= kSyncBase) {
        const int i = index - kSyncBase;
        return i < kMaxParams ? mSyncNames[i].c_str() : "";
    }
    return e->name(index - 1).c_str();
}

ParamRange FxNode::paramRange(int index) const {
    if (index == 0) return {0.f, static_cast<float>(kNumTypes - 1), true};
    if (index == kAmount) return {};
    if (index >= kSyncBase) return {0.f, 1.f, true};
    const Effect* e = mEffect.load();
    return e ? e->range(index - 1) : ParamRange{};
}

bool FxNode::paramDisplay(int index, float value, char* out, int capacity) const {
    if (index == 0) {
        std::snprintf(out, static_cast<size_t>(capacity), "%s",
                      kTypeNames[std::clamp(static_cast<int>(std::lround(value)), 0, kNumTypes - 1)]);
        return true;
    }
    if (index == kAmount) {
        std::snprintf(out, static_cast<size_t>(capacity), "%.0f %%", value * 100.0f);
        return true;
    }
    if (index >= kSyncBase) {
        std::snprintf(out, static_cast<size_t>(capacity), "%s", value > 0.5f ? "on" : "off");
        return true;
    }
    const Effect* e = mEffect.load();
    return e && e->display(index - 1, value, out, capacity);
}

void FxNode::process(const ProcessArgs& args) {
    mRenderSeq.fetch_add(1, std::memory_order_seq_cst);   // odd: inside
    Effect* e = mEffect.load(std::memory_order_seq_cst);

    mQueue.drain([&](int tagged, float v) {
        if (e == nullptr || tagged / kTagStride != e->gen) return;   // meant for another effect
        const int index = tagged % kTagStride;
        if (index >= kSyncBase) e->setSync(index - kSyncBase, v > 0.5f);
        else e->setValue(index - 1, v);
    });
    if (e) e->setTempo(args.tempo);
    mTempo.store(args.tempo, std::memory_order_relaxed);

    const AudioPort& in = args.audioIn[0];
    const AudioPort& out = args.audioOut[0];
    const float* inL = in.channel(0);
    const float* inR = in.width > 1 ? in.channel(1) : inL;
    float* outL = out.channel(0);
    float* outR = out.width > 1 ? out.channel(1) : nullptr;

    const float target = mAmountTarget.load(std::memory_order_relaxed);
    for (int i = 0; i < args.numFrames; ++i) {
        if (mAmount != target) {
            mAmount = mAmount < target ? std::min(target, mAmount + mAmountStep)
                                       : std::max(target, mAmount - mAmountStep);
        }
        const float l = inL[i], r = inR[i];
        // What went in 16 frames ago, through the effect and around it.
        outL[i] = mOutL[mPos] + mDryL[mPos];
        if (outR) outR[i] = mOutR[mPos] + mDryR[mPos];
        mInL[mPos] = l * mAmount;
        mInR[mPos] = r * mAmount;
        mDryInL[mPos] = l * (1.0f - mAmount);
        mDryInR[mPos] = r * (1.0f - mAmount);
        if (++mPos == kBlock) {
            mPos = 0;
            std::memcpy(mOutL, mInL, sizeof(mOutL));
            std::memcpy(mOutR, mInR, sizeof(mOutR));
            std::memcpy(mDryL, mDryInL, sizeof(mDryL));
            std::memcpy(mDryR, mDryInR, sizeof(mDryR));
            if (e) e->processBlock(mOutL, mOutR);
        }
    }
    mRenderSeq.fetch_add(1, std::memory_order_seq_cst);   // even: out
}

}  // namespace rigalong
