#pragma once

#include <cmath>
#include <cstdint>

// The CP-88's USB endpoint is 16-bit (measured: HW format=I16, regardless of what
// the app stream requests). The engine runs in float, so something has to quantise
// at the very end. Plain truncation produces quantisation distortion that is
// correlated with the signal -- audible precisely where this instrument will be
// judged: long reverb tails decaying into silence, and quiet solo passages.
//
// TPDF (triangular probability density function) dither decorrelates the error,
// turning distortion into a constant, benign noise floor about 4.8 dB above the
// undithered one. At 16 bits that floor sits near -93 dBFS: inaudible on any
// stage, and cheap enough to be free.
class Dither {
public:
    // Converts interleaved float samples in [-1, 1] to 16-bit with TPDF dither.
    void process(const float* in, int16_t* out, int numSamples) {
        for (int i = 0; i < numSamples; ++i) {
            // Two independent uniform randoms summed give a triangular
            // distribution spanning +/-1 LSB.
            const float r1 = nextUniform();
            const float r2 = nextUniform();
            const float tpdf = r1 - r2;   // triangular, [-1, 1) LSB

            float scaled = in[i] * 32767.0f + tpdf;

            // Saturate. There is no limiter in the default chain any more, so
            // this is the deliberate end of the range rather than a backstop:
            // the master volume is the last thing before the conversion, and an
            // over is meant to be audible as an over rather than wrapping.
            if (scaled > 32767.0f) scaled = 32767.0f;
            if (scaled < -32768.0f) scaled = -32768.0f;

            out[i] = static_cast<int16_t>(std::lrintf(scaled));
        }
    }

private:
    // xorshift32. Not cryptographic, not meant to be -- it is fast, allocation
    // free, and has more than enough quality for dither.
    inline float nextUniform() {
        mState ^= mState << 13;
        mState ^= mState >> 17;
        mState ^= mState << 5;
        // Map to [0, 1)
        return static_cast<float>(mState >> 8) * (1.0f / 16777216.0f);
    }

    uint32_t mState = 0x9E3779B9u;
};
