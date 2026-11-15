#include "LatencyProbe.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <thread>

#define LOG_TAG "rigalong-audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

void LatencyProbe::retire() {
    // run() publishes that it is inside and THEN reads the state; this stores
    // the state and THEN looks for run(). One of the two sees the other, so
    // after the wait nothing is inside the stream, and run() changes the state
    // only by compare-and-swap from what it read, so it cannot undo this.
    mState.store(State::Unavailable, std::memory_order_seq_cst);
    while (mInRun.load(std::memory_order_seq_cst)) std::this_thread::yield();
}

void LatencyProbe::close() {
    retire();
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
    mDeviceId = -1;
}

bool LatencyProbe::open(int32_t deviceId) {
    // Already up on this device? Then leave it ALONE.
    //
    // Reopening was not harmless, which is how this was found. The probe times
    // out after half a second of INPUT frames, and a freshly opened AAudio
    // capture stream needs about that long before it delivers anything but
    // zeros -- so every measurement that reopened the stream first measured a
    // stream that was not yet running, and reported "PROBE FAILED maxPeak=
    // 0.0000". Tapping MEASURE twice in a row did it; so did every scripted
    // probe, since each one enabled the input again on its way in.
    if (mStream != nullptr && mDeviceId == deviceId) return true;
    close();

    // Try progressively more relaxed configurations. The output side can demand
    // exclusive/44100/I16 because the CP-88 grants it; the capture side turns out
    // to be fussier, and for a measurement stream correctness matters far more
    // than squeezing out the last millisecond.
    struct Attempt {
        const char* name;
        oboe::SharingMode sharing;
        bool pinFormat;     // demand I16 / stereo exactly
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

        // No data callback: the input is drained from the render side, so the
        // two directions stay locked and frame counting stays honest.
        const oboe::Result opened = builder.openStream(mStream);
        if (opened != oboe::Result::OK || !mStream) {
            LOGE("input open [%s] failed: %s", a.name, oboe::convertToText(opened));
            mStream.reset();
            continue;
        }

        const oboe::Result started = mStream->requestStart();
        if (started != oboe::Result::OK) {
            LOGE("input start [%s] failed: %s", a.name, oboe::convertToText(started));
            mStream->close();
            mStream.reset();
            continue;
        }

        // CHECK WHAT CAME BACK, exactly as the output side does. Asking for an
        // input device that cannot serve the request does NOT fail: AAudio
        // falls back to the default input -- the built-in microphone -- and
        // returns OK, so `open=true` on a device id means nothing by itself.
        //
        // Found on 2026-09-25 while testing whether the CP-88 loops host
        // playback back into its capture endpoint. open(6692) returned true,
        // logged a fresh stream, and was still the microphone: sending the
        // output to the phone's own speaker took the probe's peak from 0,015
        // to 0,66, which only a microphone a few centimetres from that speaker
        // can do. Without this check the measurement would have been read as
        // "the CP-88 loops back, quietly".
        const int32_t channels = mStream->getChannelCount();
        const int32_t gotIn = mStream->getDeviceId();
        LOGI("input open [%s]: rate=%d ch=%d burst=%d sharing=%d perf=%d dev=%d (asked %d)",
             a.name, mStream->getSampleRate(), channels,
             mStream->getFramesPerBurst(),
             static_cast<int>(mStream->getSharingMode()),
             static_cast<int>(mStream->getPerformanceMode()),
             gotIn, deviceId);
        if (deviceId != oboe::kUnspecified && gotIn != deviceId) {
            LOGE("input: asked for device %d and got %d -- refusing it",
                 deviceId, gotIn);
            mStream->stop();
            mStream->close();
            mStream.reset();
            continue;
        }

        mChannels = std::max(1, channels);
        mBuffer.assign(static_cast<size_t>(kBufferFrames) * mChannels, 0);
        mNoiseFloor = 0.0f;
        mDeviceId = deviceId;
        mState.store(State::Idle, std::memory_order_release);
        return true;
    }

    LOGE("input stream unavailable after all attempts (deviceId=%d)", deviceId);
    return false;
}

float LatencyProbe::inputLatencyMs() const {
    if (state() == State::Unavailable) return 0.0f;
    auto input = mStream;
    if (!input) return 0.0f;
    auto latency = input->calculateLatencyMillis();
    return latency ? static_cast<float>(latency.value()) : 0.0f;
}

void LatencyProbe::start() {
    if (state() == State::Unavailable) return;
    // THE ENVELOPE MUST BE CLEARED WHEN ARMING, and forgetting it produced a
    // false POSITIVE rather than a miss, which is the dangerous direction.
    //
    // The envelope decays at 0.9995 per sample -- about 90 ms to fall by half
    // at 44,1 kHz. Arming a second probe while the previous click's envelope
    // was still above the threshold made the detector fire on the first sample
    // it looked at, and the result was reported as a perfectly good
    // measurement: "PROBE roundTrip=0.00 ms peak=0.0000". A success line
    // carrying no sound at all.
    //
    // Found while building an acoustic oracle for THE LATCH, where it mattered
    // more than usual: the probe was the only way to tell a latched rig from a
    // healthy one, and it was scoring silence as OK on every second run.
    mEnvelope = 0.0f;
    mCandidateFrame = -1;
    mAboveCount = 0;
    mMaxPeakSinceClick = 0.0f;
    State s = state();
    while (s != State::Unavailable &&
           !mState.compare_exchange_weak(s, State::Armed, std::memory_order_acq_rel)) {}
}

void LatencyProbe::emitClick(float* out, int32_t frames) {
    // A short full-scale burst is easy to detect and too brief to be unpleasant.
    for (int32_t i = 0; i < frames; ++i) {
        const float v = (i % 4 < 2) ? 0.9155f : -0.9155f;
        out[i * 2] = v;
        out[i * 2 + 1] = v;
    }
}

void LatencyProbe::run(float* out, int32_t numFrames) {
    mInRun.store(true, std::memory_order_seq_cst);
    struct Leave {
        std::atomic<bool>& inRun;
        ~Leave() { inRun.store(false, std::memory_order_release); }
    } leave{mInRun};
    State state = mState.load(std::memory_order_seq_cst);
    if (state == State::Unavailable) return;
    oboe::AudioStream* const input = mStream.get();
    if (input == nullptr) return;

    // Drain the input FULLY every block. Reading only one output block's worth
    // is not enough: the input burst (96 frames) is larger than the output burst
    // (89), so a backlog builds and you end up analysing audio from seconds ago.
    // That is exactly what happened on the first attempt -- reported input latency
    // climbed past 3000 ms and the probe timed out against stale audio.
    const int32_t want = std::min(numFrames * 2, kBufferFrames);
    const int32_t ch = mChannels;
    // A floor of 0.01 (-40 dBFS): low enough for a click that has crossed a
    // room, high enough not to trigger on the room itself.
    const float threshold = std::max(0.01f, mNoiseFloor * 6.0f);

    float peak = 0.0f;
    int64_t firstHit = -1;          // frames since the click went out
    int32_t got = 0;

    // How long the level must stay up before an onset is believed. 3 ms rejects
    // a transient while not biasing the result: the time REPORTED is the first
    // crossing, not the confirmation.
    const auto sustainFrames = static_cast<int32_t>(0.003 * input->getSampleRate());

    for (int iter = 0; iter < 32; ++iter) {
        auto readResult = input->read(mBuffer.data(), want, 0);
        const int32_t n = readResult ? readResult.value() : 0;
        if (n <= 0) break;

        for (int32_t i = 0; i < n; ++i) {
            const float v = std::fabs(static_cast<float>(mBuffer[i * ch]) / 32768.0f);
            if (v > peak) peak = v;

            if (state != State::Waiting || firstHit >= 0) continue;

            const int64_t pos = mFramesSinceClick + got + i;

            // Compare an ENVELOPE, never the raw sample. Any waveform crosses
            // zero twice per cycle, so "N consecutive samples above threshold"
            // is unachievable for real audio -- the synthetic click only passed
            // because a full-scale square never approaches zero.
            mEnvelope = std::max(v, mEnvelope * 0.9995f);

            if (mEnvelope > threshold) {
                if (mCandidateFrame < 0) mCandidateFrame = pos;
                if (++mAboveCount >= sustainFrames) firstHit = mCandidateFrame;
            } else {
                mCandidateFrame = -1;     // a transient, not the click
                mAboveCount = 0;
            }
        }
        got += n;
        if (n < want) break;        // input is drained
    }

    mInputPeak.store(peak, std::memory_order_relaxed);

    // Every change of state from here is from the one read above: a start() or
    // a close() that came in meanwhile wins.
    switch (state) {
        case State::Idle:
            // Track a slow-moving noise floor while idle, so the detection
            // threshold adapts to whatever is actually on the input.
            mNoiseFloor = mNoiseFloor * 0.95f + peak * 0.05f;
            break;

        case State::Armed: {
            // Anything read above was pre-click backlog; the count starts now.
            // Emit the click, overwriting this block.
            const int32_t n = std::min(numFrames, kClickFrames);
            emitClick(out, n);
            mClickRemaining = kClickFrames - n;   // continue over following blocks
            mFramesSinceClick = 0;
            mState.compare_exchange_strong(state, State::Waiting, std::memory_order_acq_rel);
            break;
        }

        case State::Waiting:
            if (peak > mMaxPeakSinceClick) mMaxPeakSinceClick = peak;
            // The click is longer than one block, so finish emitting it.
            if (mClickRemaining > 0) {
                const int32_t n = std::min(numFrames, mClickRemaining);
                emitClick(out, n);
                mClickRemaining -= n;
            }
            if (firstHit >= 0) {
                const auto ms = static_cast<float>(1000.0 * static_cast<double>(firstHit) /
                                                   input->getSampleRate());
                mRoundTripMs.store(ms, std::memory_order_relaxed);
                mState.compare_exchange_strong(state, State::Done, std::memory_order_acq_rel);
                // Logged so a run of trials can be collected and compared without
                // reading the number off the screen each time.
                LOGI("PROBE roundTrip=%.2f ms peak=%.4f thresh=%.4f", ms, peak, threshold);
            } else {
                mFramesSinceClick += got;
                if (mFramesSinceClick > kTimeoutFrames) {
                    // Log what was actually heard: the usual cause of a failure
                    // is simply that the room is too quiet for the microphone,
                    // and the observed peak says so immediately.
                    LOGE("PROBE FAILED (click) maxPeak=%.4f thresh=%.4f",
                         mMaxPeakSinceClick, threshold);
                    mState.compare_exchange_strong(state, State::Failed, std::memory_order_acq_rel);
                }
            }
            break;

        default:
            break;
    }
}
