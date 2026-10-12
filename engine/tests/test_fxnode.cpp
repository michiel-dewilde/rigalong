// FxNode: Surge XT's effects behind one node -- latency, timing, tempo sync,
// the type swap, the reset and the send.
//
// FxNode lives in the app (it includes sst-effects), but nothing in it is
// Android: run_tests.sh compiles it here with the same headers, so what the
// tablet measured on the line out is pinned down where it can be rerun.

#include "FxNode.h"

#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "harness.h"

using rigalong::FxNode;

namespace {

constexpr double kRate = 44100.0;

int indexOf(const FxNode& n, const std::string& name) {
    for (int i = 0; i < n.numParams(); ++i) {
        if (name == n.paramName(i)) return i;
    }
    return -1;
}

// Feeds `in` (mono, both channels) through the node in blocks of `block`; returns the left output.
std::vector<float> run(FxNode& n, const std::vector<float>& in, int block = 128, double tempo = 120.0) {
    std::vector<float> out(in.size(), 0.0f);
    std::vector<float> ib(static_cast<size_t>(block) * 2), ob(static_cast<size_t>(block) * 2);
    for (size_t at = 0; at < in.size(); at += static_cast<size_t>(block)) {
        const int frames = static_cast<int>(std::min<size_t>(static_cast<size_t>(block), in.size() - at));
        for (int i = 0; i < frames; ++i) ib[static_cast<size_t>(i)] = ib[static_cast<size_t>(block + i)] = in[at + static_cast<size_t>(i)];
        rigalong::AudioPort pin{ib.data(), 2, block};
        rigalong::AudioPort pout{ob.data(), 2, block};
        rigalong::ProcessArgs a;
        a.numFrames = frames;
        a.sampleRate = kRate;
        a.tempo = tempo;
        a.audioIn = &pin;
        a.numAudioIn = 1;
        a.audioOut = &pout;
        a.numAudioOut = 1;
        n.process(a);
        std::memcpy(out.data() + at, ob.data(), static_cast<size_t>(frames) * sizeof(float));
    }
    return out;
}

std::vector<float> impulse(size_t length, size_t at = 0) {
    std::vector<float> x(length, 0.0f);
    x[at] = 1.0f;
    return x;
}

// Where the largest magnitude sits within [from, to).
size_t peakAt(const std::vector<float>& x, size_t from, size_t to) {
    size_t best = from;
    for (size_t i = from; i < to && i < x.size(); ++i) {
        if (std::fabs(x[i]) > std::fabs(x[best])) best = i;
    }
    return best;
}

float energy(const std::vector<float>& x, size_t from, size_t to) {
    float e = 0.0f;
    for (size_t i = from; i < to && i < x.size(); ++i) e += x[i] * x[i];
    return e;
}

// Surge's delay glides its time towards a new setting rather than jumping
// (a jump would be a click); three seconds is enough for it to arrive.
void settle(FxNode& n, double tempo = 120.0);

// A delay with one clean echo: no feedback, no modulation, filters wide open.
void plainDelay(FxNode& n, float seconds) {
    n.prepare(kRate, 512);
    n.setParam(0, FxNode::kDelay);
    n.setParam(indexOf(n, "Left"), std::log2(seconds));
    n.setParam(indexOf(n, "Right"), std::log2(seconds));
    n.setParam(indexOf(n, "Feedback"), 0.0f);
    n.setParam(indexOf(n, "Mix"), 0.5f);
}

void settle(FxNode& n, double tempo) { run(n, std::vector<float>(static_cast<size_t>(kRate * 3), 0.0f), 128, tempo); }

}  // namespace

TEST(fx_off_passes_the_signal_sixteen_frames_late_at_any_block_size) {
    for (int block : {128, 37, 16, 1}) {
        FxNode n;
        n.prepare(kRate, 512);
        std::vector<float> y = run(n, impulse(1000, 5), block);
        CHECK_EQ(peakAt(y, 0, y.size()), static_cast<size_t>(5 + FxNode::kBlock));
        CHECK_NEAR(y[5 + FxNode::kBlock], 1.0f, 1e-6f);
    }
}

TEST(fx_delay_echoes_after_its_time) {
    FxNode n;
    plainDelay(n, 0.1f);
    settle(n);
    std::vector<float> y = run(n, impulse(20000, 100));
    const size_t echo = peakAt(y, 1000, y.size());
    // 100 ms = 4410 frames after the dry impulse (which is 16 frames late itself).
    CHECK(std::labs(static_cast<long>(echo) - (100 + 16 + 4410)) <= 3);
}

TEST(fx_a_synced_delay_follows_the_tempo) {
    for (double bpm : {120.0, 150.0}) {
        FxNode n;
        plainDelay(n, 0.5f);                          // 1/4 note at 120
        n.setParam(indexOf(n, "Left sync"), 1.0f);
        n.setParam(indexOf(n, "Right sync"), 1.0f);
        settle(n, bpm);
        std::vector<float> y = run(n, impulse(40000, 100), 128, bpm);
        const long want = static_cast<long>(100 + 16 + std::lround(kRate * 60.0 / bpm));
        // A tempo change glides the time like any other change, and Surge's
        // float lag stalls short of its target: about 10 frames (0,23 ms) at
        // 17640, where a step drops below the float's resolution -- measured
        // steady from 3 s to 18 s. Inaudible; an effect built for the song's
        // tempo (configure, below) is exact.
        CHECK(std::labs(static_cast<long>(peakAt(y, 2000, y.size())) - want) <= 12);
    }
}

// A song's effect is built at its values and at the song's tempo: the very
// first echo is where it belongs, with no glide from the defaults.
TEST(fx_configure_starts_at_the_songs_values_and_tempo) {
    for (double bpm : {120.0, 150.0}) {
        FxNode::setHostTempo(bpm);
        FxNode n;
        n.prepare(kRate, 512);
        n.configure(FxNode::kDelay, {"Left sync", "Right sync", "Left", "Right", "Feedback", "Mix"},
                    {1.0f, 1.0f, -1.0f, -1.0f, 0.0f, 0.5f});
        std::vector<float> y = run(n, impulse(40000, 100), 128, bpm);
        const long want = static_cast<long>(100 + 16 + std::lround(kRate * 60.0 / bpm));
        CHECK(std::labs(static_cast<long>(peakAt(y, 2000, y.size())) - want) <= 3);
    }
    FxNode::setHostTempo(120.0);
}

TEST(fx_configure_leaves_nothing_from_the_last_song) {
    FxNode n;
    n.prepare(kRate, 512);
    n.configure(FxNode::kDelay, {"Feedback", "Left sync"}, {0.75f, 1.0f});
    const int fb = indexOf(n, "Feedback");
    const int sync = indexOf(n, "Left sync");
    CHECK_NEAR(n.getParam(fb), 0.75f, 1e-6f);
    CHECK_EQ(n.getParam(sync), 1.0f);
    // The next song: the same effect, naming neither.
    n.configure(FxNode::kDelay, {"Mix"}, {0.3f});
    FxNode fresh;
    fresh.prepare(kRate, 512);
    fresh.setParam(0, FxNode::kDelay);
    CHECK_NEAR(n.getParam(fb), fresh.getParam(fb), 1e-6f);
    CHECK_EQ(n.getParam(sync), 0.0f);
    CHECK_NEAR(n.getParam(indexOf(n, "Mix")), 0.3f, 1e-6f);
}

TEST(fx_a_value_sent_to_one_effect_never_lands_on_the_next) {
    FxNode n;
    n.prepare(kRate, 512);
    n.setParam(0, FxNode::kDelay);
    const int mixIndex = indexOf(n, "Mix");
    CHECK(mixIndex > 0);
    n.setParam(mixIndex, 0.9f);                        // queued for the delay...
    n.setParam(0, FxNode::kPhaser);                    // ...which is gone before it drains
    const float before = n.getParam(mixIndex);
    run(n, std::vector<float>(256, 0.0f));
    CHECK_EQ(n.getParam(mixIndex), before);
    // ...nor on a fresh effect of the SAME type, as a song change makes.
    n.configure(FxNode::kDelay, {}, {});
    const float fresh = n.getParam(mixIndex);
    n.setParam(mixIndex, 0.9f);
    n.configure(FxNode::kDelay, {}, {});
    run(n, std::vector<float>(256, 0.0f));
    CHECK_EQ(n.getParam(mixIndex), fresh);
}

TEST(fx_amount_zero_stops_feeding_but_lets_the_tail_ring) {
    FxNode n;
    plainDelay(n, 0.1f);
    settle(n);
    // An impulse at full amount, then the amount taken to 0 before its echo.
    std::vector<float> a = run(n, impulse(2000, 100));
    n.setParam(FxNode::kAmount, 0.0f);
    std::vector<float> b = run(n, std::vector<float>(6000, 0.0f));
    // The echo of the first impulse still arrives (100 + 16 + 4410 - 2000 into b).
    CHECK(energy(b, 2400, 2700) > 0.05f);
    // A new impulse now: full level, dry, and no echo of its own.
    std::vector<float> c = run(n, impulse(12000, 100));
    CHECK_NEAR(c[100 + FxNode::kBlock], 1.0f, 1e-4f);
    CHECK(energy(c, 4000, 5000) < 1e-6f);
}

TEST(fx_types_swap_while_running) {
    FxNode n;
    n.prepare(kRate, 512);
    std::vector<float> noise(1024);
    for (size_t i = 0; i < noise.size(); ++i) noise[i] = static_cast<float>((i * 7919) % 200) / 100.0f - 1.0f;
    for (int round = 0; round < 3; ++round) {
        for (int t = 0; t < FxNode::kNumTypes; ++t) {
            n.setParam(0, static_cast<float>(t));
            std::vector<float> y = run(n, noise, 64);
            for (float v : y) CHECK(std::isfinite(v) && std::fabs(v) < 8.0f);
        }
    }
}
