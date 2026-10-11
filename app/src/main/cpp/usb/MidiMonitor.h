#pragma once

// The MIDI monitor (REQUIREMENTS S20.5): the most recent messages in and out
// of the direct-USB MIDI path, for the setup view. The first thing to look at
// when a footswitch or a CP-88 button "does nothing".
//
// One ring that keeps the newest kSize and overwrites the oldest; written from
// the MIDI threads (in: the reaper; out: whoever sends), read by the UI. A
// reader can catch an entry mid-write and show it once wrong -- acceptable for
// a display, and it keeps every writer to one relaxed store.
//
// Entry: (out << 31) | (length << 24) | (b0 << 16) | (b1 << 8) | b2.

#include <atomic>
#include <cstdint>

namespace midimon {

constexpr uint32_t kSize = 64;

struct Ring {
    std::atomic<uint32_t> entries[kSize] = {};
    std::atomic<uint32_t> count{0};
};

inline Ring& ring() {
    static Ring r;
    return r;
}

// Realtime (F8-FF) is left out: the CP-88's active sensing would bury the rest.
inline void note(bool out, int length, uint8_t b0, uint8_t b1, uint8_t b2) {
    if (b0 >= 0xF8 || length < 1) return;
    Ring& r = ring();
    const uint32_t n = r.count.fetch_add(1, std::memory_order_relaxed);
    r.entries[n % kSize].store((uint32_t(out) << 31) | (uint32_t(length & 3) << 24) |
                                   (uint32_t(b0) << 16) | (uint32_t(b1) << 8) | b2,
                               std::memory_order_relaxed);
}

// Copies up to `max` of the newest entries, oldest first; returns how many,
// and the running total in `total` so a reader can tell what is new.
inline int recent(uint32_t* out, int max, uint32_t* total) {
    Ring& r = ring();
    const uint32_t n = r.count.load(std::memory_order_relaxed);
    *total = n;
    const uint32_t have = n < kSize ? n : kSize;
    const uint32_t take = have < static_cast<uint32_t>(max) ? have : static_cast<uint32_t>(max);
    for (uint32_t i = 0; i < take; ++i) {
        out[i] = r.entries[(n - take + i) % kSize].load(std::memory_order_relaxed);
    }
    return static_cast<int>(take);
}

}  // namespace midimon
