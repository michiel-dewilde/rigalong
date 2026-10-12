#pragma once

// USB-MIDI wire formats, decoded to plain MIDI 1.0 messages.
//
// In the direct-USB MIDI modes the app reads the MIDI streaming endpoint
// itself, so it meets the bytes snd-usb-audio normally digests:
//
//   * USB MIDI 1.0 (bcdMSC 1.00): 4-byte event packets,
//       [cable << 4 | CIN, b0, b1, b2]
//     where the Code Index Number says how many of b0..b2 are real.
//
//   * USB MIDI 2.0 (bcdMSC 2.00): Universal MIDI Packets, 32-bit words
//     sent little-endian, 1 to 4 words per packet depending on the message
//     type in the top nibble of the first word.
//
// The engine speaks MIDI 1.0 (status, data1, data2), so UMP is translated
// down following the MIDI Association's UMP spec (M2-104-UM) "MIDI 2.0 to
// MIDI 1.0" rules: 16-bit velocity to 7, 32-bit controllers to 7, 32-bit pitch
// bend to 14, RPN/NRPN messages to their CC sequences, bank-valid program
// changes to bank select plus program change. Per-note controllers and other
// messages with no MIDI 1.0 equivalent are dropped, which is what the spec's
// translation does too.
//
// Header-only and allocation-free: the decoder runs on the MIDI reader thread.

#include <cstddef>
#include <cstdint>

namespace rigalong::usbmidi {

// A MIDI 1.0 message of 1 to 3 bytes. `port` is the USB-MIDI cable (1.0) or
// the UMP group (2.0).
struct Message {
    uint8_t port = 0;
    uint8_t length = 0;
    uint8_t bytes[3] = {0, 0, 0};
};

// MIDI bytes carried per Code Index Number (USB MIDI 1.0, table 4-1). 0x0 and
// 0x1 are "reserved for future use"; nothing real is sent with them.
inline int cinLength(uint8_t cin) {
    static constexpr int8_t kLen[16] = {0, 0, 2, 3, 3, 1, 2, 3, 3, 3, 3, 3, 2, 2, 3, 1};
    return kLen[cin & 0x0F];
}

// Decodes one 4-byte USB-MIDI 1.0 event packet. Returns false for an empty or
// reserved packet (a device may pad its bulk transfer with zeros).
//
// SysEx arrives as CIN 0x4 (start/continue, 3 bytes) and 0x5-0x7 (end, 1-3
// bytes); those are passed through as raw byte runs for the caller's own
// running parser, which already skips SysEx.
inline bool decodePacket(const uint8_t p[4], Message& out) {
    const uint8_t cin = p[0] & 0x0F;
    const int n = cinLength(cin);
    if (n == 0) return false;
    out.port = p[0] >> 4;
    out.length = static_cast<uint8_t>(n);
    out.bytes[0] = p[1];
    out.bytes[1] = n > 1 ? p[2] : 0;
    out.bytes[2] = n > 2 ? p[3] : 0;
    return true;
}

// Words per UMP by message type (UMP spec, table 4).
inline int umpWords(uint32_t firstWord) {
    static constexpr int8_t kWords[16] = {1, 1, 1, 2, 2, 4, 1, 1, 2, 2, 2, 3, 3, 4, 4, 4};
    return kWords[(firstWord >> 28) & 0x0F];
}

// Reads a UMP word from the wire, which USB MIDI 2.0 sends little-endian.
inline uint32_t umpWordLE(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

// Translates one complete UMP (1-4 words) into up to four MIDI 1.0 messages.
// Returns how many were written. Four is the worst case: an RPN becomes
// CC 101, CC 100, CC 6, CC 38.
inline int translateUmp(const uint32_t* w, Message out[4]) {
    const uint8_t mt = (w[0] >> 28) & 0x0F;
    const uint8_t group = (w[0] >> 24) & 0x0F;
    const uint8_t status = (w[0] >> 16) & 0xFF;
    const uint8_t b1 = (w[0] >> 8) & 0xFF;
    const uint8_t b2 = w[0] & 0xFF;

    auto put = [&](int i, uint8_t s, uint8_t d1, uint8_t d2, uint8_t len) {
        out[i].port = group;
        out[i].length = len;
        out[i].bytes[0] = s;
        out[i].bytes[1] = d1;
        out[i].bytes[2] = d2;
    };

    switch (mt) {
        case 0x1: {  // System real time and system common: already MIDI 1.0
            int len = 1;
            if (status == 0xF1 || status == 0xF3) len = 2;
            else if (status == 0xF2) len = 3;
            put(0, status, b1 & 0x7F, b2 & 0x7F, static_cast<uint8_t>(len));
            return 1;
        }
        case 0x2: {  // MIDI 1.0 channel voice, carried in a UMP
            const uint8_t type = status & 0xF0;
            const uint8_t len = (type == 0xC0 || type == 0xD0) ? 2 : 3;
            put(0, status, b1 & 0x7F, b2 & 0x7F, len);
            return 1;
        }
        case 0x4: {  // MIDI 2.0 channel voice: translate down
            const uint8_t type = status & 0xF0;
            const uint8_t ch = status & 0x0F;
            const uint32_t data = w[1];
            switch (type) {
                case 0x80:
                case 0x90: {
                    // 16-bit velocity in the top half of word 2.
                    uint8_t v = static_cast<uint8_t>((data >> 25) & 0x7F);
                    // A MIDI 2.0 note-on with a velocity that rounds to zero
                    // must not become a MIDI 1.0 note-off (spec 7.4.2).
                    if (type == 0x90 && v == 0) v = 1;
                    put(0, status, b1 & 0x7F, v, 3);
                    return 1;
                }
                case 0xA0:  // poly pressure, 32-bit
                    put(0, status, b1 & 0x7F, static_cast<uint8_t>(data >> 25), 3);
                    return 1;
                case 0xB0:  // control change, 32-bit value
                    put(0, status, b1 & 0x7F, static_cast<uint8_t>(data >> 25), 3);
                    return 1;
                case 0xC0: {  // program change, optional bank
                    const bool bankValid = (b2 & 0x01) != 0;
                    const uint8_t program = (data >> 24) & 0x7F;
                    int n = 0;
                    if (bankValid) {
                        put(n++, static_cast<uint8_t>(0xB0 | ch), 0, (data >> 8) & 0x7F, 3);
                        put(n++, static_cast<uint8_t>(0xB0 | ch), 32, data & 0x7F, 3);
                    }
                    put(n++, status, program, 0, 2);
                    return n;
                }
                case 0xD0:  // channel pressure, 32-bit
                    put(0, status, static_cast<uint8_t>(data >> 25), 0, 2);
                    return 1;
                case 0xE0: {  // pitch bend, 32-bit -> 14-bit
                    const uint32_t v14 = data >> 18;
                    put(0, status, v14 & 0x7F, (v14 >> 7) & 0x7F, 3);
                    return 1;
                }
                case 0x20:    // registered controller (RPN)
                case 0x30: {  // assignable controller (NRPN)
                    const bool rpn = type == 0x20;
                    const uint8_t cc = static_cast<uint8_t>(0xB0 | ch);
                    const uint32_t v14 = data >> 18;
                    put(0, cc, rpn ? 101 : 99, b1 & 0x7F, 3);   // bank -> MSB
                    put(1, cc, rpn ? 100 : 98, b2 & 0x7F, 3);   // index -> LSB
                    put(2, cc, 6, (v14 >> 7) & 0x7F, 3);        // data entry MSB
                    put(3, cc, 38, v14 & 0x7F, 3);              // data entry LSB
                    return 4;
                }
                default:
                    // Per-note controllers, per-note pitch bend, relative
                    // controllers, per-note management: no MIDI 1.0 form.
                    return 0;
            }
        }
        default:
            // Utility (0x0), SysEx7 (0x3), SysEx8/mixed data (0x5), flex data
            // (0xD), UMP stream (0xF) and reserved types: nothing the engine
            // plays. SysEx is not used by any node, and the byte-stream path
            // skips it too.
            return 0;
    }
}

// Reassembles UMPs from a byte stream of little-endian words, since a bulk
// transfer boundary may fall inside a multi-word packet.
class UmpAssembler {
public:
    // Feeds raw bytes; calls sink(const Message&) for every translated message.
    template <typename Sink>
    void feed(const uint8_t* data, size_t length, Sink&& sink) {
        for (size_t i = 0; i < length; ++i) {
            mByte[mByteCount++] = data[i];
            if (mByteCount < 4) continue;
            mByteCount = 0;
            const uint32_t word = umpWordLE(mByte);
            if (mWordCount == 0) mNeed = umpWords(word);
            mWords[mWordCount++] = word;
            if (mWordCount < mNeed) continue;
            mWordCount = 0;
            Message m[4];
            const int n = translateUmp(mWords, m);
            for (int k = 0; k < n; ++k) sink(m[k]);
        }
    }

    void reset() { mByteCount = 0; mWordCount = 0; mNeed = 1; }

private:
    uint8_t mByte[4] = {};
    int mByteCount = 0;
    uint32_t mWords[4] = {};
    int mWordCount = 0;
    int mNeed = 1;
};

}  // namespace rigalong::usbmidi
