// What Dexed's plugin layer would have supplied, supplied here instead.
//
// `Source/msfa/tuning.cc` declares `createStandardTuning()` and friends, but it
// is the one file in msfa that pulls in JUCE and the Surge Synth Team tuning
// library, so it is not compiled. Only the standard tuning is needed -- a CP-88
// is twelve-tone equal temperament and nothing else -- and it is twenty lines.
//
// Same arrangement as `beatrix/beatrix_glue.c` does for setBfree's `main.c`.

#include "msfa/tuning.h"

#include <cstdarg>
#include <cstdio>

namespace {

// Upstream's StandardTuning, kept verbatim in arithmetic because the constant
// is not obvious and getting it wrong would detune the whole instrument:
// `base` is (1 << 24) * (log2(440) - 69/12), i.e. A4 = 440 Hz expressed as a
// 24-bit fixed-point log2 frequency, which is what msfa works in throughout.
struct StandardTuning : public TuningState {
    StandardTuning() {
        const int base = 50857777;
        const int step = (1 << 24) / 12;
        for (int mn = 0; mn < 128; ++mn) {
            table_[mn] = base + step * mn;
        }
    }

    int32_t midinote_to_logfreq(int midinote) override {
        if (midinote < 0) midinote = 0;
        if (midinote > 127) midinote = 127;
        return table_[midinote];
    }

    int32_t table_[128];
};

}  // namespace

std::shared_ptr<TuningState> createStandardTuning() {
    return std::make_shared<StandardTuning>();
}

// msfa's TRACE macro expands to this under DEBUG and to nothing otherwise. It is
// defined regardless so that a debug build of this project still links. C++
// linkage, matching msfa/synth.h, which declares it.
void dexed_trace(const char* source, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "dexed %s: ", source);
    vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    va_end(args);
}
