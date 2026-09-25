#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include <string>
#include <thread>

#include <bagosynths/Dither.h>
#include <bagosynths/Gain.h>
#include "MidiInput.h"
#include "RealtimePriority.h"
#include "usb/UsbAudioOut.h"
#include <bagosynths/Graph.h>
#include "graph/HammondNode.h"
#include "graph/SurgeNode.h"
#include "graph/DexedNode.h"
#include "graph/PlaitsNode.h"
#include "graph/FluidNode.h"
#include "graph/Nodes.h"

// Stream settings measured as working on this rig (Galaxy Tab S9 Ultra -> CP-88
// over USB, 2026-09-19): AAudio, MMAP + EXCLUSIVE granted, 44100 Hz, 89-frame
// burst, 5.5-9.9 ms reported output latency at a one-burst buffer.
//
// CORRECTED 2026-09-21. This used to claim the device endpoint is 16-bit
// "whatever we ask for". It is not. Android's own policy dump enumerates the
// CP-88's single profile straight from its USB descriptors:
//
//   Port ID 1240  "USB-Audio - CP88/CP73"
//     1. [dynamic format]; AUDIO_FORMAT_PCM_24_BIT_PACKED (0x6)
//        sampling rates: 44100
//        channel masks: 0x0003
//
// 44100 only -- which matches Yamaha's documentation -- and TWENTY-FOUR bit.
//
// CORRECTED AGAIN, 2026-09-22: the ring is FLOAT and the device is asked for
// its own format, so the CP-88 gets its full 24 bits. The whole graph renders
// in float and the single conversion happens at the sink -- the Oboe callback
// or the direct-USB engine -- saturating on overs, and dithered only for a
// 16-bit target, where truncation would otherwise be audible as a noise floor
// that modulates with the signal.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    ~AudioEngine() override;

    // deviceId: from AudioDeviceInfo.getId(), or kUnspecifiedDevice to let
    // AAudio choose. Always pass the CP-88 explicitly -- auto-select can land on
    // the built-in speaker and produce misleadingly good numbers.
    static constexpr int32_t kUnspecifiedDevice = oboe::kUnspecified;

    // Reported as the "device id" while the direct-USB sink is live. No
    // AudioDeviceInfo has a negative id, so the Kotlin watchdog can never
    // mistake this for an AAudio device it should compare against.
    static constexpr int32_t kUsbDeviceId = -2;

    bool start(int32_t deviceId, int bufferBursts);

    // The direct-USB sink: the same render thread and ring, drained by this
    // process's own isochronous engine instead of an Oboe callback. The caller
    // (Kotlin) has already claimed the interfaces and selected the alternate
    // setting; `rate` is what the device's clock is now running at.
    bool startUsb(const UsbAudioOut::Config& config, std::string* error);
    // The isochronous engine, so the MIDI reader can queue async URBs on
    // the same descriptor and have this loop dispatch them.
    UsbAudioOut& usbOut() { return mUsbOut; }
    bool usbActive() const { return mUsbActive.load(std::memory_order_acquire); }

    // The Oboe stream AND the USB sink, whichever is live.
    void stop();
    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }

    // The rate the graph runs at: the USB device's, the stream's, or 44100
    // before either exists.
    double currentRate() const;

    // Builds the graph if no sink has yet. The instrument loaders need the
    // nodes to exist (Surge's init quietly does nothing otherwise), and a
    // direct-USB start is asynchronous -- it waits for the kernel to release
    // the interface -- so bring-up cannot count on a sink having built it.
    void prepareGraph();

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

    /** Ring depth in ms; 0 restores the burst-derived default. See mFillOverrideMs. */
    void setFillMs(int ms);

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

        // --- direct USB --------------------------------------------------
        //
        // `usbDeficitMs` is wall-clock time minus the audio the controller
        // says it put on the wire. It settles at a constant and STAYS there;
        // growth is a hole -- milliseconds in which the device received
        // nothing. It is the one choppiness measure on this path that cannot
        // flatter itself, and it is how the screen-off latch of 2026-09-22 was
        // seen coming (38 holes, 361 ms, then silence).
        int32_t usbActive = 0;
        int32_t usbRunning = 0;
        float usbDeficitMs = 0.0f;
        int32_t usbLate = 0;
        int32_t usbStarved = 0;
        int32_t usbErrors = 0;
        float usbFeedbackRate = 0.0f;
        float usbMaxGapMs = 0.0f;
    };

    Stats stats() const;
    void resetPeaks();
    void setConcealment(bool on) { mConcealOn.store(on, std::memory_order_relaxed); }

    // EXPERIMENT ONLY: stop the render thread producing, while leaving the USB
    // transport running on concealment.
    //
    // The last in-process difference from usbhost -- which holds `late` at 32
    // under a system load of 62 -- is that this app renders the whole graph
    // every block whether or not a note is sounding, and usbhost has no
    // equivalent thread at all. `late` measures the reap thread and does not
    // care what is in the ring, so pausing production isolates that difference
    // from everything else about being an Android app.
    void setRenderPaused(bool paused) {
        mRenderPaused.store(paused, std::memory_order_relaxed);
    }
    void injectUsbRawZero(int count) { mUsbOut.injectRawZero(count); }
    void injectUsbSize(int count, int frames) { mUsbOut.injectSize(count, frames); }
    void injectUsbBias(int count, int delta) { mUsbOut.injectBias(count, delta); }
    void injectUsbStarve(int count) { mUsbOut.injectStarve(count); }
    void setUsbTone(float hz) { mUsbOut.setTone(hz); }
    void dumpUsbTrace(const char* path) { mUsbOut.dumpTrace(path); }
    void holdUsbOut(int us, int count, int every) { mUsbOut.holdOut(us, count, every); }

    /// The rate the CHOSEN DEVICE declares, from AudioDeviceInfo. 0 = let
    /// AAudio decide. Takes effect on the next openStream().
    ///
    /// This has to come from the device's own profile and not from whatever
    /// AAudio hands back: the CP-88 declares 44100 only, and opening it at
    /// 48000 succeeds while putting a resampler in the HAL underneath.
    void setPreferredRate(int32_t rate) {
        mPreferredRate.store(rate, std::memory_order_relaxed);
    }

    /// The format the CHOSEN DEVICE declares, as an oboe::AudioFormat value
    /// (1 = I16, 2 = Float, 3 = I24, 4 = I32). 0 = let Oboe decide.
    ///
    /// Asked of the device for the same reason the rate is: the CP-88 declares
    /// 24-bit packed, and asking it for I16 has the HAL pad eight bits away.
    void setPreferredFormat(int32_t format) {
        mPreferredFormat.store(format, std::memory_order_relaxed);
    }

    /// Takes effect on the next openStream(), so the caller stops and starts.
    /**
     * The master volume, in dB, applied at the very END of the chain:
     * immediately before the float frames go into the ring, and therefore
     * before the one conversion to the device's format.
     *
     * NOTHING follows it but that conversion, which saturates on overs and
     * dithers only for a 16-bit target. There is deliberately no limiter in
     * the default patch: a gain reduction with no control in the UI changes
     * how the instrument sounds rather than how loud it is (Michiel,
     * 2026-09-22). LimiterNode remains available for the graph editor to place
     * where it can be seen and set.
     *
     * The meter reads post-master, so it shows what really leaves.
     *
     * Range -40 dB (silent) to +12 dB. Centred at 0 dB = unity, so the control
     * cuts and boosts either side of the mix as it was designed.
     *
     * It is deliberately NOT a graph node and NOT part of a patch: how loud the
     * room needs it is a property of the room, not of the sound, and a patch
     * that carried it would fight the last person who set it.
     */
    void setMasterDb(float db);
    float masterDb() const { return mMasterDb.load(std::memory_order_relaxed); }

    static constexpr float kMasterMinDb = -40.0f;
    static constexpr float kMasterMaxDb = 12.0f;

    void setExclusive(bool exclusive) {
        mWantExclusive.store(exclusive, std::memory_order_relaxed);
    }
    bool exclusive() const { return mWantExclusive.load(std::memory_order_relaxed); }

private:
    bool openStream(int32_t deviceId, int bufferBursts);
    void closeStream();

    // Sizes the ring and its reserve for a sink that consumes `burst` frames
    // at a time. Shared by both sinks, so their buffering is the same by
    // construction.
    void prepareRing(int32_t burst, int32_t floorMs);

    // Just the three fill targets, with no allocation and no cursor reset, so
    // that changing the depth does NOT interrupt the stream. prepareRing calls
    // it; so does setFillMs, which must not reallocate a ring the render thread
    // and the callback are both using.
    void applyFillTargets(int32_t burst, int32_t floorMs);

    // Copies up to `want` frames of finished stereo audio out of the ring into
    // `dst`, the one operation both sinks perform. Returns how many were
    // there; the caller fills the rest with silence. Counts the underrun and
    // updates the fill and the peak, so the two sinks report identically.
    uint32_t pullFromRing(float* dst, uint32_t want);
    /** Device-format bytes for the USB sink. Conceals a shortfall in place. */
    uint32_t pullBytes(uint8_t* dst, uint32_t want);
    static int pullBytesForUsb(void* ctx, uint8_t* dst, int frames);
    int flushBytes();
    static int flushBytesForUsb(void* ctx);
    /** Stereo float -> the endpoint's channel count and sample format. */
    void packForUsb(uint8_t* dst, const float* stereo, int frames);
    /** Conceal `missing` frames at `dst`, in the device's packed format. */
    void concealBytes(uint8_t* dst, uint32_t take, uint32_t missing, uint32_t base);
    static int pullForUsb(void* ctx, float* dst, int frames);

    UsbAudioOut mUsbOut;
    std::atomic<bool> mUsbActive{false};
    std::atomic<int32_t> mUsbRate{0};

    // Runs on the audio thread, after the synth has rendered. May overwrite the
    // output block with a click while a measurement is in flight.
    void runLatencyProbe(float* out, int32_t numFrames);

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

    // WHICH SCHEDULE THE RENDER THREAD IS INSIDE. A hazard pointer.
    //
    // Retiring used to be "keep the last three and free the rest", with this
    // reasoning: "the audio thread may still have been inside one when we
    // stored, so keep the previous generation alive for a moment". That is a
    // guess about TIMING, not a handshake -- nothing anywhere published which
    // generation the render thread was actually executing. It holds while
    // rebuilds are seconds apart, which is a human tapping the UI. It does not
    // hold when four rebuilds arrive in ten seconds and the render thread is
    // descheduled in between, which is a slow phone driven by broadcasts: the
    // generation still in use gets freed, the render thread writes into freed
    // memory, and the damage surfaces later as a fault inside free().
    //
    // Measured that way on an XQ-CC54 (Snapdragon 695), 2026-09-25: SIGSEGV,
    // SEGV_ACCERR, inside scudo::deallocate under Schedule::~Schedule.
    //
    // The protocol is the standard hazard-pointer one, and it is correct with a
    // single reader. The reader publishes the pointer it intends to use and
    // then RE-READS the current one; if they differ it tries again. The writer
    // publishes the new schedule FIRST and only then reads the hazard. So for
    // any generation the reader is inside: either the reader saw the new store
    // and is on the new one, or the writer sees the old one in the hazard --
    // there is no interleaving in which the writer frees what the reader holds.
    std::atomic<bagosynths::Schedule*> mScheduleHazard{nullptr};
    bagosynths::SfizzNode* mSfizz = nullptr;
    bagosynths::HammondNode* mHammond = nullptr;
    bagosynths::SurgeNode* mSurge = nullptr;
    bagosynths::DexedNode* mDexed = nullptr;
    bagosynths::PlaitsNode* mPlaits = nullptr;
    bagosynths::FluidNode* mFluid = nullptr;
    bagosynths::ReverbNode* mReverb = nullptr;
    // Not in the default patch any more; kept for the graph editor to place.
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
    // Which device mInputStream is on, so openInput can tell "already up on the
    // right device" from "up on the wrong one". See openInput.
    int32_t mInputDeviceId = -1;
    std::vector<int16_t> mInputBuffer;
    int32_t mInputChannels = 2;

    std::atomic<ProbeState> mProbeState{ProbeState::Unavailable};
    std::atomic<float> mRoundTripMs{0.0f};
    std::atomic<float> mInputPeak{0.0f};
    std::atomic<float> mOutputPeak{0.0f};

    // Peak of the int16 frames handed to AAudio, measured in the callback. The
    // only reading that sees what leaves this process rather than what the
    // graph produced.
    std::atomic<float> mCallbackPeak{0.0f};

    // EXCLUSIVE or SHARED, for the next open.
    //
    // EXCLUSIVE + MMAP is what this project asks for and what every
    // measurement in MEASUREMENTS.md was taken on. It is also the one
    // unusual thing the rig does, so when the USB audio path started dying
    // after a quarter of an hour it became a hypothesis worth being able to
    // test rather than argue about. Toggled by long-pressing Buffer.
    std::atomic<bool> mWantExclusive{true};

    std::atomic<float> mMasterDb{0.0f};
    std::atomic<float> mMasterGain{1.0f};

    // The master gain, ramped. Render thread only. See SmoothedGain.
    bagosynths::SmoothedGain mMasterRamp;

    std::atomic<int32_t> mPreferredRate{0};
    std::atomic<int32_t> mPreferredFormat{0};

    // What the stream actually granted, cached so the callback does not ask.
    oboe::AudioFormat mStreamFormat = oboe::AudioFormat::I16;
    int32_t mBytesPerFrame = 4;

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

    // A FLOOR IN TIME, FOR THE AAUDIO PATH ONLY.
    //
    // The derived floor is one and a half BURSTS, which is a sound rule when a
    // burst is a sizeable slice of time and a meaningless one when it is not.
    // An MMAP burst is 2 ms, so 1,5 of them is 3-4 ms of reserve for the render
    // thread -- and the render thread is the part that has to produce 64 voices
    // of Salamander on whatever core the governor felt like using.
    //
    // Measured on the phone, 2026-09-25, intensive piano, ring depth swept with
    // the 4 ms point repeated at the end as a control (121 first, 221 last):
    //
    //   4,0 ms -> 121 underruns     12,0 ms -> 35
    //   8,0 ms ->  86               16,0 ms -> 14
    //
    // against 6-16 on the same machine's SHARED stream, whose 882-frame burst
    // derives a 30 ms floor and therefore never needed this. So the floor is
    // what decides, and the adaptive layer above it climbs THROUGH the chop
    // rather than preventing it -- it reacts to concealment that has already
    // been heard.
    //
    // EIGHT, chosen by Michiel on 2026-09-25 against that table. It is the
    // latency-leaning end of the trade and it is a deliberate one: 8 ms roughly
    // halves the chop of the derived floor without spending the 12 ms that
    // would match the shared path's underrun band. The ALSA path is a FALLBACK
    // -- direct USB is 15-27 ms and is what the instrument actually uses -- so
    // buying latency back here past the point of usefulness would be paying for
    // a path nobody plays on.
    //
    // It is a floor, not a target: mTargetFill still grows above it under load,
    // and FILL --ei ms N overrides it for a measurement.
    //
    // NOT applied to the direct-USB sink, which passes 0. That path is the
    // primary one, its reserve is tuned against a 6 ms transport buffer, and
    // nothing here should move it.
    static constexpr int kAAudioFloorMs = 8;

    // What prepareRing was last called with, so setFillMs can retarget without
    // needing to know which sink is live.
    int32_t mRingFloorMs = 0;
    static constexpr int kMaxFillFrames = 704;     // ~16 ms ceiling
    // HOW THE RESERVE ADAPTS, rewritten 2026-09-23.
    //
    // It used to add 256 frames (5,8 ms) on any change of the underrun COUNT
    // and give back 32 frames (0,7 ms) per six calm seconds. Two faults. It
    // could not tell one frame short from forty-five, so a trivial hiccup cost
    // the same as a real stall; and the asymmetry meant a single bad moment
    // cost about fifty seconds of raised latency, while a hard passage pinned
    // it at the 16 ms ceiling for roughly ninety-six. The instrument got
    // measurably laggier after loud playing and stayed that way.
    //
    // Now: grow by what was actually MISSED, and only when the miss is worth
    // hearing; shrink geometrically so a big excursion comes back quickly and
    // the last millimetre comes back gently.
    //
    // The threshold is deliberately low. Concealment makes short gaps "less
    // clicky - better" by ear, which is a modest improvement rather than a
    // cure, so this is not licence to ignore small underruns -- only to stop
    // charging 5,8 ms for one.
    // The ceiling must sit a useful distance above whatever floor a device
    // ends up with, or the reserve has nowhere to grow into. 512 frames is
    // ~11,6 ms, which is what two of the old fixed growth steps came to.
    static constexpr int kFillHeadroomFrames = 512;
    static constexpr int kGrowThresholdFrames = 32;   // ~0,7 ms before reacting
    static constexpr int kGrowMarginFrames = 64;      // headroom above the miss
    static constexpr int kShrinkDivisor = 8;          // give back an eighth
    static constexpr int kShrinkMinFrames = 16;
    static constexpr double kShrinkAfterSeconds = 4.0;

    // FLOAT, not int16. The engine renders in float and the devices differ:
    // the CP-88 wants 24-bit packed, the tablet speaker 16-bit. Keeping the ring
    // at the engine's own precision means the single conversion to the device's
    // format happens once, in the callback, at the last possible moment --
    // instead of quantising to 16 bits here and padding back up afterwards.
    std::vector<float> mRing;

    // THE DEVICE-FORMAT RING, used only when direct USB is the sink.
    //
    // The reap thread is the one thread in this process that must never be
    // late, and it was doing the most work per URB of anything in the audio
    // path: read the float ring, map channels, dither, pack to 24-bit, conceal.
    // usbhost -- which holds `late` at 32 under a system load of 62 -- computes
    // a sine inline and packs it, touching nothing shared. That difference in
    // critical-section length is the last structural one between the two, and
    // it is the one thing that moved `late` by large factors all day.
    //
    // So the producer now does the conversion. The render thread writes bytes
    // in exactly the layout the endpoint wants, and the reap thread does a
    // memcpy -- two across the wrap -- and sets the packet length. It never
    // needs to know the packet size in advance, because it is slicing a byte
    // stream rather than re-blocking fixed packets, so implicit feedback still
    // decides 44 or 45 frames at fill time exactly as before.
    //
    // ALSA keeps using the float ring for now; the two sinks are mutually
    // exclusive, so a flag at stream start is enough to say which is live.
    std::vector<uint8_t> mByteRing;
    std::vector<float> mPackScratch;      // channel-mapped, render thread only
    std::vector<int16_t> mPackI16;
    Dither mUsbDither;                    // 16-bit targets only; render thread
    int mUsbChannels = 2;
    int mUsbSubslot = 3;
    int mUsbFrameBytes = 6;

    // Contiguous copy of one callback's worth, because the ring wraps and the
    // dither wants a straight run of samples. Preallocated at open.
    std::vector<float> mCbScratch;
    std::atomic<uint32_t> mRingWrite{0};
    std::atomic<uint32_t> mRingRead{0};
    // Scheduler hint for the render thread: see RealtimePriority.h.
    bos::PerfHint mPerfHint;

    std::thread mRenderThread;
    std::atomic<bool> mRenderRun{false};
    std::atomic<int64_t> mRingUnderruns{0};

    // UNDERRUN CONCEALMENT, and how far into one we are.
    //
    // A late render used to become a hard jump to zero: a click AND a gap, and
    // `ringUnder` reaches four figures under stress, so there are a lot of
    // them. Michiel's suggestion was to repeat the last audio instead of
    // sending silence, which is right for the short gaps that dominate.
    //
    // Straight repetition is wrong for long ones -- repeating two
    // milliseconds a hundred times turns a dropout into a drone, which is
    // worse than silence -- so the repeat decays to nothing over kConcealFadeMs
    // and a sustained stall ends up silent anyway, just without the click.
    //
    // This counts frames concealed CONSECUTIVELY, so the fade continues across
    // successive calls rather than restarting at full level on each one. Reset
    // the moment the ring delivers everything asked for.
    //
    // It conceals a symptom and changes no packet size, so it cannot affect THE
    // LATCH; the device is still sent exactly the frame counts it asked for.
    uint32_t mConcealedFrames = 0;
    // Switchable so concealment can be judged by ear against its own absence
    // on the same load, rather than in the absolute. Default on.
    std::atomic<bool> mConcealOn{true};
    std::atomic<bool> mRenderPaused{false};
    // Frames concealed since the stream started. The reserve adapts on the
    // DELTA of this rather than on the underrun count, because it carries
    // severity: one frame short and a whole packet short are not the same
    // event and should not buy the same latency.
    std::atomic<int64_t> mConcealedTotal{0};
    static constexpr int kConcealPeriodFrames = 441;   // ~10 ms at 44,1 kHz
    // 20 ms, not 40. The repeat loops a fixed period, and for pitched material
    // that seam does not match the waveform, so a long gap clicks once per
    // loop. Most underruns are one or two packets and never reach the seam at
    // all; fading twice as fast means the few long ones are near silence by the
    // time they would. Judged by ear against concealment switched off: "less
    // clicky - better", which is a modest win, so this is tuned modestly.
    static constexpr int kConcealFadeFrames = 882;     // ~20 ms to silence
    static constexpr int kConcealXfadeFrames = 64;
    std::atomic<float> mRingFillMs{0.0f};
    std::atomic<float> mRenderMaxMs{0.0f};
    std::atomic<int32_t> mTargetFill{160};

    // Floor and ceiling for mTargetFill, computed at open time from the burst
    // the stream actually granted. The constants above are the CP-88's numbers
    // (89-frame burst); the tablet's own speaker hands the callback 882 frames
    // at a time and needs a proportionally deeper reserve or it underruns on
    // every callback. See openStream().
    // THE RENDER RING'S DEPTH, IN MILLISECONDS, AS A SWEEPABLE PARAMETER.
    //
    // Zero keeps the computed default. It is a parameter because the default
    // is derived from the BURST, and that turns out to hide a 7,5x difference
    // in the thing that actually matters -- how much slack the render thread
    // has -- behind two configurations of the same device.
    //
    // Measured on the phone, 2026-09-25, intensive piano, 60 rounds of 16
    // voices, the two ALSA arms interleaved:
    //
    //   44100 shared, burst 882 -> minFill 1323 frames = 30,0 ms -> ringUnder 8, 9
    //   48000 excl,   burst  96 -> minFill  192 frames =  4,0 ms -> ringUnder 347, 448
    //
    // Same machine, same music, a factor of forty-five. AAudio itself was never
    // late in either (xruns 0) -- it is the render thread missing a 4 ms
    // deadline on a Snapdragon 695. The exclusive arm has room to pay for more:
    // it measures 30-43 ms of acoustic round trip against the shared arm's
    // 128-134, so several milliseconds of ring are affordable where they would
    // be unthinkable on the direct-USB path.
    std::atomic<int32_t> mFillOverrideMs{0};

    std::atomic<int32_t> mMinFill{kMinFillFrames};
    std::atomic<int32_t> mMaxFill{kMaxFillFrames};

    void renderLoop();
    void renderBlockIntoRing(int frames);

    // Logs the MIDI path stage by stage, so a dropped note can be located
    // rather than argued about. See the definition.
    void logMidiChain() const;

    // Gates instruments in and out of the schedule by level, releasing their
    // voices on each transition. Called from the render thread.
    void updateInstrumentGates(int frames);

    // A silenced instrument keeps RUNNING until it has actually gone quiet.
    //
    // Its level is already zero, so none of it is audible; the point is to let
    // whatever it holds internally -- the Hammond's rotary and reverb live
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

    // Held peak of each instrument's own output, before its level. Render
    // thread writes, logProfile() reads and resetPeaks() clears. See
    // updateInstrumentGates.
    float mNodePeakHold[6] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

    bool mGateFlushing[6] = {false, false, false, false, false, false};
    int mGateFade[6] = {0, 0, 0, 0, 0, 0};    // frames left of the level ramp
    int mGateQuiet[6] = {0, 0, 0, 0, 0, 0};   // frames of continuous silence
    int mGateFlush[6] = {0, 0, 0, 0, 0, 0};   // frames spent flushing in total

    // Highest sfizz voice count seen. Mutable because stats() is const and is
    // the only place the count is sampled. See stats().
    mutable std::atomic<int> mPeakVoices{0};

    // Previous sample of the USB transport's error counters, so stats() can log
    // the TRANSITION rather than the running total. See the hole log in
    // stats(): a latch is an event, and a total that stopped moving looks
    // exactly like one that never moved.
    mutable std::atomic<int64_t> mUsbLastLate{0};
    mutable std::atomic<int64_t> mUsbLastErrors{0};
    mutable std::atomic<float> mUsbLastDeficitMs{0.0f};

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
