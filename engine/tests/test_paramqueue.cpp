// Tests for the parameter queue: the path every knob takes from the UI thread
// to the audio thread.
//
// Untested until now, and implemented five times -- once per instrument node,
// byte-identical each time. These are the cases those five copies were relying
// on being right.

#include "harness.h"

#include <bagosynths/ParamQueue.h>

#include <cstdint>
#include <limits>
#include <vector>

using namespace bagosynths;

namespace {

/// Collects what the consumer was handed, so a test can assert on the order and
/// the values rather than on the queue's internal counters.
struct Sink {
    std::vector<int> indices;
    std::vector<float> values;
    void operator()(int i, float v) {
        indices.push_back(i);
        values.push_back(v);
    }
    size_t size() const { return indices.size(); }
};

}  // namespace

TEST(push_then_drain_preserves_order) {
    ParamQueue<8> q;
    CHECK(q.empty());
    CHECK(q.push(3, 0.5f));
    CHECK(q.push(1, 0.25f));
    CHECK(q.push(7, 1.0f));
    CHECK_EQ((int)q.size(), 3);

    Sink sink;
    CHECK_EQ(q.drain(sink), 3);
    CHECK_EQ((int)sink.size(), 3);
    if (sink.size() == 3) {
        CHECK_EQ(sink.indices[0], 3);
        CHECK_EQ(sink.indices[1], 1);
        CHECK_EQ(sink.indices[2], 7);
        CHECK_NEAR(sink.values[0], 0.5f, 1e-9);
        CHECK_NEAR(sink.values[2], 1.0f, 1e-9);
    }
    CHECK(q.empty());
}

TEST(drain_on_an_empty_queue_does_nothing) {
    ParamQueue<8> q;
    Sink sink;
    CHECK_EQ(q.drain(sink), 0);
    CHECK_EQ((int)sink.size(), 0);
    // And again, because "does nothing" must also be idempotent -- the audio
    // thread calls this at the top of EVERY block, and most blocks are empty.
    CHECK_EQ(q.drain(sink), 0);
    CHECK(q.empty());
}

// Full means DROP, not overwrite. Overwriting would silently discard a change
// the audio thread has already been promised.
TEST(a_full_queue_drops_the_newest_and_says_so) {
    ParamQueue<4> q;
    for (int i = 0; i < 4; ++i) CHECK(q.push(i, (float)i));
    CHECK(q.full());
    CHECK(!q.push(99, 99.0f));
    CHECK(!q.push(98, 98.0f));
    CHECK_EQ((int)q.size(), 4);

    Sink sink;
    CHECK_EQ(q.drain(sink), 4);
    // The four that were accepted are the four that survive, in order, and the
    // rejected ones are simply absent.
    if (sink.size() == 4) {
        CHECK_EQ(sink.indices[0], 0);
        CHECK_EQ(sink.indices[3], 3);
    }
}

// The capacity is a mask, so the slot index wraps constantly in ordinary use.
TEST(indices_wrap_through_the_ring_many_times) {
    ParamQueue<4> q;
    Sink sink;
    for (int round = 0; round < 50; ++round) {
        CHECK(q.push(round, (float)round));
        CHECK_EQ(q.drain(sink), 1);
    }
    CHECK_EQ((int)sink.size(), 50);
    if (sink.size() == 50) {
        CHECK_EQ(sink.indices[0], 0);
        CHECK_EQ(sink.indices[49], 49);
    }
}

TEST(partially_filled_then_refilled_keeps_order) {
    ParamQueue<4> q;
    CHECK(q.push(1, 1.0f));
    CHECK(q.push(2, 2.0f));
    Sink first;
    CHECK_EQ(q.drain(first), 2);

    CHECK(q.push(3, 3.0f));
    CHECK(q.push(4, 4.0f));
    CHECK(q.push(5, 5.0f));
    Sink second;
    CHECK_EQ(q.drain(second), 3);
    if (second.size() == 3) {
        CHECK_EQ(second.indices[0], 3);
        CHECK_EQ(second.indices[2], 5);
    }
}

TEST(pop_takes_one_at_a_time) {
    ParamQueue<8> q;
    q.push(10, 0.1f);
    q.push(11, 0.2f);

    ParamQueue<8>::Change c;
    CHECK(q.pop(c));
    CHECK_EQ(c.index, 10);
    CHECK(q.pop(c));
    CHECK_EQ(c.index, 11);
    CHECK(!q.pop(c));
    CHECK(q.empty());
}

// ---------------------------------------------------------------- the subtle bit
//
// The counters are free-running uint32 and are never reduced modulo the
// capacity, so after about 2^32 changes the write index wraps past zero while
// the read index has not. `write - read` stays correct through that because
// unsigned subtraction wraps to the true distance. The obvious "improvement",
// `write < read + capacity`, does not -- and the failure would be a queue that
// reports itself permanently full, i.e. an instrument whose knobs stop working
// after a few days of uptime.
//
// Four billion pushes is not a test. The predicate is, which is why it is a
// free function.

TEST(fullness_is_correct_across_the_uint32_wrap) {
    const uint32_t cap = 256;
    const uint32_t max = std::numeric_limits<uint32_t>::max();

    // Ordinary, far from the wrap.
    CHECK(!detail::queueFull(0, 0, cap));
    CHECK(!detail::queueFull(255, 0, cap));
    CHECK(detail::queueFull(256, 0, cap));
    CHECK(detail::queueFull(1000, 100, cap));

    // Straddling it: write has wrapped past zero, read has not.
    CHECK(!detail::queueFull(0, max, cap));          // distance 1
    CHECK(!detail::queueFull(max - 1 + 255, max - 1, cap));
    CHECK(detail::queueFull(254, max - 1, cap));     // distance 256
    CHECK(detail::queueFull(1000, max - 100, cap));

    // Exactly at the boundary on both sides of the wrap.
    CHECK(!detail::queueFull(max, max - 255, cap));  // distance 255
    CHECK(detail::queueFull(max, max - 256, cap));   // distance 256
}

// Deliberately NOT called anything about wrapping: it does not cross UINT32_MAX
// and a name that claimed it did would be worse than no test at all. What it
// does prove is that push and drain agree over many cycles of filling and
// emptying a tiny queue, which is where an off-by-one in the mask would show.
TEST(push_and_drain_agree_over_many_cycles) {
    ParamQueue<2> q;
    Sink sink;
    // The wrap itself is covered by fullness_is_correct_across_the_uint32_wrap,
    // which can reach the boundary because it tests the predicate directly.
    for (int i = 0; i < 1000; ++i) {
        CHECK(q.push(i, (float)i));
        CHECK(q.push(i, (float)i));
        CHECK(!q.push(i, (float)i));     // capacity 2: the third is dropped
        CHECK_EQ(q.drain(sink), 2);
        CHECK(q.empty());
    }
    CHECK_EQ((int)sink.size(), 2000);
}

TEST(size_and_empty_agree_with_what_was_pushed) {
    ParamQueue<16> q;
    CHECK(q.empty());
    CHECK_EQ((int)q.size(), 0);
    for (int i = 0; i < 5; ++i) q.push(i, 0.0f);
    CHECK_EQ((int)q.size(), 5);
    CHECK(!q.empty());
    CHECK(!q.full());
    ParamQueue<16>::Change c;
    q.pop(c);
    CHECK_EQ((int)q.size(), 4);
}

// A knob being dragged sends a stream of values for ONE index. The last one is
// the one that matters, and every one of them must arrive in order, because the
// consumer applies them in sequence and the final state has to be the final
// value.
TEST(a_dragged_knob_ends_on_its_final_value) {
    ParamQueue<256> q;
    for (int i = 0; i <= 100; ++i) q.push(7, i / 100.0f);

    float last = -1.0f;
    const int applied = q.drain([&](int index, float v) {
        CHECK_EQ(index, 7);
        last = v;
    });
    CHECK_EQ(applied, 101);
    CHECK_NEAR(last, 1.0f, 1e-6);
}
