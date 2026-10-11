#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include <bagosynths/Graph.h>
#include <bagosynths/ParamQueue.h>

namespace bagosynths {

// An insert effect (PLAN.md M6): one of Surge XT's effects, from sst-effects,
// on one engine's output -- a delay, a phaser, a flanger, the rotary speaker or
// Surge's own reverb. The rack puts one after every engine; "Off" passes the
// signal through.
//
// PARAMETERS. 0 is the type. 1..kMaxParams are the effect's own, named and
// ranged from its metadata, so the generic editor shows them as they are;
// kSyncBase + i is the tempo sync of parameter i, for those that have one: a
// synced time or rate follows the song's tempo (I12.1) in note values.
//
// THE TYPE is changed on the calling thread, never the render thread: the new
// effect is allocated and initialised there, published with one atomic store,
// and the old one freed only once the render thread is provably not inside it
// (a sequence count it bumps around every block). Parameter changes go through
// a queue tagged with the generation of the effect they were sent to, so a
// value sent to the old effect never lands on the new one, even of the same type.
//
// AMOUNT (index kAmount) is a send, not a mix: the effect is fed the signal
// times the amount, and the rest passes dry. Taken to 0 -- a live set without
// the effect -- it stops feeding the delay or the hall and lets what is in it
// ring out, instead of cutting it off. Ramped over 15 ms.
//
// LATENCY. sst-effects process 16 frames at a time; the node runs them
// through a 16-frame delay line so any block size works, and keeps that delay
// when off as well, so switching an effect in never jumps the signal in time.
class FxNode : public Node {
public:
    enum Type { kOff, kDelay, kPhaser, kFlanger, kRotary, kReverb, kNumTypes };
    static constexpr int kMaxParams = 20;
    static constexpr int kSyncBase = 1 + kMaxParams;
    static constexpr int kBlock = 16;
    static constexpr int kAmount = kSyncBase + kMaxParams;

    FxNode();
    ~FxNode() override;

    // The host's tempo, for effects created from now on: they start at it
    // rather than at the last tempo a block carried, which a synced delay
    // would otherwise glide away from over seconds (Surge glides its times).
    static void setHostTempo(double bpm);

    // A song's effect, whole: a fresh one of `type` (even the same type as now,
    // so nothing carries over from the last song), its named values -- syncs
    // first -- applied and the effect initialised WITH them before the render
    // thread ever sees it. Through the queue instead, a delay would glide from
    // its default time to the song's over seconds, as Surge glides its times.
    // Names it does not know are ignored. Off the render thread.
    void configure(int type, const std::vector<std::string>& names, const std::vector<float>& values);

    const char* typeName() const override { return "Fx"; }
    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Audio, 2, "in"}; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 2, "out"}; }
    int latencyFrames() const override { return kBlock; }

    void prepare(double sampleRate, int maxFrames) override;
    void process(const ProcessArgs& args) override;

    int numParams() const override { return kAmount + 1; }
    void setParam(int index, float value) override;
    // From the render thread: the amount only (an atomic); the rest is queued
    // by the UI thread alone, so it is ignored here.
    void setParamRealtime(int index, float value) override {
        if (index == kAmount) setParam(index, value);
    }
    float getParam(int index) const override;
    const char* paramName(int index) const override;
    ParamRange paramRange(int index) const override;
    bool paramDisplay(int index, float value, char* out, int capacity) const override;

    // The effect behind it: the type-erased face of sst-effects' templates.
    struct Effect {
        int type = kOff;
        // Which publish this is: a queued change carries the generation it was
        // sent to, so it never lands on a later effect, even one of the same type.
        int gen = 0;
        virtual ~Effect() = default;
        virtual void processBlock(float* l, float* r) = 0;
        virtual void setValue(int i, float v) = 0;
        virtual float value(int i) const = 0;
        virtual void setSync(int i, bool on) = 0;
        virtual bool sync(int i) const = 0;
        virtual void setTempo(double bpm) = 0;
        virtual int count() const = 0;
        virtual const std::string& name(int i) const = 0;
        virtual ParamRange range(int i) const = 0;
        virtual bool canSync(int i) const = 0;
        virtual bool display(int i, float v, char* out, int capacity) const = 0;
        virtual void reinit() = 0;
    };

private:
    void setType(int type);
    void publish(Effect* fresh, int type);
    Effect* make(int type) const;

    double mRate = 44100.0;
    std::atomic<Effect*> mEffect{nullptr};
    std::atomic<int> mType{kOff};
    std::atomic<int> mGen{0};
    // Odd while the render thread is inside process(): see setType.
    std::atomic<uint32_t> mRenderSeq{0};
    ParamQueue<256> mQueue;

    float mInL[kBlock] = {}, mInR[kBlock] = {};
    float mDryInL[kBlock] = {}, mDryInR[kBlock] = {};
    float mDryL[kBlock] = {}, mDryR[kBlock] = {};
    std::atomic<float> mAmountTarget{1.0f};
    float mAmount = 1.0f;
    float mAmountStep = 1.0f / 661.0f;
    alignas(16) float mOutL[kBlock] = {};
    alignas(16) float mOutR[kBlock] = {};
    int mPos = 0;
    std::atomic<double> mTempo{120.0};

    std::string mSyncNames[kMaxParams];
};

}  // namespace bagosynths
