// WireControl: a live set switch changes wires, and must never strand a note
// or click. REQUIREMENTS S4.8, I4.13, I4.14; PLAN.md D7, D8.

#include <bagosynths/Wire.h>

#include <cmath>
#include <vector>

#include "harness.h"

using namespace bagosynths;

namespace {

constexpr double kRate = 44100.0;

MidiEvent ev(uint8_t status, uint8_t d1, uint8_t d2) { return MidiEvent{0, status, d1, d2}; }

MidiBuffer buf(std::initializer_list<MidiEvent> es) {
    MidiBuffer b;
    for (const MidiEvent& e : es) b.push(e);
    return b;
}

bool has(const MidiBuffer& b, uint8_t status, uint8_t d1, uint8_t d2) {
    for (int i = 0; i < b.size(); ++i) {
        if (b[i].status == status && b[i].data1 == d1 && b[i].data2 == d2) return true;
    }
    return false;
}

}  // namespace

TEST(an_on_wire_passes_everything) {
    WireControl w;
    MidiBuffer out;
    w.passMidi(buf({ev(0x90, 60, 100), ev(0xB0, 1, 20)}), out, true, true);
    CHECK_EQ(out.size(), 2);
}

TEST(note_off_follows_its_note_on_after_the_wire_is_switched_off) {
    WireControl w;
    MidiBuffer out;
    w.passMidi(buf({ev(0x90, 60, 100)}), out, true, true);
    w.setOn(false);
    // A new note is not let through; the held note's release is.
    w.passMidi(buf({ev(0x90, 64, 100), ev(0x80, 60, 0), ev(0x80, 64, 0)}), out, true, true);
    CHECK_EQ(out.size(), 1);
    CHECK(has(out, 0x80, 60, 0));
    // Once released, it is forgotten: a second note-off does not pass.
    w.passMidi(buf({ev(0x80, 60, 0)}), out, true, true);
    CHECK_EQ(out.size(), 0);
}

TEST(note_on_with_velocity_zero_counts_as_a_release) {
    WireControl w;
    MidiBuffer out;
    w.passMidi(buf({ev(0x91, 48, 90)}), out, true, true);
    w.setOn(false);
    w.passMidi(buf({ev(0x91, 48, 0)}), out, true, true);
    CHECK(has(out, 0x91, 48, 0));
}

TEST(pedal_up_follows_a_pedal_down_it_carried) {
    WireControl w;
    MidiBuffer out;
    w.passMidi(buf({ev(0xB0, 64, 127), ev(0x90, 60, 100), ev(0x80, 60, 0)}), out, true, true);
    w.setOn(false);
    w.passMidi(buf({ev(0xB0, 64, 0)}), out, true, true);
    CHECK(has(out, 0xB0, 64, 0));
    // A pedal-down while off is not passed.
    w.passMidi(buf({ev(0xB0, 64, 127)}), out, true, true);
    CHECK_EQ(out.size(), 0);
}

TEST(poly_aftertouch_of_a_held_note_passes_while_off) {
    WireControl w;
    MidiBuffer out;
    w.passMidi(buf({ev(0x90, 60, 100)}), out, true, true);
    w.setOn(false);
    w.passMidi(buf({ev(0xA0, 60, 50), ev(0xA0, 61, 50)}), out, true, true);
    CHECK_EQ(out.size(), 1);
    CHECK(has(out, 0xA0, 60, 50));
}

TEST(panic_always_passes) {
    WireControl w;
    w.setOn(false);
    MidiBuffer out;
    w.passMidi(buf({ev(0xB0, 123, 0), ev(0xB0, 120, 0)}), out, true, true);
    CHECK_EQ(out.size(), 2);
}

TEST(coming_on_replays_the_controller_state_first) {
    WireControl w;
    w.setOn(false);
    MidiBuffer out;
    // Seen while off: pedal down, mod wheel, a bend.
    w.passMidi(buf({ev(0xB0, 64, 127), ev(0xB0, 1, 40), ev(0xE0, 0, 0x50)}), out, true, true);
    CHECK_EQ(out.size(), 0);
    w.setOn(true);
    w.passMidi(buf({ev(0x90, 60, 100)}), out, true, true);
    CHECK(has(out, 0xB0, 64, 127));
    CHECK(has(out, 0xB0, 1, 40));
    CHECK(has(out, 0xE0, 0, 0x50));
    CHECK_EQ(out[out.size() - 1].status, 0x90);   // the note comes after the state
}

TEST(a_disabled_node_is_brought_up_to_date_when_enabled) {
    WireControl w;
    MidiBuffer out;
    w.passMidi(buf({ev(0xB0, 64, 127)}), out, true, false);   // node not running
    w.passMidi(buf({}), out, true, true);                       // enabled again
    CHECK(has(out, 0xB0, 64, 127));
}

TEST(default_controller_values_are_not_replayed) {
    WireControl w;
    w.setOn(false);
    MidiBuffer out;
    w.passMidi(buf({ev(0xB0, 64, 0), ev(0xB0, 1, 0)}), out, true, true);
    w.setOn(true);
    w.passMidi(buf({}), out, true, true);
    CHECK_EQ(out.size(), 0);
}

TEST(audio_switches_off_as_a_ramp_not_a_step) {
    WireControl w;
    const int n = 2048;
    std::vector<float> src(n, 1.0f), dst(n, 0.0f);
    w.setOn(false);
    w.mixAudio(src.data(), dst.data(), 1, n, n, true, kRate);
    float worst = 0.0f;
    for (int i = 1; i < n; ++i) worst = std::max(worst, std::fabs(dst[i] - dst[i - 1]));
    CHECK(worst <= 1.0f / static_cast<float>(WireControl::kRampSeconds * kRate) + 1e-6f);
    CHECK_EQ(dst[n - 1], 0.0f);                // 15 ms is well inside 2048 frames
    CHECK(dst[0] > 0.99f);
}

TEST(a_wire_off_and_settled_writes_silence_when_first) {
    WireControl w;
    w.setOn(false);
    std::vector<float> src(64, 1.0f), dst(64, 7.0f);
    for (int k = 0; k < 40; ++k) w.mixAudio(src.data(), dst.data(), 1, 64, 64, false, kRate);
    std::fill(dst.begin(), dst.end(), 7.0f);
    w.mixAudio(src.data(), dst.data(), 1, 64, 64, true, kRate);
    for (float v : dst) CHECK_EQ(v, 0.0f);
}

TEST(gain_is_applied_and_ramped_to) {
    WireControl w;
    w.setGain(0.5f);
    std::vector<float> src(4096, 1.0f), dst(4096, 0.0f);
    w.mixAudio(src.data(), dst.data(), 1, 4096, 4096, true, kRate);
    CHECK_NEAR(dst[4095], 0.5f, 1e-6f);
}

TEST(switchable_edges_get_a_mixing_step_even_alone) {
    // A plain single edge is aliased; a switchable one must be mixed, or its
    // on/off could never be applied.
    struct Src : Node {
        const char* typeName() const override { return "src"; }
        int numOutputs() const override { return 1; }
        PinSpec outputPin(int) const override { return {DataType::Audio, 1, "out"}; }
        void process(const ProcessArgs& a) override {
            for (int i = 0; i < a.numFrames; ++i) a.audioOut[0].channel(0)[i] = 1.0f;
        }
    };
    struct Sink : Node {
        const char* typeName() const override { return "sink"; }
        int numInputs() const override { return 1; }
        PinSpec inputPin(int) const override { return {DataType::Audio, 1, "in"}; }
        float last = -1.0f;
        void process(const ProcessArgs& a) override { last = a.audioIn[0].channel(0)[a.numFrames - 1]; }
    };
    Graph g;
    auto* sink = new Sink;
    const NodeId s = g.addNode(std::make_unique<Src>());
    const NodeId d = g.addNode(std::unique_ptr<Node>(sink));
    auto control = g.connectSwitchable(s, 0, d, 0);
    CHECK(control != nullptr);
    auto sched = g.compile(kRate, 1024);
    CHECK(sched != nullptr);
    CHECK_EQ((int)sched->audioSums.size(), 1);
    sched->run(1024, kRate);
    CHECK_NEAR(sink->last, 1.0f, 1e-6f);
    control->setOn(false);
    sched->run(1024, kRate);
    CHECK_NEAR(sink->last, 0.0f, 1e-6f);
}
