// MidiZoneNode: splits, layers, transpose and channel maps that a live set may
// change while notes are held -- without ever stranding one (REQUIREMENTS S4.2,
// S4.8, I4.14).

#include <bagosynths/MidiZone.h>

#include "harness.h"

using namespace bagosynths;

namespace {

struct Rig {
    MidiZoneNode zone;
    MidiBuffer in, out;
    const MidiBuffer* inPtr = &in;
    MidiBuffer* outPtr = &out;

    const MidiBuffer& run(std::initializer_list<MidiEvent> es) {
        in.clear();
        for (const MidiEvent& e : es) in.push(e);
        ProcessArgs a;
        a.numFrames = 64;
        a.midiIn = &inPtr;
        a.numMidiIn = 1;
        a.midiOut = &outPtr;
        a.numMidiOut = 1;
        zone.process(a);
        return out;
    }
};

MidiEvent ev(uint8_t s, uint8_t a, uint8_t b) { return MidiEvent{0, s, a, b}; }

}  // namespace

TEST(zone_defaults_pass_everything) {
    Rig r;
    const MidiBuffer& o = r.run({ev(0x90, 60, 100), ev(0xB0, 1, 30), ev(0xE0, 0, 64), ev(0xC0, 5, 0)});
    CHECK_EQ(o.size(), 4);
}

TEST(zone_key_range_makes_a_split) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kKeyLow, 60);
    const MidiBuffer& o = r.run({ev(0x90, 59, 100), ev(0x90, 60, 100)});
    CHECK_EQ(o.size(), 1);
    CHECK_EQ(o[0].data1, 60);
}

TEST(zone_velocity_range_makes_a_layer) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kVelLow, 100);
    const MidiBuffer& o = r.run({ev(0x90, 60, 99), ev(0x90, 61, 100)});
    CHECK_EQ(o.size(), 1);
    CHECK_EQ(o[0].data1, 61);
}

TEST(zone_transposes_and_remaps_the_channel) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kTranspose, 12);
    r.zone.setParam(MidiZoneNode::kChannelOut, 3);
    const MidiBuffer& o = r.run({ev(0x90, 60, 100)});
    CHECK_EQ(o[0].status, 0x92);
    CHECK_EQ(o[0].data1, 72);
}

TEST(note_off_follows_the_transpose_of_its_note_on) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kTranspose, 12);
    r.run({ev(0x90, 60, 100)});
    r.zone.setParam(MidiZoneNode::kTranspose, -5);          // the live set changed
    const MidiBuffer& o = r.run({ev(0x80, 60, 0)});
    CHECK_EQ(o.size(), 1);
    CHECK_EQ(o[0].status, 0x80);
    CHECK_EQ(o[0].data1, 72);                                // released as it was struck
}

TEST(note_off_passes_even_after_the_split_moved_away) {
    Rig r;
    r.run({ev(0x90, 50, 100)});
    r.zone.setParam(MidiZoneNode::kKeyLow, 60);
    const MidiBuffer& o = r.run({ev(0x80, 50, 0), ev(0x90, 50, 100)});
    CHECK_EQ(o.size(), 1);                                   // the release, not the new note
    CHECK_EQ(o[0].status, 0x80);
}

TEST(a_note_that_did_not_pass_has_no_release) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kKeyLow, 60);
    r.run({ev(0x90, 50, 100)});
    r.zone.setParam(MidiZoneNode::kKeyLow, 0);
    const MidiBuffer& o = r.run({ev(0x80, 50, 0)});
    CHECK_EQ(o.size(), 0);
}

TEST(pedal_up_follows_the_channel_its_pedal_down_went_to) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kChannelOut, 2);
    r.run({ev(0xB0, 64, 127)});
    r.zone.setParam(MidiZoneNode::kChannelOut, 5);
    const MidiBuffer& o = r.run({ev(0xB0, 64, 0)});
    CHECK_EQ(o.size(), 1);
    CHECK_EQ(o[0].status, 0xB1);                             // channel 2, where the pedal went down
}

TEST(pedal_up_is_released_even_with_controllers_now_blocked) {
    Rig r;
    r.run({ev(0xB0, 64, 127)});
    r.zone.setParam(MidiZoneNode::kPassCC, 0);
    const MidiBuffer& o = r.run({ev(0xB0, 64, 0), ev(0xB0, 1, 50)});
    CHECK_EQ(o.size(), 1);
    CHECK_EQ(o[0].data1, 64);
}

TEST(channel_filter_lets_only_its_channel_through) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kChannelIn, 2);
    const MidiBuffer& o = r.run({ev(0x90, 60, 100), ev(0x91, 61, 100)});
    CHECK_EQ(o.size(), 1);
    CHECK_EQ(o[0].data1, 61);
}

TEST(cc_remap_renumbers_and_drops) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kCcFromA, 1);
    r.zone.setParam(MidiZoneNode::kCcToA, 11);
    r.zone.setParam(MidiZoneNode::kCcFromB, 7);
    r.zone.setParam(MidiZoneNode::kCcToB, 128);              // drop
    const MidiBuffer& o = r.run({ev(0xB0, 1, 40), ev(0xB0, 7, 100), ev(0xB0, 74, 9)});
    CHECK_EQ(o.size(), 2);
    CHECK_EQ(o[0].data1, 11);
    CHECK_EQ(o[1].data1, 74);
}

TEST(message_types_can_be_blocked) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kPassBend, 0);
    r.zone.setParam(MidiZoneNode::kPassProgram, 0);
    const MidiBuffer& o = r.run({ev(0xE0, 0, 64), ev(0xC0, 3, 0), ev(0xD0, 20, 0)});
    CHECK_EQ(o.size(), 1);
    CHECK_EQ(o[0].status, 0xD0);
}

TEST(panic_reaches_the_output_channel) {
    Rig r;
    r.zone.setParam(MidiZoneNode::kChannelIn, 4);
    r.zone.setParam(MidiZoneNode::kChannelOut, 9);
    const MidiBuffer& o = r.run({ev(0xB0, 123, 0)});         // channel 1 in, filtered channel 4
    CHECK_EQ(o.size(), 1);
    CHECK_EQ(o[0].status, 0xB8);
}
