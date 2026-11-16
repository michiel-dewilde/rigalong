#include "Rack.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <pthread.h>
#include <set>

#include <rigalong/Gain.h>

#define LOG_TAG "rigalong-audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

Rack::~Rack() {
    // The sfizz loader holds a raw pointer to a node the graph owns, so it must
    // not outlive the graph. The other loaders are detached and cannot be
    // joined; in this app the rack belongs to an engine that outlives
    // everything, so that is not a lifetime problem today -- but it IS the
    // reason this comment exists, because it would become one if the engine
    // were ever made destroyable for real.
    if (mLoaderThread.joinable()) mLoaderThread.join();
}

// ------------------------------------------------------------------- graph

void Rack::build() {
    if (mSfizz != nullptr) return;
    auto midiIn = std::make_unique<rigalong::MidiInNode>();
    auto sfizz = std::make_unique<rigalong::SfizzNode>();
    auto setBfreeNode = std::make_unique<rigalong::SetBfreeNode>();
    auto surge = std::make_unique<rigalong::SurgeNode>();
    auto dexed = std::make_unique<rigalong::DexedNode>();
    auto plaits = std::make_unique<rigalong::PlaitsNode>();
    auto fluid = std::make_unique<rigalong::FluidNode>();
    auto reverb = std::make_unique<rigalong::ReverbNode>();
    auto out = std::make_unique<rigalong::AudioOutNode>();

    mSfizz = sfizz.get();
    mSetBfree = setBfreeNode.get();
    mSurge = surge.get();
    mDexed = dexed.get();
    mPlaits = plaits.get();
    mFluid = fluid.get();

    const rigalong::NodeId idMidi = mGraph.addNode(std::move(midiIn));
    const rigalong::NodeId idSfizz = mGraph.addNode(std::move(sfizz));
    const rigalong::NodeId idSetBfree = mGraph.addNode(std::move(setBfreeNode));
    const rigalong::NodeId idSurge = mGraph.addNode(std::move(surge));
    const rigalong::NodeId idDexed = mGraph.addNode(std::move(dexed));
    const rigalong::NodeId idPlaits = mGraph.addNode(std::move(plaits));
    const rigalong::NodeId idFluid = mGraph.addNode(std::move(fluid));
    const rigalong::NodeId idReverb = mGraph.addNode(std::move(reverb));
    const rigalong::NodeId idOut = mGraph.addNode(std::move(out));

    // The default patch. Built in code; identical in every respect to what
    // the editor will later produce by hand.
    //
    //   MIDI In --+--> sfizz    --+
    //             |               |
    //             +--> setBfree --+
    //             |               |
    //             +--> Surge XT --+--> Reverb --> Audio Out
    //             |               |
    //             +--> Dexed    --+
    //             |               |
    //             +--> Plaits   --+
    //             |               |
    //             +--> FluidSynth-+
    //
    // NO LIMITER IN THE DEFAULT PATCH (Michiel, 2026-09-22). LimiterNode
    // still exists and the graph editor may place one, but it does not
    // belong in the minimal chain: it reduced gain invisibly, with no
    // control anywhere in the UI, which is a change to how the instrument
    // SOUNDS that nobody asked for and nobody can see. What follows the
    // master volume now is the conversion to the device's format and
    // nothing else -- saturating on overs, dithered only for a 16-bit
    // target. An over is then audible as an over, which is honest and is
    // what a fader is for.
    //
    // Parts are mixed, not switched: every instrument is always in the
    // graph, so moving between them is a level change, with no gap, no
    // voice stealing and no reload. The ones at level zero are parked
    // (updateGates), so only what can be heard costs CPU.
    //
    // THE RACK: MIDI In -> zone -> engine and engine -> reverb, each
    // engine-side wire switchable (Wire.h), so a live set can split, layer
    // and route without a recompile and without stranding a held note.
    // THE ORDER NODES ARE ADDED IN IS A CONTRACT: the app addresses nodes
    // by ordinal (MIDI In 0, the engines 1-6, the reverb 7, Audio Out 8,
    // then the zones, the insert effects and the master effect), so
    // anything new goes after what is here.
    const rigalong::NodeId engines[kEngines] = {idSfizz, idSetBfree, idSurge,
                                                   idDexed, idPlaits, idFluid};
    mIdMidiIn = idMidi;
    mIdReverb = idReverb;
    for (int i = 0; i < kEngines; ++i) {
        auto z = std::make_unique<rigalong::MidiZoneNode>();
        mZone[i] = z.get();
        const rigalong::NodeId idZone = mGraph.addNode(std::move(z));
        mGraph.connect(idMidi, 0, idZone, 0);
        mMidiWire[i] = mGraph.connectSwitchable(idZone, 0, engines[i], 0);
    }
    // An insert effect after every engine (M6), "Off" until a song asks for
    // one; added after the zones, for the same reason as they were. The
    // switchable wire follows it, so an engine's delay tail goes where the
    // engine's sound goes.
    for (int i = 0; i < kEngines; ++i) {
        auto fx = std::make_unique<rigalong::FxNode>();
        mFx[i] = fx.get();
        const rigalong::NodeId idFx = mGraph.addNode(std::move(fx));
        mGraph.connect(engines[i], 0, idFx, 0);
        mAudioWire[i] = mGraph.connectSwitchable(idFx, 0, idReverb, 0);
    }
    // The six are instances 0-5.
    rigalong::Node* nodes[kEngines] = {mSfizz, mSetBfree, mSurge, mDexed, mPlaits, mFluid};
    const int levels[kEngines] = {rigalong::SfizzNode::kLevel, rigalong::SetBfreeNode::kLevel,
                                   rigalong::SurgeNode::kLevel, rigalong::DexedNode::kLevel,
                                   rigalong::PlaitsNode::kLevel, rigalong::FluidNode::kLevel};
    for (int i = 0; i < kEngines; ++i) {
        mInstanceType[i] = i;
        mInstanceNode[i] = nodes[i];
        mInstanceLevelParam[i] = levels[i];
    }
    mInstanceCount.store(kEngines, std::memory_order_release);
    // And one on the whole mix, after the room: a song's hall or delay over
    // everything. The seventh Fx node, so the UI finds it by order.
    auto masterFx = std::make_unique<rigalong::FxNode>();
    const rigalong::NodeId idMasterFx = mGraph.addNode(std::move(masterFx));
    mGraph.connect(idReverb, 0, idMasterFx, 0);
    mGraph.connect(idMasterFx, 0, idOut, 0);

    mGraph.setMidiEntry(idMidi);
    mGraph.setAudioExit(idOut);

    // Everything but sfizz starts silent: the app opens on sfizz,
    // and each of the others is brought in with its own fader.
    mSetBfree->setParam(rigalong::SetBfreeNode::kLevel, 0.0f);
    mSurge->setParam(rigalong::SurgeNode::kLevel, 0.0f);
    mDexed->setParam(rigalong::DexedNode::kLevel, 0.0f);
    mPlaits->setParam(rigalong::PlaitsNode::kLevel, 0.0f);
    mFluid->setParam(rigalong::FluidNode::kLevel, 0.0f);
}

int Rack::engineNamed(const std::string& name) const {
    const int count = std::min(instanceCount(), static_cast<int>(kEngines));
    for (int i = 0; i < count; ++i) {
        if (name == mInstanceNode[i]->typeName()) return mInstanceType[i];
    }
    return -1;
}

const char* Rack::engineName(int type) const {
    const int count = std::min(instanceCount(), static_cast<int>(kEngines));
    for (int i = 0; i < count; ++i) {
        if (mInstanceType[i] == type) return mInstanceNode[i]->typeName();
    }
    return "";
}

// ----------------------------------------------------------------- loaders

void Rack::loadInstrumentAsync(const std::string& id, const std::string& path) {
    {
        std::lock_guard<std::mutex> lock(mLoadLock);
        mPendingLoad = path;
        mPendingId = id;
    }
    if (mLoaderThread.joinable()) mLoaderThread.join();

    mLoading.store(true, std::memory_order_relaxed);
    mLoaderThread = std::thread([this] {
        std::string path, id;
        {
            std::lock_guard<std::mutex> lock(mLoadLock);
            path = mPendingLoad;
            id = mPendingId;
        }
        if (mSfizz != nullptr && !path.empty()) {
            // Ordinary priority, deliberately not pinned to the big cores: this
            // must never compete with the audio thread. Rendering continues on
            // whatever instrument was loaded before.
            mSfizz->loadFile(id, path);
        }
        mLoading.store(false, std::memory_order_relaxed);
    });
}

// Surge's loader thread is created by hand rather than with std::thread, for
// ONE reason: the stack size.
//
// bionic gives a new thread 1 MB, where the main thread gets 8 MB. Constructing
// SurgeStorage walks the patch library with a recursive directory iterator and
// builds Tunings::Tuning values -- each of which carries two 512-entry double
// tables -- as temporaries in deep frames. That overflows 1 MB, and it does not
// overflow politely: the crash surfaced as a SIGSEGV reading 0x4088ffffffffffe8
// inside a std::string destructor, which is a stack-shaped address, four frames
// into the constructor. std::thread offers no way to set the stack, so pthread
// is used directly.
//
// The identical code had run perfectly as a standalone probe, which is exactly
// what made this worth writing down: the probe ran on the MAIN thread, and that
// one difference was the whole bug.
namespace {
constexpr size_t kLoaderStackBytes = 8u * 1024u * 1024u;

// Runs `fn` on a detached thread with a stack big enough for Surge. Returns
// false if the thread could not be created, in which case the caller must clear
// its own busy flag.
template <typename Fn>
bool runOnBigStack(Fn&& fn) {
    auto* task = new std::function<void()>(std::forward<Fn>(fn));

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, kLoaderStackBytes);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

    pthread_t tid;
    const int rc = pthread_create(&tid, &attr, [](void* p) -> void* {
        auto* f = static_cast<std::function<void()>*>(p);
        (*f)();
        delete f;
        return nullptr;
    }, task);
    pthread_attr_destroy(&attr);

    if (rc != 0) {
        LOGE("could not start loader thread: %d", rc);
        delete task;
        return false;
    }
    return true;
}
}  // namespace

void Rack::initSurgeAsync(const std::string& dataPath, const std::string& homePath) {
    mSurgeDataPath = dataPath;
    mSurgeHomePath = homePath;
    if (mSurge == nullptr || mSurge->ready()) return;
    if (mSurgeBusy.exchange(true, std::memory_order_acq_rel)) return;   // already going

    // Ordinary priority, deliberately not pinned to the big cores: scanning 641
    // patches must never compete with the audio thread. The rig plays normally
    // throughout; Surge simply renders silence until it is up.
    if (!runOnBigStack([this, dataPath, homePath] {
            mSurge->init(dataPath, homePath);
            mSurgeBusy.store(false, std::memory_order_release);
        })) {
        mSurgeBusy.store(false, std::memory_order_release);
    }
}

void Rack::loadSurgePatchNamedAsync(const std::string& name) {
    if (mSurge == nullptr) return;

    // Record the intent FIRST, unconditionally. A patch restored during
    // onResume asks for its Surge patch by name before the loader thread has
    // finished scanning the library, and the old code simply returned here --
    // so a restored patch silently came back on whatever Surge had, with no
    // error anywhere. The node now remembers and whoever finishes loading
    // honours it.
    mSurge->requestPatchNamed(name);
    if (!mSurge->ready()) return;                                 // init() will apply it
    if (mSurgeBusy.exchange(true, std::memory_order_acq_rel)) return;  // so will the load in flight
    if (!runOnBigStack([this] {
            mSurge->applyRequestedPatch();
            mSurgeBusy.store(false, std::memory_order_release);
        })) {
        mSurgeBusy.store(false, std::memory_order_release);
    }
}

// FluidSynth. Building the fluid_synth is cheap and listing a directory is cheaper,
// but LOADING a font is neither: a 30 MB GM SoundFont is megabytes of samples,
// and an SF3 decodes every one of them from Ogg Vorbis on the way in. All of it
// therefore runs here, off both the audio and the UI thread.
//
// Init and the scan are one task rather than two, because a scan that finishes
// before the fluid_synth exists has nothing to load into.
void Rack::initFluidAsync(const std::vector<std::string>& dirs, double rate) {
    mFluidDirs = dirs;
    if (mFluid == nullptr) return;
    if (mFluidBusy.exchange(true, std::memory_order_acq_rel)) return;

    if (!runOnBigStack([this, dirs, rate] {
            if (mFluid->init(rate)) {
                mFluid->scanSoundFonts(dirs);
                // Honour a restore that arrived before the scan did. Recording
                // the intent unconditionally and applying it here is the fix for
                // the bug FINDINGS 5b describes -- which this project has now
                // had in two engines, one of them silently.
                mFluid->applyRequestedSoundFont();
                // And the preset in it that was asked for with it (REVIEW S2).
                const int program = mFluidProgram[kFluid].load(std::memory_order_acquire) - 1;
                if (program >= 0) mFluid->selectPreset(program);
            }
            mFluidBusy.store(false, std::memory_order_release);
        })) {
        mFluidBusy.store(false, std::memory_order_release);
    }
}

void Rack::loadSoundFontNamedAsync(const std::string& name) {
    if (mFluid == nullptr) return;

    // Unconditionally, and BEFORE the ready() check: a patch restored during
    // onResume names a font the loader has not listed yet, and dropping the
    // request there is exactly how Surge silently ignored its saved patch.
    mFluid->requestSoundFontNamed(name);
    if (!mFluid->ready()) return;              // initFluidAsync will apply it

    if (mFluidBusy.exchange(true, std::memory_order_acq_rel)) return;
    if (!runOnBigStack([this] {
            mFluid->applyRequestedSoundFont();
            mFluidBusy.store(false, std::memory_order_release);
        })) {
        mFluidBusy.store(false, std::memory_order_release);
    }
}

void Rack::loadDexedBanksAsync(const std::vector<std::string>& dirs) {
    mDexedDirs = dirs;
    if (mDexed == nullptr) return;
    if (mDexedBusy.exchange(true, std::memory_order_acq_rel)) return;
    if (!runOnBigStack([this, dirs] {
            mDexed->loadBanks(dirs);
            mDexedBusy.store(false, std::memory_order_release);
        })) {
        mDexedBusy.store(false, std::memory_order_release);
    }
}

void Rack::rescanContentAsync() {
    runOnBigStack([this] {
        std::set<rigalong::Node*> done;
        auto each = [&](int type, rigalong::Node* n) {
            if (n == nullptr || !done.insert(n).second) return;
            switch (type) {
                case kFluid: static_cast<rigalong::FluidNode*>(n)->scanSoundFonts(mFluidDirs); break;
                case kDexed: static_cast<rigalong::DexedNode*>(n)->loadBanks(mDexedDirs); break;
                case kSurge: static_cast<rigalong::SurgeNode*>(n)->refreshPatches(); break;
                default: break;
            }
        };
        each(kFluid, mFluid);
        each(kDexed, mDexed);
        each(kSurge, mSurge);
        for (int i = 0; i < instanceCount(); ++i) each(mInstanceType[i], mInstanceNode[i]);
        LOGI("content rescanned in %zu engine(s)", done.size());
    });
}

std::string Rack::surgeUserPatchesPath() const {
    return mSurge != nullptr ? mSurge->userPatchesPath() : std::string();
}

// --------------------------------------------------------------- instances

int Rack::addInstance(int type, double rate, const std::function<bool(std::string*)>& compile) {
    const int i = mInstanceCount.load(std::memory_order_acquire);
    if (i >= kMaxInstances || mIdMidiIn < 0 || type < 0 || type >= kEngines) return -1;

    std::unique_ptr<rigalong::Node> node;
    int level = 0;
    switch (type) {
        case kSfizz:   node = std::make_unique<rigalong::SfizzNode>();   level = rigalong::SfizzNode::kLevel; break;
        case kSetBfree: node = std::make_unique<rigalong::SetBfreeNode>(); level = rigalong::SetBfreeNode::kLevel; break;
        case kSurge:   node = std::make_unique<rigalong::SurgeNode>();   level = rigalong::SurgeNode::kLevel; break;
        case kDexed:   node = std::make_unique<rigalong::DexedNode>();   level = rigalong::DexedNode::kLevel; break;
        case kPlaits:  node = std::make_unique<rigalong::PlaitsNode>();  level = rigalong::PlaitsNode::kLevel; break;
        default:           node = std::make_unique<rigalong::FluidNode>();   level = rigalong::FluidNode::kLevel; break;
    }
    node->setParam(level, 0.0f);   // silent until the song gives it a level
    rigalong::Node* raw = node.get();
    const rigalong::NodeId idNode = mGraph.addNode(std::move(node));
    wireInstance(i, type, raw, idNode, level);

    std::string error;
    if (!compile(&error)) {
        LOGE("instance %d: compile failed: %s", i, error.c_str());
        return -1;
    }
    // Its library, as the first one of its kind got it -- in the background.
    mInstanceBusy[i].store(true, std::memory_order_release);
    auto done = [this, i] { mInstanceBusy[i].store(false, std::memory_order_release); };
    bool started = true;
    switch (type) {
        case kSurge: {
            auto* s = static_cast<rigalong::SurgeNode*>(raw);
            started = runOnBigStack([s, this, done] { s->init(mSurgeDataPath, mSurgeHomePath); done(); });
            break;
        }
        case kFluid: {
            auto* f = static_cast<rigalong::FluidNode*>(raw);
            started = runOnBigStack([f, this, rate, done] {
                if (f->init(rate)) { f->scanSoundFonts(mFluidDirs); f->applyRequestedSoundFont(); }
                done();
            });
            break;
        }
        case kDexed: {
            auto* d = static_cast<rigalong::DexedNode*>(raw);
            started = runOnBigStack([d, this, done] { d->loadBanks(mDexedDirs); done(); });
            break;
        }
        default:
            done();
    }
    if (!started) done();
    LOGI("instance %d added: %s", i, raw->typeName());
    return i;
}

void Rack::wireInstance(int i, int type, rigalong::Node* node, rigalong::NodeId idNode, int levelParam) {
    auto z = std::make_unique<rigalong::MidiZoneNode>();
    mZone[i] = z.get();
    const rigalong::NodeId idZone = mGraph.addNode(std::move(z));
    mGraph.connect(mIdMidiIn, 0, idZone, 0);
    mMidiWire[i] = mGraph.connectSwitchable(idZone, 0, idNode, 0);
    auto fx = std::make_unique<rigalong::FxNode>();
    mFx[i] = fx.get();
    const rigalong::NodeId idFx = mGraph.addNode(std::move(fx));
    mGraph.connect(idNode, 0, idFx, 0);
    mAudioWire[i] = mGraph.connectSwitchable(idFx, 0, mIdReverb, 0);
    mInstanceType[i] = type;
    mInstanceNode[i] = node;
    mInstanceLevelParam[i] = levelParam;
    // Last: the render thread reads entries only below the published count.
    mInstanceCount.store(i + 1, std::memory_order_release);
}

bool Rack::instanceBusy(int i) const {
    if (i < 0 || i >= instanceCount()) return false;
    if (i < kEngines) {
        switch (i) {
            case kSurge: return mSurgeBusy.load(std::memory_order_relaxed) || (mSurge && !mSurge->ready());
            case kFluid: return mFluidBusy.load(std::memory_order_relaxed);
            case kDexed: return mDexedBusy.load(std::memory_order_relaxed);
            case kSfizz: return mLoading.load(std::memory_order_relaxed);
            default: return false;
        }
    }
    return mInstanceBusy[i].load(std::memory_order_acquire);
}

std::string Rack::instancePreset(int i) const {
    rigalong::Node* n = instanceNode(i);
    if (n == nullptr) return {};
    switch (mInstanceType[i]) {
        case kSfizz: return static_cast<rigalong::SfizzNode*>(n)->instrumentId();
        case kSurge: return static_cast<rigalong::SurgeNode*>(n)->currentPatchName();
        case kFluid: return static_cast<rigalong::FluidNode*>(n)->currentSoundFontName();
        default: return {};
    }
}

std::string Rack::instanceSound(int i) const {
    rigalong::Node* n = instanceNode(i);
    if (n == nullptr) return {};
    switch (mInstanceType[i]) {
        case kDexed: return static_cast<rigalong::DexedNode*>(n)->currentVoiceName();
        case kPlaits: {
            const int model = static_cast<int>(std::lround(n->getParam(rigalong::PlaitsNode::kEngine)));
            return model >= 0 && model < rigalong::PlaitsNode::kNumEngines ? rigalong::PlaitsNode::engineName(model) : "";
        }
        default: return instancePreset(i);
    }
}

int Rack::instanceProgram(int i) const {
    rigalong::Node* n = instanceNode(i);
    if (n == nullptr || mInstanceType[i] != kFluid) return -1;
    return static_cast<rigalong::FluidNode*>(n)->currentPreset();
}

void Rack::loadInstancePreset(int i, const std::string& name, int program, const std::string& path) {
    rigalong::Node* n = instanceNode(i);
    if (n == nullptr || name.empty()) return;
    // The only way an instance gets a preset (REVIEW S2). The first of each
    // engine keeps its own loader and flag, shared with the setup's engine
    // panels; the rest load on their own background threads. Every loader
    // remembers a request made before its engine is up and applies it then.
    switch (mInstanceType[i]) {
        case kSfizz: {
            auto* s = static_cast<rigalong::SfizzNode*>(n);
            if (i == kSfizz) { loadInstrumentAsync(name, path); return; }
            if (s->instrumentId() == name || path.empty()) return;
            mInstanceBusy[i].store(true, std::memory_order_release);
            if (!runOnBigStack([this, s, i, name, path] {
                    s->loadFile(name, path);
                    mInstanceBusy[i].store(false, std::memory_order_release);
                })) mInstanceBusy[i].store(false, std::memory_order_release);
            return;
        }
        case kSetBfree: {
            auto* h = static_cast<rigalong::SetBfreeNode*>(n);
            for (int k = 0; k < rigalong::SetBfreeNode::presetCount(); ++k) {
                if (name == rigalong::SetBfreeNode::presetName(k)) { h->applyPreset(k, 0); return; }
            }
            return;
        }
        case kSurge: {
            if (i == kSurge) { loadSurgePatchNamedAsync(name); return; }
            auto* s = static_cast<rigalong::SurgeNode*>(n);
            s->requestPatchNamed(name);   // honoured by init() if it is not up yet
            if (!s->ready() || mInstanceBusy[i].exchange(true, std::memory_order_acq_rel)) return;
            if (!runOnBigStack([this, s, i] {
                    s->applyRequestedPatch();
                    mInstanceBusy[i].store(false, std::memory_order_release);
                })) mInstanceBusy[i].store(false, std::memory_order_release);
            return;
        }
        case kDexed:
            static_cast<rigalong::DexedNode*>(n)->selectVoiceNamed(name);   // remembered until the banks are in
            return;
        case kPlaits: {
            for (int k = 0; k < rigalong::PlaitsNode::kNumEngines; ++k) {
                if (name == rigalong::PlaitsNode::engineName(k)) {
                    n->setParam(rigalong::PlaitsNode::kEngine, static_cast<float>(k));
                    return;
                }
            }
            return;
        }
        default: {
            // FluidSynth, the first instance like any other. THE LATEST FONT AND
            // PRESET ASKED FOR WIN: a request that finds a load in flight is not
            // dropped (it used to be, and two songs in a row, or stepping
            // through presets, left the wrong one playing); the loader takes
            // it up when it is done.
            auto* f = static_cast<rigalong::FluidNode*>(n);
            std::atomic<bool>* busy = (i == kFluid) ? &mFluidBusy : &mInstanceBusy[i];
            f->requestSoundFontNamed(name);
            mFluidProgram[i].store(program + 1, std::memory_order_release);
            if (!f->ready() || busy->exchange(true, std::memory_order_acq_rel)) return;
            if (f->currentSoundFontName() == name) {
                // The font is in: a preset is a pointer swapped, done here.
                f->requestSoundFontNamed("");
                if (program >= 0) f->selectPreset(program);
                busy->store(false, std::memory_order_release);
                return;
            }
            if (!runOnBigStack([this, f, i, busy] {
                    std::string failed;      // a font that would not load is not tried for ever
                    int applied = -1;
                    for (;;) {
                        for (;;) {
                            const std::string want = f->requestedSoundFont();
                            if (!want.empty() && want != f->currentSoundFontName() && want != failed) {
                                f->applyRequestedSoundFont();
                                if (f->currentSoundFontName() != want) failed = want;
                            } else if (!want.empty()) {
                                f->requestSoundFontNamed("");
                            }
                            applied = mFluidProgram[i].load(std::memory_order_acquire) - 1;
                            if (applied >= 0) f->selectPreset(applied);
                            const std::string now = f->requestedSoundFont();
                            if ((now.empty() || now == failed) &&
                                mFluidProgram[i].load(std::memory_order_acquire) - 1 == applied) break;
                        }
                        busy->store(false, std::memory_order_release);
                        // Asked again between the last look and the flag going down?
                        const std::string now = f->requestedSoundFont();
                        const bool more = (!now.empty() && now != failed) ||
                            mFluidProgram[i].load(std::memory_order_acquire) - 1 != applied;
                        if (!more || busy->exchange(true, std::memory_order_acq_rel)) break;
                    }
                })) busy->store(false, std::memory_order_release);
            return;
        }
    }
}

// ------------------------------------------------------------------- gates

// Only the instruments that can be heard actually run.
//
// The pedalboard model had every engine rendering every block whatever its
// level, so choosing one part cost the CPU of all six. An engine at level zero
// is provably silent, so this gates it OUT of the schedule entirely (see
// Node::setEnabled) -- which is what makes "only the selected instrument runs"
// true rather than merely inaudible.
//
// ON EVERY TRANSITION the node's voices are released. A node that is not
// processed cannot finish the notes it is holding: without this it would keep
// a voice state nobody can hear and then resurrect it, mid-chord, the moment
// the part was selected again. Releasing costs a little work, but only when a
// part is switched, which is a human action and not a per-block one.
void Rack::updateGates(int frames, double rate) {
    struct Gate { rigalong::Node* node; float level; };
    // Every instance, the added ones too: read up to the published count only.
    const int count = mInstanceCount.load(std::memory_order_acquire);
    Gate gates[kMaxInstances];
    for (int k = 0; k < count; ++k) {
        rigalong::Node* n = mInstanceNode[k];
        gates[k] = {n, n ? n->getParam(mInstanceLevelParam[k]) : 0.0f};
    }
    const int quietFrames = static_cast<int>(rate * kQuietSeconds);
    // Twice the gain ramp, so the level is provably at zero before any voice is
    // cut. Cheap insurance against an off-by-one-block.
    const int fadeFrames =
        static_cast<int>(rate * rigalong::SmoothedGain::kRampSeconds * 2.0) + 1;
    const int ceilingFrames = static_cast<int>(rate * kQuietCeilingSeconds);
    for (int i = 0; i < count; ++i) {
        const Gate& g = gates[i];
        if (g.node == nullptr) continue;

        // Loudness, held. The on-screen meter decays in 60 ms, which makes it
        // unusable for "how loud is THIS engine for that note" -- the question
        // behind Michiel's report that sfizz is much quieter than the rest.
        // Taken before the node's own level, so it measures the ENGINE and not
        // where its fader happens to be. Cleared by resetPeaks().
        // Only while the node is actually RUNNING. A parked node is not
        // processed, so its last peak is frozen at whatever it was rendering
        // before it stopped -- sampling that reports a silent engine as loud,
        // which is exactly how this probe first lied.
        if (g.node->enabled()) {
            const float p = g.node->lastRenderPeak();
            if (p > mNodePeakHold[i]) mNodePeakHold[i] = p;
        }
        // A hair above zero, not zero: a fader dragged to the bottom lands on
        // exactly 0, but a restored patch can carry a value like 1e-7.
        const bool want = g.level > 0.005f;

        if (want) {
            // Selected again DURING a flush. The node never stopped, so the
            // "running" line below does not fire -- and without this one
            // nothing in the log says why an engine went on rendering for a
            // minute. Michiel saw exactly that: flicking back and forth
            // between two parts restarted the silence clock each time, and the
            // engine stayed live the whole while with no line to show it.
            if (mGateFlushing[i]) {
                LOGI("%s selected again while flushing -- still running",
                     g.node->typeName());
            }
            mGateFlushing[i] = false;
            mGateFade[i] = 0;
            mGateQuiet[i] = 0;
            mGateFlush[i] = 0;
            g.node->setMidiMuted(false);        // it can hear again
            if (!g.node->enabled()) {
                // Start clean: anything still held from last time dies before
                // the first block, rather than resuming mid-decay.
                g.node->allNotesOff();
                g.node->setEnabled(true);
                LOGI("%s running", g.node->typeName());
            }
            continue;
        }
        if (!g.node->enabled()) continue;

        // --- stopping, but not instantly ------------------------------------
        //
        // Killing the voices is not enough for an engine that carries its OWN
        // effects. setBfree's rotary speaker and reverb live inside beatrix,
        // and a frozen effect resumes where it left off: Michiel heard the tail
        // of a setBfree note return when the part was selected again "an eon
        // later".
        //
        // So the node keeps RUNNING after its voices are killed, until it has
        // been measurably silent -- its own pre-level peak under the floor --
        // for a full second without interruption. Nothing of this is audible;
        // its level is already zero. It costs a second or two of CPU after a
        // part switch, which is a human action, not a per-block one.
        //
        // Waiting for the sound rather than for a stopwatch is the difference
        // between "probably long enough" and "demonstrably finished": a five
        // second reverb tail gets five seconds, and an engine that drops dead
        // on the first block gets one.
        if (!mGateFlushing[i]) {
            // NOT allNotesOff() yet. The node's level is ramping down over
            // 15 ms (see SmoothedGain), and killing its voices during that ramp
            // would put back exactly the discontinuity the ramp exists to
            // remove -- sfizz's allSoundOff() is a hard stop, not a release.
            // So the fade is allowed to finish first.
            mGateFlushing[i] = true;
            mGateFade[i] = fadeFrames;
            mGateQuiet[i] = 0;
            mGateFlush[i] = 0;
            // Deaf from this instant. It is inaudible from here on, so any
            // note it accepted would be a note started by nobody -- and would
            // be waiting, already sounding, if the part were selected again.
            g.node->setMidiMuted(true);
            LOGI("%s silenced, fading out", g.node->typeName());
            continue;
        }
        if (mGateFade[i] > 0) {
            mGateFade[i] -= frames;
            if (mGateFade[i] <= 0) {
                mGateFade[i] = 0;
                g.node->allNotesOff();
                LOGI("%s faded, flushing its tails", g.node->typeName());
            }
            continue;
        }

        // The peak is from the block just rendered: the gate runs before
        // schedule->run(), so it is one block behind, which costs 3 ms of a
        // one-second wait.
        const float peak = g.node->lastRenderPeak();
        mGateFlush[i] += frames;
        // A node that does not report a peak falls back to the stopwatch:
        // every instrument here reports one, but the graph does not require it.
        if (peak < 0.0f || peak <= kSilenceFloor) {
            mGateQuiet[i] += frames;
        } else {
            mGateQuiet[i] = 0;
        }

        const bool quiet = mGateQuiet[i] >= quietFrames;
        const bool droning = mGateFlush[i] >= ceilingFrames;
        if (quiet || droning) {
            g.node->setEnabled(false);
            mGateFlushing[i] = false;
            if (droning && !quiet) {
                LOGI("%s stopped (level 0) after %.0f s WITHOUT going quiet -- "
                     "it is still producing %.6f into a muted output",
                     g.node->typeName(), kQuietCeilingSeconds,
                     static_cast<double>(peak));
            } else {
                LOGI("%s stopped (level 0), quiet after %.2f s",
                     g.node->typeName(),
                     static_cast<double>(mGateFlush[i]) / rate);
            }
            mGateQuiet[i] = 0;
            mGateFlush[i] = 0;
        }
    }
}

// ----------------------------------------------------------------- profile

void Rack::resetProfiles() {
    if (mSurge != nullptr) mSurge->resetProfile();
    if (mDexed != nullptr) mDexed->resetProfile();
    if (mPlaits != nullptr) mPlaits->resetProfile();
    if (mFluid != nullptr) mFluid->resetProfile();
    if (mSetBfree != nullptr) mSetBfree->resetProfile();
    if (mSfizz != nullptr) mSfizz->resetProfile();
    for (float& p : mNodePeakHold) p = 0.0f;
}

void Rack::logProfile() {
    if (mSfizz != nullptr) {
        LOGI("PROFILE   sfizz split: noteOn max=%.3f ms, render max=%.3f ms, "
             "started=%d queued=%d dropped=%d",
             mSfizz->maxNoteMs(), mSfizz->maxRenderMs(), mSfizz->lastNoteOns(),
             mSfizz->pendingNoteOns(), mSfizz->pendingDropped());
        mSfizz->resetProfile();
    }
    // Per-engine loudness, so gain staging is a measurement rather than an
    // argument. dBFS of the engine's own output BEFORE its level fader, held
    // since the last reset: play the same note on each engine in turn and the
    // numbers say what "much quieter" is worth in decibels.
    {
        for (int i = 0; i < instanceCount(); ++i) {
            if (mNodePeakHold[i] <= 0.0f || mInstanceNode[i] == nullptr) continue;
            LOGI("PROFILE %-12s #%d peak %.4f (%.1f dBFS, pre-level)", mInstanceNode[i]->typeName(), i,
                 static_cast<double>(mNodePeakHold[i]),
                 20.0 * std::log10(static_cast<double>(mNodePeakHold[i])));
        }
    }
}
