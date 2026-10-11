#pragma once

#include <atomic>

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

// The node graph. See GRAPH.md for the design and the reasoning.
//
// Two halves, deliberately separated:
//
//   Graph     -- nodes and edges. Edited on the UI thread. Never touched by the
//                audio thread.
//   Schedule  -- the compiled result: a flat list of steps with pre-assigned
//                buffers. Immutable. The audio thread walks it and nothing else.
//
// Publishing a new Schedule is a single atomic pointer store. The old one is
// retired to a queue and freed off the audio thread.

namespace bagosynths {

// ----------------------------------------------------------------- data types

enum class DataType : uint8_t {
    Audio,   // float samples, `width` channels
    Midi,    // events, per block
    Param,   // one float per channel, per block (control rate)
};

// Every pin carries a width: how many parallel channels flow along the edge.
// 1 = mono, 2 = stereo today. This is the reserved dimension for polyphony --
// wider edges later mean per-voice streams, exactly as VCV's polyphonic cables.
// See GRAPH.md. `kInherit` means "whatever is connected to me".
struct PinSpec {
    DataType type = DataType::Audio;
    int width = 1;
    const char* name = "";

    static constexpr int kInherit = -1;
};

struct MidiEvent {
    uint32_t frame = 0;        // offset within the block
    uint8_t status = 0;
    uint8_t data1 = 0;
    uint8_t data2 = 0;
};

// A MIDI buffer is bounded and preallocated like everything else. A flood of
// events must never be able to allocate inside the callback.
class MidiBuffer {
public:
    static constexpr int kCapacity = 512;

    void clear() { mCount = 0; }
    int size() const { return mCount; }
    const MidiEvent& operator[](int i) const { return mEvents[i]; }

    void push(const MidiEvent& e) {
        if (mCount < kCapacity) mEvents[mCount++] = e;
    }
    void append(const MidiBuffer& other) {
        for (int i = 0; i < other.mCount; ++i) push(other.mEvents[i]);
    }

private:
    MidiEvent mEvents[kCapacity];
    int mCount = 0;
};

// What a node is handed for one pin during process().
struct AudioPort {
    float* data = nullptr;     // width * numFrames, channel-major
    int width = 0;
    float* channel(int c) const { return data + static_cast<size_t>(c) * mStride; }
    int mStride = 0;           // frames per channel
};

// Peak magnitude of one block, folded into a running value so a stereo pair
// takes two calls. Branch on sign rather than std::fabs: this runs per block
// per instrument and there is no reason to pull in <cmath> for it.
inline float blockPeak(const float* p, int n, float sofar = 0.0f) {
    for (int i = 0; i < n; ++i) {
        const float a = p[i] < 0.0f ? -p[i] : p[i];
        if (a > sofar) sofar = a;
    }
    return sofar;
}

struct ProcessArgs {
    int numFrames = 0;
    double sampleRate = 44100.0;

    const AudioPort* audioIn = nullptr;    int numAudioIn = 0;
    AudioPort* audioOut = nullptr;         int numAudioOut = 0;
    const MidiBuffer** midiIn = nullptr;   int numMidiIn = 0;
    MidiBuffer** midiOut = nullptr;        int numMidiOut = 0;
    const float* paramIn = nullptr;        int numParamIn = 0;
};

// What a parameter may be set to (PLAN.md D6): its range, and whether only
// whole numbers mean anything (a drawbar, a model number, semitones).
struct ParamRange {
    float min = 0.0f;
    float max = 1.0f;
    bool integer = false;
};

// ------------------------------------------------------------------- the node

class Node {
public:
    virtual ~Node() = default;

    virtual const char* typeName() const = 0;

    // Called off the audio thread, before the node is scheduled. Allocate here
    // and nowhere else.
    virtual void prepare(double sampleRate, int maxFrames) {}

    // Called on the audio thread. The contract from M2 applies without
    // exception: no allocation, no locks, no file I/O, no JNI.
    virtual void process(const ProcessArgs& args) = 0;

    virtual int numInputs() const { return 0; }
    virtual int numOutputs() const { return 0; }
    virtual PinSpec inputPin(int) const { return {}; }
    virtual PinSpec outputPin(int) const { return {}; }

    // Frames of delay this node introduces. Non-zero for convolution and
    // lookahead limiters.
    //
    // RESERVED, NOT IMPLEMENTED. compile() does not read this and does not
    // align parallel paths that reconverge -- an earlier version of this
    // comment said it did, which was never true. Harmless so far because every
    // node in the project returns zero, and engine/tests/test_graph.cpp asserts
    // exactly that, so the day one does not the gap stops being silent.
    virtual int latencyFrames() const { return 0; }

    // Parameter access, used by the UI and by Param edges. Index space is the
    // node's own.
    virtual int numParams() const { return 0; }
    virtual void setParam(int index, float value) {}
    virtual float getParam(int index) const { return 0.0f; }
    virtual const char* paramName(int index) const { return ""; }
    // Defaults to 0..1, continuous: override where a parameter is not.
    virtual ParamRange paramRange(int index) const { return {}; }

    // --- running or not ----------------------------------------------------
    //
    // A DISABLED node is not processed at all, and its outputs are zeroed so a
    // consumer sees silence rather than the last block it produced.
    //
    // This is what makes "only the selected instrument runs" true rather than
    // merely inaudible. The pedalboard model -- every instrument always
    // rendering, mixed by level -- costs the CPU of every engine whether or not
    // it can be heard: measured, the idle render peak went 0,81 ms at three
    // instruments to 1,30 ms at four, against a 2,902 ms block. An engine at
    // level zero is PROVABLY silent, so rendering it buys nothing.
    //
    // Set off the audio thread, read on it: a relaxed atomic, because a block
    // either side of the change is equally correct.
    void setEnabled(bool on) { mEnabled.store(on, std::memory_order_relaxed); }
    bool enabled() const { return mEnabled.load(std::memory_order_relaxed); }

    // --- deaf, but still running -------------------------------------------
    //
    // A node that is being FLUSHED -- its level taken to zero, its tails still
    // draining -- must not accept new notes. It is still enabled and still
    // scheduled, so without this it goes on hearing everything played while it
    // is inaudible, and starts notes nobody asked it to start.
    //
    // Michiel found it the way these things are always found: hold a key down,
    // switch the part, and the newly selected engine sounds the note you
    // pressed BEFORE you switched, "as if it consumed that key down". It had:
    // it was still flushing, so it took the note-on silently, and selecting it
    // merely faded that already-running note up.
    //
    // Set off the audio thread, read on it; relaxed, because a block either
    // side of the change is equally correct.
    void setMidiMuted(bool on) { mMidiMuted.store(on, std::memory_order_relaxed); }
    bool midiMuted() const { return mMidiMuted.load(std::memory_order_relaxed); }

    // Release every sounding voice. Called on the render thread, between
    // blocks, when a node is about to be disabled: a node that is not
    // processed cannot finish the notes it is holding, and would resurrect
    // them the moment it is enabled again. Default does nothing, which is
    // right for anything that holds no state.
    virtual void allNotesOff() {}

    // Peak magnitude of the last block this node rendered, measured BEFORE its
    // own output level is applied.
    //
    // This exists for one question: "has this engine actually gone quiet?".
    // Reading the node's output PORT cannot answer it, because every
    // instrument applies its Level on the way out, so a muted engine writes
    // exact zeros while its reverb and rotary speaker are still ringing
    // internally. Freezing it there and resuming later replays that tail.
    //
    // -1 means the node does not report; callers then fall back to a timeout.
    virtual float lastRenderPeak() const { return -1.0f; }

private:
    std::atomic<bool> mEnabled{true};
    std::atomic<bool> mMidiMuted{false};
};

// ------------------------------------------------------------- graph and edge

using NodeId = int32_t;

class WireControl;   // Wire.h

struct Edge {
    NodeId fromNode = -1;
    int fromPin = 0;
    NodeId toNode = -1;
    int toPin = 0;
    // Set for a SWITCHABLE edge (Graph::connectSwitchable): its on/off and
    // gain can change without a recompile, so it always gets a mixing step.
    // Null for a plain edge, which a single source aliases straight through.
    std::shared_ptr<WireControl> control;
};

// The compiled, immutable result. The audio thread sees only this.
class Schedule {
public:
    // Fan-in. Several edges into one input pin have to be mixed, and that
    // mixing is a step of its own rather than a branch inside process(): the
    // executor stays a flat loop and nodes never learn how many sources feed
    // them.
    //
    // `first` marks the edge that initialises the destination -- it copies where
    // the others add, which saves clearing the buffer and is the only reason the
    // flag exists.
    struct AudioSum {
        const float* src = nullptr;
        float* dst = nullptr;
        int channels = 0;
        int stride = 0;        // frames between channels; buffers are channel-major
        bool first = false;
        WireControl* wire = nullptr;   // switchable edge: gain and on/off, ramped
    };
    struct MidiSum {
        const MidiBuffer* src = nullptr;
        MidiBuffer* dst = nullptr;
        bool first = false;
        WireControl* wire = nullptr;   // switchable edge: gating with note tracking
    };

    struct Step {
        Node* node = nullptr;
        int firstAudioIn = 0,  numAudioIn = 0;
        int firstAudioOut = 0, numAudioOut = 0;
        int firstMidiIn = 0,   numMidiIn = 0;
        int firstMidiOut = 0,  numMidiOut = 0;
        int firstAudioSum = 0, numAudioSum = 0;
        int firstMidiSum = 0,  numMidiSum = 0;

        // Reserved for the parallel executor. Present and correct even though
        // the current executor walks the list serially -- see GRAPH.md, so that
        // switching on parallelism is an executor swap, not a redesign.
        int dependencyCount = 0;
        int firstDependent = 0, numDependents = 0;
    };

    void run(int numFrames, double sampleRate);

    // Per-node worst-case time. Cheap (one clock read per node per block) and
    // the only way to answer "which node blew the deadline" without guessing.
    std::vector<float> stepMaxMs;
    std::vector<float> stepLastMs;
    void resetProfile();

    std::vector<Step> steps;
    std::vector<AudioSum> audioSums;
    std::vector<MidiSum> midiSums;
    std::vector<int> dependents;        // flattened adjacency, for the future

    // Keeps every switchable edge's control alive while this schedule runs,
    // even if the graph drops the edge in the meantime.
    std::vector<std::shared_ptr<WireControl>> wireRefs;

    // Buffer pools, preallocated at compile time.
    std::vector<std::vector<float>> audioBuffers;
    std::vector<std::unique_ptr<MidiBuffer>> midiBuffers;

    // Flattened per-step port tables, indexed by the Step's first*/num* fields.
    std::vector<AudioPort> audioInPorts, audioOutPorts;
    std::vector<const MidiBuffer*> midiInPorts;
    std::vector<MidiBuffer*> midiOutPorts;

    // Handed to a node that is MIDI-muted, in place of its real inputs. Sized
    // at compile time and permanently empty, so the swap costs nothing and
    // allocates nothing on the audio thread. See Node::setMidiMuted.
    MidiBuffer emptyMidi;
    std::vector<const MidiBuffer*> mutedMidiIn;

    // Which buffers the entry and exit nodes use, so the engine can feed MIDI in
    // and take audio out without knowing anything about the graph.
    MidiBuffer* midiInputSink = nullptr;
    const AudioPort* audioOutputSource = nullptr;

    int maxFrames = 0;
};

class Graph {
public:
    NodeId addNode(std::unique_ptr<Node> node);
    void removeNode(NodeId id);
    bool connect(NodeId from, int fromPin, NodeId to, int toPin, std::string* error = nullptr);
    // A wire a live set can switch (Wire.h). Returns its control, or null with
    // `error` filled. The control survives recompiles.
    std::shared_ptr<WireControl> connectSwitchable(NodeId from, int fromPin, NodeId to, int toPin,
                                                   std::string* error = nullptr);
    void disconnect(const Edge& edge);

    Node* node(NodeId id) const;
    const std::vector<Edge>& edges() const { return mEdges; }

    // Ordered access, for anything that needs to walk the graph rather than look
    // a node up: saving a patch, and later drawing one. Order is insertion order
    // and is stable, which is what makes "the second sfizz node" a meaningful
    // thing to say in a file.
    int nodeCount() const { return static_cast<int>(mNodes.size()); }
    Node* nodeAt(int index) const {
        return (index >= 0 && index < nodeCount()) ? mNodes[index].node.get() : nullptr;
    }
    NodeId idAt(int index) const {
        return (index >= 0 && index < nodeCount()) ? mNodes[index].id : -1;
    }

    // Builds a Schedule. Off the audio thread. Returns null and fills `error` if
    // the graph is invalid -- a cycle without a delay, or a width mismatch.
    std::unique_ptr<Schedule> compile(double sampleRate, int maxFrames,
                                      std::string* error = nullptr) const;

    void setMidiEntry(NodeId id) { mMidiEntry = id; }
    void setAudioExit(NodeId id) { mAudioExit = id; }

private:
    struct Slot {
        NodeId id = -1;
        std::unique_ptr<Node> node;
    };

    std::vector<Slot> mNodes;
    std::vector<Edge> mEdges;
    NodeId mNextId = 1;
    NodeId mMidiEntry = -1;
    NodeId mAudioExit = -1;
};

}  // namespace bagosynths
