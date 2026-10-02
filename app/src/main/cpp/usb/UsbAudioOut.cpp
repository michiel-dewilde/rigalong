#include "UsbAudioOut.h"

#include "../RealtimePriority.h"

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <limits>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <ctime>

#include <bagosynths/SampleFormat.h>

#define LOG_TAG "bos-usb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

size_t urbBytes(int packets) {
    return sizeof(usbdevfs_urb) + static_cast<size_t>(packets) * sizeof(usbdevfs_iso_packet_desc);
}

uint32_t le24(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16); }
uint32_t le32(const uint8_t* p) { return le24(p) | (static_cast<uint32_t>(p[3]) << 24); }

}  // namespace

// ------------------------------------------------------------ lifecycle ---

bool UsbAudioOut::start(const Config& config, PullFn pull, FlushFn flush, void* ctx,
                        std::string* error) {
    stop();
    mCfg = config;
    mPull = pull;
    mFlush = flush;
    mCtx = ctx;
    mFatal.clear();
    mFatalSet.store(0, std::memory_order_relaxed);

    auto fail = [&](const char* why) {
        if (error) *error = why;
        LOGE("start refused: %s", why);
        return false;
    };
    if (mCfg.fd < 0) return fail("no usbfs descriptor");
    if (mCfg.outEp == 0 || mCfg.outMaxPacket <= 0) return fail("no OUT endpoint");
    if (mCfg.subslotBytes < 2 || mCfg.subslotBytes > 4) return fail("unsupported sample width");
    if (mCfg.channels < 1 || mCfg.rate <= 0) return fail("bad format");

    // Period of one isochronous service interval. bInterval is an exponent
    // for iso endpoints at both speeds: 2^(bInterval-1) frames or microframes.
    const int unitUs = mCfg.highSpeed ? 125 : 1000;
    const int exp = std::clamp(mCfg.outInterval, 1, 16) - 1;
    mPeriodUs = static_cast<double>(unitUs) * static_cast<double>(1 << exp);

    // Each URB carries about 2 ms, and the URB count makes up the requested
    // depth. On the CP-88 (full speed, 1 ms) 8 ms is 4 URBs x 2 packets --
    // exactly the configuration that measured cleanest in the sweep. Packets
    // per URB is held at 2 ms rather than grown with depth because 4-packet
    // URBs measured feedback starvation at 32 ms.
    mPackets = mCfg.packetsPerUrb > 0
                       ? std::clamp(mCfg.packetsPerUrb, 1, 8)
                       : std::max(1, static_cast<int>(std::lround(2000.0 / mPeriodUs)));
    const double urbMs = mPackets * mPeriodUs / 1000.0;
    mUrbs = std::clamp(static_cast<int>(std::lround(mCfg.bufferMs / urbMs)), 2, 64);
    // The IN side sizes itself: see Config::inPacketsPerUrb and inBufferMs. 128
    // is the kernel's own comfort, and 8 packets each is the batching. Never
    // fewer URBs than the OUT count -- the feedback stream must never be the
    // shallower one.
    mInPacketsPerUrb = std::clamp(mCfg.inPacketsPerUrb > 0 ? mCfg.inPacketsPerUrb : 8,
                                  1, 128);
    const double inUrbMs = mInPacketsPerUrb * mPeriodUs / 1000.0;
    mInUrbs = std::clamp(static_cast<int>(std::lround(mCfg.inBufferMs / inUrbMs)),
                         mUrbs, 1024);

    mFrameBytes = mCfg.channels * mCfg.subslotBytes;
    const int maxFramesPerPacket = mCfg.outMaxPacket / mFrameBytes;
    if (maxFramesPerPacket < 1) return fail("OUT packet smaller than one frame");
    mFramesPerUrb = static_cast<int>(std::lround(mCfg.rate * urbMs / 1000.0));


    mNominalAcc = 0;
    mFbQ16 = 0;
    mFbAcc = 0;
    mSizeHead = mSizeCount = 0;
    mFrames = 0; mOutBytes = 0; mOutErr = 0; mInErr = 0;
    mStarved = 0; mLate = 0; mMaxGapUs = 0;
    mInPacketsTotal = 0;
    // mInFramesTotal was NOT in this list, so every counter derived from it --
    // fbDrift, and now bal -- carried the previous stream's total across a
    // restart and read as a surplus of millions. The numbers were nonsense from
    // the first REINIT onwards and nobody noticed, because the health line only
    // ever gets compared against itself.
    mInFramesTotal = 0;
    mFbDrop = 0; mFbClamp = 0; mZeroAvoided = 0; mShaped = 0;
    mBalMin = std::numeric_limits<int64_t>::max();
    mBalPeak = std::numeric_limits<int64_t>::min();
    mBalRise = 0;
    mOutShort = 0; mOutOdd = 0;
    mOutDry = 0;
    mOutEmpty = 0;
    mTrace.assign(kTraceCap, TraceEntry{});
    mTraceAt = 0;
    mTraceT0 = nowNs();
    mPreRoll = true;
    mSilenceFrames = 0;
    mPreRolls = 0; mSilenceTotal = 0; mOutTrims = 0; mResyncs = 0;
    mFifoLen = std::clamp(mCfg.fifoLength > 0 ? mCfg.fifoLength : 8, 2, kFifoCap);
    mFifoPreRoll = std::clamp(mCfg.fifoPreRoll > 0 ? mCfg.fifoPreRoll : mFifoLen / 2,
                              1, mFifoLen - 1);
    mFifoResync = mCfg.fifoResync;
    mResyncGapUs = mCfg.resyncGapUs > 0 ? mCfg.resyncGapUs : 4000;
    mInGap = false;
    mGapUntilNs = 0;
    mFifoMin = 1 << 30; mFifoMax = 0;
    mOutIdle.clear();
    mOutIdle.reserve(static_cast<size_t>(mUrbs));
    mOutRingUs = static_cast<int64_t>(mUrbs * mPackets * mPeriodUs);
    mOutRingNs = mOutRingUs * 1000;
    mInRingNs = static_cast<int64_t>(mInUrbs * mInPacketsPerUrb * mPeriodUs * 1000.0);
    mFbRate = static_cast<float>(mCfg.rate);

    if (!allocStream(mOut, mUrbs, mPackets, mCfg.outEp, true, mCfg.outMaxPacket)) {
        freeStream(mOut);
        return fail("out of memory for OUT URBs");
    }
    if (mCfg.feedback == kImplicit) {
        if (mCfg.fbFrameBytes <= 0) { freeStream(mOut); return fail("implicit feedback without a frame size"); }
        if (!allocStream(mIn, mInUrbs, mInPacketsPerUrb, mCfg.fbEp, false, mCfg.fbMaxPacket)) {
            freeStream(mOut); freeStream(mIn);
            return fail("out of memory for feedback URBs");
        }
    } else if (mCfg.feedback == kExplicit) {
        // Two single-packet URBs keep one feedback read always outstanding.
        if (!allocStream(mIn, 2, 1, mCfg.fbEp, false, mCfg.fbMaxPacket)) {
            freeStream(mOut); freeStream(mIn);
            return fail("out of memory for feedback URBs");
        }
    }

    mRunning.store(true, std::memory_order_release);
    mStartNs.store(nowNs(), std::memory_order_relaxed);

    // The feedback stream first: an implicitly clocked OUT stream has nothing
    // to say until the device has reported its cadence -- the ordering the
    // kernel uses too.
    for (Urb& b : mIn) {
        fillIn(b, mCfg.feedback == kExplicit ? 1 : mInPacketsPerUrb, mCfg.fbMaxPacket);
        if (!submit(b)) { stop(); if (error) *error = fatal(); return false; }
    }
    // PRIME THE SIZE QUEUE ON THIS THREAD, before the reap thread exists.
    //
    // This used to start the reap thread here and then fill the OUT URBs, which
    // is a data race and always was: completeIn() on the reap thread pushes to
    // mSizes while nextFrames() on this thread pops from it, and mSizeHead and
    // mSizeCount are plain ints. Every stream start ran it. A corrupted count
    // yields packet sizes the device never asked for, which is exactly the
    // class of fault -- a late implementation putting something unreasonable on
    // the wire -- that this investigation is chasing. Michiel's hunch, and he
    // was right that the shape exists even though the OUT data buffer itself is
    // safe: fillOut() only touches a URB between its reap and its resubmit, and
    // it writes the samples BEFORE setting iso_frame_desc[].length, so a length
    // never advertises bytes that are not there yet.
    //
    // Harvesting the first completions here keeps the whole priming sequence
    // single-threaded. The guard bounds the loop; REAPURBNDELAY returns EAGAIN
    // once nothing more is ready.
    if (!mIn.empty()) {
        usleep(20 * 1000);
        for (int guard = 0; guard < 4 * mInUrbs; ++guard) {
            void* p = nullptr;
            if (ioctl(mCfg.fd, USBDEVFS_REAPURBNDELAY, &p) < 0) break;
            auto* u = static_cast<usbdevfs_urb*>(p);
            // NOT OURS: the descriptor is shared, so this loop can be handed a
            // MIDI URB -- including a stale one cancelled before we started.
            // Casting it to our Urb and reading `out` is type confusion at
            // best and a use-after-free at worst, which is exactly what
            // AddressSanitizer caught here on 2026-09-25.
            if (!ownsContext(u->usercontext)) continue;
            auto* b = static_cast<Urb*>(u->usercontext);
            if (b == nullptr || b->out) continue;
            if (mCfg.feedback == kExplicit) {
                completeFeedback(*b);
                fillIn(*b, 1, mCfg.fbMaxPacket);
            } else {
                completeIn(*b);
                fillIn(*b, mInPacketsPerUrb, mCfg.fbMaxPacket);
            }
            if (!submit(*b)) { stop(); if (error) *error = fatal(); return false; }
        }
    }

    // NO OUT URB IS SUBMITTED HERE. Every one of them starts idle and the reap
    // thread's pre-roll releases them once the FIFO holds mFifoPreRoll asks --
    // the same path a dry FIFO takes later, so the stream's first packet and
    // its first packet after a gap are produced by exactly one piece of code.
    // Starting the OUT stream here would mean sizing packets before the device
    // had asked for anything, which is the one thing this transport does not do.
    for (Urb& b : mOut) mOutIdle.push_back(&b);

    // Only now does a second thread exist, and from here on mSizes is touched
    // by that thread alone.
    mThread = std::thread([this] { reapLoop(); });

    LOGI("usb out: ep 0x%02x %s, %d ch x %d B @ %d Hz, period %.0f us, "
         "%d URBs x %d packets = %.1f ms out (%.1f ms slack) / "
         "%d URBs x %d packets = %.0f ms in, fifo %d/%d, feedback %s",
         mCfg.outEp, mCfg.highSpeed ? "high speed" : "full speed", mCfg.channels,
         mCfg.subslotBytes, mCfg.rate, mPeriodUs, mUrbs, mPackets, mUrbs * urbMs,
         (mUrbs - 1) * urbMs,
         mCfg.feedback == kImplicit ? mInUrbs : 0,
         mCfg.feedback == kImplicit ? mInPacketsPerUrb : 0,
         mCfg.feedback == kImplicit ? mInUrbs * inUrbMs : 0.0,
         mFifoPreRoll, mFifoLen,
         mCfg.feedback == kImplicit ? "implicit" : mCfg.feedback == kExplicit ? "explicit" : "none");
    return true;
}

void UsbAudioOut::stop() {
    const bool wasRunning = mRunning.exchange(false, std::memory_order_acq_rel);
    if (!wasRunning && !mThread.joinable() && mOut.empty() && mIn.empty()) return;

    // Discarding makes each URB complete with -ENOENT, which wakes the blocked
    // reap. An error here only means the URB had already completed.
    for (Urb& b : mOut) if (b.u) ioctl(mCfg.fd, USBDEVFS_DISCARDURB, b.u);
    for (Urb& b : mIn) if (b.u) ioctl(mCfg.fd, USBDEVFS_DISCARDURB, b.u);
    if (mThread.joinable()) mThread.join();

    // Drain whatever the kernel still holds before the buffers go.
    void* p = nullptr;
    while (ioctl(mCfg.fd, USBDEVFS_REAPURBNDELAY, &p) == 0) {}

    // Before the buffers go, or it holds pointers into a freed vector.
    mOutIdle.clear();
    freeStream(mOut);
    freeStream(mIn);
    LOGI("usb out stopped");
}

std::string UsbAudioOut::fatal() const {
    return mFatalSet.load(std::memory_order_acquire) ? mFatal : std::string();
}

void UsbAudioOut::setFatal(const std::string& why) {
    if (mFatalSet.exchange(1, std::memory_order_acq_rel) == 0) {
        mFatal = why;
        LOGE("usb out: %s", why.c_str());
    }
}

// -------------------------------------------------------------- buffers ---

bool UsbAudioOut::allocStream(std::vector<Urb>& v, int count, int packets, int ep,
                              bool out, int bytesPerPacket) {
    v.assign(static_cast<size_t>(count), Urb{});
    for (Urb& b : v) {
        b.out = out;
        b.u = static_cast<usbdevfs_urb*>(calloc(1, urbBytes(packets)));
        b.data = static_cast<uint8_t*>(calloc(static_cast<size_t>(packets),
                                              static_cast<size_t>(bytesPerPacket)));
        if (!b.u || !b.data) return false;
        b.u->type = USBDEVFS_URB_TYPE_ISO;
        b.u->endpoint = static_cast<unsigned char>(ep);
        // ASAP throughout, as libusb does. The kernel schedules all but the
        // first URB by explicit start_frame; with ASAP a late submission
        // skips forward instead of erroring, and the skip shows up in
        // deficitMs rather than killing the stream.
        b.u->flags = USBDEVFS_URB_ISO_ASAP;
        b.u->buffer = b.data;
        b.u->number_of_packets = packets;
        b.u->usercontext = &b;
    }
    return true;
}

void UsbAudioOut::freeStream(std::vector<Urb>& v) {
    for (Urb& b : v) { free(b.u); free(b.data); }
    v.clear();
}

bool UsbAudioOut::submit(Urb& b) {
    b.submittedNs = nowNs();
    if (ioctl(mCfg.fd, USBDEVFS_SUBMITURB, b.u) < 0) {
        setFatal(std::string("SUBMITURB ") + (b.out ? "OUT" : "IN") + " failed: " +
                 std::strerror(errno));
        return false;
    }
    return true;
}

// ------------------------------------------------------- packet sizing ---

int UsbAudioOut::nextFrames() {
    if (mCfg.feedback == kImplicit) {
        // TEST ONLY: force the dry-queue path with a queue that is not dry.
        bool starve = false;
        if (mInjectStarve.load(std::memory_order_relaxed) > 0) {
            mInjectStarve.fetch_sub(1, std::memory_order_relaxed);
            starve = true;
        }
        if (!starve && mSizeCount > 0) {
            const int n = mSizes[mSizeHead];
            mSizeHead = (mSizeHead + 1) % kFifoCap;
            --mSizeCount;
            return n;
        }
        // DRY FIFO, AND THERE IS NOTHING HONEST TO SEND. Not a nominal size,
        // not a zero-length packet, not the last size again: NOTHING. The
        // caller checks the depth before it fills a URB, so the only way here
        // is the injectStarve test hook above, and this path exists to serve it
        // and to be counted if anything else ever reaches it.
        //
        // Both of the alternatives this code has held in the past are now known
        // to be fatal, which is why neither is here any more:
        //
        //   A ZERO-LENGTH PACKET wedges the CP-88 permanently. One. 11 trials
        //   of 11, with a full queue and no load, cleared only by a USB reset.
        //
        //   A NOMINAL SIZE is not a substitution but an ADDITION -- the ask it
        //   stood in for is still in the FIFO and gets honoured a packet later
        //   -- so every starved packet is ~44 frames of permanent surplus. And
        //   even without that, 44,1 is not the device's rate: it asks for
        //   44,09929, so a nominal accumulator runs 43 frames ahead in a minute
        //   and reaches the ~200-frame cliff in about three and a half.
        //
        // Returning 0 means "no packet". fillOut submits a shorter URB, or none
        // at all, which is the free direction: absence costs nothing, and a gap
        // in the OUT stream discards whatever deviation the device had built up.
        mStarved.fetch_add(1, std::memory_order_relaxed);
        return 0;
    } else if (mCfg.feedback == kExplicit && mFbQ16 != 0) {
        const uint32_t unitsPerPacket = 1u << (std::clamp(mCfg.outInterval, 1, 16) - 1);
        mFbAcc += mFbQ16 * unitsPerPacket;
        const int n = static_cast<int>(mFbAcc >> 16);
        mFbAcc &= 0xFFFFu;
        return n;
    }
    // Nominal: `rate` frames per second, carried exactly.
    mNominalAcc += static_cast<int64_t>(mCfg.rate) * static_cast<int64_t>(mPeriodUs);
    const int n = static_cast<int>(mNominalAcc / 1000000);
    mNominalAcc -= static_cast<int64_t>(n) * 1000000;
    return n;
}

int UsbAudioOut::fillOut(Urb& b) {
    const int maxFrames = mCfg.outMaxPacket / mFrameBytes;
    int offset = 0;
    int filled = 0;
    for (int p = 0; p < mPackets; ++p) {
        const bool inject = mInjectSizeLeft.load(std::memory_order_relaxed) > 0 ||
                            mInjectBiasLeft.load(std::memory_order_relaxed) > 0;
        const int want = nextFrames();
        // NO ASK, NO PACKET. The FIFO was empty, so there is nothing the device
        // asked for and nothing this transport is prepared to invent. The URB
        // ends here -- short, or not submitted at all -- and serviceOut puts the
        // stream back into pre-roll. Absence is the free direction: 11,6 s of it
        // measured with no ill effect, and a gap in the OUT stream DISCARDS
        // whatever deviation the device had accumulated, which makes it curative
        // rather than merely survivable.
        if (want <= 0 && !inject) break;
        int frames = std::clamp(want, 0, maxFrames);
        if (want != frames) mFbClamp.fetch_add(1, std::memory_order_relaxed);
        // THE INVARIANT, and it is now unreachable by construction -- the push
        // rule never enqueues a zero and the line above turns one into "no
        // packet". It stays because `zeroAvoided` in the health line is the
        // alarm for exactly this, and an alarm that can only ring if something
        // above is wrong is worth its two lines. One zero-length OUT packet
        // wedges this device permanently and only a USB reset clears it.
        if (!inject && frames <= 0) {
            mZeroAvoided.fetch_add(1, std::memory_order_relaxed);
            frames = 1;
        }
        // TEST ONLY: overrule the size the invariant just guaranteed, so the
        // device can be asked directly what it refuses. See injectSize.
        if (mInjectSizeLeft.load(std::memory_order_relaxed) > 0) {
            mInjectSizeLeft.fetch_sub(1, std::memory_order_relaxed);
            frames = std::clamp(mInjectSizeFrames.load(std::memory_order_relaxed),
                                0, maxFrames);
            mShaped.fetch_add(1, std::memory_order_relaxed);
        } else if (mInjectBiasLeft.load(std::memory_order_relaxed) > 0) {
            mInjectBiasLeft.fetch_sub(1, std::memory_order_relaxed);
            frames = std::clamp(frames + mInjectBiasDelta.load(std::memory_order_relaxed),
                                0, maxFrames);
            mShaped.fetch_add(1, std::memory_order_relaxed);
        }

        // THE ASYMMETRY THIS TRANSPORT IS SHAPED BY, measured on the CP-88 and
        // left here as arithmetic rather than as a clamp:
        //
        //   sending LESS than it asked, cumulatively, is free. 10 s at one
        //   frame per packet below the request -- 227 ms of deficit -- played
        //   on. So did a hundred consecutive ONE-FRAME packets.
        //   sending MORE than it asked WEDGES IT. One frame per packet above
        //   the request: 175 packets survived, 190 wedged, every time. That is
        //   about 170 frames, 3,9 ms. It accumulates across gaps -- three
        //   bursts of 100 two seconds apart wedged it just the same -- so the
        //   device is integrating, and nothing ever drains what it integrates
        //   except a discontinuity in the stream.
        //
        // Nothing here intervenes on that any more; the pop rule makes a
        // surplus impossible at the source, because a size is only ever one the
        // device itself stated. These counters just watch. See the note in the
        // header on why the ceiling that used to sit here is gone.
        {
            const int64_t bal = mFrames.load(std::memory_order_relaxed) + frames -
                                mInFramesTotal.load(std::memory_order_relaxed);
            int64_t seen = mBalPeak.load(std::memory_order_relaxed);
            while (bal > seen &&
                   !mBalPeak.compare_exchange_weak(seen, bal,
                                                   std::memory_order_relaxed)) {}
            // THE RISE, which is the number that means something to the device.
            // `bal` tracks the device's playout FIFO up to a constant -- but
            // only while the FIFO has anything in it. A hole empties it, and an
            // empty FIFO cannot go further negative, so from that point our
            // accumulated deficit is fiction and the only honest measure is how
            // far we have REFILLED since: bal minus its own running minimum.
            // A surplus of about 170 frames wedges this device, and the deficit
            // a hole leaves behind is measured in seconds, so the interesting
            // question is not how deep the hole was but how hard the recovery
            // pushed afterwards. Nothing has ever measured that.
            if (bal < mBalMin) mBalMin = bal;
            const int64_t rise = bal - mBalMin;
            seen = mBalRise.load(std::memory_order_relaxed);
            while (rise > seen &&
                   !mBalRise.compare_exchange_weak(seen, rise,
                                                   std::memory_order_relaxed)) {}
        }

        // ONE MEMCPY. That is the whole of this thread's per-packet work with
        // the samples, and it is deliberate: see the thread contract at the top
        // of this file. The producer has already mapped channels, dithered,
        // packed to the endpoint's format and concealed any shortfall.
        //
        // Or, while the pre-roll's silence is owed, no pull at all: the render
        // ring was just emptied and it needs the time to rebuild. Pulling from
        // it here would drain what little it has and hand the device the
        // concealer's output at the exact moment the stream is restarting.
        // SILENCE IS A CORRECTLY-SIZED PACKET OF ZEROES -- `frames` of them,
        // the size the device asked for. It is never a zero-length packet.
        uint8_t* dst = b.data + offset;
        if (mSilenceFrames > 0) {
            std::memset(dst, 0, static_cast<size_t>(frames) * mFrameBytes);
            mSilenceFrames -= frames;
            mSilenceTotal.fetch_add(frames, std::memory_order_relaxed);
        } else {
            const int got = mPull ? mPull(mCtx, dst, frames) : 0;
            if (got < frames) {
                std::memset(dst + static_cast<size_t>(got) * mFrameBytes, 0,
                            static_cast<size_t>(frames - got) * mFrameBytes);
            }
        }

        // MICHIEL'S EXPERIMENT: usbhost's payload, in this process.
        //
        // usbhost has never latched and this app does, and "it has no
        // synthesiser" was withdrawn as an explanation because it explains
        // nothing. This isolates the one remaining difference in what reaches
        // the wire: the bytes. The pull above STILL RUNS, so the ring drains at
        // exactly the normal rate and the render thread keeps rendering at
        // exactly the normal load -- the render loop only works when
        // `filled < target`, so a reaper that stopped consuming would quietly
        // switch the load off and prove nothing. Same threads, same priorities,
        // same sizes, same ring traffic. Only the payload changes, to the tone
        // usbhost sends.
        if (mToneHz.load(std::memory_order_relaxed) > 0.0f) {
            const double hz = mToneHz.load(std::memory_order_relaxed);
            const double step = 2.0 * M_PI * hz / mCfg.rate;
            const int chans = std::max(1, mCfg.channels);
            const int sub = std::max(1, mCfg.subslotBytes);
            for (int f = 0; f < frames; ++f) {
                mTonePhase += step;
                if (mTonePhase > 2.0 * M_PI) mTonePhase -= 2.0 * M_PI;
                // Quiet on purpose: the verdict comes from the probe with the
                // tone switched back off, and content has been excluded anyway.
                const double v = std::sin(mTonePhase) * 0.02;
                int32_t q = static_cast<int32_t>(v * 8388607.0);
                if (sub == 2) q = static_cast<int32_t>(v * 32767.0);
                else if (sub == 4) q = static_cast<int32_t>(v * 2147483647.0);
                for (int c = 0; c < chans; ++c) {
                    uint8_t* o = dst + (static_cast<size_t>(f) * chans + c) * sub;
                    for (int k = 0; k < sub; ++k) o[k] = static_cast<uint8_t>(q >> (8 * k));
                }
            }
        }

        mTrace[mTraceAt++ & (kTraceCap - 1)] = {
                static_cast<int32_t>((nowNs() - mTraceT0) / 1000),
                static_cast<int16_t>(frames), 0};

        const int bytes = frames * mFrameBytes;
        b.u->iso_frame_desc[p].length = static_cast<unsigned>(bytes);
        b.u->iso_frame_desc[p].actual_length = 0;
        b.u->iso_frame_desc[p].status = 0;
        offset += bytes;
        ++filled;
        mFrames.fetch_add(frames, std::memory_order_relaxed);
    }
    // A URB carrying fewer packets than it was allocated for is perfectly
    // legal, and it is the honest answer to a FIFO that ran out mid-URB: send
    // what was asked for and nothing else. At one packet per URB -- the default
    // -- this is always all or nothing anyway.
    b.u->number_of_packets = filled;
    b.u->buffer_length = offset;
    return filled;
}

void UsbAudioOut::fillIn(Urb& b, int packets, int bytesPerPacket) {
    for (int p = 0; p < packets; ++p) {
        b.u->iso_frame_desc[p].length = static_cast<unsigned>(bytesPerPacket);
        b.u->iso_frame_desc[p].actual_length = 0;
        b.u->iso_frame_desc[p].status = 0;
    }
    b.u->buffer_length = packets * bytesPerPacket;
}

// ----------------------------------------------------------- completion ---

void UsbAudioOut::completeIn(Urb& b) {
    for (int p = 0; p < mInPacketsPerUrb; ++p) {
        const auto& d = b.u->iso_frame_desc[p];
        // THE PUSH RULE, and both halves of it are refusals. An IN packet with
        // a non-zero status is not a statement about the device's clock, it is
        // a transfer that failed; a zero-length one is not an ask for zero
        // frames, it is the absence of an ask. Neither pushes anything. What
        // the device DID deliver is what it wants back, and only that.
        if (d.status != 0) { mInErr.fetch_add(1, std::memory_order_relaxed); continue; }
        const int frames = static_cast<int>(d.actual_length) / mCfg.fbFrameBytes;
        // A zero pushed here would be a zero popped there, and one zero-length
        // OUT packet wedges the device for good. This path does not touch
        // `starved`, so it is the one that could do it silently. Counted, never
        // enqueued.
        if (frames <= 0) {
            mZeroAvoided.fetch_add(1, std::memory_order_relaxed);
            continue;
        }
        // Latch probe: the spread of these is the device's clock talking.
        //
        // These two were declared, reset on read and printed in the health line
        // from the day the probe was written, and NEVER ASSIGNED -- so the field
        // read `inSize=0..0` on every run and nobody noticed, because the health
        // line's guard prints 0..0 for an empty interval too. It was reported as
        // evidence that the IN sizes are identical latched and healthy. It was
        // evidence of nothing. Assigned here, which is what was always meant.
        {
            int seen = mInSizeMin.load(std::memory_order_relaxed);
            while (frames < seen &&
                   !mInSizeMin.compare_exchange_weak(seen, frames,
                                                     std::memory_order_relaxed)) {}
            seen = mInSizeMax.load(std::memory_order_relaxed);
            while (frames > seen &&
                   !mInSizeMax.compare_exchange_weak(seen, frames,
                                                     std::memory_order_relaxed)) {}
        }
        mTrace[mTraceAt++ & (kTraceCap - 1)] = {
                static_cast<int32_t>((nowNs() - mTraceT0) / 1000),
                static_cast<int16_t>(frames), 1};
        mInPackets.fetch_add(1, std::memory_order_relaxed);
        mInPacketsTotal.fetch_add(1, std::memory_order_relaxed);
        mInFramesTotal.fetch_add(frames, std::memory_order_relaxed);
        // FULL ON AN IN REAP: POP AS YOU PUSH, so the FIFO stays full and holds
        // the NEWEST asks. Something has to give -- a count dropped is frames
        // the device asked for that will never be sent -- and the oldest is the
        // one to lose: a size from 48 ms ago still describes this cadence,
        // where one from half a second ago describes a cadence the device has
        // moved on from. The frames in it are a debt that is acknowledged,
        // bounded and counted in fbDrop rather than pretended away.
        if (mSizeCount >= mFifoLen) {
            mSizeHead = (mSizeHead + 1) % kFifoCap;
            --mSizeCount;
            mFbDrop.fetch_add(1, std::memory_order_relaxed);
        }
        mSizes[(mSizeHead + mSizeCount) % kFifoCap] = frames;
        ++mSizeCount;
    }
    noteFifoDepth();
}

// The depth is the only accounting instrument this transport has, so the spread
// it covers over an interval is worth a line in the health log: resting in a
// narrow band near the pre-roll depth is healthy, riding the cap means OUT is
// not keeping up with IN, and touching zero means the stream ran dry.
//
// Called at BOTH ends of the cycle -- after a batch of asks is pushed and after
// the pops that answer them -- because sampling only one of them samples one
// phase. The first tablet run read a flat "22..22" for exactly that reason,
// which looks like perfect balance and is really one measurement repeated.
void UsbAudioOut::noteFifoDepth() {
    int seen = mFifoMin.load(std::memory_order_relaxed);
    while (mSizeCount < seen &&
           !mFifoMin.compare_exchange_weak(seen, mSizeCount,
                                           std::memory_order_relaxed)) {}
    seen = mFifoMax.load(std::memory_order_relaxed);
    while (mSizeCount > seen &&
           !mFifoMax.compare_exchange_weak(seen, mSizeCount,
                                           std::memory_order_relaxed)) {}
}

void UsbAudioOut::completeFeedback(Urb& b) {
    const auto& d = b.u->iso_frame_desc[0];
    if (d.status != 0) { mInErr.fetch_add(1, std::memory_order_relaxed); return; }

    // USB 2.0 5.12.4.2: full speed reports 10.14 in three bytes, high speed
    // 16.16 in four, both in frames per (micro)frame. Some full-speed devices
    // send four-byte 16.16 anyway; the length tells them apart.
    uint32_t q16 = 0;
    if (d.actual_length >= 4) q16 = le32(b.data);
    else if (d.actual_length == 3) q16 = le24(b.data) << 2;
    else return;

    const double unitUs = mCfg.highSpeed ? 125.0 : 1000.0;
    const double nominal = mCfg.rate * unitUs / 1e6 * 65536.0;
    const auto plausible = [&](double v) { return v > 0.75 * nominal && v < 1.25 * nominal; };
    double v = q16;
    // A value off by exactly 8x is a device reporting per frame on a high-
    // speed bus, or per microframe on a full-speed one; the kernel carries
    // quirks for both.
    if (!plausible(v) && plausible(v / 8.0)) v /= 8.0;
    else if (!plausible(v) && plausible(v * 8.0)) v *= 8.0;
    if (!plausible(v)) return;

    mFbQ16 = static_cast<uint32_t>(v);
    mFbRate.store(static_cast<float>(v / 65536.0 * 1e6 / unitUs), std::memory_order_relaxed);
}

void UsbAudioOut::completeOut(Urb& b) {
    // What was SUBMITTED, not what was allocated: a URB whose FIFO ran out
    // mid-fill carries fewer packets than mPackets, and the descriptors beyond
    // it are last cycle's.
    for (int p = 0; p < b.u->number_of_packets; ++p) {
        const auto& d = b.u->iso_frame_desc[p];
        if (d.status != 0) { mOutErr.fetch_add(1, std::memory_order_relaxed); continue; }
        // Isochronous OUT has no acknowledgement: status 0 means scheduled.
        // actual_length is the controller's account of what it put on the
        // wire, which is what deficitMs is built from.
        //
        //
        // AND WHAT WENT OUT IS COMPARED WITH WHAT WAS ASKED, which it never
        // was. Michiel's question: can a pre-empted reap thread put half a
        // frame on the wire? Not from here -- the URB is a snapshot, the
        // length is always a whole number of frames, and nothing touches the
        // buffer after submit. But this device is full speed, so behind a
        // high-speed hub every 270-byte packet goes as split transactions,
        // 188 bytes and then 82, and a transaction translator that runs out of
        // time delivers what it has. That is 31 and a third frames: a literal
        // half frame, produced by hardware, invisible to every counter we
        // print. `outShort` counts a packet the controller says it cut, and
        // `outOdd` counts one whose length is not a whole number of frames.
        // An earlier pair of counters like these read 0 throughout and were
        // removed as answered -- but that was before there was a reproducer
        // that latches three times in three, so the question is open again.
        if (d.actual_length != d.length) {
            mOutShort.fetch_add(1, std::memory_order_relaxed);
        }
        if (d.actual_length % static_cast<unsigned>(mFrameBytes) != 0) {
            mOutOdd.fetch_add(1, std::memory_order_relaxed);
        }
        mOutBytes.fetch_add(d.actual_length, std::memory_order_relaxed);
    }
}

// One reaped completion. IN is finished here and now -- pushed, refilled,
// resubmitted -- because an ask the FIFO does not hold yet is an ask that does
// not exist. OUT is only PARKED: nothing about the output is decided until the
// kernel's queue is empty. See the drain in reapLoop().
// Is this URB's context one of ours? A range check on the two vectors, which is
// O(1) and safe: they are contiguous and never resized after start().
bool UsbAudioOut::ownsContext(const void* p) const {
    auto within = [p](const std::vector<Urb>& v) {
        return !v.empty() &&
               p >= static_cast<const void*>(&v.front()) &&
               p <= static_cast<const void*>(&v.back());
    };
    return within(mOut) || within(mIn);
}

bool UsbAudioOut::dispatch(void* reaped) {
    auto* u = static_cast<usbdevfs_urb*>(reaped);
    // NOT OURS: hand it to whoever submitted it, before any cast that would
    // read it as one of our Urbs. See setForeignReaper.
    if (!ownsContext(u->usercontext)) {
        ForeignFn fn = mForeignFn;
        return fn != nullptr ? fn(mForeignCtx, u) : true;
    }
    auto* b = static_cast<Urb*>(u->usercontext);
    if (b == nullptr || !mRunning.load(std::memory_order_acquire)) return true;

    // Per direction, because the two queues are deliberately different depths.
    // Measuring an IN URB against the OUT ring made `late` meaningless the
    // moment the feedback stream went deep -- every IN URB legitimately sits
    // queued for its whole second, and the counter read 119582 in one trial.
    if (nowNs() - b->submittedNs > 2 * (b->out ? mOutRingNs : mInRingNs)) {
        mLate.fetch_add(1, std::memory_order_relaxed);
    }

    if (b->out) {
        completeOut(*b);
        mOutIdle.push_back(b);
        return true;
    }
    if (mCfg.feedback == kExplicit) {
        completeFeedback(*b);
        fillIn(*b, 1, mCfg.fbMaxPacket);
    } else {
        completeIn(*b);
        fillIn(*b, mInPacketsPerUrb, mCfg.fbMaxPacket);
    }
    return submit(*b);
}

// The OUT side of one wake-up, run once the kernel's queue is empty and every
// ask it was holding is in the FIFO.
bool UsbAudioOut::serviceOut() {
    // TEST ONLY: THE SHAPE A HOLE ACTUALLY HAS, with no load to make it.
    //
    // Under contention this transport does NOT stall symmetrically. The IN
    // stream is 128 URBs deep, so the controller keeps servicing it from
    // buffers already queued and the device's packets keep flowing; the OUT
    // stream is four URBs deep, so four milliseconds after this thread stops
    // running the OUT endpoint has nothing queued and the host puts NOTHING on
    // the wire. The uniform reap stalls tested previously do not reproduce
    // that: this does, and with no synthesiser, no spinners and no lateness.
    // It sits here rather than in the reap path so that it delays the OUT
    // submission ONLY -- IN has already been drained and resubmitted above.
    const int64_t holdUs = mHoldOutUs.load(std::memory_order_relaxed);
    if (holdUs > 0) {
        bool due = true;
        const int64_t every = mHoldOutEvery.load(std::memory_order_relaxed);
        if (every > 1) due = (++mHoldOutSeen % every) == 0;
        if (due && mHoldOutLeft.fetch_sub(1, std::memory_order_relaxed) > 0) {
            timespec ts{static_cast<time_t>(holdUs / 1000000),
                        static_cast<long>((holdUs % 1000000) * 1000)};
            nanosleep(&ts, nullptr);
        }
    }

    // WAS THE QUEUE EMPTY? Asked on the state this call was ENTERED with, and
    // asked here rather than lower down because everything below legitimately
    // empties the queue on purpose.
    //
    // The two exclusions are not conveniences, they are the whole correctness
    // of the test:
    //
    //   mPreRoll -- start-up, and every pass where the FIFO ran short. The
    //               stream is withheld deliberately; an empty queue is the
    //               intended state, not a fault.
    //   mInGap   -- a resync gap in progress, which is deliberate silence. Note
    //               the pass that ENDS a gap enters with mInGap still true, so
    //               it is excluded too. Without that, ending a gap would be
    //               scored as a dry event and -- with the trigger on -- would
    //               immediately open another one, forever.
    const bool outWasEmpty =
            !mPreRoll && !mInGap && mOutIdle.size() == mOut.size();
    if (outWasEmpty) {
        mOutEmpty.fetch_add(1, std::memory_order_relaxed);
        if (mOutEmptyResync.load(std::memory_order_relaxed) && mFifoResync) {
            // Same repair as the FIFO-full path: a timed gap, the FIFO left
            // taking asks, then a trim to the working depth on the way out.
            mGapUntilNs = nowNs() + mResyncGapUs * 1000;
            mInGap = true;
            mResyncs.fetch_add(1, std::memory_order_relaxed);
        }
    }

    // THE FIFO IS FULL: OUT HAS FALLEN BEHIND IN. Two answers, and which one is
    // right is the open question of 2026-09-25.
    //
    // RESYNC (Michiel's, the default). Clear the FIFO and drop into pre-roll.
    // The in-flight URBs drain, the wire goes quiet for a few milliseconds while
    // mFifoPreRoll fresh asks arrive, and then the stream starts again from the
    // live edge. That silence is the point: section 34 measured a gap as the one
    // thing that makes this device DISCARD the deviation it has accumulated, and
    // 2 ms is enough to do it. So instead of catching up -- which is what a host
    // that has fallen behind cannot honestly do, since it has no idea what the
    // device did with the packets it missed -- the transport takes the device
    // back to a known state.
    //
    // TRIM (what section 36 shipped). Discard the oldest asks, keep the stream
    // contiguous, carry on. Conservative, seamless, and the rig latched under it
    // with 924 gaps and a wire that was perfect to the frame -- which is exactly
    // why the other answer is worth measuring.
    //
    // The trigger is deliberately the same for both, and it is read AFTER the
    // IN queue has been drained: a depth of mFifoLen means the host is that many
    // packets behind, and with 8/4 that is 8 ms, about one OUT ring plus its
    // slack.
    if (mSizeCount >= mFifoLen && !mInGap) {
        if (mFifoResync) {
            // A TIMED gap, and the FIFO is deliberately NOT cleared. It goes on
            // taking asks while the wire is quiet -- it is full, so pop-as-you-
            // push keeps the newest -- and when the gap expires we trim to the
            // working depth and answer with sizes from NOW. That gives the
            // repair (a discontinuity) and the freshness (newest asks) without
            // paying a full pre-roll of silence for them.
            mGapUntilNs = nowNs() + mResyncGapUs * 1000;
            mInGap = true;
            mResyncs.fetch_add(1, std::memory_order_relaxed);
        } else {
            while (mSizeCount > mFifoPreRoll) {
                mSizeHead = (mSizeHead + 1) % kFifoCap;
                --mSizeCount;
                mFbDrop.fetch_add(1, std::memory_order_relaxed);
            }
            mOutTrims.fetch_add(1, std::memory_order_relaxed);
        }
    }

    // IN THE GAP: submit nothing at all until the clock says so. The OUT URBs
    // drain and park, the wire goes quiet, and the device discards whatever it
    // had accumulated.
    if (mInGap) {
        if (nowNs() < mGapUntilNs) return true;
        mInGap = false;
        // Resume on the NEWEST asks at the working depth. The ones dropped here
        // are frames the device asked for during the silence and can never be
        // delivered, which is a deficit, which is the free direction.
        while (mSizeCount > mFifoPreRoll) {
            mSizeHead = (mSizeHead + 1) % kFifoCap;
            --mSizeCount;
            mFbDrop.fetch_add(1, std::memory_order_relaxed);
        }
        // The balance restarts here for the same reason it restarts after a
        // pre-roll: for the device, a gap wipes the slate.
        mBalMin = mFrames.load(std::memory_order_relaxed) -
                  mInFramesTotal.load(std::memory_order_relaxed);
        mSilenceFrames = mFlush ? std::max(0, mFlush(mCtx)) : 0;
    }

    // Nothing to submit into. The decisions above still had to be taken,
    // because the FIFO can fill while every OUT URB is in flight.
    if (mOutIdle.empty()) return true;

    // PRE-ROLL. THE OUT STREAM IS WITHHELD ENTIRELY -- not one packet of a
    // nominal size, not a zero-length one, nothing at all -- until the FIFO
    // holds mFifoPreRoll asks again.
    //
    // This is the start-up path and the recovery path, deliberately the same
    // code. It is also, unexpectedly, the repair: a gap in the OUT stream makes
    // the device DISCARD whatever deviation it had accumulated, measured, with
    // 2 ms of gap enough to do it. So the thing that looks like giving up is
    // the thing that puts the device back at zero, and the depth we rebuild to
    // is the phase lag we then run at -- a permanent deficit, the free
    // direction.
    if (mPreRoll) {
        if (mSizeCount < mFifoPreRoll) return true;
        mPreRoll = false;
        mPreRolls.fetch_add(1, std::memory_order_relaxed);
        // AND THE BALANCE STARTS AGAIN FROM HERE, because for the device it
        // does. A gap discards whatever deviation it had accumulated, so the
        // frames it asked for during the pre-roll and never got are not a debt
        // it is still carrying -- and leaving the floor down there made `rise`
        // read a permanent ~440 off a trough that no longer exists. Measured
        // the first time this ran on the tablet: rise=441 with a flat FIFO and
        // nothing wrong anywhere. An alarm that is always ringing is not one.
        mBalMin = mFrames.load(std::memory_order_relaxed) -
                  mInFramesTotal.load(std::memory_order_relaxed);
        // EMPTY THE RENDER BUFFER, and take back the silence it owes. Whatever
        // is in the ring is audio that was due during the gap; carrying it
        // would turn every gap into permanent added latency, and the device
        // absorbs about 110 frames, 2,7 ms, and no more.
        mSilenceFrames = mFlush ? std::max(0, mFlush(mCtx)) : 0;
    }

    size_t sent = 0;
    bool ok = true;
    for (Urb* b : mOutIdle) {
        // THE POP RULE. One count per packet, at exactly that size, and if the
        // FIFO cannot fill this URB then this URB is not submitted. Nothing is
        // ever manufactured, in either direction: a size the device did not
        // state is fatal and cumulative, and absence is free.
        if (mSizeCount < mPackets || fillOut(*b) <= 0) {
            mPreRoll = true;
            break;
        }
        ++sent;
        if (!submit(*b)) { ok = false; break; }
    }
    if (sent > 0) {
        mOutIdle.erase(mOutIdle.begin(),
                       mOutIdle.begin() + static_cast<std::ptrdiff_t>(sent));
        noteFifoDepth();
    }
    return ok;
}

void UsbAudioOut::reapLoop() {
    // SCHED_FIFO if the system grants it, else nice -19 -- the framework's
    // THREAD_PRIORITY_URGENT_AUDIO, measured to be granted to an ordinary app
    // on this tablet. Logged either way, because a silently refused priority
    // would show up only as unexplained holes.
    // The highest band an app is given, and above the render thread: this is
    // the thread that actually puts audio on the wire.
    bos::raiseRenderPriority("usb reap thread", bos::kTransportNice);
    // The deadline is one URB: if this thread has not reaped, refilled and
    // resubmitted within that, the endpoint runs dry.
    mHint.start({gettid()},
                static_cast<int64_t>(mPackets * mPeriodUs * 1000.0));

    int64_t lastReap = nowNs();

    while (mRunning.load(std::memory_order_acquire)) {
        void* p = nullptr;
        if (ioctl(mCfg.fd, USBDEVFS_REAPURB, &p) < 0) {
            if (errno == EINTR) continue;
            if (mRunning.load(std::memory_order_acquire)) {
                setFatal(errno == ENODEV ? std::string("device disconnected")
                                         : std::string("REAPURB failed: ") + std::strerror(errno));
            }
            break;
        }
        const int64_t t = nowNs();
        const int64_t gapUs = (t - lastReap) / 1000;

        if (gapUs > mMaxGapUs.load(std::memory_order_relaxed)) {
            mMaxGapUs.store(gapUs, std::memory_order_relaxed);
        }
        // HOW OFTEN THE OUT ENDPOINT RAN DRY, which is not the same question as
        // how long it was dry for, and only the second one has ever been
        // measured. `deficit` is total absence: the run that latches shows 73
        // ms of it, and a run of forty 300 ms holes shows 11 612 ms and plays
        // on. So the dose is not the variable. The count might be, because
        // every URB here carries ISO_ASAP: a ring that merely runs late is
        // resumed, but a ring that EMPTIES is restarted at whatever frame the
        // controller has reached by then. Forty big holes are forty restarts;
        // a render thread jittering either side of six milliseconds could be
        // thousands, for almost no total absence at all.
        if (gapUs > mOutRingUs) mOutDry.fetch_add(1, std::memory_order_relaxed);
        lastReap = t;

        if (!dispatch(p)) break;

        // DRAIN THE QUEUE TO EAGAIN BEFORE ANYTHING IS DECIDED. This is the
        // ordering the whole transport rests on and it is not optional.
        //
        // One REAPURB returns ONE completion, and the kernel holds whatever
        // else is ready: 0-2 when this thread is keeping up, but 18-19 measured
        // after a 30 ms stall. At 8 packets per IN URB that is up to ~150 asks
        // sitting unread -- and the FIFO depth is the only accounting
        // instrument that exists here, because usbfs will not tell us a frame
        // number by any route (probed exhaustively, section 35). Deciding
        // anything about the OUT side against a depth that stale is deciding
        // against a number that is simply wrong, and the trim would throw away
        // asks that had already arrived.
        //
        // So: every completion the kernel is holding, IN first by construction
        // -- pushed and resubmitted -- and only then serviceOut.
        bool drained = true;
        for (;;) {
            void* q = nullptr;
            if (ioctl(mCfg.fd, USBDEVFS_REAPURBNDELAY, &q) < 0) break;
            if (!dispatch(q)) { drained = false; break; }
        }
        if (!drained) break;

        if (!serviceOut()) break;

        // WHAT THE WORK ACTUALLY COST, which is not the same as the period.
        //
        // The first version of this reported the interval from one submit to
        // the next reap, which is dominated by blocking in REAPURB waiting for
        // the controller -- roughly the whole 2 ms period. Telling the governor
        // that work costing tens of microseconds takes the entire deadline is
        // simply false, and it asks for a permanent clock boost on a thread
        // that is asleep for most of it. Measured here instead: reap returned
        // at `t`, everything since then is completing, refilling and
        // resubmitting, and that is the CPU time the deadline is about.
        mHint.report(nowNs() - t);
    }
    mHint.stop();
    mRunning.store(false, std::memory_order_release);
}

UsbAudioOut::Stats UsbAudioOut::stats() const {
    Stats s;
    s.frames = mFrames.load(std::memory_order_relaxed);
    s.outBytes = mOutBytes.load(std::memory_order_relaxed);
    s.outErrors = mOutErr.load(std::memory_order_relaxed);
    s.inErrors = mInErr.load(std::memory_order_relaxed);
    s.starved = mStarved.load(std::memory_order_relaxed);
    s.late = mLate.load(std::memory_order_relaxed);
    // Reset on read: each sample describes its own interval.
    s.inSizeMin = mInSizeMin.exchange(1 << 30, std::memory_order_relaxed);
    s.inSizeMax = mInSizeMax.exchange(0, std::memory_order_relaxed);
    s.inPackets = mInPackets.exchange(0, std::memory_order_relaxed);
    s.inFramesTotal = mInFramesTotal.load(std::memory_order_relaxed);
    s.fbDrop = mFbDrop.load(std::memory_order_relaxed);
    s.fbClamp = mFbClamp.load(std::memory_order_relaxed);
    s.zeroAvoided = mZeroAvoided.load(std::memory_order_relaxed);
    s.shaped = mShaped.load(std::memory_order_relaxed);
    s.balance = mFrames.load(std::memory_order_relaxed) -
                mInFramesTotal.load(std::memory_order_relaxed);
    s.balPeak = mBalPeak.exchange(std::numeric_limits<int64_t>::min(),
                                  std::memory_order_relaxed);
    s.balRise = mBalRise.exchange(0, std::memory_order_relaxed);
    s.outShort = mOutShort.load(std::memory_order_relaxed);
    s.outOdd = mOutOdd.load(std::memory_order_relaxed);
    s.outDry = mOutDry.load(std::memory_order_relaxed);
    s.outEmpty = mOutEmpty.load(std::memory_order_relaxed);
    s.preRolls = mPreRolls.load(std::memory_order_relaxed);
    s.silenceFrames = mSilenceTotal.load(std::memory_order_relaxed);
    s.outTrims = mOutTrims.load(std::memory_order_relaxed);
    s.resyncs = mResyncs.load(std::memory_order_relaxed);
    // Reset on read, like the other spreads: each sample its own interval.
    s.fifoMin = mFifoMin.exchange(1 << 30, std::memory_order_relaxed);
    s.fifoMax = mFifoMax.exchange(0, std::memory_order_relaxed);
    s.maxGapMs = mMaxGapUs.load(std::memory_order_relaxed) / 1000.0f;
    s.feedbackRate = mFbRate.load(std::memory_order_relaxed);
    const int64_t start = mStartNs.load(std::memory_order_relaxed);
    if (start != 0 && mFrameBytes > 0 && mCfg.rate > 0) {
        const double wallMs = (nowNs() - start) / 1e6;
        const double wireMs = (static_cast<double>(s.outBytes) / mFrameBytes) * 1000.0 / mCfg.rate;
        s.deficitMs = static_cast<float>(wallMs - wireMs);

        // The device emits one IN packet per service interval on its own clock,
        // so what it has sent since the stream started is the elapsed time in
        // periods. The shortfall against what we actually received is packets
        // that landed with no URB queued to catch them -- requests destroyed
        // before they were read. The first URBs are primed before the clock
        // starts, so a small negative is possible; floor it.
        const double expected = wallMs * 1000.0 / mPeriodUs;
        const int64_t got = mInPacketsTotal.load(std::memory_order_relaxed);
        const int64_t lost = static_cast<int64_t>(expected) - got;
        s.inLostPackets = lost > 0 ? lost : 0;
    }
    return s;
}

// Oldest first, so the end of the file is the end of the stream. Written from
// the UI thread while the reap thread is still running, which is deliberate: a
// few torn entries at the write cursor cost nothing and stopping the stream to
// read it would destroy the state being examined.
void UsbAudioOut::dumpTrace(const char* path) const {
    FILE* f = std::fopen(path, "w");
    if (f == nullptr) return;
    std::fprintf(f, "# us kind frames   (kind 0 = OUT submitted, 1 = IN completed)\n");
    const int64_t n = std::min<int64_t>(mTraceAt, kTraceCap);
    const int64_t start = mTraceAt - n;
    for (int64_t i = start; i < mTraceAt; ++i) {
        const TraceEntry& e = mTrace[i & (kTraceCap - 1)];
        std::fprintf(f, "%d %d %d\n", e.tUs, e.kind, e.frames);
    }
    std::fclose(f);
}
