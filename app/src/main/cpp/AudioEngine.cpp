#include "AudioEngine.h"

#include <chrono>

#include "RealtimePriority.h"

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

#include <bagosynths/Denormals.h>
#include <bagosynths/SampleFormat.h>

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

    // TWO ATTEMPTS: strictly first, then letting Oboe negotiate. Only a device
    // that would otherwise fail to open ever reaches the second, so the piano's
    // measurements are untouched.
    //
    // This is interim. Once the graph editor exists the output is a property of
    // the patch and none of it is hardcoded.
    oboe::Result result = oboe::Result::ErrorInternal;
    bool relaxed = false;

    for (int attempt = 0; attempt < 2; ++attempt) {
        relaxed = (attempt == 1);
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(mWantExclusive.load(std::memory_order_relaxed)
                                         ? oboe::SharingMode::Exclusive
                                         : oboe::SharingMode::Shared)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                // Game, not Media: this is interactive audio, not a media
                // session. Verified to keep MMAP/EXCLUSIVE on this device.
                ->setUsage(oboe::Usage::Game)
                ->setContentType(oboe::ContentType::Music)
                ->setSpatializationBehavior(oboe::SpatializationBehavior::Never)
                ->setDeviceId(deviceId)
                ->setDataCallback(this)
                ->setErrorCallback(this);

        // The FORMAT is asked for as I16 because the render ring is int16 and
        // the dither that fills it is deliberate. The two attempts differ only
        // in whether a device that cannot do I16 is allowed AAudio's conversion.
        //
        // Whatever rate comes back is handed to Graph::compile(), and every node
        // takes the rate in prepare(), so the whole instrument follows -- sfizz
        // included, which resamples its own content and therefore needs no
        // separate 48 kHz Salamander.
        // Ask the DEVICE's format, as with the rate. The CP-88 declares 24-bit
        // packed; asking it for I16 had the HAL pad eight bits away. The ring
        // is float now, so any of these costs one conversion in the callback
        // and no loss on our side.
        const int32_t wantFmt = mPreferredFormat.load(std::memory_order_relaxed);
        b.setFormat(wantFmt > 0 ? static_cast<oboe::AudioFormat>(wantFmt)
                                : oboe::AudioFormat::I16)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
                ->setFormatConversionAllowed(relaxed);

        // Ask for the rate the DEVICE ITSELF declares, which Kotlin reads from
        // AudioDeviceInfo. Leaving it unspecified is not the same thing and was
        // a mistake: AAudio handed back 48000 for the CP-88, which declares
        // 44100 and nothing else, and the stream opened happily with the HAL
        // resampling underneath. Neither setSampleRateConversionQuality(None)
        // nor setFormatConversionAllowed(false) prevents that -- both govern
        // OBOE's own conversion layer, not the HAL's.
        const int32_t want = mPreferredRate.load(std::memory_order_relaxed);
        if (want > 0) b.setSampleRate(want);

        result = b.openStream(mStream);
        if (result == oboe::Result::OK && mStream) break;

        if (!relaxed) {
            LOGI("strict open refused (%s) -- retrying and letting the device choose "
                 "its own rate and format", oboe::convertToText(result));
        }
    }

    if (result != oboe::Result::OK || !mStream) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        return false;
    }

    // Cache what was granted. The callback must not ask the stream on every
    // block, and it needs the frame size to fill an underrun correctly.
    mStreamFormat = mStream->getFormat();
    switch (mStreamFormat) {
        case oboe::AudioFormat::I16:   mBytesPerFrame = 2 * 2; break;
        case oboe::AudioFormat::I24:   mBytesPerFrame = 3 * 2; break;
        case oboe::AudioFormat::I32:
        case oboe::AudioFormat::Float: mBytesPerFrame = 4 * 2; break;
        default:                       mBytesPerFrame = 2 * 2; break;
    }
    LOGI("format granted: %d (%d bytes per frame), asked for %d",
         static_cast<int>(mStreamFormat), mBytesPerFrame,
         mPreferredFormat.load(std::memory_order_relaxed));

    if (relaxed) {
        LOGI("opened on the device's own terms: rate=%d format=%d channels=%d",
             mStream->getSampleRate(), static_cast<int>(mStream->getFormat()),
             mStream->getChannelCount());
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

    prepareRing(burst);

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

void AudioEngine::prepareRing(int32_t burst) {
    // Preallocate render scratch with generous headroom. Allocating inside the
    // callback is the cardinal sin; this is the one place it is allowed.
    // The master ramp needs the rate to know how long 15 ms is, and this is
    // the one place that runs off the audio thread whenever the rate can have
    // changed. It starts AT the current gain, so opening a stream does not
    // fade the instrument in.
    mMasterRamp.prepare(currentRate(), mMasterGain.load(std::memory_order_relaxed));

    mScratchFrames = std::max(2048, burst * 16);
    mScratch.assign(static_cast<size_t>(mScratchFrames) * 2, 0.0f);
    mDitherOut.assign(static_cast<size_t>(mScratchFrames) * 2, 0);
    mRing.assign(static_cast<size_t>(kRingFrames) * 2, 0.0f);
    mCbScratch.assign(static_cast<size_t>(mScratchFrames) * 2, 0.0f);
    mRingWrite.store(0, std::memory_order_relaxed);
    mRingRead.store(0, std::memory_order_relaxed);
    // The ring's floor and ceiling are RELATIVE TO THE BURST, not constants.
    //
    // The constants were chosen against the CP-88's 89-frame burst, where a
    // 192-frame floor is a comfortable two bursts. Without the piano the app
    // opens the tablet's own speaker, which is SHARED, not MMAP, and hands the
    // callback 882 frames at a time -- so a 192-frame floor is less than a
    // QUARTER of one callback, the ring is drained to empty every time, and the
    // callback zero-fills the remainder. Measured 2026-09-21 with no CP-88
    // attached: ringUnder climbing 52 per second, which is exactly the callback
    // rate, and choppy sound.
    //
    // A floor of one and a half bursts restores the original intent on any
    // device. The CP-88 path is untouched by construction: 89 + 44 = 133, below
    // the 192-frame constant, so it still gets 192 and every measurement taken
    // on it still stands.
    const int32_t minFill = std::min(std::max(kMinFillFrames, burst + burst / 2),
                                     kRingFrames / 2);
    mMinFill.store(minFill, std::memory_order_relaxed);
    mMaxFill.store(std::min(std::max(kMaxFillFrames, minFill + kFillHeadroomFrames),
                            kRingFrames / 2), std::memory_order_relaxed);
    mTargetFill.store(minFill, std::memory_order_relaxed);
}

double AudioEngine::currentRate() const {
    if (mUsbActive.load(std::memory_order_acquire)) {
        const int32_t r = mUsbRate.load(std::memory_order_relaxed);
        if (r > 0) return r;
    }
    return mStream ? mStream->getSampleRate() : 44100.0;
}

void AudioEngine::setMasterDb(float db) {
    const float clamped = std::clamp(db, kMasterMinDb, kMasterMaxDb);
    mMasterDb.store(clamped, std::memory_order_relaxed);
    // The bottom of the range is silence rather than -40 dB of signal, so the
    // slider has a real "off" at its left end.
    const float gain = clamped <= kMasterMinDb ? 0.0f : std::pow(10.0f, clamped / 20.0f);
    mMasterGain.store(gain, std::memory_order_relaxed);
    LOGI("master volume %.1f dB (gain %.4f)", static_cast<double>(clamped),
         static_cast<double>(gain));
}

void AudioEngine::prepareGraph() {
    std::lock_guard<std::mutex> lock(mStreamLock);
    if (mSchedule.load(std::memory_order_acquire) != nullptr) return;
    std::string graphError;
    if (!rebuildGraph(&graphError)) LOGE("graph compile failed: %s", graphError.c_str());
}

bool AudioEngine::startUsb(const UsbAudioOut::Config& config, std::string* error) {
    std::lock_guard<std::mutex> lock(mStreamLock);
    if (mRunning.load(std::memory_order_acquire)) closeStream();

    mUsbRate.store(config.rate, std::memory_order_relaxed);
    mUsbActive.store(true, std::memory_order_release);
    mActualDeviceId.store(kUsbDeviceId, std::memory_order_relaxed);
    mOpenedDeviceId.store(kUsbDeviceId, std::memory_order_relaxed);

    // Size the ring for what the USB sink takes per call: one packet, a
    // millisecond on the CP-88 -- the burst the ring's floor is relative to.
    // The floor constant (192 frames) still governs there, so the reserve on
    // this path is the same as on the AAudio path, and the latency comparison
    // between the two is like for like.
    const int32_t packetFrames = std::max(1, static_cast<int32_t>(config.rate / 1000));
    prepareRing(packetFrames);

    // The endpoint's own layout, so the render thread can write it directly.
    mUsbChannels = std::max(1, config.channels);
    mUsbSubslot = std::clamp(config.subslotBytes, 2, 4);
    mUsbFrameBytes = mUsbChannels * mUsbSubslot;
    mByteRing.assign(static_cast<size_t>(kRingFrames) * mUsbFrameBytes, 0);
    mPackScratch.assign(static_cast<size_t>(kRenderBlockFrames) * mUsbChannels, 0.0f);
    mPackI16.assign(static_cast<size_t>(kRenderBlockFrames) * mUsbChannels, 0);
    mFlushDenormalsDone.store(false, std::memory_order_relaxed);
    mCallbackMaxMs.store(0.0f, std::memory_order_relaxed);

    std::string graphError;
    if (!rebuildGraph(&graphError)) LOGE("graph compile failed: %s", graphError.c_str());

    // The render thread first, so the ring is not empty when the first OUT
    // packets are sized.
    mRunning.store(true, std::memory_order_release);
    mRenderRun.store(true, std::memory_order_release);
    mRenderThread = std::thread([this] { renderLoop(); });

    if (!mUsbOut.start(config, &AudioEngine::pullBytesForUsb,
                       &AudioEngine::flushBytesForUsb, this, error)) {
        closeStream();
        return false;
    }
    LOGI("direct USB output running: %d Hz, %d ms", config.rate, config.bufferMs);
    return true;
}

uint32_t AudioEngine::pullFromRing(float* dst, uint32_t want) {
    const uint32_t read = mRingRead.load(std::memory_order_relaxed);
    const uint32_t write = mRingWrite.load(std::memory_order_acquire);
    const uint32_t availableFrames = write - read;          // unsigned wrap is fine
    // Clamped to the scratch as well as to what the ring holds. A request
    // larger than the scratch would otherwise run off the end of it; clamping
    // reports an underrun and leaves silence for the remainder, which is a
    // bounded fault rather than a buffer overrun.
    const uint32_t take = std::min({availableFrames, want,
                                    static_cast<uint32_t>(mScratchFrames)});
    for (uint32_t i = 0; i < take; ++i) {
        const uint32_t idx = ((read + i) & (kRingFrames - 1)) * 2;
        dst[i * 2] = mRing[idx];
        dst[i * 2 + 1] = mRing[idx + 1];
    }
    mRingRead.store(read + take, std::memory_order_release);

    // Peak of what is ACTUALLY handed to the device, measured here rather than
    // on the float graph output. See onAudioReady for why that matters.
    float cbNow = 0.0f;
    for (uint32_t i = 0; i < take * 2; ++i) cbNow = std::max(cbNow, std::fabs(dst[i]));
    const float cbHeld = mCallbackPeak.load(std::memory_order_relaxed) * 0.90f;
    mCallbackPeak.store(std::max(cbNow, cbHeld), std::memory_order_relaxed);

    if (take < want) {
        mRingUnderruns.fetch_add(1, std::memory_order_relaxed);

        // CONCEAL rather than zero-fill. See the note on mConcealedFrames.
        //
        // The source is the ring ITSELF, read behind the play position: the
        // writer is ahead of the reader, so the frames just consumed are still
        // there and need no separate history buffer. One period back, looped.
        //
        // Three things make it sound like a covered gap rather than a fault:
        // a short crossfade at the splice so the join is not a step, a decay
        // to silence over ~40 ms so a long stall does not drone, and a counter
        // that persists across calls so a stall spanning many pulls keeps
        // fading instead of restarting at full level each time.
        // Bounded by the scratch exactly as `take` is, because that clamp is
        // what stops a request larger than the buffer running off the end of
        // it. Concealment must not be the thing that reintroduces that.
        const uint32_t safe = std::min(want, static_cast<uint32_t>(mScratchFrames));
        const uint32_t missing = safe > take ? safe - take : 0;
        if (!mConcealOn.load(std::memory_order_relaxed)) {
            // The old behaviour, kept switchable: a hard jump to zero.
            std::fill_n(dst + take * 2, missing * 2, 0.0f);
            mConcealedFrames = 0;
            mRingFillMs.store(0.0f, std::memory_order_relaxed);
            return safe;
        }
        const uint32_t base = read + take;      // where the hole begins
        for (uint32_t i = 0; i < missing; ++i) {
            const uint32_t age = mConcealedFrames + i;
            float g = 1.0f - static_cast<float>(age) / kConcealFadeFrames;
            if (g < 0.0f) g = 0.0f;
            const uint32_t phase = age % kConcealPeriodFrames;
            const uint32_t back = kConcealPeriodFrames - phase;
            const uint32_t idx = ((base + i - back) & (kRingFrames - 1)) * 2;
            float l = mRing[idx];
            float r = mRing[idx + 1];
            // The loop SEAM needs the same treatment as the splice. Repeating a
            // fixed period of pitched audio puts a waveform discontinuity at
            // every wrap; blending the last frames of the period into the first
            // removes it, at the cost of a little smearing nobody will hear
            // under a fade this short.
            if (phase >= kConcealPeriodFrames - kConcealXfadeFrames) {
                const float w = static_cast<float>(kConcealPeriodFrames - phase) /
                                kConcealXfadeFrames;
                const uint32_t nIdx =
                        ((base + i - back + kConcealPeriodFrames) & (kRingFrames - 1)) * 2;
                l = l * w + mRing[nIdx] * (1.0f - w);
                r = r * w + mRing[nIdx + 1] * (1.0f - w);
            }
            l *= g;
            r *= g;
            // Crossfade the very first frames of a NEW hole against the audio
            // that really was delivered, so the splice is not a step.
            if (mConcealedFrames == 0 && i < kConcealXfadeFrames && take > 0) {
                const float w = static_cast<float>(i) / kConcealXfadeFrames;
                const uint32_t lastIdx = ((base - 1) & (kRingFrames - 1)) * 2;
                l = mRing[lastIdx] * (1.0f - w) + l * w;
                r = mRing[lastIdx + 1] * (1.0f - w) + r * w;
            }
            dst[(take + i) * 2] = l;
            dst[(take + i) * 2 + 1] = r;
        }
        mConcealedFrames += missing;
        mConcealedTotal.fetch_add(missing, std::memory_order_relaxed);
        // Everything that fits is now in dst, concealed or real.
        for (uint32_t i = take * 2; i < safe * 2; ++i) {
            cbNow = std::max(cbNow, std::fabs(dst[i]));
        }
        mCallbackPeak.store(std::max(cbNow,
                                     mCallbackPeak.load(std::memory_order_relaxed)),
                            std::memory_order_relaxed);
        mRingFillMs.store(0.0f, std::memory_order_relaxed);
        return safe;
    }
    mConcealedFrames = 0;
    mRingFillMs.store(static_cast<float>(1000.0 * (availableFrames - take) / currentRate()),
                      std::memory_order_relaxed);
    return take;
}

int AudioEngine::pullForUsb(void* ctx, float* dst, int frames) {
    auto* self = static_cast<AudioEngine*>(ctx);
    return static_cast<int>(self->pullFromRing(dst, static_cast<uint32_t>(std::max(0, frames))));
}

bool AudioEngine::openInput(int32_t deviceId) {
    // Already up on this device? Then leave it ALONE.
    //
    // Reopening was not harmless, which is how this was found. The probe times
    // out after half a second of INPUT frames, and a freshly opened AAudio
    // capture stream needs about that long before it delivers anything but
    // zeros -- so every measurement that reopened the stream first measured a
    // stream that was not yet running, and reported "PROBE FAILED maxPeak=
    // 0.0000". Tapping MEASURE twice in a row did it; so did every scripted
    // probe, since each one enabled the input again on its way in.
    if (mInputStream != nullptr && mInputDeviceId == deviceId) return true;

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
        mInputDeviceId = deviceId;
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

// mEnvelope MUST be cleared when arming, and forgetting it produced a false
// POSITIVE rather than a miss, which is the dangerous direction.
//
// The envelope is a member that decays at 0.9995 per sample -- about 90 ms to
// fall by half at 44,1 kHz. Arming a second probe while the previous click's
// envelope was still above the threshold made the detector fire on the first
// sample it looked at, and the result was reported as a perfectly good
// measurement: "PROBE roundTrip=0.00 ms peak=0.0000". A success line carrying
// no sound at all.
//
// Found while building an acoustic oracle for THE LATCH, where it mattered
// more than usual: the probe was the only way to tell a latched rig from a
// healthy one, and it was scoring silence as OK on every second run.
static void armProbeState(float& envelope, int64_t& candidate, int32_t& above) {
    envelope = 0.0f;
    candidate = -1;
    above = 0;
}

void AudioEngine::startLatencyProbe() {
    if (!mInputStream) return;
    mProbeIsNote = false;
    armProbeState(mEnvelope, mCandidateFrame, mAboveCount);
    mMaxPeakSinceTrigger = 0.0f;
    mProbeState.store(ProbeState::Armed, std::memory_order_release);
}

void AudioEngine::startNoteProbe() {
    if (!mInputStream) return;
    mProbeIsNote = true;
    armProbeState(mEnvelope, mCandidateFrame, mAboveCount);
    mMaxPeakSinceTrigger = 0.0f;
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
    // The USB sink AFTER the render thread has stopped, so nothing is pulling
    // from the ring while it is torn down.
    if (mUsbActive.exchange(false, std::memory_order_acq_rel)) {
        mUsbOut.stop();
        mActualDeviceId.store(kUnspecifiedDevice, std::memory_order_relaxed);
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
    // Copy finished audio out of the ring and convert it to whatever the device
    // asked for. No synthesis, no MIDI, no allocation, nothing that can block.
    //
    // The conversion lives here rather than in the render thread because this is
    // the only place that knows the granted format, and because doing it at the
    // last moment keeps the ring at the engine's own precision. It costs one
    // pass over a burst -- 96 frames on the CP-88 -- which is arithmetic on data
    // already in cache.
    const auto want = static_cast<uint32_t>(numFrames);

    // Straighten the ring out first: it wraps, and the dither wants a run.
    // pullFromRing is shared with the direct-USB sink, so the two paths count
    // underruns, fill and peak identically.
    float* src = mCbScratch.data();
    const uint32_t take = pullFromRing(src, want);

    const uint32_t samples = take * 2;
    switch (mStreamFormat) {
        case oboe::AudioFormat::I16: {
            // Dither ONLY here. Going from 24 bits of headroom down to 16 is
            // where truncation would be audible as a noise floor that modulates
            // with the signal; the wider formats below have no such problem, so
            // they get a straight scale and no added noise.
            mDither.process(src, static_cast<int16_t*>(audioData), static_cast<int>(samples));
            break;
        }
        case oboe::AudioFormat::Float: {
            std::memcpy(audioData, src, samples * sizeof(float));
            break;
        }
        case oboe::AudioFormat::I24:
            // The CP-88's format. Implemented in engine/, where it is tested
            // against known bytes -- see SampleFormat.h for why that matters.
            bagosynths::packI24(src, static_cast<uint8_t*>(audioData),
                                static_cast<int>(samples));
            break;

        case oboe::AudioFormat::I32:
            bagosynths::packI32(src, static_cast<int32_t*>(audioData),
                                static_cast<int>(samples));
            break;
        default:
            std::memset(audioData, 0, static_cast<size_t>(take) * mBytesPerFrame);
            break;
    }

    // The peak of what is ACTUALLY handed to AAudio is taken in pullFromRing,
    // on the frames straight out of the ring.
    //
    // For the silence that survives a stream restart: the engine meter says
    // 0,148 and nothing is audible, and everything between the two is invisible
    // -- the dither, the probe overwrite, the ring copy. If that peak reads zero
    // while the graph meter does not, the samples die inside this process.
    // If both read the same, we are handing real audio to the HAL and the
    // fault is below us -- which is how the dock was found on 2026-09-20.

    if (take < want) {
        // The render thread did not keep up. Emit silence for the remainder --
        // audible, but bounded, and pullFromRing has counted it.
        std::memset(static_cast<uint8_t*>(audioData) +
                        static_cast<size_t>(take) * mBytesPerFrame,
                    0, static_cast<size_t>(want - take) * mBytesPerFrame);
    }
    (void)stream;
    return oboe::DataCallbackResult::Continue;
}

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
void AudioEngine::updateInstrumentGates(int frames) {
    struct Gate { bagosynths::Node* node; float level; };
    const Gate gates[] = {
        {mSfizz,   mSfizz   ? mSfizz->getParam(bagosynths::SfizzNode::kLevel)     : 0.0f},
        {mHammond, mHammond ? mHammond->getParam(bagosynths::HammondNode::kLevel) : 0.0f},
        {mSurge,   mSurge   ? mSurge->getParam(bagosynths::SurgeNode::kLevel)     : 0.0f},
        {mDexed,   mDexed   ? mDexed->getParam(bagosynths::DexedNode::kLevel)     : 0.0f},
        {mPlaits,  mPlaits  ? mPlaits->getParam(bagosynths::PlaitsNode::kLevel)   : 0.0f},
        {mFluid,   mFluid   ? mFluid->getParam(bagosynths::FluidNode::kLevel)     : 0.0f},
    };
    const int quietFrames = static_cast<int>(currentRate() * kQuietSeconds);
    // Twice the gain ramp, so the level is provably at zero before any voice is
    // cut. Cheap insurance against an off-by-one-block.
    const int fadeFrames =
        static_cast<int>(currentRate() * bagosynths::SmoothedGain::kRampSeconds * 2.0) + 1;
    const int ceilingFrames = static_cast<int>(currentRate() * kQuietCeilingSeconds);
    int index = 0;
    for (const Gate& g : gates) {
        const int i = index++;
        if (g.node == nullptr) continue;

        // Loudness, held. The on-screen meter decays in 60 ms, which makes it
        // unusable for "how loud is THIS engine for that note" -- the question
        // behind Michiel's report that the piano is much quieter than the rest.
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
        // effects. The Hammond's rotary speaker and reverb live inside beatrix,
        // and a frozen effect resumes where it left off: Michiel heard the tail
        // of an organ note return when the part was selected again "an eon
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
                     static_cast<double>(mGateFlush[i]) / currentRate());
            }
            mGateQuiet[i] = 0;
            mGateFlush[i] = 0;
        }
    }
}

// The MIDI chain, stage by stage, so a dropped note can be LOCATED.
//
// Michiel loses about one note-on or note-off in thirty, at random, playing
// single notes on any engine in USB device mode. That rules out voice stealing
// and the sampler's note-on queue, and reading the path found nothing, so the
// path reports itself instead:
//
//   pkt    bulk transfers that returned data
//   msg    messages pushed into the reader's ring
//   on/off notes the READER saw
//   ->     notes the GRAPH received
//
// The two note figures are the whole test. If they agree with each other but
// not with the keys played, the wire or the ioctl lost it; if they disagree,
// the ring or the parser did, and ringFull/short/undec/sinkFull say which.
void AudioEngine::logMidiChain() const {
    const UsbMidiIn& u = mMidi.usb();
    const uint64_t on = u.noteOns(), off = u.noteOffs();
    const uint64_t gotOn = mMidi.noteOnsOut(), gotOff = mMidi.noteOffsOut();
    if (on == 0 && off == 0 && gotOn == 0 && gotOff == 0) return;   // nothing played

    LOGI("midi pkt=%llu msg=%llu on=%llu off=%llu -> on=%llu off=%llu "
         "ringFull=%llu short=%llu undec=%llu filt=%llu sinkFull=%llu err=%llu(%d) "
         // THE WINDOW IN WHICH NOTHING WAS QUEUED ON THE MIDI ENDPOINT, and
         // the longest single bulk call. USBDEVFS_BULK is synchronous, so one
         // request exists at a time and `gap` is how long endpoint 0x82 had
         // none at all -- the audio IN side queues 24 URBs against exactly
         // this. A gap in the tens of milliseconds means the device held its
         // MIDI and handed it over in a burst, which is the shape section 28
         // measured as a shrinker of the device's own margin.
         "gap=%.1fms hold=%.1fms",
         static_cast<unsigned long long>(u.packets()),
         static_cast<unsigned long long>(u.messages()),
         static_cast<unsigned long long>(on),
         static_cast<unsigned long long>(off),
         static_cast<unsigned long long>(gotOn),
         static_cast<unsigned long long>(gotOff),
         static_cast<unsigned long long>(u.ringFull()),
         static_cast<unsigned long long>(u.shortReads()),
         static_cast<unsigned long long>(u.undecoded()),
         static_cast<unsigned long long>(u.filtered()),
         static_cast<unsigned long long>(mMidi.sinkFull()),
         static_cast<unsigned long long>(u.errors()), u.lastErrno(),
         u.maxGapUs() / 1000.0, u.maxHoldUs() / 1000.0);
}

void AudioEngine::renderBlockIntoRing(int frames) {
    bagosynths::Schedule* schedule = mSchedule.load(std::memory_order_acquire);
    if (schedule == nullptr) return;

    updateInstrumentGates(frames);

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
    schedule->run(chunk, currentRate());

    // Interleave, dither, and write into the ring.
    if (schedule->audioOutputSource != nullptr) {
        const bagosynths::AudioPort& src = *schedule->audioOutputSource;
        const float* l = src.channel(0);
        const float* r = src.width > 1 ? src.channel(1) : l;
        // Peak the block while it is being interleaved: the samples are already
        // in registers, so the meter is very nearly free. "Am I making signal?"
        // is a question a three-part rig raises constantly, and answering it on
        // screen beats answering it by listening to a PA at soundcheck.
        //
        // The master volume is applied HERE, at the end of everything: after
        // the limiter, before the ring, and so before the single conversion to
        // the device's format. The meter below therefore reads post-master --
        // what actually leaves, not what the graph produced.
        // Ramped like the instrument levels, and for the same reason: a fader
        // drag steps the gain once per block, and a big jump -- a preset
        // recall, a mute -- is a step in the samples. The ramp is applied to
        // the interleaved scratch, which is the last place the float signal
        // exists before the sink converts it.
        const float master = mMasterGain.load(std::memory_order_relaxed);
        for (int i = 0; i < chunk; ++i) {
            mScratch[i * 2] = l[i];
            mScratch[i * 2 + 1] = r[i];
        }
        mMasterRamp.applyInterleavedStereo(mScratch.data(), chunk, master);
        float peak = 0.0f;
        for (int i = 0; i < chunk * 2; ++i) {
            peak = std::max(peak, std::fabs(mScratch[i]));
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

    // NO DITHER HERE ANY MORE. The ring carries the engine's own float, and the
    // conversion to the device's format happens in the callback -- the only
    // place that knows what that format is, and where the dither belongs now
    // that it applies only to 16-bit targets.
    runLatencyProbe(mScratch.data(), chunk);

    const uint32_t write = mRingWrite.load(std::memory_order_relaxed);
    if (mUsbActive.load(std::memory_order_acquire)) {
        // Straight into the endpoint's layout. Split at the wrap so the reader
        // can memcpy without caring where the boundary falls.
        const uint32_t first = std::min<uint32_t>(
                static_cast<uint32_t>(chunk), kRingFrames - (write & (kRingFrames - 1)));
        packForUsb(mByteRing.data() +
                           static_cast<size_t>(write & (kRingFrames - 1)) * mUsbFrameBytes,
                   mScratch.data(), static_cast<int>(first));
        if (first < static_cast<uint32_t>(chunk)) {
            packForUsb(mByteRing.data(), mScratch.data() + first * 2,
                       static_cast<int>(chunk - first));
        }
    } else {
        for (int i = 0; i < chunk; ++i) {
            const uint32_t idx = ((write + i) & (kRingFrames - 1)) * 2;
            mRing[idx] = mScratch[i * 2];
            mRing[idx + 1] = mScratch[i * 2 + 1];
        }
    }
    mRingWrite.store(write + static_cast<uint32_t>(chunk), std::memory_order_release);
}

void AudioEngine::renderLoop() {
    // Real-time if the system will grant it, urgent-audio nice if not, and it
    // says which in the log. Denormal flushing is per-thread, and this is the
    // thread that does the arithmetic.
    // BELOW the USB reap thread on purpose. The ring exists so that a late
    // render block is absorbed and inaudible; nothing absorbs a late URB. See
    // RealtimePriority.h.
    bos::raiseRenderPriority("render thread", bos::kRenderNice);
    enableFlushToZero();

    // And the part that actually moves the needle on this tablet: a
    // Performance Hint session. A nice value only orders this thread against
    // others; the hint tells the governor there is a DEADLINE, so it raises
    // the clock and keeps the thread off the little cores rather than
    // discovering the miss afterwards. The deadline is one render block.
    mPerfHint.start({gettid()},
                    static_cast<int64_t>(kRenderBlockFrames / currentRate() * 1e9));

    int64_t seenUnderruns = mRingUnderruns.load(std::memory_order_relaxed);
    int64_t seenConcealed = mConcealedTotal.load(std::memory_order_relaxed);
    double lastChange = nowSeconds();

    while (mRenderRun.load(std::memory_order_acquire)) {
        // --- adapt the reserve -------------------------------------------
        const int64_t underruns = mRingUnderruns.load(std::memory_order_relaxed);
        int32_t target = mTargetFill.load(std::memory_order_relaxed);
        const double now = nowSeconds();

        const int64_t concealed = mConcealedTotal.load(std::memory_order_relaxed);
        const int64_t missed = concealed - seenConcealed;
        seenUnderruns = underruns;                  // kept for the health line
        if (missed > 0) {
            seenConcealed = concealed;
            // Any concealment at all counts as "not calm", so the reserve never
            // shrinks through a rough patch made of small misses.
            lastChange = now;
            if (missed >= kGrowThresholdFrames) {
                // Deepen by what was actually missed, plus headroom. A one
                // packet hiccup buys about 2 ms; a real stall still jumps hard.
                const int32_t grow = static_cast<int32_t>(
                        std::min<int64_t>(missed, mMaxFill.load(std::memory_order_relaxed))) +
                        kGrowMarginFrames;
                target = std::min(target + grow, mMaxFill.load(std::memory_order_relaxed));
                mTargetFill.store(target, std::memory_order_relaxed);
            }
        } else if (now - lastChange > kShrinkAfterSeconds &&
                   target > mMinFill.load(std::memory_order_relaxed)) {
            // Geometric: fast while far above the floor, gentle near it, so it
            // does not hunt around the threshold the way a fixed step does.
            const int32_t floor = mMinFill.load(std::memory_order_relaxed);
            const int32_t step = std::max(kShrinkMinFrames, (target - floor) / kShrinkDivisor);
            target = std::max(target - step, floor);
            mTargetFill.store(target, std::memory_order_relaxed);
            lastChange = now;
        }

        const uint32_t write = mRingWrite.load(std::memory_order_relaxed);
        const uint32_t read = mRingRead.load(std::memory_order_acquire);
        const uint32_t filled = write - read;

        if (mRenderPaused.load(std::memory_order_relaxed)) {
            // Produce nothing and sleep, so this thread costs what usbhost's
            // absent render thread costs. The transport runs on concealment.
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        if (filled < static_cast<uint32_t>(target)) {
            const double t0 = nowSeconds();
            renderBlockIntoRing(kRenderBlockFrames);
            const double spent = nowSeconds() - t0;
            const auto ms = static_cast<float>(spent * 1000.0);
            if (ms > mRenderMaxMs.load(std::memory_order_relaxed)) {
                mRenderMaxMs.store(ms, std::memory_order_relaxed);
            }
            // Only the blocks we actually rendered are reported. The sleep
            // branch below is idle time, and telling the governor that the
            // work took no time at all would make it wind the clock DOWN.
            mPerfHint.report(static_cast<int64_t>(spent * 1e9));
        } else {
            // Reserve is full. Sleep briefly rather than spin: this thread must
            // not steal CPU from the callback.
            std::this_thread::sleep_for(std::chrono::microseconds(500));
        }
    }
    mPerfHint.stop();
}

void AudioEngine::runLatencyProbe(float* out, int32_t numFrames) {
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
                const float v = (i % 4 < 2) ? 0.9155f : -0.9155f;
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
                    const float v = (i % 4 < 2) ? 0.9155f : -0.9155f;
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
    // The high-water mark, because the instantaneous count is sampled four
    // times a second and the moment that matters -- hitting the ceiling, where
    // sfizz starts stealing voices -- lasts far less than that. Without this,
    // "it reaches 64" is something only a person watching the screen can say.
    {
        int peak = mPeakVoices.load(std::memory_order_relaxed);
        while (s.activeVoices > peak &&
               !mPeakVoices.compare_exchange_weak(peak, s.activeVoices,
                                                  std::memory_order_relaxed)) {
        }
    }
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
        s.streamState = static_cast<int32_t>(stream->getState());

        // --- is the audio actually LEAVING the box? -----------------------
        //
        // For the dropout that needs a replug to clear. Everything this process
        // can see stays healthy through it -- the callback fires, the ring
        // drains, voices rise, the meter moves -- so ask the one source that is
        // NOT this process: the HAL's timestamp, which reports the frame
        // position the hardware has actually PRESENTED.
        //
        // If that stops advancing while the engine is still producing signal,
        // the audio is dying below us and nothing in the graph will show it. If
        // it keeps advancing and there is still no sound, the fault is past the
        // USB socket and not in this program at all. Either answer is worth
        // more than any amount of staring at the node graph.
        //
        // Sampled here, from the UI poller at 4 Hz, and deliberately not from
        // the render thread: getTimestamp() is a call into the HAL and has no
        // business on a thread with a 2 ms deadline.
        auto ts = stream->getTimestamp(CLOCK_MONOTONIC);
        const double nowTs = nowSeconds();
        if (ts) {
            s.timestampOk = 1;
            const int64_t pos = ts.value().position;
            const int64_t last = mLastPresented.load(std::memory_order_relaxed);
            if (pos != last) {
                mLastPresented.store(pos, std::memory_order_relaxed);
                mLastPresentedAt.store(nowTs, std::memory_order_relaxed);
                mStalledSeconds.store(0.0f, std::memory_order_relaxed);
            } else if (last >= 0) {
                mStalledSeconds.store(
                        static_cast<float>(nowTs - mLastPresentedAt.load(std::memory_order_relaxed)),
                        std::memory_order_relaxed);
            }
        } else {
            // No timestamp. SAY so rather than reporting a healthy zero: an
            // absent measurement and a good one must never look the same. That
            // is the lesson from the disconnect that reported success while
            // sitting on the wrong device.
            s.timestampOk = 0;
            mStalledSeconds.store(0.0f, std::memory_order_relaxed);
        }
        s.stalledSeconds = mStalledSeconds.load(std::memory_order_relaxed);

        auto latency = stream->calculateLatencyMillis();
        if (latency) s.latencyMs = static_cast<float>(latency.value());
    }

    if (mUsbActive.load(std::memory_order_acquire)) {
        const UsbAudioOut::Stats u = mUsbOut.stats();
        const double rate = currentRate();
        s.usbActive = 1;
        s.usbRunning = mUsbOut.running() ? 1 : 0;
        // A USB sink that has died on its own (the device went away, or a
        // submission failed) must read as NOT running, or the watchdog would
        // never rebuild it.
        s.running = (s.running != 0 && s.usbRunning != 0) ? 1 : 0;
        s.sampleRate = static_cast<int32_t>(rate);
        s.burstFrames = mUsbOut.framesPerUrb();
        s.bufferFrames = mUsbOut.framesPerUrb() * mUsbOut.urbCount();
        // What sits between the render thread and the wire: the URBs in flight
        // plus the ring's reserve. There is no HAL latency to ask for here, and
        // nothing hidden below it.
        s.latencyMs = static_cast<float>(1000.0 * s.bufferFrames / rate) +
                      mRingFillMs.load(std::memory_order_relaxed);
        s.sharingMode = -1;
        s.performanceMode = -1;
        s.usesAAudio = 0;
        s.usbDeficitMs = u.deficitMs;
        s.usbLate = static_cast<int32_t>(u.late);
        s.usbStarved = static_cast<int32_t>(u.starved);
        s.usbErrors = static_cast<int32_t>(u.outErrors + u.inErrors);
        s.usbFeedbackRate = u.feedbackRate;
        s.usbMaxGapMs = u.maxGapMs;

        // THE HOLE LOG. A latch is an EVENT -- a burst of late URBs inside a
        // few tens of milliseconds -- and the 10 s heartbeat below averages it
        // into invisibility: by the time it prints, `late` has stopped moving
        // and all that is left is a number that has always been there. This
        // fires on the TRANSITION instead, at whatever rate stats() is polled
        // (4 Hz from the UI), which is what makes the moment of the latch
        // visible at all.
        //
        // Costs nothing when nothing is wrong: three comparisons.
        {
            const int64_t lastLate = mUsbLastLate.exchange(u.late, std::memory_order_relaxed);
            const int64_t lastErr = mUsbLastErrors.exchange(
                    u.outErrors + u.inErrors, std::memory_order_relaxed);
            const float lastDef = mUsbLastDeficitMs.exchange(u.deficitMs, std::memory_order_relaxed);
            const float jump = u.deficitMs - lastDef;
            if (u.late != lastLate || (u.outErrors + u.inErrors) != lastErr || jump > 2.0f) {
                // flightUs and inPkt belong HERE and not only in the health
                // line, which prints every 10 s while both of these reset on
                // every stats() call a quarter of a second apart -- so the
                // health line shows one arbitrary 250 ms window and the
                // interesting one is almost always missed. A hole fires AT the
                // transition, which is the window that matters: on 2026-09-23
                // the run that latched showed flightUs 2057/2025 against a
                // full-queue ~7500, with IN delivery collapsed to 54 packets
                // per 250 ms against ~250, in the same interval.
                LOGI("usb hole: late %lld (+%lld) errors %lld (+%lld) deficit %.1f ms (%+.1f) "
                     "maxGap %.1f ms starved %lld inPkt %lld inLost %lld "
                     "frames %lld voices %d",
                     static_cast<long long>(u.late),
                     static_cast<long long>(u.late - lastLate),
                     static_cast<long long>(u.outErrors + u.inErrors),
                     static_cast<long long>((u.outErrors + u.inErrors) - lastErr),
                     static_cast<double>(u.deficitMs), static_cast<double>(jump),
                     static_cast<double>(u.maxGapMs),
                     static_cast<long long>(u.starved),
                     static_cast<long long>(u.inPackets),
                     static_cast<long long>(u.inLostPackets),
                     static_cast<long long>(u.frames), s.activeVoices);
            }
        }

        const double now = nowSeconds();
        if (now - mLastHealthLogAt.load(std::memory_order_relaxed) > 10.0) {
            mLastHealthLogAt.store(now, std::memory_order_relaxed);
            const std::string why = mUsbOut.fatal();
            LOGI("health usb %s  out=%.4f cb=%.4f voices=%d/%d frames=%lld deficit=%.1fms "
                 "late=%lld starved=%lld errors=%lld fb=%.1fHz ringUnder=%lld concealed=%lld ringTarget=%.1fms "
                 "inSize=%d..%d/%lld fbDrift=%lld fbDrop=%lld fbClamp=%lld "
                 "inLost=%lld zeroAvoided=%lld shaped=%lld bal=%lld/%lld rise=%lld "
                 "fifo=%d..%d preRoll=%lld resync=%lld trim=%lld silence=%lld "
                 "outShort=%lld outOdd=%lld outDry=%lld%s%s",
                 s.usbRunning ? "ok" : "DOWN",
                 static_cast<double>(s.outputPeak),
                 static_cast<double>(mCallbackPeak.load(std::memory_order_relaxed)),
                 s.activeVoices, mPeakVoices.load(std::memory_order_relaxed),
                 static_cast<long long>(u.frames),
                 static_cast<double>(u.deficitMs), static_cast<long long>(u.late),
                 static_cast<long long>(u.starved),
                 static_cast<long long>(u.outErrors + u.inErrors),
                 static_cast<double>(u.feedbackRate),
                 static_cast<long long>(mRingUnderruns.load(std::memory_order_relaxed)),
                 // FRAMES OF AUDIO THE RENDERER DID NOT PRODUCE IN TIME, and
                 // the direct measure of chopping. It is a RENDER-side fault,
                 // not a transport one: a late render leaves the byte ring
                 // short, the reap thread still sends a correctly sized packet,
                 // and what is in it is concealment. So this number moving is
                 // sfizz reaching for a sample that is not in memory yet, and
                 // no amount of USB buffer fixes it -- only a deeper render
                 // reserve, which `ringTarget` beside it is already adapting.
                 static_cast<long long>(mConcealedTotal.load(std::memory_order_relaxed)),
                 // The adaptive reserve, in milliseconds. Watching it is the
                 // only way to see the growth and shrink policy behave.
                 1000.0 * mTargetFill.load(std::memory_order_relaxed) / currentRate(),
                 // -1..-1 means NO IN packets arrived in this interval. The
                 // old guard keyed off inSizeMax and so printed 0..0 for that
                 // -- indistinguishable from a device delivering nothing but
                 // zero-length packets, which is precisely the signature this
                 // probe exists to catch.
                 u.inPackets ? u.inSizeMin : -1,
                 u.inPackets ? u.inSizeMax : -1,
                 static_cast<long long>(u.inPackets),
                 static_cast<long long>(u.inFramesTotal - u.frames),
                 static_cast<long long>(u.fbDrop),
                 static_cast<long long>(u.fbClamp),
                 // IN packets the device sent that no URB was queued to catch:
                 // requests destroyed before they were read. Should be zero.
                 static_cast<long long>(u.inLostPackets),
                 // MUST be 0. A single zero-length OUT packet wedges the
                 // device permanently, so this counts near misses.
                 static_cast<long long>(u.zeroAvoided),
                 // OUT packets a deliberate size or bias injection rewrote.
                 // Non-zero means a trial is running and the stream is NOT
                 // representative of normal operation.
                 static_cast<long long>(u.shaped),
                 // THE counter for this device: cumulative frames sent minus
                 // frames asked, and the worst it reached this interval. Sending
                 // MORE than was asked wedges the CP-88 at about +170 frames;
                 // sending less is free. Both of these must stay negative.
                 static_cast<long long>(u.balance),
                 // balPeak's "no samples" sentinel is INT64_MIN, and an
                 // interval in which not one IN packet arrived printed it raw:
                 // bal=.../-9223372036854775808. Report the running balance
                 // instead, which is the honest value for an empty interval.
                 static_cast<long long>(u.inPackets ? u.balPeak : u.balance),
                 // How far the balance climbed back off its own floor: the
                 // only part of the deficit the device can actually feel,
                 // because its FIFO stops at empty and ours does not.
                 static_cast<long long>(u.balRise),
                 // THE PACKET-FRAMES FIFO, the depth it covered this interval.
                 // It is the only accounting instrument this transport has.
                 // Healthy is a narrow band near the pre-roll depth of 24.
                 // Riding the cap of 48 means OUT is not keeping up with IN;
                 // reaching 0 means the stream ran dry and restarted.
                 u.inPackets ? u.fifoMin : -1,
                 u.inPackets ? u.fifoMax : -1,
                 // Times the OUT stream was withheld and restarted from the
                 // pre-roll. One at start-up is normal and expected. More than
                 // that is the transport refusing to invent a size, which is
                 // the design working -- but each one is an audible gap and a
                 // discarded render backlog, so the count is the thing to
                 // watch under load.
                 static_cast<long long>(u.preRolls),
                 // Times a full FIFO forced a gap instead of catching up: the
                 // host had fallen a whole FIFO behind, so the stream was cut
                 // and restarted to make the device discard its accumulated
                 // deviation. Each one is an audible seam, so this is the price
                 // of the resync policy, in events.
                 static_cast<long long>(u.resyncs),
                 // Times a full FIFO was cut back on an OUT reap: asks that
                 // arrived while nothing was going out.
                 static_cast<long long>(u.outTrims),
                 // Frames of pre-roll silence sent. Correctly sized packets of
                 // zeroes while the render ring rebuilds, never zero-length.
                 static_cast<long long>(u.silenceFrames),
                 // What the controller says it PUT ON THE WIRE against what it
                 // was asked for. Both must stay 0. A short packet, or one
                 // that is not a whole number of frames, is the one fault no
                 // other counter here can see.
                 static_cast<long long>(u.outShort),
                 static_cast<long long>(u.outOdd),
                 // Times the OUT ring certainly emptied, so ISO_ASAP RESTARTED
                 // the stream instead of resuming it. The count, not the
                 // duration -- `deficit` is the duration and it does not
                 // separate a run that latches from one that does not.
                 static_cast<long long>(u.outDry),
                 why.empty() ? "" : " fatal=", why.c_str());
            logMidiChain();
        }
    }

    s.ringUnderruns = mRingUnderruns.load(std::memory_order_relaxed);
    s.ringFillMs = mRingFillMs.load(std::memory_order_relaxed);
    s.renderMaxMs = mRenderMaxMs.load(std::memory_order_relaxed);
    s.ringTargetMs = static_cast<float>(1000.0 * mTargetFill.load(std::memory_order_relaxed) /
                                        currentRate());
    s.probeState = static_cast<int32_t>(mProbeState.load(std::memory_order_acquire));
    s.roundTripMs = mRoundTripMs.load(std::memory_order_relaxed);
    s.inputPeak = mInputPeak.load(std::memory_order_relaxed);

    auto input = mInputStream;
    if (input) {
        auto inLatency = input->calculateLatencyMillis();
        if (inLatency) s.inputLatencyMs = static_cast<float>(inLatency.value());
    }

    // --- the heartbeat ----------------------------------------------------
    //
    // Ten seconds apart, so a logcat left running across a set stays readable
    // and the state BEFORE a dropout is on the record rather than reconstructed
    // after it. An intermittent fault that takes minutes to appear is only ever
    // diagnosed from what was captured while it was happening.
    if (stream) {
        const double now = nowSeconds();
        const double lastLog = mLastHealthLogAt.load(std::memory_order_relaxed);
        const bool stalled = s.timestampOk != 0 && s.stalledSeconds > 1.0f;
        if (now - lastLog > 10.0 || (stalled && now - lastLog > 1.0)) {
            mLastHealthLogAt.store(now, std::memory_order_relaxed);
            LOGI("health %s  out=%.4f cb=%.4f voices=%d/%d presented=%lld stalled=%.1fs "
                 "state=%d xruns=%d ringUnder=%lld fill=%.1fms dev=%d",
                 stalled ? "STALLED" : "ok",
                 static_cast<double>(s.outputPeak),
                 static_cast<double>(mCallbackPeak.load(std::memory_order_relaxed)),
                 s.activeVoices, mPeakVoices.load(std::memory_order_relaxed),
                 static_cast<long long>(mLastPresented.load(std::memory_order_relaxed)),
                 static_cast<double>(s.stalledSeconds), s.streamState, s.xRuns,
                 static_cast<long long>(s.ringUnderruns),
                 static_cast<double>(s.ringFillMs), s.deviceId);
            // Also on the ALSA path: the comparison only means something if
            // both modes are counted the same way.
            logMidiChain();

            // The one unambiguous combination, shouted so it is findable in a
            // long log: the engine is making signal, and the hardware is not
            // playing it.
            if (stalled && s.outputPeak > 0.001f) {
                LOGE("AUDIO STALLED: producing signal (peak %.4f) but the device has "
                     "presented nothing for %.1f s -- the output path is dead below "
                     "this process, not in the graph",
                     static_cast<double>(s.outputPeak),
                     static_cast<double>(s.stalledSeconds));
            }
        }
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
    mUsbOut.resetPeaks();

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
    for (float& p : mNodePeakHold) p = 0.0f;
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
        auto out = std::make_unique<bagosynths::AudioOutNode>();

        mSfizz = sfizz.get();
        mHammond = hammond.get();
        mSurge = surge.get();
        mDexed = dexed.get();
        mPlaits = plaits.get();
        mFluid = fluid.get();
        mReverb = reverb.get();

        const bagosynths::NodeId idMidi = mGraph.addNode(std::move(midiIn));
        const bagosynths::NodeId idSfizz = mGraph.addNode(std::move(sfizz));
        const bagosynths::NodeId idHammond = mGraph.addNode(std::move(hammond));
        const bagosynths::NodeId idSurge = mGraph.addNode(std::move(surge));
        const bagosynths::NodeId idDexed = mGraph.addNode(std::move(dexed));
        const bagosynths::NodeId idPlaits = mGraph.addNode(std::move(plaits));
        const bagosynths::NodeId idFluid = mGraph.addNode(std::move(fluid));
        const bagosynths::NodeId idReverb = mGraph.addNode(std::move(reverb));
        const bagosynths::NodeId idOut = mGraph.addNode(std::move(out));

        // The default patch. Built in code; identical in every respect to what
        // the editor will later produce by hand.
        //
        //   MIDI In --+--> sfizz    --+
        //             |               |
        //             +--> Hammond  --+
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
        mGraph.connect(idReverb, 0, idOut, 0);

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

    const double rate = currentRate();
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

    const double rate = currentRate();
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
    // Per-engine loudness, so gain staging is a measurement rather than an
    // argument. dBFS of the engine's own output BEFORE its level fader, held
    // since the last reset: play the same note on each engine in turn and the
    // numbers say what "much quieter" is worth in decibels.
    {
        static const char* kNames[6] = {"sfizz", "Hammond", "Surge XT",
                                        "Dexed", "Plaits", "FluidSynth"};
        for (int i = 0; i < 6; ++i) {
            if (mNodePeakHold[i] <= 0.0f) continue;
            LOGI("PROFILE %-12s peak %.4f (%.1f dBFS, pre-level)", kNames[i],
                 static_cast<double>(mNodePeakHold[i]),
                 20.0 * std::log10(static_cast<double>(mNodePeakHold[i])));
        }
    }
    LOGI("PROFILE block deadline = %.3f ms, xruns = %d",
         mStream ? 1000.0 * mStream->getFramesPerBurst() / mStream->getSampleRate() : 0.0,
         mStream ? mStream->getXRunCount().value() : -1);
    sched->resetProfile();
}

// --------------------------------------------------------- device format ---

void AudioEngine::packForUsb(uint8_t* dst, const float* stereo, int frames) {
    // Channel map first: L/R into the first two and silence beyond, a mono
    // device gets the sum. Same rule the USB sink used to apply per packet,
    // moved to the producer where being late is absorbed by the ring.
    const int ch = mUsbChannels;
    float* f = mPackScratch.data();
    for (int i = 0; i < frames; ++i) {
        const float l = stereo[i * 2], r = stereo[i * 2 + 1];
        if (ch == 1) {
            f[i] = 0.5f * (l + r);
        } else {
            f[i * ch] = l;
            f[i * ch + 1] = r;
            for (int c = 2; c < ch; ++c) f[i * ch + c] = 0.0f;
        }
    }
    const int samples = frames * ch;
    switch (mUsbSubslot) {
        case 2:
            // Dither belongs with the conversion, so it moved here too.
            mUsbDither.process(f, mPackI16.data(), samples);
            std::memcpy(dst, mPackI16.data(), static_cast<size_t>(samples) * 2);
            break;
        case 3:
            bagosynths::packI24(f, dst, samples);
            break;
        default:
            bagosynths::packI32(f, reinterpret_cast<int32_t*>(dst), samples);
            break;
    }
}

uint32_t AudioEngine::pullBytes(uint8_t* dst, uint32_t want) {
    const uint32_t read = mRingRead.load(std::memory_order_relaxed);
    const uint32_t write = mRingWrite.load(std::memory_order_acquire);
    const uint32_t take = std::min(write - read, want);

    // Two memcpys at most, and usually one. This is the whole of the reap
    // thread's per-packet work now.
    const uint32_t off = read & (kRingFrames - 1);
    const uint32_t first = std::min(take, kRingFrames - off);
    std::memcpy(dst, mByteRing.data() + static_cast<size_t>(off) * mUsbFrameBytes,
                static_cast<size_t>(first) * mUsbFrameBytes);
    if (first < take) {
        std::memcpy(dst + static_cast<size_t>(first) * mUsbFrameBytes, mByteRing.data(),
                    static_cast<size_t>(take - first) * mUsbFrameBytes);
    }
    mRingRead.store(read + take, std::memory_order_release);

    if (take < want) {
        mRingUnderruns.fetch_add(1, std::memory_order_relaxed);
        const uint32_t missing = want - take;
        if (!mConcealOn.load(std::memory_order_relaxed)) {
            std::memset(dst + static_cast<size_t>(take) * mUsbFrameBytes, 0,
                        static_cast<size_t>(missing) * mUsbFrameBytes);
        } else {
            // Repeat from a period back, decaying. On packed bytes the fade
            // needs a decode and re-encode per sample, which is fine: this runs
            // only during an underrun, never on the healthy path.
            concealBytes(dst, take, missing, read + take);
        }
        mConcealedFrames += missing;
        mConcealedTotal.fetch_add(missing, std::memory_order_relaxed);
        mRingFillMs.store(0.0f, std::memory_order_relaxed);
        return want;
    }
    mConcealedFrames = 0;
    mRingFillMs.store(static_cast<float>(1000.0 * (write - read - take) / currentRate()),
                      std::memory_order_relaxed);
    return take;
}

int AudioEngine::pullBytesForUsb(void* ctx, uint8_t* dst, int frames) {
    auto* self = static_cast<AudioEngine*>(ctx);
    return static_cast<int>(self->pullBytes(dst, static_cast<uint32_t>(std::max(0, frames))));
}

// THE OUT STREAM RESTARTED, so throw away what the ring is holding.
//
// Called on the reap thread at the end of a pre-roll, and it is the consumer
// side of the ring doing the discarding -- mRingRead belongs to this thread, so
// moving it forward to the write cursor is a plain store and needs no lock.
//
// Everything in the ring at this moment is audio that was due while the OUT
// stream was silent. Playing it out now would put the whole gap back as added
// latency and keep it there: the device absorbs about 110 frames, 2,7 ms, so
// there is nowhere for a backlog to go but into delay that never comes off
// again. Discarding it costs exactly the audio nobody heard anyway and puts the
// instrument back at the live edge, which is the whole reason this app exists.
//
// The return value is how deep the producer wants to be before it is steady --
// its own adaptive target -- and the transport sends correctly-sized silence
// for that many frames while the renderer rebuilds.
int AudioEngine::flushBytes() {
    const uint32_t write = mRingWrite.load(std::memory_order_acquire);
    mRingRead.store(write, std::memory_order_release);
    mRingFillMs.store(0.0f, std::memory_order_relaxed);
    mConcealedFrames = 0;
    return static_cast<int>(mTargetFill.load(std::memory_order_relaxed));
}

int AudioEngine::flushBytesForUsb(void* ctx) {
    return static_cast<AudioEngine*>(ctx)->flushBytes();
}

namespace {

// One packed sample in and out, whatever the subslot width. Signed, and for
// 24-in-4 the value is left justified exactly as packI32 writes it.
inline int32_t loadSample(const uint8_t* p, int subslot) {
    switch (subslot) {
        case 2: return static_cast<int16_t>(p[0] | (p[1] << 8));
        case 3: {
            int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
            if (v & 0x800000) v |= ~0xFFFFFF;
            return v;
        }
        default:
            return static_cast<int32_t>(static_cast<uint32_t>(p[0]) |
                                        (static_cast<uint32_t>(p[1]) << 8) |
                                        (static_cast<uint32_t>(p[2]) << 16) |
                                        (static_cast<uint32_t>(p[3]) << 24));
    }
}

inline void storeSample(uint8_t* p, int subslot, int32_t v) {
    switch (subslot) {
        case 2:
            p[0] = static_cast<uint8_t>(v & 0xFF);
            p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            break;
        case 3:
            p[0] = static_cast<uint8_t>(v & 0xFF);
            p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
            break;
        default:
            p[0] = static_cast<uint8_t>(v & 0xFF);
            p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
            p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
            break;
    }
}

}  // namespace

void AudioEngine::concealBytes(uint8_t* dst, uint32_t take, uint32_t missing, uint32_t base) {
    const int ch = mUsbChannels;
    const int sub = mUsbSubslot;
    const int fb = mUsbFrameBytes;
    for (uint32_t i = 0; i < missing; ++i) {
        const uint32_t age = mConcealedFrames + i;
        float g = 1.0f - static_cast<float>(age) / kConcealFadeFrames;
        if (g < 0.0f) g = 0.0f;
        const uint32_t back = kConcealPeriodFrames - (age % kConcealPeriodFrames);
        const uint8_t* src =
                mByteRing.data() +
                static_cast<size_t>((base + i - back) & (kRingFrames - 1)) * fb;
        uint8_t* out = dst + static_cast<size_t>(take + i) * fb;
        // Crossfade the first frames of a new hole against the audio that
        // really was delivered, so the splice is not a step.
        const bool splice = (mConcealedFrames == 0 && i < kConcealXfadeFrames && take > 0);
        const uint8_t* last =
                splice ? mByteRing.data() +
                                 static_cast<size_t>((base - 1) & (kRingFrames - 1)) * fb
                       : nullptr;
        const float w = splice ? static_cast<float>(i) / kConcealXfadeFrames : 1.0f;
        for (int c = 0; c < ch; ++c) {
            float v = static_cast<float>(loadSample(src + c * sub, sub)) * g;
            if (splice) {
                v = static_cast<float>(loadSample(last + c * sub, sub)) * (1.0f - w) + v * w;
            }
            storeSample(out + c * sub, sub, static_cast<int32_t>(v));
        }
    }
}
