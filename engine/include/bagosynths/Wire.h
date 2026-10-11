#pragma once

#include <algorithm>
#include <atomic>

#include "Graph.h"
#include "MidiTrack.h"

// A wire's live state (REQUIREMENTS I4.9, S4.8, I4.13; PLAN.md D7, D8).
//
// A live set switch changes values, never the topology: routing differences
// between live sets are wires switched on and off. So every edge carries one
// of these, created by Graph::connect and kept across recompiles, and the
// executor asks it how to mix:
//
//   audio  ramped to on ? gain : 0, so a wire switching is a fade, never a
//          click (15 ms, the same as every other gain in the engine);
//   MIDI   passed whole while on. While off it still passes what belongs to
//          notes it carried: their note-offs, their poly aftertouch, the
//          pedal-up for a pedal-down it passed, and the panic controllers.
//          When it comes back on, or its node is enabled again, it first
//          replays the controller state (sustain, mod wheel, expression,
//          pressure, bend) so the new sound starts where the hands are.
//
// on/gain are set off the audio thread (relaxed: a block either side is
// equally correct); everything else belongs to the audio thread.

namespace bagosynths {

class WireControl {
public:
    void setOn(bool on) { mOn.store(on, std::memory_order_relaxed); }
    void setGain(float g) { mGain.store(g, std::memory_order_relaxed); }
    bool on() const { return mOn.load(std::memory_order_relaxed); }
    float gain() const { return mGain.load(std::memory_order_relaxed); }

    // --- audio thread ------------------------------------------------------

    void mixAudio(const float* src, float* dst, int channels, int stride, int numFrames,
                  bool first, double sampleRate) {
        const float target = on() ? gain() : 0.0f;
        if (mCurrent == target) {
            if (target == 0.0f) {
                if (first) for (int c = 0; c < channels; ++c) std::fill_n(dst + static_cast<size_t>(c) * stride, numFrames, 0.0f);
                return;
            }
            for (int c = 0; c < channels; ++c) {
                const float* s = src + static_cast<size_t>(c) * stride;
                float* d = dst + static_cast<size_t>(c) * stride;
                if (first) for (int k = 0; k < numFrames; ++k) d[k] = s[k] * target;
                else for (int k = 0; k < numFrames; ++k) d[k] += s[k] * target;
            }
            return;
        }
        const float maxStep = static_cast<float>(1.0 / (kRampSeconds * sampleRate));
        float g = mCurrent;
        for (int k = 0; k < numFrames; ++k) {
            if (g < target) g = g + maxStep < target ? g + maxStep : target;
            else if (g > target) g = g - maxStep > target ? g - maxStep : target;
            for (int c = 0; c < channels; ++c) {
                const size_t i = static_cast<size_t>(c) * stride + k;
                if (first) dst[i] = src[i] * g;
                else dst[i] += src[i] * g;
            }
        }
        mCurrent = g;
    }

    // `nodeEnabled`: whether the destination node runs this block. A node that
    // does not run has had its voices released (Node::allNotesOff), so its
    // routes are forgotten; its controller state is still followed.
    void passMidi(const MidiBuffer& src, MidiBuffer& dst, bool first, bool nodeEnabled) {
        if (first) dst.clear();
        if (!nodeEnabled) {
            for (int i = 0; i < src.size(); ++i) mCtl.observe(src[i]);
            mNotes.clear();
            mDelivered = false;
            return;
        }
        const bool deliver = on();
        if (deliver && !mDelivered) mCtl.chase(dst, src.size() > 0 ? src[0].frame : 0);
        for (int i = 0; i < src.size(); ++i) {
            const MidiEvent& e = src[i];
            mCtl.observe(e);
            const int ch = channelOf(e);
            const uint8_t type = e.status & 0xF0u;
            if (deliver) {
                if (isNoteOn(e)) mNotes.on(ch, e.data1, ch, e.data1);
                else if (isNoteOff(e)) mNotes.off(ch, e.data1);
                else if (type == 0xB0 && e.data1 == 64) mNotes.sustain(ch, e.data2 >= 64);
                dst.push(e);
                continue;
            }
            int oc, outNote;
            if (isNoteOff(e)) {
                if (mNotes.find(ch, e.data1, oc, outNote)) { dst.push(e); mNotes.off(ch, e.data1); }
            } else if (type == 0xA0) {
                if (mNotes.find(ch, e.data1, oc, outNote)) dst.push(e);
            } else if (type == 0xB0 && e.data1 == 64) {
                if (e.data2 < 64 && mNotes.sustained(ch)) { dst.push(e); mNotes.sustain(ch, false); }
            } else if (type == 0xB0 && (e.data1 == 120 || e.data1 == 121 || e.data1 == 123)) {
                dst.push(e);                    // panic and resets always get through
            }
        }
        mDelivered = deliver;
    }

    static constexpr double kRampSeconds = 0.015;

private:
    std::atomic<bool> mOn{true};
    std::atomic<float> mGain{1.0f};
    float mCurrent = 1.0f;
    bool mDelivered = true;
    NoteRoute mNotes;
    Controllers mCtl;
};

}  // namespace bagosynths
