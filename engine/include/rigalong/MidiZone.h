#pragma once

#include <atomic>

#include "Graph.h"
#include "MidiTrack.h"

// A MIDI zone (REQUIREMENTS S4.2): the one MIDI processor a live set needs,
// shaped like a stage keyboard's zone so it reads the way players think.
//
//   channel in     0 = any, 1-16 = only that channel
//   channel out    0 = unchanged, 1-16 = rewritten to it
//   key range      lowest and highest note let through (a split)
//   velocity range lowest and highest note-on velocity let through (a layer)
//   transpose      semitones, octave included, -48..+48
//   pass ...       notes, controllers, pitch bend, aftertouch, program change
//   cc remap       two pairs: controller A becomes B (B = 128 drops A)
//
// Changing any of these while notes are held must not strand them (S4.8): a
// note-on that passed is remembered with the channel and note it went out as,
// and its note-off, its poly aftertouch and the pedal-up that releases it all
// follow that record, whatever the zone says by then.
//
// Parameters are set off the audio thread (relaxed atomics: a block either
// side is equally correct) and read once per block.

namespace rigalong {

class MidiZoneNode : public Node {
public:
    enum Param {
        kChannelIn, kChannelOut, kKeyLow, kKeyHigh, kVelLow, kVelHigh, kTranspose,
        kPassNotes, kPassCC, kPassBend, kPassPressure, kPassProgram,
        kCcFromA, kCcToA, kCcFromB, kCcToB,
        kNumParams
    };

    MidiZoneNode() {
        const float defaults[kNumParams] = {0, 0, 0, 127, 1, 127, 0, 1, 1, 1, 1, 1, 128, 128, 128, 128};
        for (int i = 0; i < kNumParams; ++i) mParams[i].store(defaults[i], std::memory_order_relaxed);
    }

    const char* typeName() const override { return "midi.zone"; }
    int numInputs() const override { return 1; }
    int numOutputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Midi, 1, "midi"}; }
    PinSpec outputPin(int) const override { return {DataType::Midi, 1, "midi"}; }

    int numParams() const override { return kNumParams; }
    void setParam(int i, float v) override {
        if (i >= 0 && i < kNumParams) mParams[i].store(v, std::memory_order_relaxed);
    }
    float getParam(int i) const override {
        return (i >= 0 && i < kNumParams) ? mParams[i].load(std::memory_order_relaxed) : 0.0f;
    }
    const char* paramName(int i) const override {
        static const char* const names[kNumParams] = {
            "channelIn", "channelOut", "keyLow", "keyHigh", "velLow", "velHigh", "transpose",
            "passNotes", "passCC", "passBend", "passPressure", "passProgram",
            "ccFromA", "ccToA", "ccFromB", "ccToB"};
        return (i >= 0 && i < kNumParams) ? names[i] : "";
    }

    void allNotesOff() override { mRoute.clear(); }

    void process(const ProcessArgs& a) override {
        MidiBuffer& out = *a.midiOut[0];
        out.clear();
        if (a.numMidiIn < 1 || a.midiIn[0] == nullptr) return;
        const MidiBuffer& in = *a.midiIn[0];

        int p[kNumParams];
        for (int i = 0; i < kNumParams; ++i) p[i] = static_cast<int>(mParams[i].load(std::memory_order_relaxed));

        for (int i = 0; i < in.size(); ++i) {
            MidiEvent e = in[i];
            if (e.status >= 0xF0) {                     // system messages pass as they are
                out.push(e);
                continue;
            }
            const int ch = channelOf(e);
            const uint8_t type = e.status & 0xF0u;
            const int outCh = p[kChannelOut] >= 1 && p[kChannelOut] <= 16 ? p[kChannelOut] - 1 : ch;
            const bool channelOk = p[kChannelIn] < 1 || p[kChannelIn] > 16 || p[kChannelIn] - 1 == ch;

            // What a held note needs passes whatever the zone says now.
            int rc, rn;
            if (isNoteOff(e)) {
                if (mRoute.find(ch, e.data1, rc, rn)) {
                    out.push({e.frame, static_cast<uint8_t>(0x80 | rc), static_cast<uint8_t>(rn), e.data2});
                    mRoute.off(ch, e.data1);
                }
                continue;
            }
            if (type == 0xA0) {
                if (mRoute.find(ch, e.data1, rc, rn)) {
                    out.push({e.frame, static_cast<uint8_t>(0xA0 | rc), static_cast<uint8_t>(rn), e.data2});
                }
                continue;
            }
            if (type == 0xB0 && e.data1 == 64 && e.data2 < 64) {
                // A pedal-up for every channel a pedal-down went out on, from this input channel.
                bool sent = false;
                for (int c = 0; c < 16; ++c) {
                    if (mPedalFrom[ch] & (1u << c)) {
                        out.push({e.frame, static_cast<uint8_t>(0xB0 | c), 64, e.data2});
                        mRoute.sustain(c, false);
                        sent = true;
                    }
                }
                mPedalFrom[ch] = 0;
                if (sent || !channelOk || !p[kPassCC]) continue;
            }
            if (type == 0xB0 && (e.data1 == 120 || e.data1 == 123)) {
                out.push({e.frame, static_cast<uint8_t>(0xB0 | outCh), e.data1, e.data2});
                continue;
            }
            if (!channelOk) continue;

            if (isNoteOn(e)) {
                if (!p[kPassNotes]) continue;
                if (e.data1 < p[kKeyLow] || e.data1 > p[kKeyHigh]) continue;
                if (e.data2 < p[kVelLow] || e.data2 > p[kVelHigh]) continue;
                const int note = e.data1 + p[kTranspose];
                if (note < 0 || note > 127) continue;
                out.push({e.frame, static_cast<uint8_t>(0x90 | outCh), static_cast<uint8_t>(note), e.data2});
                mRoute.on(ch, e.data1, outCh, note);
                continue;
            }
            switch (type) {
                case 0xB0: {
                    if (!p[kPassCC]) break;
                    int cc = e.data1;
                    if (cc == p[kCcFromA]) cc = p[kCcToA];
                    else if (cc == p[kCcFromB]) cc = p[kCcToB];
                    if (cc < 0 || cc > 127) break;
                    out.push({e.frame, static_cast<uint8_t>(0xB0 | outCh), static_cast<uint8_t>(cc), e.data2});
                    if (cc == 64 && e.data2 >= 64) {
                        mPedalFrom[ch] |= 1u << outCh;
                        mRoute.sustain(outCh, true);
                    }
                    break;
                }
                case 0xC0:
                    if (p[kPassProgram]) out.push({e.frame, static_cast<uint8_t>(0xC0 | outCh), e.data1, 0});
                    break;
                case 0xD0:
                    if (p[kPassPressure]) out.push({e.frame, static_cast<uint8_t>(0xD0 | outCh), e.data1, 0});
                    break;
                case 0xE0:
                    if (p[kPassBend]) out.push({e.frame, static_cast<uint8_t>(0xE0 | outCh), e.data1, e.data2});
                    break;
                default:
                    break;
            }
        }
    }

private:
    std::atomic<float> mParams[kNumParams];
    NoteRoute mRoute;
    uint16_t mPedalFrom[16] = {};       // per input channel: output channels a pedal-down went to
};

}  // namespace rigalong
