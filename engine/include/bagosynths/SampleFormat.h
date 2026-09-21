// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>

// Converting the engine's float output to whatever the output device asked for.
//
// This lives in engine/ -- which depends on nothing -- rather than inside
// AudioEngine, for one reason: it is the part that cannot be checked by
// listening to a meter. The level meters read the FLOAT source, so a packing
// bug (wrong byte order, wrong sign extension, wrong scale) would leave every
// number on screen looking perfect while the device played noise. Here it can
// be tested against known values on the host instead.
//
// See engine/tests/test_sampleformat.cpp.
namespace bagosynths {

// Full-scale for 24-bit signed. Deliberately 2^23 - 1 rather than 2^23, so that
// +1.0f maps to the largest positive value instead of wrapping to the most
// negative one.
inline constexpr float kI24FullScale = 8388607.0f;

// 2^31 - 128, which is the largest float32-representable value below 2^31.
// Using 2147483647.0f would round UP to 2^31 in float and overflow on cast.
inline constexpr float kI32FullScale = 2147483520.0f;

/// One float sample to a signed 24-bit value, clamped.
inline int32_t floatToI24(float s) {
    const float c = std::clamp(s, -1.0f, 1.0f);
    return static_cast<int32_t>(c * kI24FullScale);
}

/// `n` interleaved float samples into AAUDIO_FORMAT_PCM_I24_PACKED: three bytes
/// per sample, little-endian, two's complement.
inline void packI24(const float* src, uint8_t* dst, int n) {
    for (int i = 0; i < n; ++i) {
        const int32_t v = floatToI24(src[i]);
        dst[i * 3 + 0] = static_cast<uint8_t>(static_cast<uint32_t>(v) & 0xFFu);
        dst[i * 3 + 1] = static_cast<uint8_t>((static_cast<uint32_t>(v) >> 8) & 0xFFu);
        dst[i * 3 + 2] = static_cast<uint8_t>((static_cast<uint32_t>(v) >> 16) & 0xFFu);
    }
}

/// `n` interleaved float samples into signed 32-bit, clamped.
inline void packI32(const float* src, int32_t* dst, int n) {
    for (int i = 0; i < n; ++i) {
        dst[i] = static_cast<int32_t>(std::clamp(src[i], -1.0f, 1.0f) * kI32FullScale);
    }
}

/// Reads back a packed 24-bit sample. For tests, and for anything that has to
/// prove what was actually written.
inline int32_t unpackI24(const uint8_t* p) {
    const uint32_t raw = static_cast<uint32_t>(p[0]) |
                         (static_cast<uint32_t>(p[1]) << 8) |
                         (static_cast<uint32_t>(p[2]) << 16);
    // Sign-extend from 24 bits.
    return (raw & 0x800000u) ? static_cast<int32_t>(raw | 0xFF000000u)
                             : static_cast<int32_t>(raw);
}

}  // namespace bagosynths
