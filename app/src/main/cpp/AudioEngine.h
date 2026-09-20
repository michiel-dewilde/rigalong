#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include <string>
#include <thread>

#include <bagosynths/Dither.h>
#include "MidiInput.h"
#include <bagosynths/Graph.h>
#include "graph/HammondNode.h"
#include "graph/SurgeNode.h"
#include "graph/DexedNode.h"
#include "graph/PlaitsNode.h"
#include "graph/FluidNode.h"
#include "graph/Nodes.h"

// Stream settings measured as working on this rig (Galaxy Tab S9 Ultra -> CP-88
// over USB, 2026-09-19): AAudio, MMAP + EXCLUSIVE granted, 44100 Hz, I16 at the
// HAL, 89-frame burst, 5.5-9.9 ms reported output latency at a one-burst buffer.
//
// The format is I16 on purpose. The device endpoint is 16-bit whatever we ask
// for, so we ask for I16 and do the float -> int conversion ourselves, with
// dither, instead of letting Oboe round.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    ~AudioEngine() override;

    // deviceId: from AudioDeviceInfo.getId(), or kUnspecifiedDevice to let
    // AAudio choose. Always pass the CP-88 explicitly -- auto-select can land on
    // the built-in speaker and produce misleadingly good numbers.
    static constexpr int32_t kUnspecifiedDevice = oboe::kUnspecified;

    bool start(int32_t deviceId, int bufferBursts);
    void stop();
    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }

    MidiInput& midi() { return mMidi; }

    // --- graph -------------------------------------------------------------
    // The signal path is a node graph (see GRAPH.md). M3 builds a fixed default
    // patch in code; the editor comes later and changes nothing down here.
    bagosynths::Graph& graph() { return mGraph; }

    // Recompiles and publishes a new schedule. Off the audio thread only.
    bool rebuildGraph(std::string* error = nullptr);

    // The instruments in the default patch. All are always present and all
    // always receive MIDI; which one is heard is a question of level, so that
    // layering an organ under a piano costs nothing but a fader move.
    bagosynths::HammondNode* hammond() { return mHammond; }
    bagosynths::SfizzNode* sfizz() { return mSfizz; }
    bagosynths::SurgeNode* surge() { return mSurge; }
    bagosynths::DexedNode* dexed() { return mDexed; }
    bagosynths::PlaitsNode* plaits() { return mPlaits; }
    bagosynths::FluidNode* fluid() { return mFluid; }

    // Brings Surge up on the loader thread: constructing it scans the patch
    // library, which is 41 ms of file work and has no business on the UI thread
    // or on whatever thread compiled the graph. Until it finishes, the Surge
    // node renders silence and the rest of the rig is unaffected.
    void initSurgeAsync(const std::string& dataPath, const std::string& homePath);

    // Queues a Surge patch load. Same reasoning as loadInstrumentAsync: the load
    // opens and parses a file, so it happens off both the audio and UI threads.
    // `index` addresses the scanned list; `name` is the stable "Category/Name"
    // that a saved patch stores.
    void loadSurgePatchAsync(int index);
    void loadSurgePatchNamedAsync(const std::string& name);
    bool isSurgeBusy() const { return mSurgeBusy.load(std::memory_order_relaxed); }

    // Reads every DX7 cartridge in `dirs`. File IO, so it runs on the loader.
    // Selecting a voice afterwards is cheap enough to do inline: it unpacks 128
    // bytes and hands them over through a single-slot atomic.
    void loadDexedBanksAsync(const std::vector<std::string>& dirs);
    bool isDexedBusy() const { return mDexedBusy.load(std::memory_order_relaxed); }

    // Builds FluidSynth and lists the SoundFonts in `dirs`. Neither is slow,
    // but loading a font afterwards is -- megabytes of samples, and for SF3 a
    // Vorbis decode of every one of them -- so all of it runs on the loader.
    void initFluidAsync(const std::vector<std::string>& dirs);
    void loadSoundFontAsync(int index);
    void loadSoundFontNamedAsync(const std::string& name);
    bool isFluidBusy() const { return mFluidBusy.load(std::memory_order_relaxed); }

    // Queues an SFZ load on the loader thread. Returns immediately: parsing an
    // instrument and opening its samples must never happen on the audio thread,
    // and must not block the UI either.
    void loadInstrumentAsync(const std::string& id, const std::string& path);

    std::string loadedInstrument() const;
    std::string instrumentId() const;
    bool isLoading() const { return mLoading.load(std::memory_order_relaxed); }

    // Injects a note from the on-screen keys, as if it had arrived over MIDI.
    void injectNote(int note, int velocity);
    // Fires a dense cluster in one block: the reproducible stress case.
    void stressChord(int count);
    void logProfile();
    void allNotesOff();

    // --- oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;

    // --- oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

    // --- round-trip latency probe -------------------------------------------
    //
    // The CP-88 is a 2-in/2-out interface, so if it returns its output mix on
    // its USB input we can measure our own latency exactly: write a click, count
    // how many input frames pass before it comes back. Same technique OboeTester
    // uses with a physical loopback cable, except the loop is inside the piano.
    //
    // Whether the CP-88 actually returns the USB-in signal is the open question.
    // If it does not, inputPeak simply never rises and the probe reports FAILED —
    // which is itself the answer.
    enum class ProbeState : int32_t {
        Unavailable = 0,   // no input stream (not opened, or permission denied)
        Idle = 1,
        Armed = 2,         // click will be emitted on the next callback
        Waiting = 3,       // click emitted, listening for its return
        Done = 4,
        Failed = 5,        // nothing came back within the timeout
        ArmedForNote = 6,  // waiting for a MIDI note-on to start the clock
    };

    bool openInput(int32_t deviceId);

    // Click probe: emit a click, time its return. Measures the output stage plus
    // the room and the microphone.
    void startLatencyProbe();

    // Key-to-tone probe: the clock starts when a MIDI note-on arrives from the
    // piano and stops when the resulting tone reaches the microphone. Subtracting
    // the click probe cancels the room, the microphone and the output stage,
    // leaving what the instrument itself costs.
    void startNoteProbe();

    struct Stats {
        float latencyMs = 0.0f;
        float cpuLoadPercent = 0.0f;
        float callbackMaxMs = 0.0f;
        int32_t xRuns = 0;
        int32_t burstFrames = 0;
        int32_t bufferFrames = 0;
        int32_t sampleRate = 0;
        int32_t activeVoices = 0;
        int32_t loading = 0;
        int32_t sharingMode = -1;      // 0 = Exclusive, 1 = Shared
        int32_t performanceMode = -1;  // 12 = LowLatency
        int32_t usesAAudio = 0;
        int64_t midiMessages = 0;
        int32_t running = 0;

        // probe
        int64_t ringUnderruns = 0;
        float ringFillMs = 0.0f;
        float renderMaxMs = 0.0f;
        float ringTargetMs = 0.0f;

        int32_t probeState = 0;
        float roundTripMs = 0.0f;
        float inputPeak = 0.0f;         // 0..1, so you can see whether anything returns
        float inputLatencyMs = 0.0f;

        // What is actually leaving the box, peak-decayed. With three instruments
        // mixed rather than switched, this is the one number that says whether
        // the rig is making sound at all -- and it reads the signal AFTER the
        // graph, so it covers the mixing and the effects chain too.
        float outputPeak = 0.0f;

        // The device the stream is really on, so the UI can tell whether a
        // recovery landed on the piano or on the tablet speaker.
        int32_t deviceId = 0;

        // --- output-path health ------------------------------------------
        //
        // For the dropout that needs a replug to clear: after some minutes the
        // UI still reacts, voices still rise, and nothing is heard.
        //
        // `stalledSeconds` is how long the HARDWARE presentation position has
        // been frozen. It is the one number that separates "the app stopped
        // making audio" from "the audio stopped leaving the box", because it
        // comes from the timestamp the HAL reports for frames it has actually
        // played -- not from anything this process does. Zero when healthy.
        //
        // Read it together with outputPeak: signal present AND the position
        // frozen is the stall, and nothing else looks like that.
        float stalledSeconds = 0.0f;
        int32_t streamState = 0;        // oboe::StreamState
        int32_t timestampOk = 0;        // 0 = the HAL gave us no timestamp
    };

    Stats stats() const;
    void resetPeaks();

private:
    bool openStream(int32_t deviceId, int bufferBursts);
    void closeStream();

    // Runs on the audio thread, after the synth has rendered. May overwrite the
    // output block with a click while a measurement is in flight.
    void runLatencyProbe(int16_t* out, int32_t numFrames);

public:
    // Capture the microphone plus note-on markers for `seconds`, then analyse
    // offline. Returns false if there is no input stream.
    bool startRecording(int seconds);
    bool writeRecording(const std::string& wavPath, const std::string& marksPath);
    bool isRecording() const { return mRecording.load(std::memory_order_relaxed); }
    float recordedSeconds() const;

private:

    std::shared_ptr<oboe::AudioStream> mStream;
    std::mutex mStreamLock;          // guards start/stop only, never the callback

    MidiInput mMidi;
    Dither mDither;

    // Graph, and the compiled schedule the audio thread actually runs.
    // Publication is a single atomic store; retired schedules are freed on a
    // non-audio thread, never inside the callback.
    bagosynths::Graph mGraph;
    std::atomic<bagosynths::Schedule*> mSchedule{nullptr};
    std::vector<std::unique_ptr<bagosynths::Schedule>> mScheduleKeepAlive;
    bagosynths::SfizzNode* mSfizz = nullptr;
    bagosynths::HammondNode* mHammond = nullptr;
    bagosynths::SurgeNode* mSurge = nullptr;
    bagosynths::DexedNode* mDexed = nullptr;
    bagosynths::PlaitsNode* mPlaits = nullptr;
    bagosynths::FluidNode* mFluid = nullptr;
    bagosynths::ReverbNode* mReverb = nullptr;
    bagosynths::LimiterNode* mLimiter = nullptr;

    // Events injected from the UI (on-screen keys), handed to the graph at the
    // top of the next block. Single producer, single consumer, fixed capacity.
    static constexpr int kInjectCapacity = 128;
    bagosynths::MidiEvent mInjected[kInjectCapacity];
    std::atomic<uint32_t> mInjectWrite{0};
    std::atomic<uint32_t> mInjectRead{0};

    // Background loader. Ordinary priority and deliberately NOT pinned to the
    // big cores: it must never compete with the audio thread. See GRAPH.md.
    std::thread mLoaderThread;
    std::atomic<bool> mLoading{false};

    // Surge gets its own loader rather than sharing sfizz's: bringing the synth
    // up and swapping a piano are independent, and making one wait on the other
    // would be a needless coupling on stage. It is a detached pthread rather
    // than a std::thread because it needs a bigger stack than bionic's default
    // -- see runOnBigStack() in the .cpp, which explains what that cost.
    std::atomic<bool> mSurgeBusy{false};
    std::atomic<bool> mDexedBusy{false};
    std::atomic<bool> mFluidBusy{false};
    mutable std::mutex mLoadLock;
    std::string mPendingLoad;
    std::string mPendingId;

    // Preallocated render scratch. Sized generously at open time; the callback
    // chunks if it is ever handed more frames than this, which should not happen.
    std::vector<float> mScratch;
    std::vector<int16_t> mDitherOut;
    int mScratchFrames = 0;

    // Input stream for the loopback probe. Entirely optional: if it fails to
    // open, or permission is denied, the instrument works exactly as before.
    std::shared_ptr<oboe::AudioStream> mInputStream;
    std::vector<int16_t> mInputBuffer;
    int32_t mInputChannels = 2;

    std::atomic<ProbeState> mProbeState{ProbeState::Unavailable};
    std::atomic<float> mRoundTripMs{0.0f};
    std::atomic<float> mInputPeak{0.0f};
    std::atomic<float> mOutputPeak{0.0f};

    // Presentation-position tracking for the dropout hunt. Mutable because
    // stats() is const and is the natural place to sample this: it is called by
    // the UI poller at 4 Hz, which is exactly the cadence that matters and is
    // far too slow to be worth a thread of its own.
    mutable std::atomic<int64_t> mLastPresented{-1};
    mutable std::atomic<double> mLastPresentedAt{0.0};
    mutable std::atomic<float> mStalledSeconds{0.0f};
    mutable std::atomic<double> mLastHealthLogAt{0.0};
    int64_t mFramesSinceClick = 0;
    int32_t mClickRemaining = 0;
    float mNoiseFloor = 0.0f;

    // A tone sustains; a key thunk does not. Requiring the level to STAY above
    // threshold for a few ms rejects the mechanical click of the key, which
    // reaches the microphone before the note does and would otherwise be
    // measured instead of the sound.
    int64_t mCandidateFrame = -1;
    int32_t mAboveCount = 0;
    bool mProbeIsNote = false;

    // Rolling input level, and the tone threshold captured at note-on. A played
    // note is far quieter than the synthetic click, so a fixed floor either
    // misses it or triggers on the previous note's tail. The detector instead
    // looks for a RISE above whatever was already sounding when the key went
    // down, which works for the first note and over a ringing decay alike.
    float mRecentPeak = 0.0f;
    float mNoteThreshold = 0.02f;

    // The microphone also hears the KEY ITSELF -- the mechanical thunk of the
    // keybed, which reaches it before the sound possibly can. That is not a
    // nuisance, it is the missing measurement: the thunk happens at key-down,
    // the tone cannot arrive sooner than the output path (~9 ms) plus the air,
    // so a blanking window separates them cleanly and the thunk's arrival time
    // yields the CP-88's own key-scan-to-MIDI delay.
    int64_t mThunkFrame = -1;
    float mThunkThreshold = 0.004f;
    float mMaxPeakSinceTrigger = 0.0f;
    float mEnvelope = 0.0f;

    // --- capture ------------------------------------------------------------
    // Record the microphone and the note-on positions once, then analyse the
    // result offline as many times as needed. Far better than asking a human to
    // replay a passage every time a detection threshold changes.
    //
    // The buffer is preallocated and the audio thread only ever memcpys into it;
    // the file is written afterwards, from an ordinary thread.
    std::vector<int16_t> mRecordBuffer;
    std::atomic<int32_t> mRecordWrite{0};
    std::atomic<bool> mRecording{false};
    static constexpr int kMaxMarks = 512;
    int32_t mMarks[kMaxMarks] = {};
    std::atomic<int32_t> mMarkCount{0};
    int32_t mRecordRate = 48000;

    // ~10 ms of buzz. Longer than strictly needed, because the click has to
    // survive a trip through the room and a tablet microphone, not a cable.
    static constexpr int32_t kClickFrames = 441;
    static constexpr int64_t kProbeTimeoutFrames = 44100 / 2;   // 500 ms

    // --- render thread ------------------------------------------------------
    //
    // The audio callback does NOT synthesise. It copies finished audio out of a
    // ring buffer, which is an operation that cannot be late. A separate render
    // thread fills that ring ahead of time.
    //
    // This converts a HARD deadline into a SOFT one. Previously a single 2.5 ms
    // blocking read inside sfizz's noteOn -- a cold sample hitting disk -- landed
    // directly on a 2.018 ms callback deadline and became an audible dropout. Now
    // it only eats into the ring's reserve, and is inaudible as long as the ring
    // does not drain.
    //
    // It does not reduce latency: rendering N ms ahead costs N ms, exactly as a
    // larger output buffer would. What it buys is immunity to jitter of every
    // kind -- disk, scheduler, thermal -- rather than only the kind we predicted.
    //
    // The ring holds interleaved stereo int16, already dithered, so the callback
    // is a straight memcpy.
    static constexpr int kRingFrames = 4096;          // power of two
    // 128. Finer blocks were tried and are WORSE: at 64 frames the render peak
    // was 1.38 ms to produce 1.45 ms of audio (95% utilisation), against 0.90 ms
    // for 2.9 ms at 128 (31%). sfizz carries a significant fixed cost per call,
    // so halving the block nearly doubles the overhead per sample.
    static constexpr int kRenderBlockFrames = 128;
    // The reserve ADAPTS instead of being a constant, because the right value is
    // not knowable in advance: it depends on the instrument, the passage being
    // played, storage speed and thermal state. A constant is either too thin
    // (clicks under a forearm cluster) or too deep (latency paid permanently for
    // a rare event). Both were observed.
    //
    // Crucially, adapting is seamless in BOTH directions and needs no sample to
    // be inserted or dropped:
    //   grow   -- the render thread simply runs further ahead; the extra audio
    //             queues behind what is already committed.
    //   shrink -- the render thread idles briefly and the callback drains the
    //             ring down to the new target.
    //
    // So it sits at minimum latency while things are calm, deepens the moment it
    // is actually hurt, and recovers the latency once the passage is over.
    static constexpr int kMinFillFrames = 192;     // ~4.4 ms floor
    static constexpr int kMaxFillFrames = 704;     // ~16 ms ceiling
    static constexpr int kGrowFrames = 256;        // +5.8 ms per underrun: react hard
    static constexpr int kShrinkFrames = 32;       // -0.7 ms per calm period
    static constexpr double kShrinkAfterSeconds = 6.0;   // give latency back slowly

    std::vector<int16_t> mRing;
    std::atomic<uint32_t> mRingWrite{0};
    std::atomic<uint32_t> mRingRead{0};
    std::thread mRenderThread;
    std::atomic<bool> mRenderRun{false};
    std::atomic<int64_t> mRingUnderruns{0};
    std::atomic<float> mRingFillMs{0.0f};
    std::atomic<float> mRenderMaxMs{0.0f};
    std::atomic<int32_t> mTargetFill{160};

    void renderLoop();
    void renderBlockIntoRing(int frames);

    std::atomic<bool> mRunning{false};
    std::atomic<bool> mFlushDenormalsDone{false};

    // Stats written by the audio thread, read by the UI thread. Relaxed atomics:
    // a torn read here costs a slightly wrong number on screen, nothing more.
    // Recovery after a disconnect. Six attempts, 250 ms apart, is 1.5 s -- long
    // enough for a jogged USB cable to re-enumerate with the same id, short
    // enough that a real unplug hands over to the Kotlin watchdog quickly.
    static constexpr int kReconnectAttempts = 6;
    static constexpr int kReconnectDelayMs = 250;

    // The device this stream was opened on, so a disconnect can be recovered
    // onto the SAME piano rather than onto whatever AAudio feels like.
    std::atomic<int32_t> mOpenedDeviceId{kUnspecifiedDevice};
    // What the stream is ACTUALLY on. The UI compares this against the piano
    // it can find by name, which is the only way to notice a silent fallback.
    std::atomic<int32_t> mActualDeviceId{kUnspecifiedDevice};
    // Set while a disconnect is being recovered from, so the UI can say so
    // and so that a second disconnect does not start a second recovery.
    std::atomic<bool> mReconnecting{false};

    std::atomic<float> mCpuLoad{0.0f};
    std::atomic<float> mCallbackMaxMs{0.0f};
    std::atomic<int32_t> mRequestedBursts{2};
};
