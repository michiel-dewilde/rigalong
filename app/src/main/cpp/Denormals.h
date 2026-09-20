#pragma once

#include <cstdint>

// Denormal (subnormal) floats are catastrophically slow on some cores. They turn
// up constantly in audio: every reverb tail, filter and envelope decays towards
// zero and spends its last moments in denormal range. One stuck denormal in a
// feedback loop can cost more CPU than the whole synth.
//
// On aarch64 the fix is the FZ (flush-to-zero) bit in FPCR. It is a per-thread
// setting, so this must be called from the audio thread itself, not from setup.
inline void enableFlushToZero() {
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24);   // FZ
    fpcr |= (1ULL << 19);   // FZ16
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#endif
}
