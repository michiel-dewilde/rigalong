#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include <string>
#include <thread>

#include <rigalong/Dither.h>
#include <rigalong/Click.h>
#include <rigalong/Gain.h>
#include "LatencyProbe.h"
#include "MidiInput.h"
#include "RealtimePriority.h"
#include "usb/UsbAudioOut.h"
#include "Rack.h"

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
    // The metronome (I12.2), mixed into the main out before the master volume --
    // or, where the setup sends it elsewhere (S12.4), on an output of its own.
    rigalong::Click& click() { return mClick; }
    // The click on an Android output device of its own (its AAudio device id),
    // or back in the main mix (-1). A stream of its own, on its own clock: the
    // click is its own island (S12.4). Returns the device the stream got, or -1
    // (the main mix: asked for, or the device could not be opened).
    int32_t setClickOutput(int32_t deviceId);
    // How long until a click rendered now is heard: the own output's latency
    // while the click has one, else -1 (the main out's figures apply).
    int64_t clickOutputLatencyNs();
    // The song's tempo for tempo-synced parameters (I12.1); 0 or less = none (120).
    void setTempo(double bpm) {
        mTempo.store(bpm > 0.0 ? bpm : 120.0, std::memory_order_relaxed);
        rigalong::FxNode::setHostTempo(bpm);
    }

    // --- the rack ----------------------------------------------------------
    // The node graph, its instances and their loaders (Rack.h). This engine
    // compiles it into a schedule and runs that.
    Rack& rack() { return mRack; }

    // Recompiles and publishes a new schedule. Off the audio thread only.
    bool rebuildGraph(std::string* error = nullptr);

    // A new instance of engine `type` (Rack::Engine), compiled in without
    // touching what is playing. Returns its index, or -1. Off the audio thread.
    int addInstance(int type);

    // Injects a note from the on-screen keys, as if it had arrived over MIDI.
    void injectNote(int note, int velocity);
    // Any three-byte message, the same way (a controller, for tests).
    void injectMidi(uint8_t status, uint8_t data1, uint8_t data2);

    // CONTROLLER MAPS (I6.6): a controller (CC, any channel) moving a node's
    // parameter over [min, max]. Applied on the render thread before the graph
    // runs, so a knob is heard in the block it arrives in, at block rate --
    // not at the UI's 50 ms poll. PICKUP: after the maps are set (a song or a
    // live set selected) a map acts only once the controller reaches the
    // parameter's value or crosses it, so nothing jumps. UI thread.
    struct ControlMap {
        int cc = 0;
        int node = 0;      // graph index
        int param = 0;
        float min = 0.0f, max = 1.0f;
        bool integer = false;
    };
    void setControlMaps(const ControlMap* maps, int count);
    // Learning: the next controller that arrives is reported by takeLearnedControl.
    void setControlLearn(bool on);
    int takeLearnedControl() { return mLearnedControl.exchange(-1, std::memory_order_acq_rel); }
    // The same for a key: the next note played is reported by takeLearnedNote
    // (a split set by playing the key where it is).
    void setNoteLearn(bool on) {
        mLearnedNote.store(-1, std::memory_order_relaxed);
        mNoteLearn.store(on, std::memory_order_release);
    }
    int takeLearnedNote() { return mLearnedNote.exchange(-1, std::memory_order_acq_rel); }
    // Which maps (by their index in the last setControlMaps) moved a parameter since the last call.
    uint64_t takeMovedControls() { return mControlMoved.exchange(0, std::memory_order_acq_rel); }
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

    // --- round-trip latency probe (MEASURE) ----------------------------------
    // The probe's input stream; false when none could be opened on `deviceId`.
    bool openInput(int32_t deviceId) { return mProbe.open(deviceId); }
    // Emit a click, time its return: the output stage plus the room and the
    // microphone. The result arrives in stats().
    void startLatencyProbe() { mProbe.start(); }

    /** Ring depth in ms; 0 restores the burst-derived default. See mFillOverrideMs. */
    void setFillMs(int ms);

    // The render thread's scheduling, to experiment with (RENDERPRIO): its nice
    // value and CPUs apply at once to the running thread; `fifo` -- the loop on
    // a borrowed AAudio callback thread, which is SCHED_FIFO -- from the next
    // stream start. nice 0 / cpus "" leave that part as it is.
    void setRenderScheduling(int nice, const std::string& cpus, int fifo);
    // The USB reap thread's CPUs ("all" or "": all).
    void setReapCpus(const std::string& cpus);
    // The real-time rescue (renderRescueLoop) on or off, from the next stream start.
    void setRenderRescue(bool on) { mRescueWanted.store(on, std::memory_order_relaxed); }
    // How low the ring must run, in percent of its floor, before the rescue steps in.
    void setRenderRescueAt(int percent) { if (percent > 0) mRescueAtPercent.store(percent, std::memory_order_relaxed); }
    // Blocks the rescue has rendered (or got rendered) since the stream started.
    int64_t renderRescues() const { return mRescues.load(std::memory_order_relaxed); }

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

        int32_t probeState = 0;         // LatencyProbe::State
        float roundTripMs = 0.0f;
        float inputPeak = 0.0f;         // 0..1, so you can see whether anything returns
        float inputLatencyMs = 0.0f;

        // What is actually leaving the box, peak-decayed. With three instruments
        // mixed rather than switched, this is the one number that says whether
        // the rig is making sound at all -- and it reads the signal AFTER the
        // graph, so it covers the mixing and the effects chain too.
        float outputPeak = 0.0f;

        // The device the stream is really on, so the UI can tell whether a
        // recovery landed on the CP-88 or on the tablet speaker.
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
        // says it put on the wire: it grows by the silence of every hold.
        // `usbHolds` counts those -- each one a short silence by design, never
        // a latch. `usbAltErrors` counts holds whose alt cycle was refused,
        // the one number here that should never move.
        int32_t usbActive = 0;
        int32_t usbRunning = 0;
        float usbDeficitMs = 0.0f;
        int32_t usbHolds = 0;
        int32_t usbAltErrors = 0;
        int32_t usbErrors = 0;
        float usbFeedbackRate = 0.0f;
        float usbMaxGapMs = 0.0f;
        float usbMaxAltMs = 0.0f;       // worst alt cycle: most of a hold's silence
        float usbLastAltMs = 0.0f;
        // Holds by reason (UsbAudioOut::HoldReason 1-6): proof, gap, lost,
        // bad ask, slow burst, starve -- the reap loop late, or the wire failing.
        int32_t usbHoldsBy[6] = {};
    };

    Stats stats() const;
    void resetPeaks();
    void setConcealment(bool on) { mConcealOn.store(on, std::memory_order_relaxed); }

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
    /** Stereo float -> the endpoint's channel count and sample format. */
    void packForUsb(uint8_t* dst, const float* stereo, int frames);
    /** Conceal `missing` frames at `dst`, in the device's packed format. */
    void concealBytes(uint8_t* dst, uint32_t take, uint32_t missing, uint32_t base);
    static int pullForUsb(void* ctx, float* dst, int frames);

    UsbAudioOut mUsbOut;
    std::atomic<bool> mUsbActive{false};
    std::atomic<int32_t> mUsbRate{0};

    std::shared_ptr<oboe::AudioStream> mStream;
    std::mutex mStreamLock;          // guards start/stop only, never the callback

    MidiInput mMidi;
    Dither mDither;

    // Graph, and the compiled schedule the audio thread actually runs.
    // Publication is a single atomic store; retired schedules are freed on a
    // non-audio thread, never inside the callback.
    Rack mRack;
    std::atomic<rigalong::Schedule*> mSchedule{nullptr};
    std::vector<std::unique_ptr<rigalong::Schedule>> mScheduleKeepAlive;

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
    std::atomic<rigalong::Schedule*> mScheduleHazard{nullptr};
    bool compileAndPublish(bool prepareAll, std::string* error);

    // Events injected from the UI (on-screen keys), handed to the graph at the
    // top of the next block. Single producer, single consumer, fixed capacity.
    static constexpr int kInjectCapacity = 128;
    rigalong::MidiEvent mInjected[kInjectCapacity];
    std::atomic<uint32_t> mInjectWrite{0};
    std::atomic<uint32_t> mInjectRead{0};

    // Controller maps: two tables, the UI writing the one the render thread
    // is not reading. The render thread acknowledges each generation it has
    // seen; the UI writes the other table only after that, so a table is
    // never written while it is read. Pickup state is the render thread's.
    static constexpr int kMaxControlMaps = 64;
    struct ControlTable {
        int count = 0;
        ControlMap maps[kMaxControlMaps];
        rigalong::Node* nodes[kMaxControlMaps] = {};
    };
    ControlTable mControlTables[2];
    std::atomic<uint32_t> mControlGen{0};
    std::atomic<uint32_t> mControlAck{0};
    uint32_t mControlSeen = 0;
    bool mControlPicked[kMaxControlMaps] = {};
    bool mControlHasLast[kMaxControlMaps] = {};
    float mControlLast[kMaxControlMaps] = {};
    std::atomic<uint64_t> mControlMoved{0};
    std::atomic<bool> mControlLearn{false};
    std::atomic<bool> mNoteLearn{false};
    std::atomic<int> mLearnedNote{-1};
    std::atomic<int> mLearnedControl{-1};
    void applyControlMaps(const rigalong::MidiBuffer& in);

    // Preallocated render scratch. Sized generously at open time; the callback
    // chunks if it is ever handed more frames than this, which should not happen.
    std::vector<float> mScratch;
    std::vector<int16_t> mDitherOut;
    int mScratchFrames = 0;

    // MEASURE. Run after each block is rendered; it may overwrite the block
    // with its click while a measurement is in flight.
    LatencyProbe mProbe;

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
    // test rather than argue about. Set by the SHARING test hook.
    std::atomic<bool> mWantExclusive{true};

    std::atomic<float> mMasterDb{0.0f};
    std::atomic<float> mMasterGain{1.0f};

    // The master gain, ramped. Render thread only. See SmoothedGain.
    rigalong::SmoothedGain mMasterRamp;
    rigalong::Click mClick;
    // The click's own output (setClickOutput), or null. [mClickOwn]: its
    // callback renders the click and the main mix does not. [mClickBusy] keeps
    // the two from rendering the click at once while it changes over.
    void* mClickStream = nullptr;
    std::atomic<bool> mClickOwn{false};
    std::atomic<bool> mClickBusy{false};
    std::atomic<int32_t> mClickRate{48000};
    std::mutex mClickStreamLock;
    void renderClickGuarded(float* stereo, int frames, double rate);
    std::atomic<double> mTempo{120.0};

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
    // The ring holds interleaved stereo float (see mRing); the one conversion
    // to the device's format happens at the sink.
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
    // Measured on the phone, 2026-09-25, intensive sfizz playing, ring depth swept with
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
    // A STREAM'S FIRST SECONDS TEACH THE RESERVE NOTHING. Traced on 2026-10-11
    // (LOG.md): straight after the app starts, the render thread waits runnable
    // for 1,5-14 ms at a time behind the launch itself -- surfaceflinger,
    // system_server, GMS -- nice -16 notwithstanding, for about 1,4 s. The
    // misses grew the target to 12 ms, and the shrink took a minute to give it
    // back. The reserve still grows during these seconds (someone may play at
    // once), but when they are over it returns to the floor. The same 10 s
    // AudioHealth leaves out of "the last minute".
    static constexpr double kSettleSeconds = 10.0;

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
    rigalong::PerfHint mPerfHint;

    std::thread mRenderThread;
    std::atomic<bool> mRenderRun{false};
    // The render loop's thread id while it runs, and whether it runs at all.
    std::atomic<int> mRenderTid{0};
    std::atomic<bool> mRenderLoopActive{false};
    // Scheduling asked for (setRenderScheduling); applied as the loop starts.
    std::atomic<int> mRenderNiceWanted{0};
    std::string mRenderCpus;
    std::mutex mRenderCpusLock;
    std::atomic<bool> mRenderOnFifo{false};
    // The AAudio stream whose callback thread the render loop runs on (fifo).
    void* mRenderHost = nullptr;
    std::atomic<bool> mRenderHostClaimed{false};
    void startRenderThread();
    // Every block is rendered under this lock, by the render thread or the rescue.
    rigalong::PiMutex mRenderLock;
    // The rescue: a borrowed SCHED_FIFO thread that, when the ring runs low,
    // takes the render lock -- lifting a starved render thread to its priority
    // until that block is done -- and tops the ring up to its floor itself.
    std::atomic<bool> mRescueWanted{true};
    std::atomic<int> mRescueAtPercent{50};
    std::atomic<int64_t> mRescues{0};
    // When a block was last put into the ring, by either thread (nowSeconds()).
    std::atomic<double> mLastBlockAt{0.0};
    struct Borrowed {
        AudioEngine* engine = nullptr;
        void (*body)(AudioEngine*) = nullptr;
        std::atomic<bool> claimed{false};
        std::atomic<bool> active{false};
        void* stream = nullptr;
    };
    Borrowed mRescueHost;
    void renderRescueLoop();
    bool borrowFifoThread(Borrowed& b, const char* what);
    void returnFifoThread(Borrowed& b);
    void stopRenderThread();
    void applyRenderScheduling(int tid);
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
    // Measured on the phone, 2026-09-25, intensive sfizz playing, 60 rounds of 16
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

    // Highest sfizz voice count seen. Mutable because stats() is const and is
    // the only place the count is sampled. See stats().
    mutable std::atomic<int> mPeakVoices{0};

    // Previous sample of the USB transport's holds and errors, so stats() can
    // log each one as it happens rather than a running total.
    mutable std::atomic<int64_t> mUsbLastHolds{0};
    mutable std::atomic<int64_t> mUsbLastErrors{0};

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
    // onto the SAME CP-88 rather than onto whatever AAudio feels like.
    std::atomic<int32_t> mOpenedDeviceId{kUnspecifiedDevice};
    // What the stream is ACTUALLY on. The UI compares this against the CP-88
    // it can find by name, which is the only way to notice a silent fallback.
    std::atomic<int32_t> mActualDeviceId{kUnspecifiedDevice};
    // Set while a disconnect is being recovered from, so the UI can say so
    // and so that a second disconnect does not start a second recovery.
    std::atomic<bool> mReconnecting{false};

    std::atomic<float> mCpuLoad{0.0f};
    std::atomic<float> mCallbackMaxMs{0.0f};
    std::atomic<int32_t> mRequestedBursts{2};
};
