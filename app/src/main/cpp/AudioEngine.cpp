#include "AudioEngine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <sys/resource.h>
#include <ctime>
#include <functional>
#include <pthread.h>
#include <thread>

#include "Denormals.h"

#define LOG_TAG "bos-audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

inline double nowSeconds() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1.0e-9;
}

}  // namespace

AudioEngine::~AudioEngine() {
    stop();
    // The sfizz loader holds a raw pointer to a node the graph owns, so it must
    // not outlive this object. The Surge loader is detached and cannot be
    // joined; in this app the engine is a singleton that outlives everything,
    // so that is not a lifetime problem today -- but it IS the reason this
    // comment exists, because it would become one if the engine were ever made
    // destroyable for real.
    if (mLoaderThread.joinable()) mLoaderThread.join();
}

bool AudioEngine::start(int32_t deviceId, int bufferBursts) {
    std::lock_guard<std::mutex> lock(mStreamLock);
    if (mRunning.load(std::memory_order_acquire)) return true;

    mRequestedBursts.store(std::clamp(bufferBursts, 1, 8), std::memory_order_relaxed);
    if (!openStream(deviceId, mRequestedBursts.load(std::memory_order_relaxed))) {
        return false;
    }

    const oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        closeStream();
        return false;
    }

    mRunning.store(true, std::memory_order_release);

    mRenderRun.store(true, std::memory_order_release);
    mRenderThread = std::thread([this] { renderLoop(); });
    return true;
}

bool AudioEngine::openStream(int32_t deviceId, int bufferBursts) {
    oboe::AudioStreamBuilder builder;

    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(44100)
            // The CP-88 is 44.1 kHz only. Refuse any hidden conversion rather
            // than silently accepting a resampler in the live path.
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            ->setFormatConversionAllowed(false)
            // Game, not Media: this is interactive audio, not a media session.
            // Verified to keep MMAP/EXCLUSIVE on this device.
            ->setUsage(oboe::Usage::Game)
            ->setContentType(oboe::ContentType::Music)
            ->setSpatializationBehavior(oboe::SpatializationBehavior::Never)
            ->setDeviceId(deviceId)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    const oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK || !mStream) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        return false;
    }

    // CHECK WHAT WE GOT. Asking for a device id that no longer exists does NOT
    // fail: AAudio falls back to the default output -- the built-in speaker --
    // and returns OK. Measured on 2026-09-20, recovering from a real unplug:
    //
    //   stream recovered after disconnect, on device 10051 (attempt 1)
    //     ...got Legacy, devIds = [2], perf = NO, burst = 882
    //
    // We asked for the piano, were handed the tablet speaker, and called it a
    // success. So "specify the device" is not a guarantee of anything; the only
    // guarantee is comparing what came back.
    const int32_t got = mStream->getDeviceId();
    if (deviceId != kUnspecifiedDevice && got != deviceId) {
        LOGE("asked for device %d and got %d -- refusing it", deviceId, got);
        mStream->close();
        mStream.reset();
        return false;
    }

    // Remember the REQUESTED id, not what came back. Recording the fallback is
    // how the speaker became "home" after the first bad recovery, so that every
    // subsequent disconnect recovered onto it deliberately.
    mOpenedDeviceId.store(deviceId != kUnspecifiedDevice ? deviceId : got,
                          std::memory_order_relaxed);
    mActualDeviceId.store(got, std::memory_order_relaxed);

    const int32_t burst = mStream->getFramesPerBurst();
    mStream->setBufferSizeInFrames(burst * bufferBursts);

    // ADPF (Android Dynamic Performance Framework). This matters more here than
    // it looks: the SoC is 1x3.36 GHz prime + 4x2.8 mid + 3x2.02 little, so where
    // the callback thread lands changes the available budget by a factor of ~1.7,
    // and a thread migrating to a little core mid-performance is exactly how an
    // unexplained glitch happens. The hint tells the governor this thread has a
    // hard deadline, so it ramps clocks and placement instead of guessing.
    // Device specific and may be a no-op -- there is no downside to asking.
    mStream->setPerformanceHintEnabled(true);


    // Preallocate render scratch with generous headroom. Allocating inside the
    // callback is the cardinal sin; this is the one place it is allowed.
    mScratchFrames = std::max(2048, burst * 16);
    mScratch.assign(static_cast<size_t>(mScratchFrames) * 2, 0.0f);
    mDitherOut.assign(static_cast<size_t>(mScratchFrames) * 2, 0);
    mRing.assign(static_cast<size_t>(kRingFrames) * 2, 0);
    mRingWrite.store(0, std::memory_order_relaxed);
    mRingRead.store(0, std::memory_order_relaxed);
    mTargetFill.store(kMinFillFrames, std::memory_order_relaxed);

    mFlushDenormalsDone.store(false, std::memory_order_relaxed);
    mCallbackMaxMs.store(0.0f, std::memory_order_relaxed);

    // Build (or rebuild for the new rate/block size) the graph before the stream
    // starts, so the first callback already has a schedule to run.
    std::string graphError;
    if (!rebuildGraph(&graphError)) {
        LOGE("graph compile failed: %s", graphError.c_str());
    }

    LOGI("stream open: rate=%d burst=%d buffer=%d sharing=%d perf=%d aaudio=%d",
         mStream->getSampleRate(), burst, mStream->getBufferSizeInFrames(),
         static_cast<int>(mStream->getSharingMode()),
         static_cast<int>(mStream->getPerformanceMode()),
         mStream->usesAAudio() ? 1 : 0);

    return true;
}

bool AudioEngine::openInput(int32_t deviceId) {
    // Try progressively more relaxed configurations. The output side can demand
    // exclusive/44100/I16 because the CP-88 grants it; the capture side turns out
    // to be fussier, and for a measurement stream correctness matters far more
    // than squeezing out the last millisecond.
    struct Attempt {
        const char* name;
        oboe::SharingMode sharing;
        bool pinFormat;     // demand 44100 / I16 / stereo exactly
    };
    static constexpr Attempt kAttempts[] = {
            {"exclusive, pinned", oboe::SharingMode::Exclusive, true},
            {"shared, pinned",    oboe::SharingMode::Shared,    true},
            {"shared, relaxed",   oboe::SharingMode::Shared,    false},
    };

    for (const auto& a : kAttempts) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(a.sharing)
                // Unprocessed: no AGC, no noise suppression, no echo cancellation.
                // Echo cancellation in particular exists precisely to remove what
                // this probe is looking for.
                ->setInputPreset(oboe::InputPreset::Unprocessed)
                ->setDeviceId(deviceId);

        if (a.pinFormat) {
            // Sample rate deliberately NOT pinned. Asking the microphone for
            // 44100 to match the CP-88 puts a resampler in the capture path,
            // which adds latency that calculateLatencyMillis() does not report
            // and corrupts the measurement. Let it run at its native rate; the
            // probe converts frames to milliseconds using getSampleRate(), so
            // whatever it picks is handled correctly.
            builder.setFormat(oboe::AudioFormat::I16)
                    ->setChannelCount(oboe::ChannelCount::Stereo)
                    ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
                    ->setFormatConversionAllowed(false);
        } else {
            builder.setFormat(oboe::AudioFormat::I16)
                    ->setFormatConversionAllowed(true)
                    ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        }

        // No data callback: the input is drained from inside the output callback,
        // so the two directions stay locked and frame counting stays honest.
        const oboe::Result opened = builder.openStream(mInputStream);
        if (opened != oboe::Result::OK || !mInputStream) {
            LOGE("input open [%s] failed: %s", a.name, oboe::convertToText(opened));
            mInputStream.reset();
            continue;
        }

        const oboe::Result started = mInputStream->requestStart();
        if (started != oboe::Result::OK) {
            LOGE("input start [%s] failed: %s", a.name, oboe::convertToText(started));
            mInputStream->close();
            mInputStream.reset();
            continue;
        }

        mInputChannels = mInputStream->getChannelCount();
        // May be called before the output stream exists, so do not depend on
        // mScratchFrames having been sized yet.
        const int32_t frames = std::max(4096, mScratchFrames);
        mInputBuffer.assign(
                static_cast<size_t>(frames) * std::max(1, mInputChannels), 0);
        mNoiseFloor = 0.0f;
        mProbeState.store(ProbeState::Idle, std::memory_order_relaxed);

        LOGI("input open [%s]: rate=%d ch=%d burst=%d sharing=%d perf=%d",
             a.name, mInputStream->getSampleRate(), mInputChannels,
             mInputStream->getFramesPerBurst(),
             static_cast<int>(mInputStream->getSharingMode()),
             static_cast<int>(mInputStream->getPerformanceMode()));
        return true;
    }

    LOGE("input stream unavailable after all attempts (deviceId=%d)", deviceId);
    mProbeState.store(ProbeState::Unavailable, std::memory_order_relaxed);
    return false;
}

void AudioEngine::startLatencyProbe() {
    if (!mInputStream) return;
    mProbeIsNote = false;
    mCandidateFrame = -1;
    mAboveCount = 0;
    mProbeState.store(ProbeState::Armed, std::memory_order_release);
}

void AudioEngine::startNoteProbe() {
    if (!mInputStream) return;
    mProbeIsNote = true;
    mCandidateFrame = -1;
    mAboveCount = 0;
    mProbeState.store(ProbeState::ArmedForNote, std::memory_order_release);
}

void AudioEngine::closeStream() {
    if (mRenderRun.exchange(false, std::memory_order_acq_rel)) {
        if (mRenderThread.joinable()) mRenderThread.join();
    }
    if (mInputStream) {
        mInputStream->stop();
        mInputStream->close();
        mInputStream.reset();
    }
    mProbeState.store(ProbeState::Unavailable, std::memory_order_relaxed);

    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
    mRunning.store(false, std::memory_order_release);
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mStreamLock);
    closeStream();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream,
                                                   void* audioData,
                                                   int32_t numFrames) {
    // Deliberately trivial: copy finished audio out of the ring. No synthesis, no
    // MIDI, no allocation, nothing that can block. This callback cannot be late.
    auto* out = static_cast<int16_t*>(audioData);

    const uint32_t read = mRingRead.load(std::memory_order_relaxed);
    const uint32_t write = mRingWrite.load(std::memory_order_acquire);
    const uint32_t availableFrames = write - read;          // unsigned wrap is fine
    const auto want = static_cast<uint32_t>(numFrames);
    const uint32_t take = std::min(availableFrames, want);

    for (uint32_t i = 0; i < take; ++i) {
        const uint32_t idx = ((read + i) & (kRingFrames - 1)) * 2;
        out[i * 2] = mRing[idx];
        out[i * 2 + 1] = mRing[idx + 1];
    }
    mRingRead.store(read + take, std::memory_order_release);

    if (take < want) {
        // The render thread did not keep up. Emit silence for the remainder --
        // audible, but bounded, and the counter says it happened.
        std::memset(out + take * 2, 0,
                    static_cast<size_t>(want - take) * 2 * sizeof(int16_t));
        mRingUnderruns.fetch_add(1, std::memory_order_relaxed);
    }

    const double blockSeconds = static_cast<double>(numFrames) / stream->getSampleRate();
    mRingFillMs.store(static_cast<float>(1000.0 * (availableFrames - take + 0.0) /
                                         stream->getSampleRate()),
                      std::memory_order_relaxed);
    (void)blockSeconds;
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::renderBlockIntoRing(int frames) {
    bagosynths::Schedule* schedule = mSchedule.load(std::memory_order_acquire);
    if (schedule == nullptr) return;

    // MIDI is consumed HERE, not in the callback: events are applied at render
    // time, which runs ahead of playback by the ring's reserve.
    if (schedule->midiInputSink != nullptr) {
        schedule->midiInputSink->clear();
        mMidi.poll(*schedule->midiInputSink);

        uint32_t read = mInjectRead.load(std::memory_order_relaxed);
        const uint32_t write = mInjectWrite.load(std::memory_order_acquire);
        while (read != write) {
            schedule->midiInputSink->push(mInjected[read % kInjectCapacity]);
            ++read;
        }
        mInjectRead.store(read, std::memory_order_release);

        if (mProbeState.load(std::memory_order_acquire) == ProbeState::ArmedForNote) {
            const bagosynths::MidiBuffer& in = *schedule->midiInputSink;
            for (int i = 0; i < in.size(); ++i) {
                if ((in[i].status & 0xF0u) == 0x90 && in[i].data2 > 0) {
                    mFramesSinceClick = 0;
                    mCandidateFrame = -1;
                    mAboveCount = 0;
                    mThunkFrame = -1;
                    mMaxPeakSinceTrigger = 0.0f;
                    mNoteThreshold = std::max(0.015f, mRecentPeak * 4.0f);
                    mThunkThreshold = std::max(0.004f, mRecentPeak * 1.8f);
                    mProbeState.store(ProbeState::Waiting, std::memory_order_release);
                    break;
                }
            }
        }
    }

    const int chunk = std::min(frames, mScratchFrames);
    schedule->run(chunk, mStream ? mStream->getSampleRate() : 44100.0);

    // Interleave, dither, and write into the ring.
    if (schedule->audioOutputSource != nullptr) {
        const bagosynths::AudioPort& src = *schedule->audioOutputSource;
        const float* l = src.channel(0);
        const float* r = src.width > 1 ? src.channel(1) : l;
        // Peak the block while it is being interleaved: the samples are already
        // in registers, so the meter is very nearly free. "Am I making signal?"
        // is a question a three-part rig raises constantly, and answering it on
        // screen beats answering it by listening to a PA at soundcheck.
        float peak = 0.0f;
        for (int i = 0; i < chunk; ++i) {
            const float sl = l[i];
            const float sr = r[i];
            mScratch[i * 2] = sl;
            mScratch[i * 2 + 1] = sr;
            peak = std::max(peak, std::max(std::fabs(sl), std::fabs(sr)));
        }
        // Decay, so the number falls back on its own rather than latching at the
        // loudest thing that ever happened. One block at 44100 is 2.9 ms, and
        // 0.90 per block puts the fall time at roughly 60 ms -- fast enough to
        // follow playing, slow enough to read.
        const float held = mOutputPeak.load(std::memory_order_relaxed) * 0.90f;
        mOutputPeak.store(std::max(peak, held), std::memory_order_relaxed);
    } else {
        mOutputPeak.store(0.0f, std::memory_order_relaxed);
        std::fill_n(mScratch.data(), static_cast<size_t>(chunk) * 2, 0.0f);
    }

    mDither.process(mScratch.data(), mDitherOut.data(), chunk * 2);
    runLatencyProbe(mDitherOut.data(), chunk);

    const uint32_t write = mRingWrite.load(std::memory_order_relaxed);
    for (int i = 0; i < chunk; ++i) {
        const uint32_t idx = ((write + i) & (kRingFrames - 1)) * 2;
        mRing[idx] = mDitherOut[i * 2];
        mRing[idx + 1] = mDitherOut[i * 2 + 1];
    }
    mRingWrite.store(write + static_cast<uint32_t>(chunk), std::memory_order_release);
}

void AudioEngine::renderLoop() {
    // Ordinary thread, raised priority. Denormal flushing is per-thread, and
    // this is now the thread that does the arithmetic.
    setpriority(PRIO_PROCESS, 0, -19);
    enableFlushToZero();

    int64_t seenUnderruns = mRingUnderruns.load(std::memory_order_relaxed);
    double lastChange = nowSeconds();

    while (mRenderRun.load(std::memory_order_acquire)) {
        // --- adapt the reserve -------------------------------------------
        const int64_t underruns = mRingUnderruns.load(std::memory_order_relaxed);
        int32_t target = mTargetFill.load(std::memory_order_relaxed);
        const double now = nowSeconds();

        if (underruns != seenUnderruns) {
            // It actually hurt. Deepen immediately -- latency is cheaper than a
            // dropout, and this is the only evidence that matters.
            seenUnderruns = underruns;
            target = std::min(target + kGrowFrames, kMaxFillFrames);
            mTargetFill.store(target, std::memory_order_relaxed);
            lastChange = now;
        } else if (now - lastChange > kShrinkAfterSeconds && target > kMinFillFrames) {
            // Calm for a while: give the latency back, a little at a time.
            target = std::max(target - kShrinkFrames, kMinFillFrames);
            mTargetFill.store(target, std::memory_order_relaxed);
            lastChange = now;
        }

        const uint32_t write = mRingWrite.load(std::memory_order_relaxed);
        const uint32_t read = mRingRead.load(std::memory_order_acquire);
        const uint32_t filled = write - read;

        if (filled < static_cast<uint32_t>(target)) {
            const double t0 = nowSeconds();
            renderBlockIntoRing(kRenderBlockFrames);
            const auto ms = static_cast<float>((nowSeconds() - t0) * 1000.0);
            if (ms > mRenderMaxMs.load(std::memory_order_relaxed)) {
                mRenderMaxMs.store(ms, std::memory_order_relaxed);
            }
        } else {
            // Reserve is full. Sleep briefly rather than spin: this thread must
            // not steal CPU from the callback.
            std::this_thread::sleep_for(std::chrono::microseconds(500));
        }
    }
}

void AudioEngine::runLatencyProbe(int16_t* out, int32_t numFrames) {
    if (!mInputStream) return;

    ProbeState state = mProbeState.load(std::memory_order_acquire);
    if (state == ProbeState::Unavailable) return;

    // Drain the input FULLY every callback. Reading only one output block's worth
    // is not enough: the input burst (96 frames) is larger than the output burst
    // (89), so a backlog builds and you end up analysing audio from seconds ago.
    // That is exactly what happened on the first attempt -- reported input latency
    // climbed past 3000 ms and the probe timed out against stale audio.
    const int32_t want = std::min(numFrames * 2, mScratchFrames);
    const int32_t ch = std::max(1, mInputChannels);
    // Click probe: floor of 0.01 (-40 dBFS) -- low enough for a click that has
    // crossed a room, high enough not to trigger on the room itself.
    //
    // Note probe: a much higher floor, because the previous note is still ringing
    // when the next one is played. Only an attack transient clears 0.08, so a
    // decaying tail cannot masquerade as the next onset. The cost is that notes
    // must be played with some conviction, which is what one wants to measure
    // anyway.
    const float threshold = mProbeIsNote ? mNoteThreshold
                                         : std::max(0.01f, mNoiseFloor * 6.0f);

    // Nothing within this window after note-on can be the note: the output path
    // alone is ~9 ms before any air is crossed. Anything detected here is the
    // key's own mechanical noise, recorded separately.
    const auto blankFrames =
            static_cast<int64_t>(0.007 * mInputStream->getSampleRate());

    float peak = 0.0f;
    int64_t firstHit = -1;          // frames since the clock started
    int32_t got = 0;

    // How long the level must stay up before an onset is believed. 3 ms rejects a
    // key thunk while not biasing the result: the time REPORTED is the first
    // crossing, not the confirmation.
    const auto sustainFrames =
            static_cast<int32_t>(0.003 * mInputStream->getSampleRate());

    for (int iter = 0; iter < 32; ++iter) {
        auto readResult = mInputStream->read(mInputBuffer.data(), want, 0);
        const int32_t n = readResult ? readResult.value() : 0;
        if (n <= 0) break;

        // Capture first: a plain memcpy into a preallocated buffer.
        if (mRecording.load(std::memory_order_relaxed)) {
            int32_t w = mRecordWrite.load(std::memory_order_relaxed);
            const int32_t room = static_cast<int32_t>(mRecordBuffer.size()) - w;
            const int32_t take = std::min(n, room);
            for (int32_t i = 0; i < take; ++i) mRecordBuffer[w + i] = mInputBuffer[i * ch];
            mRecordWrite.store(w + take, std::memory_order_release);
            if (take < n) mRecording.store(false, std::memory_order_release);
        }

        for (int32_t i = 0; i < n; ++i) {
            const float v = std::fabs(static_cast<float>(mInputBuffer[i * ch]) / 32768.0f);
            if (v > peak) peak = v;

            if (state != ProbeState::Waiting || firstHit >= 0) continue;

            const int64_t pos = mFramesSinceClick + got + i;   // frames since note-on

            if (mProbeIsNote && pos < blankFrames) {
                // Too early to be the sound. Record the key's mechanical noise.
                if (mThunkFrame < 0 && v > mThunkThreshold) mThunkFrame = pos;
                continue;
            }

            // Compare an ENVELOPE, never the raw sample. Any waveform crosses
            // zero twice per cycle, so "N consecutive samples above threshold"
            // is unachievable for real audio -- the synthetic click only passed
            // because a full-scale square never approaches zero.
            mEnvelope = std::max(v, mEnvelope * 0.9995f);

            if (mEnvelope > threshold) {
                if (mCandidateFrame < 0) mCandidateFrame = pos;
                if (++mAboveCount >= sustainFrames) firstHit = mCandidateFrame;
            } else {
                mCandidateFrame = -1;     // a transient, not the note
                mAboveCount = 0;
            }
        }
        got += n;
        if (n < want) break;        // input is drained
    }

    mInputPeak.store(peak, std::memory_order_relaxed);
    // Decays quickly, so "what is sounding right now" tracks a fading note
    // rather than remembering its attack.
    mRecentPeak = std::max(peak, mRecentPeak * 0.80f);

    switch (state) {
        case ProbeState::ArmedForNote:
        case ProbeState::Idle:
            // Track a slow-moving noise floor while idle, so the detection
            // threshold adapts to whatever is actually on the input.
            mNoiseFloor = mNoiseFloor * 0.95f + peak * 0.05f;
            break;

        case ProbeState::Armed: {
            // Anything read above was pre-click backlog; the count starts now.
            // Emit the click, overwriting this block. A short full-scale burst
            // is easy to detect and too brief to be unpleasant.
            const int32_t n = std::min(numFrames, kClickFrames);
            for (int32_t i = 0; i < n; ++i) {
                const int16_t v = (i % 4 < 2) ? 30000 : -30000;
                out[i * 2] = v;
                out[i * 2 + 1] = v;
            }
            mClickRemaining = kClickFrames - n;   // continue over following blocks
            mFramesSinceClick = 0;
            mProbeState.store(ProbeState::Waiting, std::memory_order_release);
            break;
        }

        case ProbeState::Waiting:
            if (peak > mMaxPeakSinceTrigger) mMaxPeakSinceTrigger = peak;
            // The click is longer than one 89-frame block, so finish emitting it.
            if (mClickRemaining > 0) {
                const int32_t n = std::min(numFrames, mClickRemaining);
                for (int32_t i = 0; i < n; ++i) {
                    const int16_t v = (i % 4 < 2) ? 30000 : -30000;
                    out[i * 2] = v;
                    out[i * 2 + 1] = v;
                }
                mClickRemaining -= n;
            }
            if (firstHit >= 0) {
                const double frames = static_cast<double>(firstHit);
                const auto ms = static_cast<float>(1000.0 * frames / mInputStream->getSampleRate());
                mRoundTripMs.store(ms, std::memory_order_relaxed);
                // The note probe re-arms itself, so a run of readings can be
                // collected by simply playing rather than tapping between notes.
                if (mProbeIsNote) {
                    mCandidateFrame = -1;
                    mAboveCount = 0;
                    mProbeState.store(ProbeState::ArmedForNote, std::memory_order_release);
                } else {
                    mProbeState.store(ProbeState::Done, std::memory_order_release);
                }
                // Logged so a run of trials can be collected and compared without
                // reading the number off the screen each time.
                const double thunkMs =
                        mThunkFrame < 0 ? -1.0
                                        : 1000.0 * static_cast<double>(mThunkFrame) /
                                                  mInputStream->getSampleRate();
                LOGI("PROBE %s=%.2f ms thunk=%.2f ms peak=%.4f thresh=%.4f",
                     mProbeIsNote ? "keyToTone" : "roundTrip", ms, thunkMs, peak, threshold);
            } else {
                mFramesSinceClick += got;
                if (mFramesSinceClick > kProbeTimeoutFrames) {
                    // Log what was actually heard: the usual cause of a failure
                    // is simply that the room is too quiet for the microphone,
                    // and the observed peak says so immediately.
                    LOGE("PROBE FAILED (%s) maxPeak=%.4f thresh=%.4f thunk=%lld",
                         mProbeIsNote ? "note" : "click", mMaxPeakSinceTrigger,
                         threshold, static_cast<long long>(mThunkFrame));
                    mProbeState.store(ProbeState::Failed, std::memory_order_release);
                }
            }
            break;

        default:
            break;
    }
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("stream error after close: %s", oboe::convertToText(error));
    mRunning.store(false, std::memory_order_release);

    // Disconnect happens for real reasons on a stage: the USB cable moved, the
    // device re-enumerated, the OS reclaimed the stream. Recover silently rather
    // than leaving the player with a dead instrument.
    //
    // But recover onto THE SAME PIANO. This used to reopen with an unspecified
    // device id, and an unspecified output on this tablet resolves to the
    // built-in speaker -- so a jogged USB cable mid-set would "recover" into the
    // tablet's own speaker, which the player cannot hear over a stage and the
    // audience does not hear at all. Silence is a better failure than that,
    // because silence is noticed.
    //
    // Retry the remembered id, briefly. A cable that was merely jogged comes
    // back with the same id within a second or so, and this catches that case
    // without involving the UI thread at all.
    //
    // A device that truly re-enumerated gets a NEW id, so no id we remember can
    // find it. That case belongs to Kotlin, which can ask AudioManager for the
    // device by NAME -- see the watchdog in MainActivity. This function
    // deliberately gives up rather than guessing.
    if (error != oboe::Result::ErrorDisconnected) return;
    if (mReconnecting.exchange(true, std::memory_order_acq_rel)) return;

    const int32_t deviceId = mOpenedDeviceId.load(std::memory_order_relaxed);
    std::thread([this, deviceId] {
        for (int attempt = 0; attempt < kReconnectAttempts; ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kReconnectDelayMs));

            // Someone else may have got there first: the UI watchdog can resolve
            // the piano by NAME, which is the only thing that works after a real
            // replug, and it is not going to wait for us. If it succeeded, stop
            // retrying -- otherwise the next attempt would close a good stream
            // and reopen the dead id we were handed.
            if (mRunning.load(std::memory_order_acquire)) {
                mReconnecting.store(false, std::memory_order_release);
                LOGI("recovery abandoned: already running again");
                return;
            }
            {
                std::lock_guard<std::mutex> lock(mStreamLock);
                closeStream();
                if (openStream(deviceId, mRequestedBursts.load(std::memory_order_relaxed)) &&
                    mStream->requestStart() == oboe::Result::OK) {
                    mRunning.store(true, std::memory_order_release);
                    mReconnecting.store(false, std::memory_order_release);
                    LOGI("stream recovered after disconnect, on device %d (attempt %d)",
                         deviceId, attempt + 1);
                    return;
                }
            }
        }
        // Out of attempts. Leave it stopped and SAY so: the Kotlin watchdog will
        // re-resolve the piano by name and start us again if it reappears.
        mReconnecting.store(false, std::memory_order_release);
        LOGE("could not recover device %d after %d attempts -- waiting for it to "
             "be found by name", deviceId, kReconnectAttempts);
    }).detach();
}

AudioEngine::Stats AudioEngine::stats() const {
    Stats s;
    s.running = mRunning.load(std::memory_order_acquire) ? 1 : 0;
    s.cpuLoadPercent = mCpuLoad.load(std::memory_order_relaxed);
    s.callbackMaxMs = mCallbackMaxMs.load(std::memory_order_relaxed);
    s.activeVoices = mSfizz ? mSfizz->activeVoices() : 0;
    s.outputPeak = mOutputPeak.load(std::memory_order_relaxed);
    s.deviceId = mActualDeviceId.load(std::memory_order_relaxed);
    s.loading = mLoading.load(std::memory_order_relaxed) ? 1 : 0;
    s.midiMessages = static_cast<int64_t>(mMidi.messageCount());

    auto stream = mStream;   // copy the shared_ptr: stop() may run concurrently
    if (stream) {
        s.burstFrames = stream->getFramesPerBurst();
        s.bufferFrames = stream->getBufferSizeInFrames();
        s.sampleRate = stream->getSampleRate();
        s.xRuns = stream->getXRunCount().value();
        s.sharingMode = static_cast<int32_t>(stream->getSharingMode());
        s.performanceMode = static_cast<int32_t>(stream->getPerformanceMode());
        s.usesAAudio = stream->usesAAudio() ? 1 : 0;

        auto latency = stream->calculateLatencyMillis();
        if (latency) s.latencyMs = static_cast<float>(latency.value());
    }

    s.ringUnderruns = mRingUnderruns.load(std::memory_order_relaxed);
    s.ringFillMs = mRingFillMs.load(std::memory_order_relaxed);
    s.renderMaxMs = mRenderMaxMs.load(std::memory_order_relaxed);
    s.ringTargetMs = static_cast<float>(1000.0 * mTargetFill.load(std::memory_order_relaxed) /
                                        (mStream ? mStream->getSampleRate() : 44100.0));
    s.probeState = static_cast<int32_t>(mProbeState.load(std::memory_order_acquire));
    s.roundTripMs = mRoundTripMs.load(std::memory_order_relaxed);
    s.inputPeak = mInputPeak.load(std::memory_order_relaxed);

    auto input = mInputStream;
    if (input) {
        auto inLatency = input->calculateLatencyMillis();
        if (inLatency) s.inputLatencyMs = static_cast<float>(inLatency.value());
    }
    return s;
}

void AudioEngine::resetPeaks() {
    mCallbackMaxMs.store(0.0f, std::memory_order_relaxed);

    // The render peak and the underrun count belong here too. They did not used
    // to be reset, which made the button quietly misleading: the figure on
    // screen stayed at whatever the worst moment since launch had been -- in
    // practice, engine start-up -- so it could never be used to answer "is this
    // passage expensive?". A peak you cannot re-arm is not a measurement.
    mRenderMaxMs.store(0.0f, std::memory_order_relaxed);
    mRingUnderruns.store(0, std::memory_order_relaxed);

    // Per-node peaks as well, so the profile log and this button agree.
    if (bagosynths::Schedule* s = mSchedule.load(std::memory_order_acquire)) {
        s->resetProfile();
    }
    if (mSurge != nullptr) mSurge->resetProfile();
    if (mDexed != nullptr) mDexed->resetProfile();
    if (mPlaits != nullptr) mPlaits->resetProfile();
    if (mFluid != nullptr) mFluid->resetProfile();
    if (mHammond != nullptr) mHammond->resetProfile();
    if (mSfizz != nullptr) mSfizz->resetProfile();
}


// ------------------------------------------------------------------- graph

bool AudioEngine::rebuildGraph(std::string* error) {
    // Off the audio thread. Build the default patch the first time round.
    if (mSfizz == nullptr) {
        auto midiIn = std::make_unique<bagosynths::MidiInNode>();
        auto sfizz = std::make_unique<bagosynths::SfizzNode>();
        auto hammond = std::make_unique<bagosynths::HammondNode>();
        auto surge = std::make_unique<bagosynths::SurgeNode>();
        auto dexed = std::make_unique<bagosynths::DexedNode>();
        auto plaits = std::make_unique<bagosynths::PlaitsNode>();
        auto fluid = std::make_unique<bagosynths::FluidNode>();
        auto reverb = std::make_unique<bagosynths::ReverbNode>();
        auto limiter = std::make_unique<bagosynths::LimiterNode>();
        auto out = std::make_unique<bagosynths::AudioOutNode>();

        mSfizz = sfizz.get();
        mHammond = hammond.get();
        mSurge = surge.get();
        mDexed = dexed.get();
        mPlaits = plaits.get();
        mFluid = fluid.get();
        mReverb = reverb.get();
        mLimiter = limiter.get();

        const bagosynths::NodeId idMidi = mGraph.addNode(std::move(midiIn));
        const bagosynths::NodeId idSfizz = mGraph.addNode(std::move(sfizz));
        const bagosynths::NodeId idHammond = mGraph.addNode(std::move(hammond));
        const bagosynths::NodeId idSurge = mGraph.addNode(std::move(surge));
        const bagosynths::NodeId idDexed = mGraph.addNode(std::move(dexed));
        const bagosynths::NodeId idPlaits = mGraph.addNode(std::move(plaits));
        const bagosynths::NodeId idFluid = mGraph.addNode(std::move(fluid));
        const bagosynths::NodeId idReverb = mGraph.addNode(std::move(reverb));
        const bagosynths::NodeId idLimiter = mGraph.addNode(std::move(limiter));
        const bagosynths::NodeId idOut = mGraph.addNode(std::move(out));

        // The default patch. Built in code; identical in every respect to what
        // the editor will later produce by hand.
        //
        //   MIDI In --+--> sfizz    --+
        //             |               |
        //             +--> Hammond  --+
        //             |               |
        //             +--> Surge XT --+--> Reverb --> Limiter --> Audio Out
        //             |               |
        //             +--> Dexed    --+
        //             |               |
        //             +--> Plaits   --+
        //             |               |
        //             +--> FluidSynth-+
        //
        // Every instrument sees every note and every one runs every block.
        // That is the pedalboard model taken at its word: parts are mixed, not
        // switched, so there is no gap, no voice stealing and no reload when
        // moving between them -- only a level change. It costs each engine's CPU
        // even when it is silent. Measurement has to keep up with that claim:
        // the idle render peak went 0.81 ms at three instruments to 1.30 ms at
        // four, against a 2.902 ms block, so it is affordable but it is NOT
        // free. See HANDOFF section 10d -- past a certain count this has to
        // become "skip the provably silent", and the number that decides when
        // is measured, not guessed.
        mGraph.connect(idMidi, 0, idSfizz, 0);
        mGraph.connect(idMidi, 0, idHammond, 0);
        mGraph.connect(idMidi, 0, idSurge, 0);
        mGraph.connect(idMidi, 0, idDexed, 0);
        mGraph.connect(idMidi, 0, idPlaits, 0);
        mGraph.connect(idMidi, 0, idFluid, 0);
        mGraph.connect(idSfizz, 0, idReverb, 0);
        mGraph.connect(idHammond, 0, idReverb, 0);
        mGraph.connect(idSurge, 0, idReverb, 0);
        mGraph.connect(idDexed, 0, idReverb, 0);
        mGraph.connect(idPlaits, 0, idReverb, 0);
        mGraph.connect(idFluid, 0, idReverb, 0);
        mGraph.connect(idReverb, 0, idLimiter, 0);
        mGraph.connect(idLimiter, 0, idOut, 0);

        mGraph.setMidiEntry(idMidi);
        mGraph.setAudioExit(idOut);

        // Everything but the piano starts silent: the app opens on the piano,
        // and each of the others is brought in with its own fader.
        mHammond->setParam(bagosynths::HammondNode::kLevel, 0.0f);
        mSurge->setParam(bagosynths::SurgeNode::kLevel, 0.0f);
        mDexed->setParam(bagosynths::DexedNode::kLevel, 0.0f);
        mPlaits->setParam(bagosynths::PlaitsNode::kLevel, 0.0f);
        mFluid->setParam(bagosynths::FluidNode::kLevel, 0.0f);
    }

    const double rate = mStream ? mStream->getSampleRate() : 44100.0;
    const int frames = mScratchFrames > 0 ? mScratchFrames : 2048;

    auto compiled = mGraph.compile(rate, frames, error);
    if (!compiled) return false;

    bagosynths::Schedule* fresh = compiled.get();
    mScheduleKeepAlive.push_back(std::move(compiled));

    // Publish. A single release store is the whole handover.
    mSchedule.store(fresh, std::memory_order_release);

    // Retire older schedules. The audio thread may still have been inside one
    // when we stored, so keep the previous generation alive for a moment rather
    // than freeing it immediately -- and never free from the callback.
    if (mScheduleKeepAlive.size() > 3) {
        mScheduleKeepAlive.erase(mScheduleKeepAlive.begin());
    }

    LOGI("graph compiled: %zu steps, %zu audio buffers, %d frames",
         fresh->steps.size(), fresh->audioBuffers.size(), frames);
    return true;
}

void AudioEngine::loadInstrumentAsync(const std::string& id, const std::string& path) {
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

void AudioEngine::initSurgeAsync(const std::string& dataPath, const std::string& homePath) {
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

void AudioEngine::loadSurgePatchAsync(int index) {
    if (mSurge == nullptr || !mSurge->ready()) return;
    if (mSurgeBusy.exchange(true, std::memory_order_acq_rel)) return;
    if (!runOnBigStack([this, index] {
            mSurge->loadPatch(index);
            mSurgeBusy.store(false, std::memory_order_release);
        })) {
        mSurgeBusy.store(false, std::memory_order_release);
    }
}

void AudioEngine::loadSurgePatchNamedAsync(const std::string& name) {
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

// FluidSynth. Building the synth is cheap and listing a directory is cheaper,
// but LOADING a font is neither: a 30 MB GM SoundFont is megabytes of samples,
// and an SF3 decodes every one of them from Ogg Vorbis on the way in. All of it
// therefore runs here, off both the audio and the UI thread.
//
// Init and the scan are one task rather than two, because a scan that finishes
// before the synth exists has nothing to load into.
void AudioEngine::initFluidAsync(const std::vector<std::string>& dirs) {
    if (mFluid == nullptr) return;
    if (mFluidBusy.exchange(true, std::memory_order_acq_rel)) return;

    const double rate = mStream ? mStream->getSampleRate() : 44100.0;
    if (!runOnBigStack([this, dirs, rate] {
            if (mFluid->init(rate)) {
                mFluid->scanSoundFonts(dirs);
                // Honour a restore that arrived before the scan did. Recording
                // the intent unconditionally and applying it here is the fix for
                // the bug FINDINGS 5b describes -- which this project has now
                // had in two engines, one of them silently.
                mFluid->applyRequestedSoundFont();
            }
            mFluidBusy.store(false, std::memory_order_release);
        })) {
        mFluidBusy.store(false, std::memory_order_release);
    }
}

void AudioEngine::loadSoundFontAsync(int index) {
    if (mFluid == nullptr || !mFluid->ready()) return;
    if (mFluidBusy.exchange(true, std::memory_order_acq_rel)) return;
    if (!runOnBigStack([this, index] {
            mFluid->loadSoundFont(index);
            mFluidBusy.store(false, std::memory_order_release);
        })) {
        mFluidBusy.store(false, std::memory_order_release);
    }
}

void AudioEngine::loadSoundFontNamedAsync(const std::string& name) {
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

void AudioEngine::loadDexedBanksAsync(const std::vector<std::string>& dirs) {
    if (mDexed == nullptr) return;
    if (mDexedBusy.exchange(true, std::memory_order_acq_rel)) return;
    if (!runOnBigStack([this, dirs] {
            mDexed->loadBanks(dirs);
            mDexedBusy.store(false, std::memory_order_release);
        })) {
        mDexedBusy.store(false, std::memory_order_release);
    }
}

std::string AudioEngine::loadedInstrument() const {
    return mSfizz ? mSfizz->loadedName() : std::string();
}

std::string AudioEngine::instrumentId() const {
    return mSfizz ? mSfizz->instrumentId() : std::string();
}

void AudioEngine::injectNote(int note, int velocity) {
    bagosynths::MidiEvent e;
    e.frame = 0;
    e.status = velocity > 0 ? 0x90 : 0x80;
    e.data1 = static_cast<uint8_t>(note);
    e.data2 = static_cast<uint8_t>(velocity);

    const uint32_t write = mInjectWrite.load(std::memory_order_relaxed);
    mInjected[write % kInjectCapacity] = e;
    mInjectWrite.store(write + 1, std::memory_order_release);
}

void AudioEngine::allNotesOff() {
    for (int ch = 0; ch < 16; ++ch) {
        bagosynths::MidiEvent e;
        e.frame = 0;
        e.status = static_cast<uint8_t>(0xB0 | ch);
        e.data1 = 123;   // all notes off
        e.data2 = 0;
        const uint32_t write = mInjectWrite.load(std::memory_order_relaxed);
        mInjected[write % kInjectCapacity] = e;
        mInjectWrite.store(write + 1, std::memory_order_release);
    }
}


// ----------------------------------------------------------------- capture

bool AudioEngine::startRecording(int seconds) {
    if (!mInputStream) return false;
    mRecordRate = mInputStream->getSampleRate();
    // Preallocated here, on the calling thread. The audio thread never resizes.
    mRecordBuffer.assign(static_cast<size_t>(mRecordRate) * std::max(1, seconds), 0);
    mRecordWrite.store(0, std::memory_order_release);
    mMarkCount.store(0, std::memory_order_release);
    mRecording.store(true, std::memory_order_release);
    LOGI("recording %d s at %d Hz", seconds, mRecordRate);
    return true;
}

float AudioEngine::recordedSeconds() const {
    return static_cast<float>(mRecordWrite.load(std::memory_order_relaxed)) /
           static_cast<float>(std::max(1, mRecordRate));
}

bool AudioEngine::writeRecording(const std::string& wavPath, const std::string& marksPath) {
    mRecording.store(false, std::memory_order_release);
    const int32_t frames = mRecordWrite.load(std::memory_order_acquire);
    if (frames <= 0) return false;

    FILE* f = fopen(wavPath.c_str(), "wb");
    if (f == nullptr) {
        LOGE("cannot write %s", wavPath.c_str());
        return false;
    }
    const uint32_t dataBytes = static_cast<uint32_t>(frames) * 2u;
    const uint32_t riff = 36u + dataBytes;
    const uint32_t rate = static_cast<uint32_t>(mRecordRate);
    const uint32_t byteRate = rate * 2u;
    auto w32 = [&](uint32_t v) { fwrite(&v, 4, 1, f); };
    auto w16 = [&](uint16_t v) { fwrite(&v, 2, 1, f); };
    fwrite("RIFF", 1, 4, f); w32(riff); fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f); w32(16); w16(1); w16(1);
    w32(rate); w32(byteRate); w16(2); w16(16);
    fwrite("data", 1, 4, f); w32(dataBytes);
    fwrite(mRecordBuffer.data(), 2, static_cast<size_t>(frames), f);
    fclose(f);

    FILE* m = fopen(marksPath.c_str(), "w");
    if (m != nullptr) {
        const int32_t count = mMarkCount.load(std::memory_order_acquire);
        fprintf(m, "# sampleRate %d\n# frames %d\n# noteOn frame positions\n", mRecordRate, frames);
        for (int32_t i = 0; i < count; ++i) fprintf(m, "%d\n", mMarks[i]);
        fclose(m);
        LOGI("recording written: %d frames, %d marks", frames, count);
    }
    return true;
}


void AudioEngine::stressChord(int count) {
    // A cluster in a single block is the worst case a pair of forearms produces.
    //
    // It SWEEPS across the keyboard rather than repeating the same notes: a test
    // that replays one cluster keeps those samples resident and measures nothing
    // about a 1.2 GB library. Real playing touches a fresh sample almost every
    // note, and that is where the spikes live.
    static int sweep = 21;
    for (int i = 0; i < count; ++i) {
        int note = 21 + ((sweep + i * 3) % 87);
        injectNote(note, 40 + ((sweep * 7 + i * 11) % 80));   // vary velocity too
    }
    sweep = (sweep + 17) % 87;
}

void AudioEngine::logProfile() {
    bagosynths::Schedule* sched = mSchedule.load(std::memory_order_acquire);
    if (sched == nullptr) return;
    for (size_t i = 0; i < sched->steps.size(); ++i) {
        LOGI("PROFILE %-12s last=%.3f ms  max=%.3f ms",
             sched->steps[i].node->typeName(),
             i < sched->stepLastMs.size() ? sched->stepLastMs[i] : 0.0f,
             i < sched->stepMaxMs.size() ? sched->stepMaxMs[i] : 0.0f);
    }
    if (mSfizz != nullptr) {
        LOGI("PROFILE   sfizz split: noteOn max=%.3f ms, render max=%.3f ms, "
             "started=%d queued=%d dropped=%d",
             mSfizz->maxNoteMs(), mSfizz->maxRenderMs(), mSfizz->lastNoteOns(),
             mSfizz->pendingNoteOns(), mSfizz->pendingDropped());
        mSfizz->resetProfile();
    }
    LOGI("PROFILE block deadline = %.3f ms, xruns = %d",
         mStream ? 1000.0 * mStream->getFramesPerBurst() / mStream->getSampleRate() : 0.0,
         mStream ? mStream->getXRunCount().value() : -1);
    sched->resetProfile();
}
