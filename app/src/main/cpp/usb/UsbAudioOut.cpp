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

bool UsbAudioOut::start(const Config& config, PullFn pull, void* ctx, std::string* error) {
    stop();
    mCfg = config;
    mPull = pull;
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
    // Separately, and far deeper: see Config::inBufferMs. Never fewer than the
    // OUT count, because the feedback stream must never be the shallower one.
    mInUrbs = std::clamp(static_cast<int>(std::lround(mCfg.inBufferMs / urbMs)),
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
    mBalanceHeld = 0;
    mBalMin = std::numeric_limits<int64_t>::max();
    mBalPeak = std::numeric_limits<int64_t>::min();
    mBalRise = 0;
    mOutShort = 0; mOutOdd = 0;
    mOutDry = 0;
    mTrace.assign(kTraceCap, TraceEntry{});
    mTraceAt = 0;
    mTraceT0 = nowNs();
    mFifoFrames = 0;
    mFifoPeak = 0;
    mFifoCeiling = static_cast<int64_t>(mUrbs) * mPackets * mCfg.rate / 1000 +
                   kFifoMarginFrames;
    mFbRate = static_cast<float>(mCfg.rate);

    if (!allocStream(mOut, mUrbs, mPackets, mCfg.outEp, true, mCfg.outMaxPacket)) {
        freeStream(mOut);
        return fail("out of memory for OUT URBs");
    }
    if (mCfg.feedback == kImplicit) {
        if (mCfg.fbFrameBytes <= 0) { freeStream(mOut); return fail("implicit feedback without a frame size"); }
        if (!allocStream(mIn, mInUrbs, mPackets, mCfg.fbEp, false, mCfg.fbMaxPacket)) {
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
        fillIn(b, mCfg.feedback == kExplicit ? 1 : mPackets, mCfg.fbMaxPacket);
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
            auto* b = static_cast<Urb*>(u->usercontext);
            if (b == nullptr || b->out) continue;
            if (mCfg.feedback == kExplicit) {
                completeFeedback(*b);
                fillIn(*b, 1, mCfg.fbMaxPacket);
            } else {
                completeIn(*b);
                fillIn(*b, mPackets, mCfg.fbMaxPacket);
            }
            if (!submit(*b)) { stop(); if (error) *error = fatal(); return false; }
        }
    }

    for (Urb& b : mOut) {
        fillOut(b);
        if (!submit(b)) { stop(); if (error) *error = fatal(); return false; }
    }

    // Only now does a second thread exist, and from here on mSizes is touched
    // by that thread alone.
    mThread = std::thread([this] { reapLoop(); });

    LOGI("usb out: ep 0x%02x %s, %d ch x %d B @ %d Hz, period %.0f us, "
         "%d URBs x %d packets = %.1f ms out / %d URBs = %.0f ms in, feedback %s",
         mCfg.outEp, mCfg.highSpeed ? "high speed" : "full speed", mCfg.channels,
         mCfg.subslotBytes, mCfg.rate, mPeriodUs, mUrbs, mPackets, mUrbs * urbMs,
         mCfg.feedback == kImplicit ? mInUrbs : 0,
         mCfg.feedback == kImplicit ? mInUrbs * urbMs : 0.0,
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
            mSizeHead = (mSizeHead + 1) % kSizeCap;
            --mSizeCount;
            return n;
        }
        // DRY QUEUE: SEND A NOMINAL SIZE. NEVER A ZERO-LENGTH PACKET.
        //
        // This said the opposite until 2026-09-23, and the reasoning was sound
        // and the conclusion was wrong. A zero-length packet is legal on an
        // isochronous OUT endpoint and it preserves the cumulative frame count
        // exactly, where an invented size is a permanent surplus. On the
        // principle, sending nothing is the honest choice.
        //
        // Measured: **a single zero-length OUT packet wedges the CP-88
        // permanently.** One. It takes a USB reset to clear, and the device
        // gives no indication of it anywhere. Injected deliberately with a full
        // queue, no load and no lateness: 11 trials of 11 silent, against a
        // control of 3 of 3 audible. A wrong-but-plausible size does NOT do it
        // -- 1x44, 1x45 and 20x44 unrequested frames all played on happily.
        // So it is the zero length specifically, not the guess.
        //
        // That also settles an A/B from earlier in this hunt that came back
        // false -> audible, true -> silent and was dismissed as noise. It was
        // the right answer.
        //
        // So: the surplus is real, it is counted in fbDrift, and it is the
        // lesser evil by a wide margin. In practice this path should never run
        // at all -- the 1024 ms feedback capture means the queue has a recent
        // size waiting even after a 300 ms deschedule -- and `starved` counting
        // up says it did.
        mStarved.fetch_add(1, std::memory_order_relaxed);
        mNominalAcc += static_cast<int64_t>(mCfg.rate) * static_cast<int64_t>(mPeriodUs);
        {
            const int n = static_cast<int>(mNominalAcc / 1000000);
            mNominalAcc -= static_cast<int64_t>(n) * 1000000;
            return n > 0 ? n : 1;
        }
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

void UsbAudioOut::fillOut(Urb& b) {
    const int maxFrames = mCfg.outMaxPacket / mFrameBytes;
    int offset = 0;
    for (int p = 0; p < mPackets; ++p) {
        const int want = nextFrames();
        int frames = std::clamp(want, 0, maxFrames);
        if (want != frames) mFbClamp.fetch_add(1, std::memory_order_relaxed);
        // THE INVARIANT, enforced at the only place that can enforce it: no OUT
        // packet is ever submitted with zero length. One of those is fatal to
        // this device and recoverable only by USBDEVFS_RESET, so no path above
        // -- dry queue, a zero-length IN packet echoed back, a clamp, or
        // anything added later -- gets to produce one. If this ever fires, the
        // rig came within a single packet of wedging and something above is
        // wrong; `zeroAvoided` is in the health line for exactly that.
        if (frames <= 0) {
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

        // THE BALANCE CEILING, and it is the whole of what this device asks of
        // a host. Measured on the CP-88, 2026-09-23, with everything else
        // normal and no lateness anywhere:
        //
        //   sending LESS than it asked, cumulatively, is free. 10 s at one
        //   frame per packet below the request -- 227 ms of deficit -- played
        //   on. So did a hundred consecutive ONE-FRAME packets.
        //   sending MORE than it asked WEDGES IT. One frame per packet above
        //   the request: 175 packets survived, 190 wedged, every time. That is
        //   about 170 frames, 3,9 ms. It accumulates across gaps -- three
        //   bursts of 100 two seconds apart wedged it just the same -- so the
        //   device is integrating, and nothing ever drains what it integrates.
        //
        // So the rule is not symmetric and the transport must not treat it as
        // if it were: cumulative frames sent may never exceed cumulative frames
        // asked. This is that rule, enforced at the only place that can enforce
        // it, and it makes the fault impossible rather than rare. A packet cut
        // down by it is a deficit, which costs a fraction of a millisecond of
        // audio and is proven harmless; the alternative is a dead device and a
        // USB reset in the middle of a piece.
        //
        // The model is a SATURATING accumulator, and the saturation is the
        // whole point. Cumulative sent-minus-asked is not what the device
        // feels: its FIFO stops at empty, so the twelve seconds of deficit a
        // hole leaves in our books are fiction to it, and a ceiling placed on
        // that number sits hundreds of frames the wrong side of the cliff. A
        // level that cannot go below zero tracks the thing that can overflow.
        if (mBalanceGuard.load(std::memory_order_relaxed)) {
            const int64_t room = mFifoCeiling - mFifoFrames;
            if (frames > room) {
                // Never zero, whatever the arithmetic says: see the invariant.
                frames = static_cast<int>(std::max<int64_t>(1, room));
                mBalanceHeld.fetch_add(1, std::memory_order_relaxed);
            }
        }
        mFifoFrames += frames;
        {
            int64_t seen = mFifoPeak.load(std::memory_order_relaxed);
            while (mFifoFrames > seen &&
                   !mFifoPeak.compare_exchange_weak(seen, mFifoFrames,
                                                    std::memory_order_relaxed)) {}
        }
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
        uint8_t* dst = b.data + offset;
        const int got = mPull ? mPull(mCtx, dst, frames) : 0;
        if (got < frames) {
            std::memset(dst + static_cast<size_t>(got) * mFrameBytes, 0,
                        static_cast<size_t>(frames - got) * mFrameBytes);
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
        mFrames.fetch_add(frames, std::memory_order_relaxed);
    }
    b.u->buffer_length = offset;
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
    for (int p = 0; p < mPackets; ++p) {
        const auto& d = b.u->iso_frame_desc[p];
        if (d.status != 0) { mInErr.fetch_add(1, std::memory_order_relaxed); continue; }
        // What the device delivered is what it wants back: the whole of the
        // implicit feedback loop.
        const int frames = static_cast<int>(d.actual_length) / mCfg.fbFrameBytes;
        // A zero-length IN packet would be echoed as a zero-length OUT packet,
        // and one of those wedges the device for good. This path does not touch
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
        // The device consumes what it asks for, so its IN packets are the only
        // statement of its drain we get. Saturating at empty: it cannot owe us.
        mFifoFrames -= frames;
        if (mFifoFrames < 0) mFifoFrames = 0;
        mTrace[mTraceAt++ & (kTraceCap - 1)] = {
                static_cast<int32_t>((nowNs() - mTraceT0) / 1000),
                static_cast<int16_t>(frames), 1};
        mInPackets.fetch_add(1, std::memory_order_relaxed);
        mInPacketsTotal.fetch_add(1, std::memory_order_relaxed);
        mInFramesTotal.fetch_add(frames, std::memory_order_relaxed);
        if (mSizeCount == kSizeCap) {
            // THE QUEUE IS FULL AND SOMETHING MUST GIVE. Dropping the oldest
            // request -- what this did before, "keep the newest" -- breaks the
            // contract: that many frames were asked for and will never be sent.
            // Dropping the NEWEST is equally a break, so neither choice is
            // clean at this point; what matters is that it is counted rather
            // than silent, because a contract broken without a counter is one
            // nobody can argue about later. Measured at 0 across every latch
            // reproduced so far, so this path is not the one that bites.
            mSizeHead = (mSizeHead + 1) % kSizeCap;
            --mSizeCount;
            mFbDrop.fetch_add(1, std::memory_order_relaxed);
        }
        mSizes[(mSizeHead + mSizeCount) % kSizeCap] = frames;
        ++mSizeCount;
    }
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
    for (int p = 0; p < mPackets; ++p) {
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
    // Per direction, because the two queues are deliberately different depths
    // now. Measuring IN URBs against the OUT ring made `late` meaningless the
    // moment the feedback stream went deep -- every IN URB legitimately sits
    // queued for its whole 256 ms, and the counter read 119582 in one trial.
    const int64_t outRingNs =
            static_cast<int64_t>(mUrbs * mPackets * mPeriodUs * 1000.0);
    mOutRingUs = static_cast<int64_t>(mUrbs * mPackets * mPeriodUs);
    const int64_t inRingNs =
            static_cast<int64_t>(mInUrbs * mPackets * mPeriodUs * 1000.0);

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

        auto* u = static_cast<usbdevfs_urb*>(p);
        auto* b = static_cast<Urb*>(u->usercontext);
        if (b == nullptr || !mRunning.load(std::memory_order_acquire)) continue;

        // CAN WE SCHEDULE EXPLICITLY? This decides whether the last structural
        // difference from snd-usb-audio is fixable at all.
        //
        // Every URB here carries USBDEVFS_URB_ISO_ASAP, so a drained endpoint
        // does not RESUME the stream, it starts a new one at whatever frame the
        // controller has reached. The kernel driver sets ASAP only on the first
        // URB and schedules the rest by explicit start_frame, which keeps the
        // stream contiguous across a late submission. Doing the same needs the
        // controller to tell us frame numbers; usbhost recorded start_frame as
        // 0 for every URB on this tablet, which would make it impossible.
        // Logged for the first few of each direction, with USBDEVFS_GETFRAME
        // alongside as the other possible source.
        if (t - b->submittedNs > 2 * (b->out ? outRingNs : inRingNs)) {
            mLate.fetch_add(1, std::memory_order_relaxed);
        }

        if (b->out) {
            // TEST ONLY: THE SHAPE A HOLE ACTUALLY HAS, with no load to make it.
            //
            // Under contention this transport does NOT stall symmetrically.
            // The IN stream is 512 URBs deep, so the controller keeps servicing
            // it from buffers already queued and the device's packets keep
            // flowing; the OUT stream is three URBs deep, so six milliseconds
            // after this thread stops running the OUT endpoint has nothing
            // queued and the host puts NOTHING on the wire. The uniform reap
            // stalls tested previously do not reproduce that: this does, and it
            // does it with no synthesiser, no spinners and no lateness.
            const int64_t holdUs = mHoldOutUs.load(std::memory_order_relaxed);
            const int64_t every = mHoldOutEvery.load(std::memory_order_relaxed);
            bool due = true;
            if (every > 1) due = (++mHoldOutSeen % every) == 0;
            if (holdUs > 0 && due &&
                mHoldOutLeft.fetch_sub(1, std::memory_order_relaxed) > 0) {
                timespec ts{static_cast<time_t>(holdUs / 1000000),
                            static_cast<long>((holdUs % 1000000) * 1000)};
                nanosleep(&ts, nullptr);
            }
            completeOut(*b);
            fillOut(*b);
        } else if (mCfg.feedback == kExplicit) {
            completeFeedback(*b);
            fillIn(*b, 1, mCfg.fbMaxPacket);
        } else {
            completeIn(*b);
            fillIn(*b, mPackets, mCfg.fbMaxPacket);
        }
        if (!submit(*b)) break;

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
    s.fifoPeak = mFifoPeak.exchange(0, std::memory_order_relaxed);
    s.outShort = mOutShort.load(std::memory_order_relaxed);
    s.outOdd = mOutOdd.load(std::memory_order_relaxed);
    s.outDry = mOutDry.load(std::memory_order_relaxed);
    s.balanceHeld = mBalanceHeld.load(std::memory_order_relaxed);
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
