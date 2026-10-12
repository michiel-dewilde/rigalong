// Tests for the USB-MIDI 1.0 packet decoder and the UMP (MIDI 2.0) translator.
//
// The 1.0 cases include the CP-88's own idle traffic, `0f fe 00 00` -- Active
// Sensing on cable 0 -- which is what the app actually read off bulk 0x82 on
// 2026-09-22. The 2.0 cases follow the UMP specification's worked translations;
// there is no MIDI 2.0 device on the bench to capture from.

#include "harness.h"

#include <rigalong/UsbMidi.h>

#include <vector>

using namespace rigalong::usbmidi;

namespace {

// ------------------------------------------------------------ USB MIDI 1.0 ---

TEST(cp88_active_sensing_packet) {
    const uint8_t p[4] = {0x0F, 0xFE, 0x00, 0x00};
    Message m;
    CHECK(decodePacket(p, m));
    CHECK_EQ(m.port, 0);
    CHECK_EQ(m.length, 1);
    CHECK_EQ(m.bytes[0], 0xFE);
}

TEST(note_on_on_cable_1) {
    const uint8_t p[4] = {0x19, 0x90, 0x3C, 0x64};
    Message m;
    CHECK(decodePacket(p, m));
    CHECK_EQ(m.port, 1);
    CHECK_EQ(m.length, 3);
    CHECK_EQ(m.bytes[0], 0x90);
    CHECK_EQ(m.bytes[1], 0x3C);
    CHECK_EQ(m.bytes[2], 0x64);
}

TEST(program_change_is_two_bytes) {
    const uint8_t p[4] = {0x0C, 0xC0, 0x05, 0x00};
    Message m;
    CHECK(decodePacket(p, m));
    CHECK_EQ(m.length, 2);
}

TEST(zero_padding_is_not_a_message) {
    const uint8_t p[4] = {0x00, 0x00, 0x00, 0x00};
    Message m;
    CHECK(!decodePacket(p, m));
}

// ----------------------------------------------------------- UMP -> 1.0 ---

int translate(std::vector<uint32_t> w, Message out[4]) { return translateUmp(w.data(), out); }

TEST(ump_midi1_channel_voice_passes_through) {
    Message m[4];
    CHECK_EQ(translate({0x20903C64u}, m), 1);   // MT 2, group 0, note on
    CHECK_EQ(m[0].bytes[0], 0x90);
    CHECK_EQ(m[0].bytes[1], 0x3C);
    CHECK_EQ(m[0].bytes[2], 0x64);
}

TEST(ump_group_becomes_the_port) {
    Message m[4];
    CHECK_EQ(translate({0x23903C64u}, m), 1);   // group 3
    CHECK_EQ(m[0].port, 3);
}

TEST(ump_midi2_note_on_full_velocity_is_127) {
    Message m[4];
    CHECK_EQ(translate({0x40903C00u, 0xFFFF0000u}, m), 1);
    CHECK_EQ(m[0].bytes[0], 0x90);
    CHECK_EQ(m[0].bytes[1], 0x3C);
    CHECK_EQ(m[0].bytes[2], 127);
}

TEST(ump_midi2_quiet_note_on_is_not_a_note_off) {
    // A 16-bit velocity below 512 scales to 0, which in MIDI 1.0 would be a
    // note-off. The spec rounds it up to 1 instead.
    Message m[4];
    CHECK_EQ(translate({0x40903C00u, 0x00100000u}, m), 1);
    CHECK_EQ(m[0].bytes[2], 1);
}

TEST(ump_midi2_note_off_velocity_scales_plainly) {
    Message m[4];
    CHECK_EQ(translate({0x40803C00u, 0x80000000u}, m), 1);
    CHECK_EQ(m[0].bytes[0], 0x80);
    CHECK_EQ(m[0].bytes[2], 64);
}

TEST(ump_midi2_cc_32_bit_to_7) {
    Message m[4];
    CHECK_EQ(translate({0x40B00700u, 0xFFFFFFFFu}, m), 1);   // CC 7, max
    CHECK_EQ(m[0].bytes[0], 0xB0);
    CHECK_EQ(m[0].bytes[1], 7);
    CHECK_EQ(m[0].bytes[2], 127);
}

TEST(ump_midi2_pitch_bend_centre_is_8192) {
    Message m[4];
    CHECK_EQ(translate({0x40E00000u, 0x80000000u}, m), 1);
    const int v14 = m[0].bytes[1] | (m[0].bytes[2] << 7);
    CHECK_EQ(v14, 8192);
}

TEST(ump_midi2_program_change_with_bank) {
    Message m[4];
    // option flags bit 0 = bank valid; program 5, bank MSB 1, LSB 2.
    CHECK_EQ(translate({0x40C00001u, 0x05000102u}, m), 3);
    CHECK_EQ(m[0].bytes[0], 0xB0); CHECK_EQ(m[0].bytes[1], 0);  CHECK_EQ(m[0].bytes[2], 1);
    CHECK_EQ(m[1].bytes[0], 0xB0); CHECK_EQ(m[1].bytes[1], 32); CHECK_EQ(m[1].bytes[2], 2);
    CHECK_EQ(m[2].bytes[0], 0xC0); CHECK_EQ(m[2].bytes[1], 5);  CHECK_EQ(m[2].length, 2);
}

TEST(ump_midi2_program_change_without_bank) {
    Message m[4];
    CHECK_EQ(translate({0x40C00000u, 0x05000000u}, m), 1);
    CHECK_EQ(m[0].bytes[0], 0xC0);
    CHECK_EQ(m[0].bytes[1], 5);
}

TEST(ump_midi2_rpn_becomes_four_ccs) {
    Message m[4];
    // RPN bank 0, index 0 (pitch bend range), value = 2 semitones in the MSB.
    CHECK_EQ(translate({0x40200000u, 0x04000000u}, m), 4);
    CHECK_EQ(m[0].bytes[1], 101); CHECK_EQ(m[0].bytes[2], 0);
    CHECK_EQ(m[1].bytes[1], 100); CHECK_EQ(m[1].bytes[2], 0);
    CHECK_EQ(m[2].bytes[1], 6);   CHECK_EQ(m[2].bytes[2], 2);
    CHECK_EQ(m[3].bytes[1], 38);  CHECK_EQ(m[3].bytes[2], 0);
}

TEST(ump_system_real_time) {
    Message m[4];
    CHECK_EQ(translate({0x10FE0000u}, m), 1);   // active sensing
    CHECK_EQ(m[0].length, 1);
    CHECK_EQ(m[0].bytes[0], 0xFE);
}

TEST(ump_per_note_controller_has_no_midi1_form) {
    Message m[4];
    CHECK_EQ(translate({0x40003C01u, 0x12345678u}, m), 0);
}

TEST(ump_word_counts_follow_the_message_type) {
    CHECK_EQ(umpWords(0x00000000u), 1);   // utility
    CHECK_EQ(umpWords(0x20000000u), 1);   // MIDI 1.0 CV
    CHECK_EQ(umpWords(0x30000000u), 2);   // SysEx7
    CHECK_EQ(umpWords(0x40000000u), 2);   // MIDI 2.0 CV
    CHECK_EQ(umpWords(0x50000000u), 4);   // data 128
    CHECK_EQ(umpWords(0xF0000000u), 4);   // UMP stream
}

// ------------------------------------------------------- the assembler ---

void appendLE(std::vector<uint8_t>& b, uint32_t w) {
    b.push_back(w & 0xFF);
    b.push_back((w >> 8) & 0xFF);
    b.push_back((w >> 16) & 0xFF);
    b.push_back(w >> 24);
}

TEST(assembler_survives_a_transfer_boundary_inside_a_packet) {
    std::vector<uint8_t> bytes;
    appendLE(bytes, 0x40903C00u);   // MIDI 2.0 note on, 2 words
    appendLE(bytes, 0xFFFF0000u);
    appendLE(bytes, 0x20803C00u);   // MIDI 1.0 note off, 1 word

    UmpAssembler a;
    std::vector<Message> got;
    auto sink = [&](const Message& m) { got.push_back(m); };
    // Split at 5 bytes: mid-word, and mid-packet.
    a.feed(bytes.data(), 5, sink);
    CHECK_EQ(static_cast<int>(got.size()), 0);
    a.feed(bytes.data() + 5, bytes.size() - 5, sink);
    CHECK_EQ(static_cast<int>(got.size()), 2);
    CHECK_EQ(got[0].bytes[0], 0x90);
    CHECK_EQ(got[0].bytes[2], 127);
    CHECK_EQ(got[1].bytes[0], 0x80);
}

TEST(assembler_skips_a_sysex7_packet_whole) {
    // A 2-word SysEx7 must be consumed as a unit, or its second word would be
    // mistaken for the start of the next packet.
    std::vector<uint8_t> bytes;
    appendLE(bytes, 0x30027E7Fu);
    appendLE(bytes, 0x00000000u);
    appendLE(bytes, 0x20903C64u);
    UmpAssembler a;
    std::vector<Message> got;
    a.feed(bytes.data(), bytes.size(), [&](const Message& m) { got.push_back(m); });
    CHECK_EQ(static_cast<int>(got.size()), 1);
    CHECK_EQ(got[0].bytes[0], 0x90);
}

}  // namespace
