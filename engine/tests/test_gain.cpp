// SmoothedGain: the ramp that stops a level change being a click.

#include <rigalong/Gain.h>

#include <cmath>
#include <vector>

#include "harness.h"

using rigalong::SmoothedGain;

namespace {

constexpr double kRate = 44100.0;

// Frames the ramp should take to cross the whole 0..1 range.
int rampFrames() {
    return static_cast<int>(kRate * SmoothedGain::kRampSeconds);
}

std::vector<float> ones(int n) { return std::vector<float>(n, 1.0f); }

// The largest step between consecutive samples of the ramped signal. This is
// the number that matters: a click IS a large step.
float biggestStep(const std::vector<float>& x) {
    float worst = 0.0f;
    for (size_t i = 1; i < x.size(); ++i) {
        worst = std::max(worst, std::fabs(x[i] - x[i - 1]));
    }
    return worst;
}

}  // namespace

TEST(gain_at_unity_leaves_the_signal_alone) {
    SmoothedGain g;
    g.prepare(kRate, 1.0f);
    std::vector<float> buf = ones(64);
    g.apply(buf.data(), nullptr, 64, 1.0f);
    for (float v : buf) CHECK_EQ(v, 1.0f);
}

TEST(gain_already_at_target_applies_flat) {
    SmoothedGain g;
    g.prepare(kRate, 0.5f);
    std::vector<float> buf = ones(32);
    g.apply(buf.data(), nullptr, 32, 0.5f);
    for (float v : buf) CHECK_EQ(v, 0.5f);
}

// The point of the whole file: muting a sounding instrument must not put a
// step into the waveform. Before the ramp this went 1 -> 0 in one sample.
TEST(muting_does_not_step_the_signal) {
    SmoothedGain g;
    g.prepare(kRate, 1.0f);
    const int n = rampFrames() * 2;
    std::vector<float> buf = ones(n);
    g.apply(buf.data(), nullptr, n, 0.0f);

    CHECK_EQ(buf[0] < 1.0f, true);         // started moving immediately
    CHECK_EQ(buf[n - 1], 0.0f);            // and got all the way there

    // No sample-to-sample jump bigger than one ramp step. A 15 ms ramp at
    // 44,1 kHz is 661 samples, so each step is under 0.002 of full scale.
    CHECK_EQ(biggestStep(buf) < 0.005f, true);
}

TEST(ramp_reaches_the_target_in_the_advertised_time) {
    SmoothedGain g;
    g.prepare(kRate, 0.0f);
    const int n = rampFrames();
    std::vector<float> buf = ones(n + 8);
    g.apply(buf.data(), nullptr, n + 8, 1.0f);
    CHECK_EQ(buf[n + 7], 1.0f);
    CHECK_EQ(g.steady(1.0f), true);
}

TEST(ramp_survives_a_block_boundary) {
    // The ramp is longer than one render block, so it MUST carry its state
    // across calls -- a per-block ramp would restart and never arrive.
    SmoothedGain g;
    g.prepare(kRate, 1.0f);
    const int block = 128;
    float last = 1.0f;
    bool arrived = false;
    for (int b = 0; b < 32; ++b) {
        std::vector<float> buf = ones(block);
        g.apply(buf.data(), nullptr, block, 0.0f);
        CHECK_EQ(buf[0] <= last, true);    // never jumps back up
        last = buf[block - 1];
        if (g.steady(0.0f)) { arrived = true; break; }
    }
    CHECK_EQ(arrived, true);
}

TEST(both_channels_get_the_same_gain) {
    SmoothedGain g;
    g.prepare(kRate, 1.0f);
    const int n = 256;
    std::vector<float> l = ones(n), r = ones(n);
    g.apply(l.data(), r.data(), n, 0.0f);
    for (int i = 0; i < n; ++i) CHECK_EQ(l[i], r[i]);
}

TEST(interleaved_matches_the_planar_ramp) {
    // The master uses the interleaved form; it must ramp identically, or a
    // fader move would behave differently from an instrument level.
    SmoothedGain planar, inter;
    planar.prepare(kRate, 1.0f);
    inter.prepare(kRate, 1.0f);

    const int n = 512;
    std::vector<float> l = ones(n), r = ones(n);
    std::vector<float> lr(static_cast<size_t>(n) * 2, 1.0f);

    planar.apply(l.data(), r.data(), n, 0.25f);
    inter.applyInterleavedStereo(lr.data(), n, 0.25f);

    for (int i = 0; i < n; ++i) {
        CHECK_EQ(lr[i * 2], l[i]);
        CHECK_EQ(lr[i * 2 + 1], r[i]);
    }
}

TEST(a_rising_target_is_ramped_too) {
    SmoothedGain g;
    g.prepare(kRate, 0.0f);
    const int n = 256;
    std::vector<float> buf = ones(n);
    g.apply(buf.data(), nullptr, n, 1.0f);
    CHECK_EQ(buf[0] > 0.0f, true);
    CHECK_EQ(buf[n - 1] > buf[0], true);
    CHECK_EQ(biggestStep(buf) < 0.005f, true);
}
