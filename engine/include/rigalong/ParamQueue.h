#pragma once

#include <atomic>
#include <cstdint>

namespace rigalong {

/// How a parameter change gets from the UI thread to the audio thread.
///
/// Single producer, single consumer, fixed capacity, no allocation and no
/// locks. The UI thread pushes; the audio thread drains the whole queue at the
/// top of a block so that a change always lands on a block boundary and never
/// halfway through one.
///
/// **This existed five times before it existed once.** Every instrument node
/// carried its own byte-identical copy of the same twelve lines. Five copies of
/// a lock-free algorithm is five chances to get a memory order subtly wrong, and
/// none of them was testable, because all five lived in files that include a
/// third-party engine header.
///
/// ## What it does when it is full
///
/// It **drops the change and says so**, rather than blocking or overwriting
/// unread entries. Blocking is not available -- the producer is the UI thread
/// and the consumer has a 2 ms deadline -- and overwriting would silently
/// discard a change that has already been promised to the audio thread. Dropping
/// is safe here because the UI holds the authoritative value and re-sends: a
/// knob at rest converges, and 256 changes in one 2,9 ms block is a fader being
/// dragged by something that is not a finger.
namespace detail {

/// Whether the queue holding `w - r` items is full.
///
/// Pulled out as a free function for exactly one reason: **the unsigned
/// wraparound is the only subtle thing here, and it is otherwise untestable.**
/// The counters are free-running uint32 and are never reduced modulo the
/// capacity, so after about 2^32 changes `w` wraps past zero while `r` has not.
/// `w - r` stays correct through that, because unsigned subtraction wraps to the
/// true distance -- but only as long as nobody "improves" it into `w < r + cap`,
/// which does not. A test can call this with counters straddling the wrap; it
/// cannot push four billion times.
inline bool queueFull(uint32_t write, uint32_t read, uint32_t capacity) {
    return (write - read) >= capacity;
}

}  // namespace detail

template <int CapacityPow2 = 256>
class ParamQueue {
public:
    struct Change {
        int index = 0;
        float value = 0.0f;
    };

    static constexpr uint32_t kCapacity = static_cast<uint32_t>(CapacityPow2);
    static_assert(CapacityPow2 > 0 && (CapacityPow2 & (CapacityPow2 - 1)) == 0,
                  "capacity must be a power of two, so the index is a mask");

    /// Producer side, UI thread. False means the queue was full and the change
    /// was dropped; the caller is expected to hold the value and re-send.
    bool push(int index, float value) {
        const uint32_t w = mWrite.load(std::memory_order_relaxed);
        const uint32_t r = mRead.load(std::memory_order_acquire);
        if (detail::queueFull(w, r, kCapacity)) return false;
        mSlots[w & (kCapacity - 1)] = Change{index, value};
        // Release: the slot must be visible before the index that publishes it.
        mWrite.store(w + 1, std::memory_order_release);
        return true;
    }

    /// Consumer side, audio thread. Applies every pending change and returns how
    /// many there were.
    ///
    /// One release store for the whole batch rather than one per item, which is
    /// what the hand-written copies did and is worth keeping: the producer only
    /// ever needs to know how far the consumer got, never the intermediate
    /// steps.
    template <class Apply>
    int drain(Apply&& apply) {
        const uint32_t w = mWrite.load(std::memory_order_acquire);
        uint32_t r = mRead.load(std::memory_order_relaxed);
        int applied = 0;
        while (r != w) {
            const Change& c = mSlots[r & (kCapacity - 1)];
            apply(c.index, c.value);
            ++r;
            ++applied;
        }
        mRead.store(r, std::memory_order_release);
        return applied;
    }

    /// One at a time. Convenient for tests; the audio path uses drain().
    bool pop(Change& out) {
        const uint32_t w = mWrite.load(std::memory_order_acquire);
        const uint32_t r = mRead.load(std::memory_order_relaxed);
        if (r == w) return false;
        out = mSlots[r & (kCapacity - 1)];
        mRead.store(r + 1, std::memory_order_release);
        return true;
    }

    uint32_t size() const {
        return mWrite.load(std::memory_order_acquire) -
               mRead.load(std::memory_order_acquire);
    }
    bool empty() const { return size() == 0; }
    bool full() const { return size() >= kCapacity; }

private:
    Change mSlots[kCapacity] = {};
    std::atomic<uint32_t> mWrite{0};
    std::atomic<uint32_t> mRead{0};
};

}  // namespace rigalong
