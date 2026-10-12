// Tests for the float -> device-format conversion.
//
// These exist because this is the one part of the output path that CANNOT be
// checked by looking at the app. Every level meter in the rig reads the float
// source, so a wrong byte order, a missing sign extension or a bad scale would
// leave `out` and `cb` reading exactly as they should while the device played
// noise -- and the CP-88 is the only 24-bit device on the bench, so the first
// person to notice would be whoever is standing in front of the speakers.

#include "harness.h"

#include <rigalong/SampleFormat.h>

#include <cstdint>
#include <vector>

using namespace rigalong;

namespace {

TEST(i24_silence_is_all_zero_bytes) {
    const float in[2] = {0.0f, 0.0f};
    uint8_t out[6] = {1, 2, 3, 4, 5, 6};
    packI24(in, out, 2);
    for (int i = 0; i < 6; ++i) CHECK_EQ(static_cast<int>(out[i]), 0);
}

TEST(i24_is_little_endian) {
    // 1.0 -> 0x7FFFFF, which must be written low byte first.
    const float in[1] = {1.0f};
    uint8_t out[3] = {};
    packI24(in, out, 1);
    CHECK_EQ(static_cast<int>(out[0]), 0xFF);
    CHECK_EQ(static_cast<int>(out[1]), 0xFF);
    CHECK_EQ(static_cast<int>(out[2]), 0x7F);
}

TEST(i24_negative_full_scale_is_two_s_complement) {
    const float in[1] = {-1.0f};
    uint8_t out[3] = {};
    packI24(in, out, 1);
    // -8388607 = 0x800001 in 24-bit two's complement.
    CHECK_EQ(static_cast<int>(out[0]), 0x01);
    CHECK_EQ(static_cast<int>(out[1]), 0x00);
    CHECK_EQ(static_cast<int>(out[2]), 0x80);
}

TEST(i24_round_trips_through_unpack) {
    const std::vector<float> in = {0.0f,  0.5f,  -0.5f, 0.25f,
                                   -0.25f, 1.0f, -1.0f, 0.001f, -0.001f};
    std::vector<uint8_t> packed(in.size() * 3);
    packI24(in.data(), packed.data(), static_cast<int>(in.size()));
    for (size_t i = 0; i < in.size(); ++i) {
        const int32_t got = unpackI24(&packed[i * 3]);
        const int32_t expect = static_cast<int32_t>(in[i] * kI24FullScale);
        CHECK_EQ(got, expect);
        // And it must stay inside the 24-bit signed range, which is the bug
        // that would show up as a full-scale click once per loud sample.
        CHECK(got <= 8388607);
        CHECK(got >= -8388608);
    }
}

TEST(i24_clamps_rather_than_wrapping) {
    // An engine that overshoots must clip, not wrap: wrapping turns a loud
    // note into a full-scale square edge, which is the worst sound a rig can
    // make and the fastest way to destroy a tweeter.
    const float in[4] = {2.0f, -2.0f, 1.5f, -1.5f};
    uint8_t out[12] = {};
    packI24(in, out, 4);
    CHECK_EQ(unpackI24(&out[0]), 8388607);
    CHECK_EQ(unpackI24(&out[3]), -8388607);
    CHECK_EQ(unpackI24(&out[6]), 8388607);
    CHECK_EQ(unpackI24(&out[9]), -8388607);
}

TEST(i24_is_monotonic_across_zero) {
    // Ascending input must give ascending output. Catches a sign-extension
    // error, which typically inverts one half of the waveform.
    int32_t prev = -9000000;
    for (int i = -100; i <= 100; ++i) {
        const float s = static_cast<float>(i) / 100.0f;
        uint8_t out[3] = {};
        packI24(&s, out, 1);
        const int32_t v = unpackI24(out);
        CHECK(v >= prev);
        prev = v;
    }
}

TEST(i32_clamps_and_does_not_overflow) {
    const float in[5] = {0.0f, 1.0f, -1.0f, 2.0f, -2.0f};
    int32_t out[5] = {};
    packI32(in, out, 5);
    CHECK_EQ(out[0], 0);
    CHECK(out[1] > 2147483000);   // near full scale, not wrapped negative
    CHECK(out[2] < -2147483000);
    CHECK_EQ(out[3], out[1]);     // clamped
    CHECK_EQ(out[4], out[2]);
}

}  // namespace
