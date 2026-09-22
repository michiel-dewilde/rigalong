// Tests for the USB descriptor parser and the plans built on it.
//
// The CP-88 cases run on the device's REAL descriptor bytes (fixtures/), so a
// misreading of the spec that happens to be self-consistent cannot pass here.
// The MIDI 2.0 and explicit-feedback cases use hand-built descriptors, because
// no such device is on the bench; they are labelled as such, and they check the
// structure the specs define rather than any one vendor's habits.

#include "harness.h"
#include "fixtures/cp88_descriptors.h"

#include <bagosynths/UsbDescriptors.h>

#include <string>
#include <vector>

using namespace bagosynths::usb;

namespace {

Device cp88() {
    Device d;
    std::string err;
    const bool ok = parse(fixtures::kCp88Descriptors, fixtures::kCp88DescriptorsSize, d, &err);
    if (!ok) ::test::fail(__FILE__, __LINE__, "cp88 fixture did not parse: " + err);
    return d;
}

// ------------------------------------------------------------ the CP-88 ---

TEST(cp88_device_descriptor) {
    const Device d = cp88();
    CHECK_EQ(d.vendorId, 0x0499);
    CHECK_EQ(d.productId, 0x172b);
    CHECK_EQ(d.bcdUSB, 0x0200);
    CHECK_EQ(d.iProduct, 2);
    CHECK_EQ(d.iSerial, 0);                  // it has no serial number
    CHECK_EQ(static_cast<int>(d.configs.size()), 2);
}

TEST(cp88_config1_has_four_interfaces) {
    const Device d = cp88();
    const Config& c = d.configs[0];
    CHECK_EQ(c.value, 1);
    const std::vector<int> n = c.interfaceNumbers();
    CHECK_EQ(static_cast<int>(n.size()), 4);
    CHECK_EQ(n[0], 0);
    CHECK_EQ(n[3], 3);
}

TEST(cp88_audio_out_plan) {
    const AudioOutPlan p = planAudioOut(cp88().configs[0]);
    CHECK(p.ok);
    CHECK_EQ(p.interfaceNumber, 1);
    CHECK_EQ(p.alt, 1);
    CHECK_EQ(p.out.address, 0x07);
    CHECK_EQ(p.out.maxPacket(), 270);       // 45 frames x 6 bytes
    CHECK_EQ(p.out.interval, 1);
    CHECK_EQ(p.out.syncType(), 1);          // asynchronous
    CHECK_EQ(p.channels, 2);
    CHECK_EQ(p.subslotBytes, 3);            // S24_3LE
    CHECK_EQ(p.bitResolution, 24);
    CHECK(p.uac2);
}

TEST(cp88_is_clocked_by_implicit_feedback_from_interface_2) {
    const AudioOutPlan p = planAudioOut(cp88().configs[0]);
    CHECK(p.feedback == Feedback::Implicit);
    CHECK_EQ(p.feedbackEp.address, 0x86);
    CHECK_EQ(p.feedbackEp.usageType(), 2);  // implicit feedback data
    CHECK_EQ(p.feedbackInterface, 2);
    CHECK_EQ(p.feedbackAlt, 1);
}

TEST(cp88_usb_interface_mode_claims_the_whole_audio_function) {
    // 1 and 2 stream; 0 is AudioControl and is claimed TOO, because on UAC2 the
    // clock lives there and the request that sets the sample rate is addressed
    // to it. usbfs refuses a control transfer to an interface this process has
    // not claimed -- EBUSY -- which is what broke USB interface mode for audio:
    //
    //     clock 1: GET RANGE failed: Device or resource busy
    //
    // MIDI (3) still stays with the kernel, which is what keeps "interface"
    // different from "device".
    const AudioOutPlan p = planAudioOut(cp88().configs[0]);
    CHECK_EQ(static_cast<int>(p.interfaces.size()), 3);
    CHECK_EQ(p.interfaces[0], 1);
    CHECK_EQ(p.interfaces[1], 2);
    CHECK_EQ(p.interfaces[2], 0);
    CHECK_EQ(p.controlInterface, 0);
}

TEST(cp88_clock_is_source_1_on_interface_0) {
    const AudioOutPlan p = planAudioOut(cp88().configs[0]);
    CHECK_EQ(p.controlInterface, 0);
    CHECK_EQ(p.clockSource, 1);
    // bmControls 0x03: the descriptor declares frequency read/write, even
    // though the clock is "internal fixed" and has one rate.
    CHECK(p.clockProgrammable);
}

TEST(cp88_midi_in_plan) {
    const MidiInPlan m = planMidiIn(cp88().configs[0], /*preferUmp=*/true);
    CHECK(m.ok);
    CHECK_EQ(m.interfaceNumber, 3);
    CHECK_EQ(m.alt, 0);
    CHECK(!m.ump);                          // MIDI 1.0 only: no UMP alt exists
    CHECK_EQ(m.in.address, 0x82);
    CHECK_EQ(m.in.transferType(), 2);       // bulk
    // Two cables, from embedded OUT jacks 0x61 and 0x62, neither named.
    CHECK_EQ(static_cast<int>(m.portStringIndex.size()), 2);
    CHECK_EQ(m.portStringIndex[0], 0);
    CHECK_EQ(m.portStringIndex[1], 0);
    CHECK_EQ(static_cast<int>(m.interfaces.size()), 1);
    CHECK_EQ(m.interfaces[0], 3);
}

TEST(cp88_midi_endpoint_lists_its_jacks_in_cable_order) {
    const Device d = cp88();
    const Config& c = d.configs[0];
    const AltSetting* ms = c.find(3, 0);
    CHECK(ms != nullptr);
    CHECK_EQ(ms->bcdMSC, 0x0100);
    const Endpoint* in = nullptr;
    for (const auto& e : ms->endpoints) if (e.isIn()) in = &e;
    CHECK(in != nullptr);
    CHECK_EQ(static_cast<int>(in->midiJacks.size()), 2);
    CHECK_EQ(in->midiJacks[0], 0x61);
    CHECK_EQ(in->midiJacks[1], 0x62);
}

TEST(cp88_vendor_config_is_not_class_audio) {
    // Configuration 2 is Yamaha's vendor-specific one: class 0xFF throughout,
    // so the class-compliant planner must find nothing there.
    const Device d = cp88();
    const Config& c = d.configs[1];
    CHECK_EQ(c.value, 2);
    CHECK(!planAudioOut(c).ok);
    CHECK(!planMidiIn(c, false).ok);
}

// --------------------------------------------------------------- naming ---

TEST(alsa_names_match_what_the_kernel_showed_for_the_cp88) {
    // `amidi -l` on Ubuntu, 2026-09-21:
    //   IO  hw:1,0,0  CP88/CP73 MIDI 1
    //   IO  hw:1,0,1  CP88/CP73 MIDI 2
    const std::string card = alsaCardName("CP88/CP73", 0x0499, 0x172b);
    CHECK_EQ(card, std::string("CP88/CP73"));
    CHECK_EQ(alsaMidiPortName(card, "", 0), std::string("CP88/CP73 MIDI 1"));
    CHECK_EQ(alsaMidiPortName(card, "", 1), std::string("CP88/CP73 MIDI 2"));
}

TEST(alsa_card_name_trims_the_product_string) {
    CHECK_EQ(alsaCardName("  Scarlett 2i2 4th Gen  ", 0x1235, 0x8219),
             std::string("Scarlett 2i2 4th Gen"));
}

TEST(alsa_card_name_without_a_product_string_uses_the_kernel_format) {
    // "%#04x": the width includes the 0x, so 0x0499 prints as 0x499.
    CHECK_EQ(alsaCardName("", 0x0499, 0x172b), std::string("USB Device 0x499:0x172b"));
}

TEST(alsa_midi_port_uses_the_jack_string_when_there_is_one) {
    CHECK_EQ(alsaMidiPortName("UMC404HD", "Port 1", 0), std::string("UMC404HD Port 1"));
}

TEST(alsa_names_are_truncated_to_31_characters) {
    const std::string longName(40, 'x');
    CHECK_EQ(static_cast<int>(alsaCardName(longName, 1, 2).size()), 31);
    CHECK_EQ(static_cast<int>(alsaMidiPortName(longName, "", 0).size()), 31);
}

// ------------------------------------------------- hand-built: MIDI 2.0 ---
//
// A minimal USB MIDI 2.0 function: alt 0 is the mandatory MIDI 1.0 setting,
// alt 1 is UMP (bcdMSC 2.00) with one group terminal block on its IN endpoint.
// Laid out from the USB MIDI 2.0 specification, section 5.

std::vector<uint8_t> midi2Config() {
    const std::vector<uint8_t> body = {
        // AudioControl, the class's mandatory companion.
        0x09, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00,
        // MIDI streaming alt 0, MIDI 1.0.
        0x09, 0x04, 0x01, 0x00, 0x02, 0x01, 0x03, 0x00, 0x00,
        0x07, 0x24, 0x01, 0x00, 0x01, 0x25, 0x00,           // MS header 1.00
        0x06, 0x24, 0x02, 0x01, 0x01, 0x00,                 // IN jack, embedded 1
        0x09, 0x24, 0x03, 0x01, 0x11, 0x01, 0x01, 0x01, 0x05,  // OUT jack 0x11, iJack 5
        0x09, 0x05, 0x01, 0x02, 0x40, 0x00, 0x00, 0x00, 0x00,
        0x05, 0x25, 0x01, 0x01, 0x01,
        0x09, 0x05, 0x81, 0x02, 0x40, 0x00, 0x00, 0x00, 0x00,
        0x05, 0x25, 0x01, 0x01, 0x11,
        // MIDI streaming alt 1, MIDI 2.0.
        0x09, 0x04, 0x01, 0x01, 0x02, 0x01, 0x03, 0x00, 0x00,
        0x07, 0x24, 0x01, 0x00, 0x02, 0x07, 0x00,           // MS header 2.00
        0x07, 0x05, 0x02, 0x02, 0x40, 0x00, 0x00,
        0x05, 0x25, 0x02, 0x01, 0x01,                       // MS_GENERAL_2_0, block 1
        0x07, 0x05, 0x82, 0x03, 0x40, 0x00, 0x01,           // interrupt IN
        0x05, 0x25, 0x02, 0x01, 0x01,
    };
    const uint16_t total = static_cast<uint16_t>(9 + body.size());
    std::vector<uint8_t> c = {0x09, 0x02, static_cast<uint8_t>(total & 0xFF),
                              static_cast<uint8_t>(total >> 8), 0x02, 0x01, 0x00, 0x80, 0x32};
    c.insert(c.end(), body.begin(), body.end());
    return c;
}

TEST(midi2_prefers_the_ump_alt_when_asked) {
    const std::vector<uint8_t> raw = midi2Config();
    Config c;
    std::string err;
    CHECK(parseConfig(raw.data(), raw.size(), c, &err));
    const MidiInPlan m = planMidiIn(c, /*preferUmp=*/true);
    CHECK(m.ok);
    CHECK_EQ(m.interfaceNumber, 1);
    CHECK_EQ(m.alt, 1);
    CHECK(m.ump);
    CHECK_EQ(m.in.address, 0x82);
    CHECK_EQ(m.in.transferType(), 3);       // interrupt, which 2.0 allows
    CHECK_EQ(static_cast<int>(m.portStringIndex.size()), 1);
}

TEST(midi2_falls_back_to_the_midi1_alt_when_ump_is_not_wanted) {
    const std::vector<uint8_t> raw = midi2Config();
    Config c;
    CHECK(parseConfig(raw.data(), raw.size(), c));
    const MidiInPlan m = planMidiIn(c, /*preferUmp=*/false);
    CHECK(m.ok);
    CHECK_EQ(m.alt, 0);
    CHECK(!m.ump);
    CHECK_EQ(m.in.address, 0x81);
    // The OUT jack carries iJack 5, so this port has a name to fetch.
    CHECK_EQ(static_cast<int>(m.portStringIndex.size()), 1);
    CHECK_EQ(m.portStringIndex[0], 5);
}

// ------------------------------------- hand-built: explicit feedback UAC2 ---
//
// The common interface design (the Scarlett family's, among others): an
// asynchronous OUT endpoint with a feedback IN endpoint in the same alt.

std::vector<uint8_t> explicitFeedbackConfig() {
    const std::vector<uint8_t> body = {
        0x09, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x20, 0x00,           // AC, UAC2
        0x08, 0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0x00,                 // clock 0x29, prog.
        0x11, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x29, 0x02,
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,                 // IT 1, clock 0x29
        0x09, 0x04, 0x01, 0x00, 0x00, 0x01, 0x02, 0x20, 0x00,
        0x09, 0x04, 0x01, 0x01, 0x02, 0x01, 0x02, 0x20, 0x00,
        0x10, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
        0x02, 0x03, 0x00, 0x00, 0x00, 0x00,                             // AS_GENERAL, 2 ch
        0x06, 0x24, 0x02, 0x01, 0x04, 0x18,                             // 4-byte slot, 24 bit
        0x07, 0x05, 0x01, 0x05, 0x40, 0x02, 0x01,                       // OUT iso async
        0x08, 0x25, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 0x05, 0x81, 0x11, 0x04, 0x00, 0x04,                       // feedback IN
    };
    const uint16_t total = static_cast<uint16_t>(9 + body.size());
    std::vector<uint8_t> c = {0x09, 0x02, static_cast<uint8_t>(total & 0xFF),
                              static_cast<uint8_t>(total >> 8), 0x02, 0x01, 0x00, 0x80, 0x32};
    c.insert(c.end(), body.begin(), body.end());
    return c;
}

TEST(explicit_feedback_is_found_in_the_same_alt) {
    const std::vector<uint8_t> raw = explicitFeedbackConfig();
    Config c;
    CHECK(parseConfig(raw.data(), raw.size(), c));
    const AudioOutPlan p = planAudioOut(c);
    CHECK(p.ok);
    CHECK(p.feedback == Feedback::Explicit);
    CHECK_EQ(p.feedbackEp.address, 0x81);
    CHECK_EQ(p.subslotBytes, 4);
    CHECK_EQ(p.bitResolution, 24);
    CHECK_EQ(p.out.maxPacket(), 0x240);
    CHECK_EQ(p.clockSource, 0x29);
    // The streaming interface plus AudioControl: explicit feedback needs no
    // second STREAMING interface, but the UAC2 clock still has to be reachable.
    CHECK_EQ(static_cast<int>(p.interfaces.size()), 2);
    CHECK_EQ(p.interfaces[0], 1);
    CHECK_EQ(p.interfaces[1], p.controlInterface);
}

// ------------------------------------------------------------- robustness ---

TEST(a_truncated_buffer_is_rejected_not_overrun) {
    Device d;
    std::string err;
    CHECK(!parse(fixtures::kCp88Descriptors, 40, d, &err));
    CHECK(!err.empty());
}

TEST(zero_padding_after_the_last_configuration_is_accepted) {
    std::vector<uint8_t> raw(fixtures::kCp88Descriptors,
                             fixtures::kCp88Descriptors + fixtures::kCp88DescriptorsSize);
    raw.resize(raw.size() + 64, 0);
    Device d;
    CHECK(parse(raw.data(), raw.size(), d));
    CHECK_EQ(static_cast<int>(d.configs.size()), 2);
}

}  // namespace
