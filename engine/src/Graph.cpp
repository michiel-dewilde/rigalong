#include <bagosynths/Graph.h>

#include <algorithm>
#include <cstring>
#include <utility>
#include <ctime>

namespace {
inline double nowSec() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}
}  // namespace

namespace bagosynths {

// ---------------------------------------------------------------- Schedule

void Schedule::run(int numFrames, double sampleRate) {
    // Serial executor. Steps are in topological order, so every input buffer is
    // already filled by the time its consumer runs.
    //
    // The parallel version replaces this loop and nothing else: dependencyCount
    // and dependents are already computed by the compiler.
    for (size_t stepIndex = 0; stepIndex < steps.size(); ++stepIndex) {
        const Step& s = steps[stepIndex];
        const double t0 = nowSec();

        // A disabled node does not run at all: no input mixing, no process().
        // Its outputs are ZEROED rather than left alone, because a consumer's
        // fan-in copies the first source and adds the rest -- stale audio would
        // otherwise be mixed in forever, and the node would go on being heard
        // after it stopped running.
        if (!s.node->enabled()) {
            for (int i = 0; i < s.numAudioOut; ++i) {
                const AudioPort& p = audioOutPorts[s.firstAudioOut + i];
                for (int c = 0; c < p.width; ++c) {
                    std::fill_n(p.channel(c), numFrames, 0.0f);
                }
            }
            for (int i = 0; i < s.numMidiOut; ++i) {
                if (MidiBuffer* m = midiOutPorts[s.firstMidiOut + i]) m->clear();
            }
            if (stepIndex < stepMaxMs.size()) stepLastMs[stepIndex] = 0.0f;
            continue;
        }

        // Mix the fan-in for this step's inputs. The first source copies and the
        // rest add, so nothing has to be cleared first.
        for (int i = 0; i < s.numAudioSum; ++i) {
            const AudioSum& m = audioSums[s.firstAudioSum + i];
            // numFrames, not the whole buffer: these are sized for the largest
            // block the engine might ever ask for, and we render 128 at a time.
            for (int c = 0; c < m.channels; ++c) {
                const float* src = m.src + static_cast<size_t>(c) * m.stride;
                float* dst = m.dst + static_cast<size_t>(c) * m.stride;
                if (m.first) {
                    std::memcpy(dst, src, static_cast<size_t>(numFrames) * sizeof(float));
                } else {
                    for (int k = 0; k < numFrames; ++k) dst[k] += src[k];
                }
            }
        }
        for (int i = 0; i < s.numMidiSum; ++i) {
            const MidiSum& m = midiSums[s.firstMidiSum + i];
            if (m.first) m.dst->clear();
            m.dst->append(*m.src);
        }

        ProcessArgs args;
        args.numFrames = numFrames;
        args.sampleRate = sampleRate;

        args.audioIn = s.numAudioIn ? &audioInPorts[s.firstAudioIn] : nullptr;
        args.numAudioIn = s.numAudioIn;
        args.audioOut = s.numAudioOut ? &audioOutPorts[s.firstAudioOut] : nullptr;
        args.numAudioOut = s.numAudioOut;
        // A flushing node is DEAF: it is handed empty MIDI so it drains its
        // tails without taking new notes. See Node::setMidiMuted.
        //
        // Swapped HERE rather than skipped in the fan-in above, and the
        // difference is not cosmetic: a single edge is wired straight through,
        // with the consumer's input port pointing AT the producer's output
        // buffer and no sum step at all. Clearing it there would have emptied
        // the producer's own buffer and silenced everyone else too -- which is
        // what the test caught.
        args.midiIn = s.numMidiIn
                          ? (s.node->midiMuted() ? mutedMidiIn.data()
                                                 : &midiInPorts[s.firstMidiIn])
                          : nullptr;
        args.numMidiIn = s.numMidiIn;
        args.midiOut = s.numMidiOut ? &midiOutPorts[s.firstMidiOut] : nullptr;
        args.numMidiOut = s.numMidiOut;

        s.node->process(args);

        const auto ms = static_cast<float>((nowSec() - t0) * 1000.0);
        if (stepIndex < stepMaxMs.size()) {
            stepLastMs[stepIndex] = ms;
            if (ms > stepMaxMs[stepIndex]) stepMaxMs[stepIndex] = ms;
        }
    }
}

void Schedule::resetProfile() {
    std::fill(stepMaxMs.begin(), stepMaxMs.end(), 0.0f);
    std::fill(stepLastMs.begin(), stepLastMs.end(), 0.0f);
}

// ------------------------------------------------------------------- Graph

NodeId Graph::addNode(std::unique_ptr<Node> node) {
    Slot slot;
    slot.id = mNextId++;
    slot.node = std::move(node);
    const NodeId id = slot.id;
    mNodes.push_back(std::move(slot));
    return id;
}

void Graph::removeNode(NodeId id) {
    mEdges.erase(std::remove_if(mEdges.begin(), mEdges.end(),
                                [id](const Edge& e) {
                                    return e.fromNode == id || e.toNode == id;
                                }),
                 mEdges.end());
    mNodes.erase(std::remove_if(mNodes.begin(), mNodes.end(),
                                [id](const Slot& s) { return s.id == id; }),
                 mNodes.end());
}

Node* Graph::node(NodeId id) const {
    for (const Slot& s : mNodes) {
        if (s.id == id) return s.node.get();
    }
    return nullptr;
}

bool Graph::connect(NodeId from, int fromPin, NodeId to, int toPin, std::string* error) {
    Node* a = node(from);
    Node* b = node(to);
    if (!a || !b) {
        if (error) *error = "no such node";
        return false;
    }
    if (fromPin < 0 || fromPin >= a->numOutputs() || toPin < 0 || toPin >= b->numInputs()) {
        if (error) *error = "no such pin";
        return false;
    }

    const PinSpec out = a->outputPin(fromPin);
    const PinSpec in = b->inputPin(toPin);

    if (out.type != in.type) {
        if (error) *error = "pin types differ";
        return false;
    }

    // Width rules, per GRAPH.md: equal connects; 1 -> N broadcasts; anything else
    // needs an explicit adapter node, so gain and channel folding are never a
    // silent surprise.
    if (in.width != PinSpec::kInherit && out.width != PinSpec::kInherit &&
        out.width != in.width && out.width != 1) {
        if (error) *error = "width mismatch: insert an adapter node";
        return false;
    }

    mEdges.push_back({from, fromPin, to, toPin});
    return true;
}

void Graph::disconnect(const Edge& edge) {
    mEdges.erase(std::remove_if(mEdges.begin(), mEdges.end(),
                                [&](const Edge& e) {
                                    return e.fromNode == edge.fromNode &&
                                           e.fromPin == edge.fromPin &&
                                           e.toNode == edge.toNode &&
                                           e.toPin == edge.toPin;
                                }),
                 mEdges.end());
}

std::unique_ptr<Schedule> Graph::compile(double sampleRate, int maxFrames,
                                         std::string* error) const {
    const int n = static_cast<int>(mNodes.size());
    if (n == 0) {
        if (error) *error = "empty graph";
        return nullptr;
    }

    // index <-> id
    std::vector<NodeId> ids(n);
    for (int i = 0; i < n; ++i) ids[i] = mNodes[i].id;
    auto indexOf = [&](NodeId id) {
        for (int i = 0; i < n; ++i) {
            if (ids[i] == id) return i;
        }
        return -1;
    };

    // --- topological sort (Kahn). A cycle is an error; feedback must go through
    // an explicit Delay node, exactly as Pd requires.
    std::vector<int> inDegree(n, 0);
    std::vector<std::vector<int>> succ(n);
    for (const Edge& e : mEdges) {
        const int a = indexOf(e.fromNode), b = indexOf(e.toNode);
        if (a < 0 || b < 0) continue;
        succ[a].push_back(b);
        inDegree[b]++;
    }

    std::vector<int> order;
    order.reserve(n);
    std::vector<int> ready;
    for (int i = 0; i < n; ++i) {
        if (inDegree[i] == 0) ready.push_back(i);
    }
    while (!ready.empty()) {
        const int i = ready.back();
        ready.pop_back();
        order.push_back(i);
        for (int j : succ[i]) {
            if (--inDegree[j] == 0) ready.push_back(j);
        }
    }
    if (static_cast<int>(order.size()) != n) {
        if (error) *error = "cycle in graph: feedback needs an explicit Delay node";
        return nullptr;
    }

    auto sched = std::make_unique<Schedule>();
    sched->maxFrames = maxFrames;

    // --- buffer assignment.
    //
    // One buffer per output pin, and one per input pin which is zeroed and then
    // summed into from every incoming edge. That costs a copy per edge, which at
    // 89 frames and a handful of nodes is nothing, and it makes fan-in (several
    // sources into one input) fall out for free.
    //
    // Reusing buffers the way a register allocator does is the obvious
    // optimisation and deliberately not done yet: correctness first, and the
    // measured CPU headroom does not call for it.
    struct PinBuf { int audio = -1; int midi = -1; };
    std::vector<std::vector<PinBuf>> outBuf(n), inBuf(n);

    auto newAudioBuffer = [&](int width) {
        sched->audioBuffers.emplace_back(static_cast<size_t>(width) * maxFrames, 0.0f);
        return static_cast<int>(sched->audioBuffers.size()) - 1;
    };
    auto newMidiBuffer = [&]() {
        sched->midiBuffers.push_back(std::make_unique<MidiBuffer>());
        return static_cast<int>(sched->midiBuffers.size()) - 1;
    };

    // Resolve inherited widths: a node that says kInherit takes the width of
    // whatever feeds it, defaulting to stereo when nothing does.
    auto resolvedWidth = [&](const PinSpec& spec) {
        return spec.width == PinSpec::kInherit ? 2 : spec.width;
    };

    for (int i = 0; i < n; ++i) {
        Node* node = mNodes[i].node.get();
        outBuf[i].resize(node->numOutputs());
        inBuf[i].resize(node->numInputs());

        for (int p = 0; p < node->numOutputs(); ++p) {
            const PinSpec s = node->outputPin(p);
            if (s.type == DataType::Audio) outBuf[i][p].audio = newAudioBuffer(resolvedWidth(s));
            else if (s.type == DataType::Midi) outBuf[i][p].midi = newMidiBuffer();
        }
        for (int p = 0; p < node->numInputs(); ++p) {
            const PinSpec s = node->inputPin(p);
            if (s.type == DataType::Audio) inBuf[i][p].audio = newAudioBuffer(resolvedWidth(s));
            else if (s.type == DataType::Midi) inBuf[i][p].midi = newMidiBuffer();
        }
    }

    // --- emit steps in topological order.
    //
    // Copy/sum edges are emitted as part of the consuming step by giving the
    // consumer an input buffer and recording which outputs feed it. To keep the
    // executor a single flat loop, the summing is done by a tiny internal step
    // type rather than branching inside run(); for now, with one edge per input
    // in the default patch, the compiler simply aliases the input port to the
    // producing output buffer when there is exactly one incoming edge.
    for (int idx : order) {
        Node* node = mNodes[idx].node.get();
        Schedule::Step step;
        step.node = node;

        step.firstAudioIn = static_cast<int>(sched->audioInPorts.size());
        step.firstMidiIn = static_cast<int>(sched->midiInPorts.size());
        step.firstAudioSum = static_cast<int>(sched->audioSums.size());
        step.firstMidiSum = static_cast<int>(sched->midiSums.size());
        for (int p = 0; p < node->numInputs(); ++p) {
            const PinSpec s = node->inputPin(p);

            // Gather incoming edges for this pin.
            std::vector<std::pair<int, int>> sources;   // node index, pin
            for (const Edge& e : mEdges) {
                if (e.toNode == ids[idx] && e.toPin == p) {
                    sources.emplace_back(indexOf(e.fromNode), e.fromPin);
                }
            }

            if (s.type == DataType::Audio) {
                AudioPort port;
                port.width = resolvedWidth(s);
                port.mStride = maxFrames;

                // One source is aliased straight through -- no copy at all. Two
                // or more are mixed into this pin's own buffer beforehand, which
                // is what makes layering two instruments into one effect work.
                int bufIndex;
                if (sources.size() == 1) {
                    bufIndex = outBuf[sources[0].first][sources[0].second].audio;
                } else {
                    bufIndex = inBuf[idx][p].audio;
                    for (size_t k = 0; k < sources.size(); ++k) {
                        Schedule::AudioSum mix;
                        mix.src = sched->audioBuffers[outBuf[sources[k].first]
                                                             [sources[k].second]
                                                                     .audio]
                                          .data();
                        mix.dst = sched->audioBuffers[bufIndex].data();
                        mix.channels = port.width;
                        mix.stride = maxFrames;
                        mix.first = (k == 0);
                        sched->audioSums.push_back(mix);
                        step.numAudioSum++;
                    }
                    // Nothing connected: the buffer stays as it was allocated,
                    // which is silence, and the node sees silence rather than
                    // last block's audio.
                    if (sources.empty()) {
                        std::fill(sched->audioBuffers[bufIndex].begin(),
                                  sched->audioBuffers[bufIndex].end(), 0.0f);
                    }
                }
                port.data = sched->audioBuffers[bufIndex].data();
                sched->audioInPorts.push_back(port);
                step.numAudioIn++;
            } else if (s.type == DataType::Midi) {
                int bufIndex;
                if (sources.size() == 1) {
                    bufIndex = outBuf[sources[0].first][sources[0].second].midi;
                } else {
                    bufIndex = inBuf[idx][p].midi;
                    for (size_t k = 0; k < sources.size(); ++k) {
                        Schedule::MidiSum mix;
                        mix.src = sched->midiBuffers[outBuf[sources[k].first]
                                                            [sources[k].second]
                                                                    .midi]
                                          .get();
                        mix.dst = sched->midiBuffers[bufIndex].get();
                        mix.first = (k == 0);
                        sched->midiSums.push_back(mix);
                        step.numMidiSum++;
                    }
                }
                sched->midiInPorts.push_back(sched->midiBuffers[bufIndex].get());
                step.numMidiIn++;
            }
        }

        step.firstAudioOut = static_cast<int>(sched->audioOutPorts.size());
        step.firstMidiOut = static_cast<int>(sched->midiOutPorts.size());
        for (int p = 0; p < node->numOutputs(); ++p) {
            const PinSpec s = node->outputPin(p);
            if (s.type == DataType::Audio) {
                AudioPort port;
                port.width = resolvedWidth(s);
                port.mStride = maxFrames;
                port.data = sched->audioBuffers[outBuf[idx][p].audio].data();
                sched->audioOutPorts.push_back(port);
                step.numAudioOut++;
            } else if (s.type == DataType::Midi) {
                sched->midiOutPorts.push_back(sched->midiBuffers[outBuf[idx][p].midi].get());
                step.numMidiOut++;
            }
        }

        sched->steps.push_back(step);
    }
    sched->stepMaxMs.assign(sched->steps.size(), 0.0f);
    sched->stepLastMs.assign(sched->steps.size(), 0.0f);

    // --- dependency counts, for the parallel executor that does not exist yet.
    {
        std::vector<int> stepOfNode(n, -1);
        for (int i = 0; i < static_cast<int>(order.size()); ++i) stepOfNode[order[i]] = i;
        for (int i = 0; i < n; ++i) {
            for (int j : succ[i]) {
                sched->steps[stepOfNode[j]].dependencyCount++;
            }
        }
        for (int i = 0; i < n; ++i) {
            Schedule::Step& s = sched->steps[stepOfNode[i]];
            s.firstDependent = static_cast<int>(sched->dependents.size());
            for (int j : succ[i]) sched->dependents.push_back(stepOfNode[j]);
            s.numDependents = static_cast<int>(sched->dependents.size()) - s.firstDependent;
        }
    }

    // --- entry and exit, so the engine can feed MIDI and take audio without
    // knowing the graph's shape.
    if (mMidiEntry >= 0) {
        const int i = indexOf(mMidiEntry);
        if (i >= 0 && !outBuf[i].empty() && outBuf[i][0].midi >= 0) {
            sched->midiInputSink = sched->midiBuffers[outBuf[i][0].midi].get();
        }
    }
    if (mAudioExit >= 0) {
        const int i = indexOf(mAudioExit);
        const int stepIndex = static_cast<int>(
                std::find(order.begin(), order.end(), i) - order.begin());
        if (i >= 0 && stepIndex < static_cast<int>(order.size())) {
            const Schedule::Step& s = sched->steps[stepIndex];
            if (s.numAudioIn > 0) {
                sched->audioOutputSource = &sched->audioInPorts[s.firstAudioIn];
            }
        }
    }

    // Prepare every node now, off the audio thread.
    for (const Slot& s : mNodes) s.node->prepare(sampleRate, maxFrames);

    // Enough empty-MIDI pointers for the hungriest step, so a muted node can be
    // handed them without allocating on the audio thread.
    {
        size_t widest = 0;
        for (const Schedule::Step& st : sched->steps) {
            widest = std::max(widest, static_cast<size_t>(st.numMidiIn));
        }
        sched->mutedMidiIn.assign(widest, &sched->emptyMidi);
    }

    return sched;
}

}  // namespace bagosynths
