#pragma once

#include <algorithm>

// A gain that MOVES to its target instead of jumping to it.
//
// Every instrument used to apply its level as a bare multiply:
//
//     for (int i = 0; i < n; ++i) out[i] *= level;
//
// which is correct for a level that never changes and wrong for one that does.
// Taking a sounding instrument from 1 to 0 -- what switching parts does -- puts
// a step discontinuity straight into the waveform, and a step is a click. On
// 2026-09-22 Michiel heard exactly that on a part switch, and the CP-88 stopped
// presenting audio at that instant and did not start again until the device was
// handed back to the kernel and re-claimed. Whether the step CAUSED that is not
// settled, but a click is a defect on its own and this removes it either way.
//
// Linear, over a fixed time rather than a fixed number of blocks: a ramp
// measured in blocks would change length with the buffer size, so the same
// fader move would click at 4 ms and not at 16 ms. 15 ms is long enough that
// nothing steps audibly and short enough to feel immediate under the hand.
//
// Allocation-free and header-only: this runs on the render thread.
namespace bagosynths {

class SmoothedGain {
public:
    static constexpr double kRampSeconds = 0.015;

    // Off the audio thread. `initial` is where the gain starts, so a node that
    // is built at level 1 does not fade in from silence on its first block.
    void prepare(double sampleRate, float initial = 1.0f) {
        mValue = initial;
        mStep = static_cast<float>(1.0 / std::max(1.0, sampleRate * kRampSeconds));
    }

    /** True when the gain has reached its target and can be applied flat. */
    bool steady(float target) const { return mValue == target; }

    float value() const { return mValue; }

    /**
     * Applies the gain to one or two channels, moving towards `target`.
     *
     * `right` may be null for a mono node. The common case -- already at the
     * target -- costs one compare and, at unity, not even a multiply.
     */
    void apply(float* left, float* right, int numFrames, float target) {
        if (mValue == target) {
            if (target == 1.0f) return;
            for (int i = 0; i < numFrames; ++i) left[i] *= target;
            if (right != nullptr) {
                for (int i = 0; i < numFrames; ++i) right[i] *= target;
            }
            return;
        }
        for (int i = 0; i < numFrames; ++i) {
            mValue = mValue < target ? std::min(target, mValue + mStep)
                                     : std::max(target, mValue - mStep);
            left[i] *= mValue;
            if (right != nullptr) right[i] *= mValue;
        }
    }

    /**
     * The same ramp over an INTERLEAVED stereo block, which is the shape the
     * signal has at the very end of the chain, just before the sink converts
     * it to the device's format.
     */
    void applyInterleavedStereo(float* lr, int numFrames, float target) {
        if (mValue == target) {
            if (target == 1.0f) return;
            for (int i = 0; i < numFrames * 2; ++i) lr[i] *= target;
            return;
        }
        for (int i = 0; i < numFrames; ++i) {
            mValue = mValue < target ? std::min(target, mValue + mStep)
                                     : std::max(target, mValue - mStep);
            lr[i * 2] *= mValue;
            lr[i * 2 + 1] *= mValue;
        }
    }

private:
    float mValue = 1.0f;
    float mStep = 1.0f;
};

}  // namespace bagosynths
