#include "Synth.h"

#include <algorithm>
#include <cmath>

// ---------------------------------------------------------------- Envelope

void Envelope::configure(float attackSec, float decaySec, float sustain, float releaseSec) {
    mSustain = sustain;
    mAttackRate = 1.0f / std::max(1.0f, attackSec * mSampleRate);
    // Exponential decay/release expressed as a per-sample coefficient reaching
    // ~-60 dB in the requested time.
    mDecayCoeff = std::exp(-6.9078f / std::max(1.0f, decaySec * mSampleRate));
    mReleaseCoeff = std::exp(-6.9078f / std::max(1.0f, releaseSec * mSampleRate));
}

float Envelope::nextSample() {
    switch (mStage) {
        case Stage::Idle:
            return 0.0f;

        case Stage::Attack:
            mLevel += mAttackRate;
            if (mLevel >= 1.0f) {
                mLevel = 1.0f;
                mStage = Stage::Decay;
            }
            break;

        case Stage::Decay:
            mLevel = mSustain + (mLevel - mSustain) * mDecayCoeff;
            if (mLevel - mSustain < 1.0e-4f) {
                mLevel = mSustain;
                mStage = Stage::Sustain;
            }
            break;

        case Stage::Sustain:
            mLevel = mSustain;
            break;

        case Stage::Release:
            mLevel *= mReleaseCoeff;
            // Cut off well above denormal range. A voice whose tail is inaudible
            // is a voice that should be freed for reuse.
            if (mLevel < 1.0e-5f) {
                mLevel = 0.0f;
                mStage = Stage::Idle;
            }
            break;
    }
    return mLevel;
}

// ------------------------------------------------------ StateVariableFilter

void StateVariableFilter::setCutoff(float hz, float q) {
    // Keep the cutoff inside a sane range: below Nyquist with margin, above DC.
    const float nyquist = mSampleRate * 0.5f;
    hz = std::clamp(hz, 20.0f, nyquist * 0.95f);

    mG = std::tan(3.14159265f * hz / mSampleRate);
    mK = 1.0f / std::max(0.05f, q);
    mA1 = 1.0f / (1.0f + mG * (mG + mK));
    mA2 = mG * mA1;
    mA3 = mG * mA2;
}

float StateVariableFilter::processLowpass(float x) {
    const float v3 = x - mIc2eq;
    const float v1 = mA1 * mIc1eq + mA2 * v3;
    const float v2 = mIc2eq + mA2 * mIc1eq + mA3 * v3;
    mIc1eq = 2.0f * v1 - mIc1eq;
    mIc2eq = 2.0f * v2 - mIc2eq;
    return v2;
}

// -------------------------------------------------------------------- Voice

// polyBLEP: adds a correction polynomial around the discontinuity of a naive
// sawtooth, removing most of the aliasing for a few flops per sample.
static inline float polyBlep(float t, float dt) {
    if (t < dt) {
        t /= dt;
        return t + t - t * t - 1.0f;
    }
    if (t > 1.0f - dt) {
        t = (t - 1.0f) / dt;
        return t * t + t + t + 1.0f;
    }
    return 0.0f;
}

float Voice::midiNoteToHz(float note) {
    return 440.0f * std::exp2((note - 69.0f) / 12.0f);
}

void Voice::setSampleRate(float sr) {
    mSampleRate = sr;
    mAmpEnv.setSampleRate(sr);
    mFilterEnv.setSampleRate(sr);
    mFilter.setSampleRate(sr);
}

void Voice::noteOn(int note, float velocity) {
    mNote = note;
    mVelocity = velocity;
    mBaseFreq = midiNoteToHz(static_cast<float>(note));
    mReleasing = false;

    // Free-running phases would click on retrigger; resetting them gives a
    // consistent, punchy attack. Slight offset between the two oscillators.
    mPhase1 = 0.0f;
    mPhase2 = 0.37f;

    // Spread voices gently across the stereo field by note number. Cheap, and it
    // makes chords sound considerably less like a mono blob.
    mPan = 0.5f + 0.25f * ((static_cast<float>(note) - 60.0f) / 36.0f);
    mPan = std::clamp(mPan, 0.15f, 0.85f);

    mFilter.reset();

    // Harder playing -> faster, brighter. Velocity should do something musical
    // even in a test voice, or you cannot tell whether MIDI velocity is arriving.
    mAmpEnv.configure(0.005f, 0.6f, 0.55f, 0.35f);
    mFilterEnv.configure(0.002f, 0.35f, 0.25f, 0.30f);
    mAmpEnv.noteOn();
    mFilterEnv.noteOn();
}

void Voice::noteOff() {
    mReleasing = true;
    mAmpEnv.noteOff();
    mFilterEnv.noteOff();
}

void Voice::kill() {
    mAmpEnv.reset();
    mFilterEnv.reset();
    mFilter.reset();
    mReleasing = false;
    mNote = -1;
}

void Voice::renderAdd(float* stereoOut, int numFrames, float pitchBendRatio, float cutoffMod) {
    if (!isActive()) return;

    const float freq = mBaseFreq * pitchBendRatio;
    const float detune = 1.0f + 0.004f;          // ~7 cents, fattens the sound
    const float inc1 = freq / mSampleRate;
    const float inc2 = freq * detune / mSampleRate;

    const float gainL = std::sqrt(1.0f - mPan) * mVelocity;
    const float gainR = std::sqrt(mPan) * mVelocity;

    for (int i = 0; i < numFrames; ++i) {
        // Two saws.
        float s1 = 2.0f * mPhase1 - 1.0f - polyBlep(mPhase1, inc1);
        float s2 = 2.0f * mPhase2 - 1.0f - polyBlep(mPhase2, inc2);

        mPhase1 += inc1;
        if (mPhase1 >= 1.0f) mPhase1 -= 1.0f;
        mPhase2 += inc2;
        if (mPhase2 >= 1.0f) mPhase2 -= 1.0f;

        float x = (s1 + s2) * 0.5f;

        // Filter cutoff tracks the note, the filter envelope, velocity and the
        // mod wheel. Recomputing coefficients every sample is wasteful; at this
        // CPU budget it does not matter, and it keeps the code obvious. If this
        // ever shows up in a profile, move it to once per block.
        const float env = mFilterEnv.nextSample();
        const float cutoff = freq * (1.5f + 12.0f * env * (0.4f + 0.6f * mVelocity)
                                     + 8.0f * cutoffMod);
        mFilter.setCutoff(cutoff, 1.4f);
        x = mFilter.processLowpass(x);

        const float amp = mAmpEnv.nextSample();
        x *= amp;

        stereoOut[2 * i]     += x * gainL;
        stereoOut[2 * i + 1] += x * gainR;
    }
}

// -------------------------------------------------------------------- Synth

void Synth::setSampleRate(float sr) {
    mSampleRate = sr;
    for (auto& v : mVoices) v.setSampleRate(sr);
}

void Synth::reset() {
    for (auto& v : mVoices) v.kill();
    mHeldBySustain.fill(false);
    mSustainPedal = false;
    mPitchBendRatio = 1.0f;
    mCutoffMod = 0.0f;
    mActiveVoices.store(0, std::memory_order_relaxed);
}

Voice* Synth::allocateVoice(int note) {
    // 1. A free voice.
    for (auto& v : mVoices) {
        if (!v.isActive()) return &v;
    }

    // 2. Otherwise steal the oldest *releasing* voice -- its tail is already
    //    fading, so it is the least missed.
    Voice* oldestReleasing = nullptr;
    for (auto& v : mVoices) {
        if (v.isReleasing()) {
            if (!oldestReleasing || v.startOrder() < oldestReleasing->startOrder()) {
                oldestReleasing = &v;
            }
        }
    }
    if (oldestReleasing) return oldestReleasing;

    // 3. Last resort: the oldest held voice.
    Voice* oldest = &mVoices[0];
    for (auto& v : mVoices) {
        if (v.startOrder() < oldest->startOrder()) oldest = &v;
    }
    return oldest;
}

void Synth::noteOn(int note, int velocity) {
    if (note < 0 || note > 127) return;

    // A note-on with velocity 0 is a note-off. Plenty of keyboards send this.
    if (velocity == 0) {
        noteOff(note);
        return;
    }

    Voice* v = allocateVoice(note);
    v->setStartOrder(++mVoiceCounter);
    // Velocity curve: linear velocity feels weak at the bottom. Squaring it maps
    // the keybed's dynamic range onto something closer to how a piano responds.
    const float vel = static_cast<float>(velocity) / 127.0f;
    v->noteOn(note, vel * vel * 0.9f + 0.1f);
    mHeldBySustain[note] = false;
}

void Synth::noteOff(int note) {
    if (note < 0 || note > 127) return;

    if (mSustainPedal) {
        // Pedal down: remember it, but let it ring.
        mHeldBySustain[note] = true;
        return;
    }
    for (auto& v : mVoices) {
        if (v.isActive() && !v.isReleasing() && v.note() == note) {
            v.noteOff();
        }
    }
}

void Synth::controlChange(int cc, int value) {
    switch (cc) {
        case 1:   // mod wheel -> filter brightness
            mCutoffMod = static_cast<float>(value) / 127.0f;
            break;

        case 7: { // channel volume
            const float v = static_cast<float>(value) / 127.0f;
            mMasterGain = 0.25f * v * v;
            break;
        }

        case 64:  // sustain pedal -- the CP-88 has one and it matters
            if (value >= 64) {
                mSustainPedal = true;
            } else {
                mSustainPedal = false;
                for (int n = 0; n < 128; ++n) {
                    if (mHeldBySustain[n]) {
                        mHeldBySustain[n] = false;
                        for (auto& v : mVoices) {
                            if (v.isActive() && !v.isReleasing() && v.note() == n) v.noteOff();
                        }
                    }
                }
            }
            break;

        case 120: // all sound off
            panic();
            break;

        case 123: // all notes off
            allNotesOff();
            break;

        default:
            break;
    }
}

void Synth::pitchBend(int lsb, int msb) {
    const int raw = (msb << 7) | lsb;          // 0..16383, centre 8192
    const float semitones = (static_cast<float>(raw - 8192) / 8192.0f) * 2.0f;
    mPitchBendRatio = std::exp2(semitones / 12.0f);
}

void Synth::allNotesOff() {
    mSustainPedal = false;
    mHeldBySustain.fill(false);
    for (auto& v : mVoices) {
        if (v.isActive() && !v.isReleasing()) v.noteOff();
    }
}

void Synth::panic() {
    for (auto& v : mVoices) v.kill();
    mHeldBySustain.fill(false);
    mSustainPedal = false;
    mActiveVoices.store(0, std::memory_order_relaxed);
}

int Synth::countActiveVoicesNow() const {
    int n = 0;
    for (const auto& v : mVoices) {
        if (v.isActive()) ++n;
    }
    return n;
}

void Synth::render(float* stereoOut, int numFrames) {
    const int numSamples = numFrames * 2;
    for (int i = 0; i < numSamples; ++i) stereoOut[i] = 0.0f;

    int active = 0;
    for (auto& v : mVoices) {
        if (v.isActive()) {
            v.renderAdd(stereoOut, numFrames, mPitchBendRatio, mCutoffMod);
            ++active;
        }
    }
    mActiveVoices.store(active, std::memory_order_relaxed);

    // Master gain plus a soft saturator standing in for the real limiter. tanh is
    // not a limiter -- it distorts rather than controls -- but it guarantees the
    // signal reaching the dither stage is inside [-1, 1], which is the contract
    // that stage depends on. Replace with a proper lookahead limiter in M6.
    for (int i = 0; i < numSamples; ++i) {
        stereoOut[i] = std::tanh(stereoOut[i] * mMasterGain);
    }
}
