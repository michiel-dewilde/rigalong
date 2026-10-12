#pragma once

// USB descriptor parsing for the direct-USB audio and MIDI modes.
//
// WHY THIS EXISTS. In the two direct modes the app, not snd-usb-audio, is the
// driver: it has to find the isochronous OUT endpoint and whatever clocks it,
// the sample format, and the MIDI endpoint and its cables, from the raw bytes
// UsbDeviceConnection.getRawDescriptors() hands back. That is exactly the work
// the kernel driver does, and getting it subtly wrong produces a device that
// "works" -- enumerates, accepts packets -- while playing noise or nothing.
//
// It lives in engine/ so it can be tested against real descriptor bytes on a
// laptop (see tests/fixtures/cp88_descriptors.h), which is the only way to
// check it short of listening.
//
// Covers what the direct modes need and nothing more:
//   * USB Audio Class 1 and 2 streaming interfaces, Type I PCM formats;
//   * the three ways an OUT stream can be clocked -- explicit feedback,
//     implicit feedback (the CP-88), or none (adaptive/synchronous);
//   * USB MIDI 1.0 (bcdMSC 1.00) and USB MIDI 2.0 (bcdMSC 2.00, UMP) streaming
//     interfaces, including which cables an endpoint carries;
//   * the ALSA-style names snd-usb-audio gives the card and its MIDI ports, so
//     the UI shows the same names in every mode.
//
// Nothing here includes an Android header; this file is pure.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace rigalong::usb {

// ------------------------------------------------------------------ raw ---

struct Endpoint {
    uint8_t address = 0;
    uint8_t attributes = 0;
    uint16_t maxPacketRaw = 0;   // wMaxPacketSize as sent, incl. HS mult bits
    uint8_t interval = 0;
    uint8_t syncAddress = 0;     // UAC1 bSynchAddress (9-byte endpoint), else 0

    bool isIn() const { return (address & 0x80) != 0; }
    int transferType() const { return attributes & 0x03; }      // 1 = iso, 2 = bulk
    int syncType() const { return (attributes >> 2) & 0x03; }   // 1 async, 2 adaptive, 3 sync
    int usageType() const { return (attributes >> 4) & 0x03; }  // 0 data, 1 feedback, 2 implicit fb data
    int maxPacket() const { return maxPacketRaw & 0x07FF; }
    // High-speed high-bandwidth: extra transactions per microframe.
    int transactionsPerMicroframe() const { return 1 + ((maxPacketRaw >> 11) & 0x03); }
    int bytesPerService() const { return maxPacket() * transactionsPerMicroframe(); }

    // USB-MIDI: the embedded jack IDs this endpoint carries, in cable order.
    std::vector<uint8_t> midiJacks;
    // USB-MIDI 2.0: the group terminal block IDs this endpoint carries.
    std::vector<uint8_t> midiBlocks;
};

struct MidiJack {
    bool isOut = false;          // OUT jack: data leaves the device's MIDI function
    bool embedded = false;       // embedded = the USB side; external = a DIN socket
    uint8_t id = 0;
    uint8_t iJack = 0;           // string index, 0 = none
};

struct AltSetting {
    uint8_t number = 0;          // bInterfaceNumber
    uint8_t alt = 0;             // bAlternateSetting
    uint8_t cls = 0, subclass = 0, protocol = 0;
    uint8_t iInterface = 0;
    std::vector<Endpoint> endpoints;

    // --- USB Audio (class 1). subclass 1 = control, 2 = streaming, 3 = MIDI.
    bool isAudioControl() const { return cls == 0x01 && subclass == 0x01; }
    bool isAudioStreaming() const { return cls == 0x01 && subclass == 0x02; }
    bool isMidiStreaming() const { return cls == 0x01 && subclass == 0x03; }
    bool isUac2() const { return protocol == 0x20; }

    // Audio streaming, from AS_GENERAL and FORMAT_TYPE.
    int terminalLink = -1;
    int formatType = 0;          // 1 = Type I
    bool pcm = false;
    int channels = 0;
    int subslotBytes = 0;
    int bitResolution = 0;
    std::vector<uint32_t> discreteRates;   // UAC1 only; UAC2 rates live in the clock
    uint32_t rateMin = 0, rateMax = 0;     // UAC1 continuous range, if given

    // Audio control, UAC2: terminal -> clock mapping, and clock sources.
    struct Terminal { uint8_t id; uint16_t type; uint8_t clockSource; bool input; };
    std::vector<Terminal> terminals;
    struct Clock { uint8_t id; uint8_t attributes; uint8_t controls; };
    std::vector<Clock> clocks;
    struct ClockSelector { uint8_t id; std::vector<uint8_t> sources; };
    std::vector<ClockSelector> clockSelectors;
    struct ClockMultiplier { uint8_t id; uint8_t source; };
    std::vector<ClockMultiplier> clockMultipliers;

    // MIDI streaming.
    uint16_t bcdMSC = 0;         // 0x0100 = MIDI 1.0, 0x0200 = MIDI 2.0 (UMP)
    std::vector<MidiJack> jacks;
};

struct Config {
    uint8_t value = 0;
    std::vector<AltSetting> alts;

    // Every distinct bInterfaceNumber, ascending.
    std::vector<int> interfaceNumbers() const;
    const AltSetting* find(int number, int alt) const;
};

struct Device {
    uint16_t bcdUSB = 0;
    uint8_t cls = 0;
    uint16_t vendorId = 0, productId = 0;
    uint8_t iManufacturer = 0, iProduct = 0, iSerial = 0;
    std::vector<Config> configs;
};

// Parses a device descriptor followed by one or more configuration
// descriptors, the layout of getRawDescriptors() and of sysfs `descriptors`.
// Tolerant of trailing zero padding. Returns false, with a reason, on a
// structurally broken buffer; unknown descriptor types are skipped.
bool parse(const uint8_t* data, size_t length, Device& out, std::string* error = nullptr);

// Just the configuration descriptors, for when the device descriptor is
// already known (UsbDeviceConnection returns only the active configuration
// on some Android versions).
bool parseConfig(const uint8_t* data, size_t length, Config& out, std::string* error = nullptr);

// ---------------------------------------------------------------- plans ---
//
// "Plans": what each use actually needs, derived from the raw structure. The
// Kotlin side claims interfaces from these and the native side opens endpoints
// from them, so both agree by construction.

enum class Feedback {
    None,       // adaptive/synchronous endpoint: send nominal packet sizes
    Implicit,   // clocked by an IN data endpoint (the CP-88)
    Explicit,   // a feedback endpoint reports the device's rate
};

struct AudioOutPlan {
    bool ok = false;
    std::string why;             // when !ok

    int interfaceNumber = -1;
    int alt = -1;
    Endpoint out;

    Feedback feedback = Feedback::None;
    Endpoint feedbackEp;         // Implicit: an IN data endpoint; Explicit: the fb endpoint
    int feedbackInterface = -1;  // for Implicit; == interfaceNumber for Explicit
    int feedbackAlt = -1;

    int channels = 0;
    int subslotBytes = 0;
    int bitResolution = 0;
    bool uac2 = false;
    std::vector<uint32_t> discreteRates;   // UAC1

    // UAC2: where the rate is read and set.
    int controlInterface = -1;
    int clockSource = -1;
    bool clockProgrammable = false;

    // Interfaces the "USB interface" mode must claim: the streaming
    // interface(s) and nothing else, so the kernel keeps AudioControl and MIDI.
    std::vector<int> interfaces;
};

// Picks the best playback streaming interface: stereo-or-more PCM, widest
// sample, lowest-numbered interface on a tie.
AudioOutPlan planAudioOut(const Config& config);

struct MidiInPlan {
    bool ok = false;
    std::string why;

    int interfaceNumber = -1;
    int alt = -1;                // MIDI 2.0 devices: alt 1 is UMP, alt 0 is MIDI 1.0
    bool ump = false;            // true when alt is a MIDI 2.0 (UMP) setting
    Endpoint in;

    // One entry per cable (MIDI 1.0) or per group terminal block (2.0): the
    // string index to name it from, 0 when the device gives none.
    std::vector<uint8_t> portStringIndex;

    std::vector<int> interfaces; // for "USB interface" mode: the MS interface
};

// preferUmp: take the MIDI 2.0 alternate setting when the device has one.
MidiInPlan planMidiIn(const Config& config, bool preferUmp);

// --------------------------------------------------------------- naming ---
//
// The names snd-usb-audio would give, so the UI reads the same in every mode.
//
// Card short name (sound/usb/card.c, usb_audio_make_shortname): the product
// string, trimmed; failing that "USB Device 0xVVVV:0xPPPP".
std::string alsaCardName(const std::string& productString, uint16_t vendorId,
                         uint16_t productId);

// MIDI port (sound/usb/midi.c, snd_usbmidi_init_substream): the card short
// name and the jack's iJack string, "%s %s"; with no jack string, the default
// jack name "MIDI" and the 1-based port number, "%s %s %d".
std::string alsaMidiPortName(const std::string& cardName, const std::string& jackString,
                             int portIndex);

}  // namespace rigalong::usb
