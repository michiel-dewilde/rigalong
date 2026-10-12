#pragma once

#include <cstdint>
#include <cstring>

#include "Graph.h"

// What a MIDI path has to remember so that switching it never strands a note
// (REQUIREMENTS S4.8, I4.13, I4.14; PLAN.md D8).
//
//   NoteRoute    which notes went through, and as what: a note-off, or the
//                poly aftertouch of a held note, follows its note-on even when
//                the path has since been switched off or re-mapped.
//   Controllers  the state of the continuous controllers seen on a stream, so
//                a path that comes on mid-phrase can be told where the sustain
//                pedal, mod wheel, expression and pitch bend are right now.
//
// Plain data, no allocation, safe on the audio thread.

namespace rigalong {

inline bool isNoteOn(const MidiEvent& e) { return (e.status & 0xF0u) == 0x90 && e.data2 > 0; }
inline bool isNoteOff(const MidiEvent& e) {
    return (e.status & 0xF0u) == 0x80 || ((e.status & 0xF0u) == 0x90 && e.data2 == 0);
}
inline int channelOf(const MidiEvent& e) { return e.status & 0x0F; }

// Per incoming (channel, note): where it went, if anywhere.
class NoteRoute {
public:
    static constexpr uint16_t kNone = 0xFFFF;

    NoteRoute() { clear(); }
    void clear() { std::memset(mOut, 0xFF, sizeof mOut); std::memset(mSustained, 0, sizeof mSustained); }

    // Record that incoming (ch, note) was sent out as (outCh, outNote).
    void on(int ch, int note, int outCh, int outNote) {
        mOut[ch][note] = static_cast<uint16_t>((outCh << 8) | outNote);
    }
    // Where incoming (ch, note) went, or false if it did not pass.
    bool find(int ch, int note, int& outCh, int& outNote) const {
        const uint16_t v = mOut[ch][note];
        if (v == kNone) return false;
        outCh = v >> 8;
        outNote = v & 0x7F;
        return true;
    }
    void off(int ch, int note) { mOut[ch][note] = kNone; }

    // Pedal-downs that went through, per OUTGOING channel: the matching
    // pedal-up must follow them.
    void sustain(int outCh, bool down) { mSustained[outCh] = down; }
    bool sustained(int outCh) const { return mSustained[outCh]; }

    bool anyHeld() const {
        for (int c = 0; c < 16; ++c) {
            if (mSustained[c]) return true;
            for (int n = 0; n < 128; ++n) if (mOut[c][n] != kNone) return true;
        }
        return false;
    }

private:
    uint16_t mOut[16][128];
    bool mSustained[16];
};

// The continuous state of a MIDI stream, per channel.
class Controllers {
public:
    Controllers() { reset(); }

    void reset() {
        for (int c = 0; c < 16; ++c) {
            mSustain[c] = 0;
            mMod[c] = 0;
            mExpression[c] = 127;
            mPressure[c] = 0;
            mBend[c] = 0x2000;
            mSeen[c] = 0;
        }
    }

    void observe(const MidiEvent& e) {
        const int ch = channelOf(e);
        switch (e.status & 0xF0u) {
            case 0xB0:
                if (e.data1 == 64) { mSustain[ch] = e.data2; mSeen[ch] |= kSeenSustain; }
                else if (e.data1 == 1) { mMod[ch] = e.data2; mSeen[ch] |= kSeenMod; }
                else if (e.data1 == 11) { mExpression[ch] = e.data2; mSeen[ch] |= kSeenExpression; }
                else if (e.data1 == 121) reset(ch);        // Reset All Controllers
                break;
            case 0xD0: mPressure[ch] = e.data1; mSeen[ch] |= kSeenPressure; break;
            case 0xE0: mBend[ch] = static_cast<uint16_t>(e.data1 | (e.data2 << 7)); mSeen[ch] |= kSeenBend; break;
            default: break;
        }
    }

    // Writes, at `frame`, the messages that bring a fresh listener up to this
    // state: only what has been seen and differs from the power-on value.
    void chase(MidiBuffer& out, uint32_t frame) const {
        for (int c = 0; c < 16; ++c) {
            const uint8_t cc = static_cast<uint8_t>(0xB0 | c);
            if ((mSeen[c] & kSeenSustain) && mSustain[c] >= 64) out.push({frame, cc, 64, mSustain[c]});
            if ((mSeen[c] & kSeenMod) && mMod[c] != 0) out.push({frame, cc, 1, mMod[c]});
            if ((mSeen[c] & kSeenExpression) && mExpression[c] != 127) out.push({frame, cc, 11, mExpression[c]});
            if ((mSeen[c] & kSeenPressure) && mPressure[c] != 0) {
                out.push({frame, static_cast<uint8_t>(0xD0 | c), mPressure[c], 0});
            }
            if ((mSeen[c] & kSeenBend) && mBend[c] != 0x2000) {
                out.push({frame, static_cast<uint8_t>(0xE0 | c), static_cast<uint8_t>(mBend[c] & 0x7F),
                          static_cast<uint8_t>(mBend[c] >> 7)});
            }
        }
    }

    bool sustainDown(int ch) const { return mSustain[ch] >= 64; }

private:
    void reset(int c) {
        mSustain[c] = 0; mMod[c] = 0; mExpression[c] = 127; mPressure[c] = 0; mBend[c] = 0x2000;
    }

    static constexpr uint8_t kSeenSustain = 1, kSeenMod = 2, kSeenExpression = 4,
                             kSeenPressure = 8, kSeenBend = 16;
    uint8_t mSustain[16], mMod[16], mExpression[16], mPressure[16];
    uint16_t mBend[16];
    uint8_t mSeen[16];
};

}  // namespace rigalong
