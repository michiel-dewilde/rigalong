// Tests for the graph compiler.
//
// This is the first test this project has ever had, and the thing it covers is
// the reason: Graph::compile is pure logic that could only be exercised by
// playing a tablet, and a bug in it -- "a second edge into any input pin
// produced silence, with no error" -- survived until an organ needed fan-in.
// The first case below is that bug, written down so it cannot come back.

#include "harness.h"

#include <bagosynths/Graph.h>

#include <memory>
#include <string>
#include <vector>

using namespace bagosynths;

namespace {

constexpr int kFrames = 8;

// ------------------------------------------------------------- helper nodes

// Emits a constant on every sample of its single audio output.
class ConstAudio : public Node {
public:
    ConstAudio(float value, int width = 1) : mValue(value), mWidth(width) {}
    const char* typeName() const override { return "ConstAudio"; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Audio, mWidth, "out"}; }
    void process(const ProcessArgs& a) override {
        for (int c = 0; c < a.audioOut[0].width; ++c) {
            float* dst = a.audioOut[0].channel(c);
            for (int i = 0; i < a.numFrames; ++i) dst[i] = mValue;
        }
    }

private:
    float mValue;
    int mWidth;
};

// Records the first sample of every input channel it is handed, so a test can
// assert what actually arrived rather than what was meant to.
class AudioProbe : public Node {
public:
    explicit AudioProbe(int width = 1) : mWidth(width) {}
    const char* typeName() const override { return "AudioProbe"; }
    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Audio, mWidth, "in"}; }
    void process(const ProcessArgs& a) override {
        seen.clear();
        for (int c = 0; c < a.audioIn[0].width; ++c) seen.push_back(a.audioIn[0].channel(c)[0]);
        ++calls;
    }
    std::vector<float> seen;
    int calls = 0;

private:
    int mWidth;
};

class MidiSource : public Node {
public:
    explicit MidiSource(uint8_t note) : mNote(note) {}
    const char* typeName() const override { return "MidiSource"; }
    int numOutputs() const override { return 1; }
    PinSpec outputPin(int) const override { return {DataType::Midi, 1, "out"}; }
    void process(const ProcessArgs& a) override {
        a.midiOut[0]->clear();
        a.midiOut[0]->push({0, 0x90, mNote, 100});
    }

private:
    uint8_t mNote;
};

class MidiProbe : public Node {
public:
    const char* typeName() const override { return "MidiProbe"; }
    int numInputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Midi, 1, "in"}; }
    void process(const ProcessArgs& a) override {
        ++blocks;
        notes.clear();
        for (int i = 0; i < a.midiIn[0]->size(); ++i) notes.push_back((*a.midiIn[0])[i].data1);
        received += static_cast<int>(notes.size());
    }
    std::vector<uint8_t> notes;
    int blocks = 0;     // how often it RAN, whether or not it heard anything
    int received = 0;   // how many events reached it in total
};

// Audio in, audio out. Used to build chains whose order can be checked.
class Passthrough : public Node {
public:
    explicit Passthrough(std::vector<std::string>* log, std::string name)
        : mLog(log), mName(std::move(name)) {}
    const char* typeName() const override { return "Passthrough"; }
    int numInputs() const override { return 1; }
    int numOutputs() const override { return 1; }
    PinSpec inputPin(int) const override { return {DataType::Audio, 1, "in"}; }
    PinSpec outputPin(int) const override { return {DataType::Audio, 1, "out"}; }
    void process(const ProcessArgs& a) override {
        if (mLog) mLog->push_back(mName);
        for (int i = 0; i < a.numFrames; ++i)
            a.audioOut[0].channel(0)[i] = a.audioIn[0].channel(0)[i];
    }

private:
    std::vector<std::string>* mLog;
    std::string mName;
};

}  // namespace

// --------------------------------------------------------------- the cases

// THE REGRESSION. Two sources into one input pin must be summed, not dropped.
TEST(fan_in_audio_sums_both_sources) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(0.25f));
    NodeId b = g.addNode(std::make_unique<ConstAudio>(0.5f));
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId p = g.addNode(std::move(probeOwned));

    std::string err;
    CHECK(g.connect(a, 0, p, 0, &err));
    CHECK(g.connect(b, 0, p, 0, &err));
    g.setAudioExit(p);

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;

    // Two edges into one pin means two AudioSum entries, and exactly one of
    // them is `first` -- the one that copies where the others add. Getting that
    // flag wrong on the wrong edge is how the destination ends up holding only
    // the last source.
    CHECK_EQ((int)sched->audioSums.size(), 2);
    CHECK_EQ(sched->audioSums[0].first, true);
    CHECK_EQ(sched->audioSums[1].first, false);

    sched->run(kFrames, 44100.0);
    CHECK_EQ((int)probe->seen.size(), 1);
    if (!probe->seen.empty()) CHECK_NEAR(probe->seen[0], 0.75f, 1e-6);
}

// Three sources, to prove the `first` flag is positional and not "not the last".
TEST(fan_in_audio_sums_three_sources) {
    Graph g;
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId p = g.addNode(std::move(probeOwned));
    std::string err;
    for (float v : {0.125f, 0.25f, 0.5f}) {
        NodeId s = g.addNode(std::make_unique<ConstAudio>(v));
        CHECK(g.connect(s, 0, p, 0, &err));
    }
    g.setAudioExit(p);

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    CHECK_EQ((int)sched->audioSums.size(), 3);
    sched->run(kFrames, 44100.0);
    if (!probe->seen.empty()) CHECK_NEAR(probe->seen[0], 0.875f, 1e-6);
}

// A single edge is aliased straight through: no buffer copy, and therefore no
// AudioSum at all. That is the fast path the fan-in code must not have broken.
TEST(single_source_is_aliased_not_copied) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(0.3f));
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId p = g.addNode(std::move(probeOwned));
    std::string err;
    CHECK(g.connect(a, 0, p, 0, &err));
    g.setAudioExit(p);

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    CHECK_EQ((int)sched->audioSums.size(), 0);
    sched->run(kFrames, 44100.0);
    if (!probe->seen.empty()) CHECK_NEAR(probe->seen[0], 0.3f, 1e-6);
}

// An input with nothing connected must read as silence, not as whatever the
// buffer happened to contain last block.
TEST(unconnected_input_is_silence) {
    Graph g;
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId p = g.addNode(std::move(probeOwned));
    g.setAudioExit(p);

    std::string err;
    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    sched->run(kFrames, 44100.0);
    CHECK_EQ((int)probe->seen.size(), 1);
    if (!probe->seen.empty()) CHECK_NEAR(probe->seen[0], 0.0f, 1e-9);
}

TEST(fan_in_midi_merges_both_sources) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<MidiSource>(60));
    NodeId b = g.addNode(std::make_unique<MidiSource>(64));
    auto probeOwned = std::make_unique<MidiProbe>();
    MidiProbe* probe = probeOwned.get();
    NodeId p = g.addNode(std::move(probeOwned));

    std::string err;
    CHECK(g.connect(a, 0, p, 0, &err));
    CHECK(g.connect(b, 0, p, 0, &err));

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    CHECK_EQ((int)sched->midiSums.size(), 2);
    sched->run(kFrames, 44100.0);
    CHECK_EQ((int)probe->notes.size(), 2);
    if (probe->notes.size() == 2) {
        CHECK_EQ((int)probe->notes[0], 60);
        CHECK_EQ((int)probe->notes[1], 64);
    }
}

// ------------------------------------------------------------- validation

TEST(connect_rejects_type_mismatch) {
    Graph g;
    NodeId m = g.addNode(std::make_unique<MidiSource>(60));
    NodeId p = g.addNode(std::make_unique<AudioProbe>());
    std::string err;
    CHECK(!g.connect(m, 0, p, 0, &err));
    CHECK_EQ(err, std::string("pin types differ"));
}

TEST(connect_rejects_width_mismatch) {
    Graph g;
    NodeId stereo = g.addNode(std::make_unique<ConstAudio>(0.5f, 2));
    NodeId mono = g.addNode(std::make_unique<AudioProbe>(1));
    std::string err;
    CHECK(!g.connect(stereo, 0, mono, 0, &err));
    CHECK_EQ(err, std::string("width mismatch: insert an adapter node"));
}

// Mono into stereo is allowed on purpose: one source feeding both channels is
// the ordinary case, and refusing it would mean an adapter node in every patch.
TEST(connect_allows_mono_into_stereo) {
    Graph g;
    NodeId mono = g.addNode(std::make_unique<ConstAudio>(0.5f, 1));
    auto probeOwned = std::make_unique<AudioProbe>(2);
    AudioProbe* probe = probeOwned.get();
    NodeId stereo = g.addNode(std::move(probeOwned));
    std::string err;
    CHECK(g.connect(mono, 0, stereo, 0, &err));

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    sched->run(kFrames, 44100.0);
    CHECK_EQ((int)probe->seen.size(), 2);
}

TEST(connect_rejects_unknown_node_and_pin) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(1.0f));
    std::string err;
    CHECK(!g.connect(a, 0, 9999, 0, &err));
    CHECK_EQ(err, std::string("no such node"));
    NodeId p = g.addNode(std::make_unique<AudioProbe>());
    CHECK(!g.connect(a, 7, p, 0, &err));
    CHECK_EQ(err, std::string("no such pin"));
}

TEST(compile_rejects_a_cycle) {
    Graph g;
    std::vector<std::string> log;
    NodeId a = g.addNode(std::make_unique<Passthrough>(&log, "a"));
    NodeId b = g.addNode(std::make_unique<Passthrough>(&log, "b"));
    std::string err;
    CHECK(g.connect(a, 0, b, 0, &err));
    CHECK(g.connect(b, 0, a, 0, &err));

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched == nullptr);
    CHECK(err.find("cycle") != std::string::npos);
}

TEST(compile_rejects_an_empty_graph) {
    Graph g;
    std::string err;
    CHECK(g.compile(44100.0, kFrames, &err) == nullptr);
    CHECK_EQ(err, std::string("empty graph"));
}

// --------------------------------------------------------------- ordering

// A node must run after everything that feeds it. The nodes are added in
// reverse order deliberately, so insertion order cannot pass this by accident.
TEST(steps_run_in_topological_order) {
    Graph g;
    std::vector<std::string> log;
    NodeId c = g.addNode(std::make_unique<Passthrough>(&log, "c"));
    NodeId b = g.addNode(std::make_unique<Passthrough>(&log, "b"));
    NodeId a = g.addNode(std::make_unique<Passthrough>(&log, "a"));
    std::string err;
    CHECK(g.connect(a, 0, b, 0, &err));
    CHECK(g.connect(b, 0, c, 0, &err));

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    sched->run(kFrames, 44100.0);
    CHECK_EQ((int)log.size(), 3);
    if (log.size() == 3) {
        CHECK_EQ(log[0], std::string("a"));
        CHECK_EQ(log[1], std::string("b"));
        CHECK_EQ(log[2], std::string("c"));
    }
}

// A value must survive a three-node chain unchanged. This is the end-to-end
// check that buffer assignment hands each node the right memory.
TEST(a_chain_carries_its_signal) {
    Graph g;
    NodeId src = g.addNode(std::make_unique<ConstAudio>(0.6f));
    NodeId mid = g.addNode(std::make_unique<Passthrough>(nullptr, "mid"));
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId sink = g.addNode(std::move(probeOwned));
    std::string err;
    CHECK(g.connect(src, 0, mid, 0, &err));
    CHECK(g.connect(mid, 0, sink, 0, &err));
    g.setAudioExit(sink);

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    sched->run(kFrames, 44100.0);
    if (!probe->seen.empty()) CHECK_NEAR(probe->seen[0], 0.6f, 1e-6);
}

// ------------------------------------------------------- disabled nodes
//
// "Only the selected instrument runs" has to mean it does not RUN, not merely
// that it cannot be heard: an engine rendering at level zero costs exactly what
// an audible one costs.

TEST(a_disabled_node_does_not_run) {
    Graph g;
    auto srcOwned = std::make_unique<ConstAudio>(0.5f);
    ConstAudio* src = srcOwned.get();
    NodeId idSrc = g.addNode(std::move(srcOwned));
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId idSink = g.addNode(std::move(probeOwned));
    std::string err;
    CHECK(g.connect(idSrc, 0, idSink, 0, &err));
    g.setAudioExit(idSink);
    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;

    sched->run(kFrames, 44100.0);
    CHECK_NEAR(probe->seen[0], 0.5f, 1e-6);

    // Disabled: the consumer still runs, and sees silence rather than the last
    // block the source produced.
    src->setEnabled(false);
    sched->run(kFrames, 44100.0);
    CHECK_NEAR(probe->seen[0], 0.0f, 1e-6);

    src->setEnabled(true);
    sched->run(kFrames, 44100.0);
    CHECK_NEAR(probe->seen[0], 0.5f, 1e-6);
}

TEST(a_disabled_node_is_not_processed_at_all) {
    Graph g;
    NodeId idSrc = g.addNode(std::make_unique<ConstAudio>(0.5f));
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId idSink = g.addNode(std::move(probeOwned));
    std::string err;
    CHECK(g.connect(idSrc, 0, idSink, 0, &err));
    g.setAudioExit(idSink);
    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;

    sched->run(kFrames, 44100.0);
    const int after = probe->calls;
    probe->setEnabled(false);
    sched->run(kFrames, 44100.0);
    sched->run(kFrames, 44100.0);
    // process() was never entered: the whole point is the CPU it does not cost.
    CHECK_EQ(probe->calls, after);
}

TEST(disabling_one_branch_leaves_the_other_audible) {
    // The case the UI asks for: one instrument selected, the rest silent AND
    // not running, mixed into the same output.
    Graph g;
    auto aOwned = std::make_unique<ConstAudio>(0.25f);
    ConstAudio* a = aOwned.get();
    NodeId idA = g.addNode(std::move(aOwned));
    NodeId idB = g.addNode(std::make_unique<ConstAudio>(0.5f));
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId idSink = g.addNode(std::move(probeOwned));
    std::string err;
    CHECK(g.connect(idA, 0, idSink, 0, &err));
    CHECK(g.connect(idB, 0, idSink, 0, &err));
    g.setAudioExit(idSink);
    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;

    sched->run(kFrames, 44100.0);
    CHECK_NEAR(probe->seen[0], 0.75f, 1e-6);   // both branches summed

    a->setEnabled(false);
    sched->run(kFrames, 44100.0);
    CHECK_NEAR(probe->seen[0], 0.5f, 1e-6);    // only the enabled one remains
}

// ----------------------------------------------------------- graph editing

TEST(remove_node_drops_its_edges) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(1.0f));
    NodeId p = g.addNode(std::make_unique<AudioProbe>());
    std::string err;
    CHECK(g.connect(a, 0, p, 0, &err));
    CHECK_EQ((int)g.edges().size(), 1);
    g.removeNode(a);
    CHECK_EQ((int)g.edges().size(), 0);
    CHECK(g.node(a) == nullptr);
    CHECK_EQ(g.nodeCount(), 1);
}

TEST(disconnect_removes_one_edge_only) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(1.0f));
    NodeId b = g.addNode(std::make_unique<ConstAudio>(2.0f));
    NodeId p = g.addNode(std::make_unique<AudioProbe>());
    std::string err;
    CHECK(g.connect(a, 0, p, 0, &err));
    CHECK(g.connect(b, 0, p, 0, &err));
    g.disconnect({a, 0, p, 0, nullptr});
    CHECK_EQ((int)g.edges().size(), 1);
    CHECK_EQ(g.edges()[0].fromNode, b);
}

// Insertion order is stable, which is what makes "the second sfizz node" a
// meaningful thing for a saved patch to say.
TEST(node_order_is_insertion_order_and_stable) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(1.0f));
    NodeId b = g.addNode(std::make_unique<ConstAudio>(2.0f));
    NodeId c = g.addNode(std::make_unique<ConstAudio>(3.0f));
    CHECK_EQ(g.idAt(0), a);
    CHECK_EQ(g.idAt(1), b);
    CHECK_EQ(g.idAt(2), c);
    g.removeNode(b);
    CHECK_EQ(g.idAt(0), a);
    CHECK_EQ(g.idAt(1), c);
    CHECK(g.idAt(2) == -1);
}

// ------------------------------------------------------------ the schedule

TEST(entry_and_exit_are_wired_for_the_engine) {
    Graph g;
    NodeId m = g.addNode(std::make_unique<MidiSource>(60));
    NodeId src = g.addNode(std::make_unique<ConstAudio>(0.4f, 2));
    auto probeOwned = std::make_unique<AudioProbe>(2);
    NodeId out = g.addNode(std::move(probeOwned));
    std::string err;
    CHECK(g.connect(src, 0, out, 0, &err));
    g.setMidiEntry(m);
    g.setAudioExit(out);

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    // The engine feeds MIDI in and takes audio out through these two, without
    // knowing anything else about the graph.
    CHECK(sched->midiInputSink != nullptr);
    CHECK(sched->audioOutputSource != nullptr);
    CHECK_EQ(sched->maxFrames, kFrames);
}

TEST(profiling_records_one_entry_per_step) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(1.0f));
    NodeId p = g.addNode(std::make_unique<AudioProbe>());
    std::string err;
    CHECK(g.connect(a, 0, p, 0, &err));
    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    sched->run(kFrames, 44100.0);
    CHECK_EQ((int)sched->stepMaxMs.size(), (int)sched->steps.size());
    CHECK_EQ((int)sched->stepLastMs.size(), (int)sched->steps.size());
    sched->resetProfile();
    for (float v : sched->stepMaxMs) CHECK_NEAR(v, 0.0f, 1e-9);
}

// Running a schedule twice must give the same answer. A node that accumulates
// into an input buffer instead of overwriting it shows up here and nowhere
// else, because one block always looks correct.
TEST(running_twice_is_idempotent) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(0.25f));
    NodeId b = g.addNode(std::make_unique<ConstAudio>(0.5f));
    auto probeOwned = std::make_unique<AudioProbe>();
    AudioProbe* probe = probeOwned.get();
    NodeId p = g.addNode(std::move(probeOwned));
    std::string err;
    CHECK(g.connect(a, 0, p, 0, &err));
    CHECK(g.connect(b, 0, p, 0, &err));

    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    sched->run(kFrames, 44100.0);
    const float once = probe->seen.empty() ? -1.0f : probe->seen[0];
    sched->run(kFrames, 44100.0);
    const float twice = probe->seen.empty() ? -2.0f : probe->seen[0];
    CHECK_NEAR(once, 0.75f, 1e-6);
    CHECK_NEAR(twice, once, 1e-9);
    CHECK_EQ(probe->calls, 2);
}

// ---------------------------------------------------------- MidiBuffer

TEST(midi_buffer_is_bounded_and_never_overflows) {
    MidiBuffer b;
    for (int i = 0; i < MidiBuffer::kCapacity + 100; ++i) b.push({0, 0x90, 60, 64});
    CHECK_EQ(b.size(), MidiBuffer::kCapacity);
    b.clear();
    CHECK_EQ(b.size(), 0);
}

TEST(midi_buffer_append_respects_capacity) {
    MidiBuffer a, b;
    for (int i = 0; i < MidiBuffer::kCapacity; ++i) a.push({0, 0x90, 60, 64});
    for (int i = 0; i < 10; ++i) b.push({0, 0x80, 61, 0});
    a.append(b);
    CHECK_EQ(a.size(), MidiBuffer::kCapacity);
    // The events already there are the ones that survive, which is the right
    // way round: a flood should drop the newest, not corrupt the oldest.
    CHECK_EQ((int)a[0].status, 0x90);
}

// ------------------------------------------------------------------- gaps
//
// Node::latencyFrames() is declared, documented as "the compiler aligns
// parallel paths that reconverge", and CALLED NOWHERE. Nothing in the project
// uses it yet -- no node returns non-zero -- so nothing is broken today, but
// the comment promises an alignment that does not exist, and a lookahead
// limiter or a convolution reverb would be silently misaligned against a dry
// path. This case exists to hold the fact, and should turn into a real
// alignment test the day a node returns non-zero.
TEST(latency_alignment_is_NOT_implemented) {
    Graph g;
    NodeId a = g.addNode(std::make_unique<ConstAudio>(1.0f));
    NodeId p = g.addNode(std::make_unique<AudioProbe>());
    std::string err;
    CHECK(g.connect(a, 0, p, 0, &err));
    auto sched = g.compile(44100.0, kFrames, &err);
    CHECK(sched != nullptr);
    if (!sched) return;
    // Every node in the project reports zero, which is why the gap is harmless
    // so far. If this ever fails, alignment has become load-bearing.
    for (const auto& s : sched->steps) CHECK_EQ(s.node->latencyFrames(), 0);
}

// --- deaf while flushing ---------------------------------------------------
//
// A node whose level has been taken to zero keeps RUNNING so its tails drain,
// but it must not accept new notes while it does. Otherwise holding a key and
// switching parts makes the newly selected engine sound a note that was
// pressed before it was selected -- reported exactly that way.
TEST(a_midi_muted_node_still_runs_but_receives_nothing) {
    Graph g;
    NodeId src = g.addNode(std::make_unique<MidiSource>(60));
    auto probeOwned = std::make_unique<MidiProbe>();
    MidiProbe* probe = probeOwned.get();
    NodeId sink = g.addNode(std::move(probeOwned));

    std::string err;
    CHECK(g.connect(src, 0, sink, 0, &err));
    auto sched = g.compile(44100.0, 128, &err);
    CHECK(sched != nullptr);
    if (!sched) return;

    // Hearing: the source's note reaches it.
    sched->run(128, 44100.0);
    CHECK_EQ(probe->received, 1);
    CHECK_EQ(probe->blocks, 1);

    // Deaf: still processed -- the tails have to drain -- but nothing arrives.
    probe->setMidiMuted(true);
    sched->run(128, 44100.0);
    CHECK_EQ(probe->received, 1);       // unchanged
    CHECK_EQ(probe->blocks, 2);         // but it DID run
    CHECK_EQ(static_cast<int>(probe->notes.size()), 0);

    // Hearing again once the part is selected.
    probe->setMidiMuted(false);
    sched->run(128, 44100.0);
    CHECK_EQ(probe->received, 2);
    CHECK_EQ(probe->blocks, 3);
}
