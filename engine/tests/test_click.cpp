// Click: the metronome, placed on the sample.

#include <rigalong/Click.h>

#include <cmath>
#include <cstdlib>
#include <vector>

#include "harness.h"

using rigalong::Click;

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

TEST(click_count_in_heard_then_silent_goes_on_counting) {
    Click c;
    c.start(120.0, 4, 0, 4, -1);  // continuous, the first four beats heard
    std::vector<float> x = run(c, 22050 * 8 + 100);
    CHECK_EQ(onsets(x).size(), static_cast<size_t>(4));
    CHECK(c.running());
    CHECK_EQ(c.beatsRendered(), static_cast<int64_t>(9));
}

TEST(click_bar_counted_in_then_every_beat_alike) {
    Click c;
    c.start(120.0, 4, 0, -1, 4);  // the bar's first beat accented in the first four beats only
    std::vector<float> x = run(c, 22050 * 6);
    const float one = peakFrom(x, 0, 1000);
    const float two = peakFrom(x, 22050, 1000);
    const float five = peakFrom(x, 22050 * 4, 1000);  // beat one of bar two
    CHECK(one > two * 1.4f);
    CHECK_NEAR(five, two, 1e-3f);
}

TEST(click_silent_run_still_counts) {
    Click c;
    c.start(120.0, 4, 0, 0, 0);
    std::vector<float> x = run(c, 22050 * 3);
    for (float v : x) CHECK_EQ(v, 0.0f);
    CHECK(c.running());
    CHECK(c.beatsRendered() >= 3);
}

// The tone of the burst at [at]: zero crossings over its first 10 ms.
static double toneAt(const std::vector<float>& x, int at) {
    int crossings = 0;
    const int n = 441;
    for (int i = at + 1; i < at + n && i < static_cast<int>(x.size()); ++i) {
        if ((x[i - 1] <= 0.0f) != (x[i] <= 0.0f)) ++crossings;
    }
    return crossings / 2.0 / (n / kRate);
}

TEST(click_grouped_bar_marks_its_groups_between_first_and_beats) {
    // 3+2/4: first, beat, beat, GROUP, beat.
    Click c;
    const uint8_t bar[] = {Click::kFirst, Click::kBeat, Click::kBeat, Click::kGroup, Click::kBeat};
    c.start(120.0, bar, 5, nullptr, 0, 0);
    std::vector<float> x = run(c, 22050 * 5 + 1000);   // bar two's first burst whole enough to measure
    std::vector<int> on = onsets(x);
    CHECK_EQ(on.size(), static_cast<size_t>(6));
    const double first = toneAt(x, on[0]), beat = toneAt(x, on[1]), group = toneAt(x, on[3]);
    CHECK(first > group && group > beat);
    CHECK_NEAR(group, Click::kGroupHz, 60.0);
    CHECK(peakFrom(x, on[3], 1000) > peakFrom(x, on[1], 1000));
    // Bar two starts on the first tone again.
    CHECK_NEAR(toneAt(x, on[5]), Click::kAccentHz, 60.0);
}

TEST(click_group_starts_only_leaves_the_other_beats_silent) {
    // 3+3/8 heard as "1 . . 2 . .": the first high, the group start low.
    Click c;
    const uint8_t bar[] = {Click::kFirst, Click::kSilent, Click::kSilent, Click::kBeat, Click::kSilent, Click::kSilent};
    c.start(240.0, bar, 6, nullptr, 0, 12);
    std::vector<float> x = run(c, 11025 * 13);
    std::vector<int> on = onsets(x);
    CHECK_EQ(on.size(), static_cast<size_t>(4));
    CHECK_NEAR(on[1] - on[0], 11025 * 3, 2);
    CHECK_NEAR(toneAt(x, on[1]), Click::kBeatHz, 60.0);
    CHECK(!c.running());
}

TEST(click_lead_bar_in_half_notes_then_the_count) {
    // 4/4 count-in "1 . 2 . | 1 2 3 4": eight beats, six heard.
    Click c;
    // The lead's "1" and "2" on the middle tone, the count's bar high and low.
    const uint8_t lead[] = {Click::kGroup, Click::kSilent, Click::kGroup, Click::kSilent};
    const uint8_t bar[] = {Click::kFirst, Click::kBeat, Click::kBeat, Click::kBeat};
    c.start(120.0, bar, 4, lead, 4, 8);
    std::vector<float> x = run(c, 22050 * 9);
    std::vector<int> on = onsets(x);
    CHECK_EQ(on.size(), static_cast<size_t>(6));
    CHECK_NEAR(on[1] - on[0], 22050 * 2, 2);       // "2" on the half note
    CHECK_NEAR(on[2] - on[0], 22050 * 4, 2);       // then bar two's "1"
    CHECK_NEAR(toneAt(x, on[0]), Click::kGroupHz, 60.0);
    CHECK_NEAR(toneAt(x, on[1]), Click::kGroupHz, 60.0);
    CHECK_NEAR(toneAt(x, on[2]), Click::kAccentHz, 60.0);
    CHECK_NEAR(toneAt(x, on[3]), Click::kBeatHz, 60.0);
    CHECK_EQ(c.beatsRendered(), static_cast<int64_t>(8));
    CHECK(!c.running());
}

TEST(click_without_accents_every_heard_beat_is_alike) {
    Click c;
    const uint8_t bar[] = {Click::kFirst, Click::kBeat, Click::kGroup, Click::kBeat};
    c.start(120.0, bar, 4, nullptr, 0, 0, -1, 0);
    std::vector<float> x = run(c, 22050 * 4);
    std::vector<int> on = onsets(x);
    CHECK_EQ(on.size(), static_cast<size_t>(4));
    for (int i : on) CHECK_NEAR(toneAt(x, i), Click::kBeatHz, 60.0);
}
