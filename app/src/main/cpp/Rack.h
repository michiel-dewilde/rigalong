#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <rigalong/Graph.h>
#include <rigalong/MidiZone.h>
#include <rigalong/Wire.h>
#include "graph/SetBfreeNode.h"
#include "graph/SurgeNode.h"
#include "graph/DexedNode.h"
#include "graph/PlaitsNode.h"
#include "graph/FluidNode.h"
#include "graph/Nodes.h"
#include "graph/FxNode.h"

// THE RACK: the node graph a song plays on, and everything that fills it.
//
//   MIDI In -> zone -> engine -> insert effect -> Reverb -> master effect -> Audio Out
//
// one such row per INSTANCE, each engine-side wire switchable (Wire.h), so a
// live set can split, layer, transpose and route without a recompile and
// without stranding a held note. An instance is one engine node with its own
// zone, insert effect and the two wires around it -- what a song calls a sound.
// The first six are one of each engine (Engine order); a song that wants a
// second sfizz or a third Surge XT gets more, added while audio runs.
//
// The rack owns the graph, builds it, loads the engines' libraries and presets
// in the background, and parks the instances nobody can hear (updateGates).
// Compiling the graph into a schedule and running it is the AudioEngine's.
//
// Threads: updateGates() on the render thread; everything else off it.
class Rack {
public:
    ~Rack();

    // The engines, in the order the first six instances are built.
    enum Engine { kSfizz, kSetBfree, kSurge, kDexed, kPlaits, kFluid, kEngines };
    static constexpr int kMaxInstances = 24;

    rigalong::Graph& graph() { return mGraph; }

    // An engine by the name of its nodes (Node::typeName: "sfizz", "Surge XT"),
    // or -1; and that name, or "". The names are the first six instances' own,
    // so there is no second list to keep in step. After build().
    int engineNamed(const std::string& name) const;
    const char* engineName(int type) const;

    // The default patch: one instance of each engine, all but sfizz silent.
    // Does nothing the second time. The caller compiles.
    void build();

    // --- instances -----------------------------------------------------------
    int instanceCount() const { return mInstanceCount.load(std::memory_order_acquire); }
    rigalong::Node* instanceNode(int i) const { return i >= 0 && i < instanceCount() ? mInstanceNode[i] : nullptr; }
    rigalong::FxNode* instanceFx(int i) const { return i >= 0 && i < instanceCount() ? mFx[i] : nullptr; }
    rigalong::MidiZoneNode* zone(int i) const { return i >= 0 && i < instanceCount() ? mZone[i] : nullptr; }
    void setWire(int i, bool midiOn, bool audioOn, float gain) {
        if (i < 0 || i >= instanceCount() || !mMidiWire[i]) return;
        mMidiWire[i]->setOn(midiOn);
        mAudioWire[i]->setOn(audioOn);
        mAudioWire[i]->setGain(gain);
    }

    // A new instance of `type`: built, wired, compiled in by `compile` (which
    // must prepare only the new nodes, so what is playing is not touched), and
    // its library loading started in the background as the first one's was.
    // Returns its index, or -1. The caller holds whatever guards a compile.
    int addInstance(int type, double rate, const std::function<bool(std::string*)>& compile);

    // Gives instance `i` the preset `name` (an SFZ id with its `path`, a setBfree
    // registration, a Surge "Category/Name", a DX7 voice, a SoundFont file and
    // `program`, a Plaits model). Slow loads run in the background; a request
    // made before the instance's library is up is honoured once it is.
    void loadInstancePreset(int i, const std::string& name, int program, const std::string& path);
    bool instanceBusy(int i) const;
    // The preset it has now, by the same name, or "".
    std::string instancePreset(int i) const;
    // What it sounds like now, by the name a song would ask for it with, for
    // every engine that has such a name (setBfree: ""); and a FluidSynth's
    // preset within its font, or -1. For a song that says "as loaded" to be
    // given what is loaded when it is saved.
    std::string instanceSound(int i) const;
    int instanceProgram(int i) const;

    // --- the first instance of each engine -----------------------------------
    // Setup's engine panels and the content lists ask these.
    rigalong::SfizzNode* sfizz() const { return mSfizz; }
    rigalong::SurgeNode* surge() const { return mSurge; }
    rigalong::DexedNode* dexed() const { return mDexed; }
    rigalong::FluidNode* fluid() const { return mFluid; }

    // Brings Surge up on a loader thread: constructing it scans the patch
    // library, which is 41 ms of file work and has no business on the UI thread
    // or on whatever thread compiled the graph. Until it finishes, the Surge
    // node renders silence and the rest of the rig is unaffected.
    void initSurgeAsync(const std::string& dataPath, const std::string& homePath);
    // Queues a Surge patch load, by the stable "Category/Name" a patch stores.
    void loadSurgePatchNamedAsync(const std::string& name);
    // Surge's folder for the player's own patches, as Surge worked it out; empty before it is up.
    std::string surgeUserPatchesPath() const;

    // Reads every DX7 cartridge in `dirs`. File IO, so it runs on a loader.
    // Selecting a voice afterwards is cheap enough to do inline: it unpacks 128
    // bytes and hands them over through a single-slot atomic.
    void loadDexedBanksAsync(const std::vector<std::string>& dirs);

    // Builds FluidSynth and lists the SoundFonts in `dirs`. Neither is slow,
    // but loading a font afterwards is -- megabytes of samples, and for SF3 a
    // Vorbis decode of every one of them -- so all of it runs on a loader.
    void initFluidAsync(const std::vector<std::string>& dirs, double rate);
    void loadSoundFontNamedAsync(const std::string& name);

    // Queues an SFZ load for the first sfizz. Returns immediately: parsing an
    // instrument and opening its samples must never happen on the audio thread,
    // and must not block the UI either.
    void loadInstrumentAsync(const std::string& id, const std::string& path);
    bool isLoading() const { return mLoading.load(std::memory_order_relaxed); }

    // Content was added to the engines' folders (an imported SoundFont, DX7
    // bank or Surge patch): every FluidSynth, Dexed and Surge in the rack looks
    // again, off the audio and UI threads. Their current sounds stay.
    void rescanContentAsync();

    // --- render thread ---------------------------------------------------------
    // Gates instances in and out of the schedule by level, releasing their
    // voices on each transition. Before each block.
    void updateGates(int frames, double rate);

    // --- profile ---------------------------------------------------------------
    // The engines' own timing figures and the held pre-level peaks, cleared.
    void resetProfiles();
    // The sfizz split and each instance's held peak, to the log.
    void logProfile();

private:
    void wireInstance(int i, int type, rigalong::Node* node, rigalong::NodeId idNode, int levelParam);

    rigalong::Graph mGraph;
    rigalong::SfizzNode* mSfizz = nullptr;
    rigalong::SetBfreeNode* mSetBfree = nullptr;
    rigalong::SurgeNode* mSurge = nullptr;
    rigalong::DexedNode* mDexed = nullptr;
    rigalong::PlaitsNode* mPlaits = nullptr;
    rigalong::FluidNode* mFluid = nullptr;

    // Per instance. Entries are written before mInstanceCount moves past them,
    // so the render thread never sees half of one.
    rigalong::MidiZoneNode* mZone[kMaxInstances] = {};
    rigalong::FxNode* mFx[kMaxInstances] = {};
    std::shared_ptr<rigalong::WireControl> mMidiWire[kMaxInstances];
    std::shared_ptr<rigalong::WireControl> mAudioWire[kMaxInstances];
    int mInstanceType[kMaxInstances] = {};
    rigalong::Node* mInstanceNode[kMaxInstances] = {};
    int mInstanceLevelParam[kMaxInstances] = {};
    std::atomic<int> mInstanceCount{0};
    rigalong::NodeId mIdMidiIn = -1;
    rigalong::NodeId mIdReverb = -1;

    // --- loaders ---------------------------------------------------------------
    // Ordinary priority and deliberately NOT pinned to the big cores: a loader
    // must never compete with the audio thread. See GRAPH.md.
    //
    // The first sfizz has a joinable thread of its own. Surge, Dexed and
    // FluidSynth, and every added instance, load on detached pthreads rather
    // than std::threads because Surge needs a bigger stack than bionic's
    // default -- see runOnBigStack() in the .cpp, which explains what that cost.
    std::thread mLoaderThread;
    std::atomic<bool> mLoading{false};
    mutable std::mutex mLoadLock;
    std::string mPendingLoad;
    std::string mPendingId;
    std::atomic<bool> mSurgeBusy{false};
    std::atomic<bool> mDexedBusy{false};
    std::atomic<bool> mFluidBusy{false};
    // The added instances' loads (the first six use the flags above).
    std::atomic<bool> mInstanceBusy[kMaxInstances] = {};
    // A FluidSynth instance's preset as last asked for, plus one (0: none).
    std::atomic<int> mFluidProgram[kMaxInstances] = {};
    // What the first instances were brought up with, for the ones added later.
    std::string mSurgeDataPath, mSurgeHomePath;
    std::vector<std::string> mFluidDirs, mDexedDirs;

    // --- gates -----------------------------------------------------------------
    // A silenced instrument keeps RUNNING until it has actually gone quiet.
    //
    // Its level is already zero, so none of it is audible; the point is to let
    // whatever it holds internally -- setBfree's rotary and reverb live
    // inside beatrix, not in this graph -- decay away. A node that stops being
    // processed freezes those tails and resumes them when it comes back, which
    // is audible and was reported as exactly that.
    //
    // A fixed grace was the first attempt and it was a guess: too short for a
    // long reverb, wasteful for a node that fell silent at once. So the gate
    // watches the node's own pre-level peak instead and asks for a full second
    // of continuous silence before parking it.
    static constexpr double kQuietSeconds = 1.0;

    // What counts as silence: -80 dBFS.
    //
    // This started at -100 dBFS, which was needlessly strict and cost real
    // time: a piano tail decays exponentially, so the last twenty decibels of
    // it take SECONDS while being inaudible throughout. Measured on this rig
    // at -100 dBFS: sfizz 17,64 s, Plaits 9,76 s, FluidSynth 5,21 s, against a
    // 1,00 s minimum for an engine that was not sounding.
    //
    // -80 dBFS is still far below hearing. The only thing the threshold has to
    // guarantee is that the FROZEN tail is inaudible when the engine is
    // brought back: at -80 dBFS, with the fader at unity and the master at its
    // +12 dB end, that tail returns at -68 dBFS. Nothing in a room hears it.
    static constexpr float kSilenceFloor = 1.0e-4f;

    // A ceiling, because "wait for silence" is not a termination proof: a
    // self-oscillating patch or a stuck reverb never reaches the floor, and the
    // point of the gate is to stop paying for engines nobody is playing. Thirty
    // seconds is far past any musical tail; reaching it is logged, because it
    // means an engine is droning with its output muted.
    static constexpr double kQuietCeilingSeconds = 30.0;

    // Held peak of each instrument's own output, before its level. The render
    // thread writes, logProfile() reads and resetProfiles() clears.
    float mNodePeakHold[kMaxInstances] = {};

    bool mGateFlushing[kMaxInstances] = {};
    int mGateFade[kMaxInstances] = {};    // frames left of the level ramp
    int mGateQuiet[kMaxInstances] = {};   // frames of continuous silence
    int mGateFlush[kMaxInstances] = {};   // frames spent flushing in total
};
