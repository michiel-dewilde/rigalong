// Click: the metronome, placed on the sample.

#include <bagosynths/Click.h>

#include <cmath>
#include <cstdlib>
#include <vector>

#include "harness.h"

using bagosynths::Click;

namespace {

constexpr double kRate = 44100.0;

// Renders `frames` frames in blocks of `block`; returns the left channel.
std::vector<float> run(Click& c, int frames, int block = 128) {
    std::vector<float> stereo(static_cast<size_t>(frames) * 2, 0.0f);
    for (int at = 0; at < frames; at += block) {
        const int n = std::min(block, frames - at);
        c.render(stereo.data() + at * 2, n, kRate);
    }
    std::vector<float> left(frames);
    for (int i = 0; i < frames; ++i) left[i] = stereo[i * 2];
    return left;
}

// Frames at which a burst starts: a non-zero sample after at least 10 ms of zeros.
std::vector<int> onsets(const std::vector<float>& x) {
    std::vector<int> out;
    int quiet = 1 << 30;
    for (int i = 0; i < static_cast<int>(x.size()); ++i) {
        if (x[i] != 0.0f) {
            if (quiet > 441) out.push_back(i);
            quiet = 0;
        } else {
            ++quiet;
        }
    }
    return out;
}

float peakFrom(const std::vector<float>& x, int at, int n) {
    float p = 0.0f;
    for (int i = at; i < at + n && i < static_cast<int>(x.size()); ++i) p = std::max(p, std::fabs(x[i]));
    return p;
}

}  // namespace

TEST(click_is_silent_until_started) {
    Click c;
    std::vector<float> x = run(c, 4410);
    for (float v : x) CHECK_EQ(v, 0.0f);
    CHECK(!c.running());
}

TEST(click_lands_on_every_beat_to_the_sample) {
    Click c;
    c.start(120.0, 4, 0);  // 22050 frames a beat
    std::vector<int> on = onsets(run(c, 22050 * 6 + 100));
    CHECK_EQ(on.size(), static_cast<size_t>(7));
    for (size_t k = 0; k < on.size(); ++k) {
        // The first sample of a burst is sin(0) = 0, so the onset is one later.
        CHECK(std::abs(on[k] - static_cast<int>(22050 * k) - 1) <= 1);
    }
    CHECK(c.running());
}

TEST(click_does_not_depend_on_the_block_size) {
    Click a, b;
    a.start(97.0, 3, 0);
    b.start(97.0, 3, 0);
    std::vector<float> x = run(a, 100000, 128);
    std::vector<float> y = run(b, 100000, 37);
    for (size_t i = 0; i < x.size(); ++i) CHECK_EQ(x[i], y[i]);
}

TEST(click_accents_the_first_beat_of_the_bar) {
    Click c;
    c.start(120.0, 3, 0);
    std::vector<float> x = run(c, 22050 * 4);
    const float bar = peakFrom(x, 0, 1000);
    const float beat2 = peakFrom(x, 22050, 1000);
    const float beat3 = peakFrom(x, 44100, 1000);
    const float bar2 = peakFrom(x, 66150, 1000);
    CHECK(bar > beat2 * 1.4f);
    CHECK_NEAR(beat2, beat3, 1e-3f);
    CHECK_NEAR(bar, bar2, 1e-3f);
}

TEST(click_count_in_stops_on_its_own) {
    Click c;
    c.start(120.0, 4, 4);
    std::vector<float> x = run(c, 22050 * 8);
    CHECK_EQ(onsets(x).size(), static_cast<size_t>(4));
    CHECK(!c.running());
    CHECK_EQ(c.beatsRendered(), static_cast<int64_t>(4));
}

TEST(click_stop_lets_the_last_burst_ring_out) {
    Click c;
    c.start(120.0, 4, 0);
    std::vector<float> stereo(2 * 22050 * 3, 0.0f);
    c.render(stereo.data(), 128, kRate);
    c.stop();
    c.render(stereo.data() + 256, 22050 * 3 - 128, kRate);
    std::vector<float> left(22050 * 3);
    for (size_t i = 0; i < left.size(); ++i) left[i] = stereo[i * 2];
    CHECK_EQ(onsets(left).size(), static_cast<size_t>(1));
    // The burst carried on past the stop, decaying, instead of being cut.
    CHECK(peakFrom(left, 128, 64) > 0.1f);
    CHECK(peakFrom(left, 2700, 22050) < 1e-6f);
}

TEST(click_restart_counts_from_one_again) {
    Click c;
    c.start(120.0, 4, 0);
    run(c, 30000);
    c.start(120.0, 4, 0);
    std::vector<float> x = run(c, 22050 + 100);
    std::vector<int> on = onsets(x);
    CHECK(!on.empty());
    // The new run's first beat is an accent, now, not wherever the old count was.
    CHECK(on[0] <= 2);
    CHECK(peakFrom(x, 0, 1000) > peakFrom(x, 22050, 1000) * 1.4f);
}

TEST(click_tempo_change_bends_the_next_beat) {
    Click c;
    c.start(120.0, 4, 0);
    run(c, 11025);       // half a beat at 120
    c.setBpm(60.0);      // the other half now takes 22050 frames
    std::vector<int> on = onsets(run(c, 40000));
    CHECK(!on.empty());
    CHECK(std::abs(on[0] - 22050 - 1) <= 1);
}

TEST(click_follows_its_gain) {
    Click c;
    c.setGain(0.25f);
    c.start(120.0, 4, 0);
    std::vector<float> x = run(c, 2000);
    const float p = peakFrom(x, 0, 2000);
    CHECK(p > 0.2f && p <= 0.25f);
}
