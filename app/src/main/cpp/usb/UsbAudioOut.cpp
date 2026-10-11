#include "UsbAudioOut.h"

#include "../RealtimePriority.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <sched.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <ctime>

#define LOG_TAG "bos-usb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// How hard to drive it; device-independent, and the values fbx was validated
// with (CP88-USB-AUDIO.md, "Parameters").
constexpr int kLead = 1;            // frames the ring must still hold beyond the current one
constexpr int kSlack = 1;           // frame rounding of the clock estimate
constexpr int kIstUs = 500;         // controller isochronous scheduling threshold
constexpr int kAnchorPpm = 200;     // fastest the frame clock may drift against CLOCK_MONOTONIC
constexpr int kInUrbs = 512;        // IN URBs kept queued: memory, not latency
// A loop away longer than this has let the IN queue run dry (kHoldStarve).
constexpr int kStarveMargin = 32;   // packets short of the whole queue: reap and resubmit take time
// After a starved queue, IN packets observed before the first append, so the
// fresh anchor is the earliest of a window rather than one late giveback.
constexpr int kReanchorPackets = 200;
constexpr int kMinDepth = 2 + kLead + kSlack;
constexpr int kMaxDepth = 64;
constexpr int kLevelUnknown = 0x7fffffff;

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

usbdevfs_urb* newUrb(int ep, int maxPacket) {
    auto* u = static_cast<usbdevfs_urb*>(
            calloc(1, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc)));
    if (u == nullptr) return nullptr;
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = static_cast<unsigned char>(ep);
    u->number_of_packets = 1;
    u->buffer = calloc(1, static_cast<size_t>(maxPacket));
    if (u->buffer == nullptr) { free(u); return nullptr; }
    return u;
}

}  // namespace

// ----------------------------------------------------------------- profile ---

UsbAudioOut::Profile UsbAudioOut::profileFor(uint16_t vendorId, uint16_t productId) {
    Profile p;
    // Yamaha CP-88 / CP73 (one USB ID for both). Measured on a bench that saw
    // every frame on the wire and the FIFO at the line out: it resumes 176
    // frames above empty after 4 packets, and a packet landing 132,7-135,5
    // frames below that has killed it. See CP88-USB-AUDIO.md.
    if (vendorId == 0x0499 && productId == 0x172b) {
        p.rate = 44100;
        p.startFill = 176;
        p.refill = 4;
        p.dangerLo = 132.7;
        p.dangerHi = 135.5;
        p.jitter = 1.0;
    }
    return p;
}

// -------------------------------------------------------------------- plan ---
//
// A gap of G packets moves the FIFO down G * nominal frames. A level can kill
// when the first packet after a gap meets it inside [dangerLo, dangerHi], or
// when the FIFO parks there; widened by the jitter, that band repeats every
// `nominal` frames above itself. Between the repeats lie safe zones. Running in
// the middle of the deepest safe zone keeps every level a gap can produce as
// far from a band as the geometry allows. The descent passes each shallower
// zone at its middle.

UsbAudioOut::Plan UsbAudioOut::derive(const Profile& d, double g) {
    Plan o;
    const double lo = d.dangerLo - d.jitter, hi = d.dangerHi + d.jitter;
    if (d.startFill > 0 && d.refill > 0 && d.dangerHi > 0 && hi - lo < g) {
        for (int n = 0; n < kMaxZones; ++n) {
            const double shallow = hi - (n + 1) * g, deep = lo - n * g;
            if ((shallow + deep) / 2 <= 0) break;          // above the start fill
            o.zoneShallow[n] = shallow > 0 ? shallow : 0;
            o.zoneDeep[n] = deep;
            o.zoneMid[n] = static_cast<int>(std::lround((shallow + deep) / 2));
            o.nZones = n + 1;
        }
    }
    o.opPoint = o.nZones ? o.zoneMid[0] : 0;
    return o;
}

// --------------------------------------------------------------- lifecycle ---

bool UsbAudioOut::start(const Config& config, PullFn pull, void* ctx, std::string* error) {
    stop();
    mCfg = config;
    mPull = pull;
    mCtx = ctx;
    mFatal.clear();
    mFatalSet.store(0, std::memory_order_relaxed);
    mStopping.store(false, std::memory_order_relaxed);

    auto fail = [&](const char* why) {
        if (error) *error = why;
        LOGE("start refused: %s", why);
        return false;
    };
    if (mCfg.fd < 0) return fail("no usbfs descriptor");
    if (mCfg.outEp == 0 || mCfg.outMaxPacket <= 0) return fail("no OUT endpoint");
    if (mCfg.inEp == 0 || mCfg.inMaxPacket <= 0 || mCfg.inFrameBytes <= 0)
        return fail("direct USB drives implicit-feedback devices only");
    if (mCfg.outIface < 0 || mCfg.outAlt < 0) return fail("no OUT interface");
    if (mCfg.subslotBytes < 2 || mCfg.subslotBytes > 4) return fail("unsupported sample width");
    if (mCfg.channels < 1 || mCfg.rate <= 0) return fail("bad format");

    // One packet per service interval: 2^(bInterval-1) frames or microframes.
    const int64_t unitNs = mCfg.highSpeed ? 125000 : 1000000;
    mPeriodNs = unitNs << (std::clamp(mCfg.outInterval, 1, 16) - 1);
    mOutFrameBytes = mCfg.channels * mCfg.subslotBytes;
    mMaxFrames = mCfg.outMaxPacket / mOutFrameBytes;
    if (mMaxFrames < 1) return fail("OUT packet smaller than one frame");
    mNominal = mCfg.rate * static_cast<double>(mPeriodNs) / 1e9;
    // A profile measured at another rate describes another FIFO.
    if (mCfg.profile.rate != mCfg.rate) mCfg.profile = Profile{};
    mPlan = derive(mCfg.profile, mNominal);
    mDepth = std::clamp(mCfg.depth, kMinDepth, kMaxDepth);

    mOut.assign(static_cast<size_t>(mDepth + 2), Urb{});
    mIn.assign(static_cast<size_t>(kInUrbs), Urb{});
    for (Urb& w : mOut) {
        w.out = true;
        if ((w.u = newUrb(mCfg.outEp, mCfg.outMaxPacket)) == nullptr) {
            freeUrbs();
            return fail("out of memory for OUT URBs");
        }
        w.u->usercontext = &w;
    }
    for (Urb& w : mIn) {
        if ((w.u = newUrb(mCfg.inEp, mCfg.inMaxPacket)) == nullptr) {
            freeUrbs();
            return fail("out of memory for IN URBs");
        }
        w.u->usercontext = &w;
    }

    mQ = mQTail = nullptr;
    mQueued = 0;
    mState = kAbsent;
    mAbsentUntil = 0;
    mInPackets = 0;
    mVerifyMark = 0;
    mVerifyArmed = false;
    mAskR = mAskW = 0;
    mAnchor = mAnchorT = 0;
    mSinceRestart = mRefillSum = 0;
    mLevel = kLevelUnknown;
    mFrames = 0; mOutBytes = 0; mHolds = 0; mRestarts = 0; mAltErrors = 0;
    mOutLost = 0; mInBad = 0; mDescent = 0; mAskedFrames = 0;
    for (auto& h : mHoldsBy) h.store(0, std::memory_order_relaxed);
    mQueuedNow = 0;
    mMaxGapNs = 0;
    mRateAsked = 0;
    mRateNs = 0;

    // The IN stream first: it is the clock and the source of every size. The
    // OUT stream starts on the reap thread, once the asks are in hand.
    for (Urb& w : mIn) {
        submitIn(w);
        if (!w.queued) {
            const int e = errno;
            mRunning.store(true, std::memory_order_release);   // so stop() collects
            stop();
            if (error) *error = std::string("SUBMITURB IN failed: ") + std::strerror(e);
            LOGE("start refused: SUBMITURB IN failed: %s", std::strerror(e));
            return false;
        }
    }

    mStartNs.store(nowNs(), std::memory_order_relaxed);
    mRunning.store(true, std::memory_order_release);
    mHostState.store(0, std::memory_order_relaxed);
    if (mCfg.hostDeviceId > 0 && openHost()) {
        for (int i = 0; i < 1000 && mHostState.load(std::memory_order_acquire) == 0; ++i)
            usleep(1000);
    }
    // Whoever claims the loop first runs it: the callback (1) or an own thread (2).
    int none = 0;
    if (mHostState.compare_exchange_strong(none, 2)) {
        if (mHost) {
            LOGE("usb out: the AAudio callback never came; reaping on an own thread");
            closeHost();
        }
        mThread = std::thread([this] { reapLoop(); });
    }

    LOGI("usb out (fbx): ep 0x%02x %s, %d ch x %d B @ %d Hz, period %.0f us, "
         "depth %d packets (%.1f ms), %d IN URBs, op point %d below %d%s",
         mCfg.outEp, mCfg.highSpeed ? "high speed" : "full speed", mCfg.channels,
         mCfg.subslotBytes, mCfg.rate, mPeriodNs / 1000.0, mDepth,
         mDepth * mPeriodNs / 1e6, kInUrbs, mPlan.opPoint, mCfg.profile.startFill,
         mCfg.profile.startFill > 0 ? "" : " (no measured profile: no descent)");
    return true;
}

// The OUT queue goes out whole: a URB cancelled on the wire can reach a device
// behind a hub as a cut packet, and a cut packet latches the CP-88. So the reap
// thread is asked to stop appending and leaves once its queue is empty -- a few
// milliseconds. Only IN URBs are cancelled, and OUT ones only if they never
// come back at all.
void UsbAudioOut::stop() {
    if (!mThread.joinable() && mOut.empty() && mIn.empty()) return;
    mStopping.store(true, std::memory_order_release);

    auto waitThread = [this](int ms) {
        for (int i = 0; i < ms && mRunning.load(std::memory_order_acquire); ++i) usleep(1000);
        return !mRunning.load(std::memory_order_acquire);
    };
    if ((mThread.joinable() || mHost) && !waitThread(200)) {
        // No completion has woken it. Cancelling IN URBs is harmless and wakes it.
        for (Urb& w : mIn) if (w.queued) ioctl(mCfg.fd, USBDEVFS_DISCARDURB, w.u);
        if (!waitThread(200)) {
            LOGE("usb out: the OUT queue did not come back; cancelling it");
            for (Urb& w : mOut) if (w.queued) ioctl(mCfg.fd, USBDEVFS_DISCARDURB, w.u);
        }
    }
    if (mThread.joinable()) mThread.join();
    closeHost();                        // its callback has returned: the loop is over

    // From here this thread is the only reaper. Cancel what is left and collect
    // every completion before the memory goes: the kernel holds a pointer to
    // each URB until it has been reaped.
    for (Urb& w : mIn) if (w.queued) ioctl(mCfg.fd, USBDEVFS_DISCARDURB, w.u);
    for (Urb& w : mOut) if (w.queued) ioctl(mCfg.fd, USBDEVFS_DISCARDURB, w.u);
    for (int idle = 0; idle < 2;) {
        void* p = nullptr;
        if (ioctl(mCfg.fd, USBDEVFS_REAPURBNDELAY, &p) == 0) {
            idle = 0;
            auto* u = static_cast<usbdevfs_urb*>(p);
            if (ownsContext(u->usercontext)) {
                static_cast<Urb*>(u->usercontext)->queued = false;
            } else if (ForeignFn fn = mForeignFn.load(std::memory_order_acquire)) {
                fn(mForeignCtx.load(std::memory_order_relaxed), u);   // MIDI: resubmits
            }
        } else if (errno != EAGAIN) {
            break;                                     // the device is gone
        } else {
            ++idle;
            usleep(2000);
        }
    }
    usbdevfs_setinterface si{};
    si.interface = static_cast<unsigned>(mCfg.outIface);
    si.altsetting = 0;
    ioctl(mCfg.fd, USBDEVFS_SETINTERFACE, &si);
    si.interface = static_cast<unsigned>(mCfg.inIface);
    ioctl(mCfg.fd, USBDEVFS_SETINTERFACE, &si);

    freeUrbs();
    mQ = mQTail = nullptr;
    mQueued = 0;
    mQueuedNow = 0;
    mRunning.store(false, std::memory_order_release);
    LOGI("usb out stopped");
}

void UsbAudioOut::freeUrbs() {
    for (std::vector<Urb>* v : {&mOut, &mIn}) {
        for (Urb& w : *v) {
            if (w.u) { free(w.u->buffer); free(w.u); }
        }
        v->clear();
    }
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

// Is this URB's context one of ours? A range check on the two pools, which are
// contiguous and never resized while running.
bool UsbAudioOut::ownsContext(const void* p) const {
    auto within = [p](const std::vector<Urb>& v) {
        return !v.empty() && p >= static_cast<const void*>(&v.front()) &&
               p <= static_cast<const void*>(&v.back());
    };
    return within(mOut) || within(mIn);
}

// ------------------------------------------------------------------- clock ---

void UsbAudioOut::clockObserve(int64_t t) {
    const int64_t c = t - static_cast<int64_t>(mInPackets) * mPeriodNs;
    if (mAnchorT == 0 || c < mAnchor) {
        mAnchor = c;
    } else {
        const int64_t rise = (t - mAnchorT) * kAnchorPpm / 1000000;
        mAnchor += std::min(rise, c - mAnchor);
    }
    mAnchorT = t;
}

// IN packets the givebacks are behind the best ever seen. The true lag, in whole
// packets, is at most this plus kSlack.
int64_t UsbAudioOut::lag() const {
    return (nowNs() - mAnchor) / mPeriodNs - static_cast<int64_t>(mInPackets);
}

// ----------------------------------------------------------------- holding ---

// The alt cycle, on an OUT queue that has run out: stopping the stream (alt 0)
// is the one request that empties the CP-88's FIFO. Re-selecting alt 1 alone
// and CLEAR_HALT were measured to leave its frames in place, and a stopped
// CP-88 keeps them through any absence (3 s measured). 6,7 ms on the tablet.
void UsbAudioOut::resetOut() {
    usbdevfs_setinterface si{};
    si.interface = static_cast<unsigned>(mCfg.outIface);
    si.altsetting = 0;
    const bool a = ioctl(mCfg.fd, USBDEVFS_SETINTERFACE, &si) == 0;
    si.altsetting = static_cast<unsigned>(mCfg.outAlt);
    const bool b = ioctl(mCfg.fd, USBDEVFS_SETINTERFACE, &si) == 0;
    if (!a || !b) mAltErrors.fetch_add(1, std::memory_order_relaxed);
}

// Nothing more onto a FIFO in doubt -- but what is queued goes out whole. The
// queued packets add no risk: only the first packet after a gap can meet the
// arrival check, and the operating point keeps it clear. Once the queue has run
// out, the alt cycle empties the FIFO, whatever those packets did to it.
void UsbAudioOut::hold(int reason) {
    if (mState != kRun) return;
    mHolds.fetch_add(1, std::memory_order_relaxed);
    mHoldsBy[reason].fetch_add(1, std::memory_order_relaxed);
    mState = kDrain;
    mVerifyArmed = false;
}

// ----------------------------------------------------------------- reaping ---

// An IN URB that could not be resubmitted is retried on every step: the IN
// queue is both the clock and the source of every size, and must not shrink.
void UsbAudioOut::submitIn(Urb& w) {
    usbdevfs_urb* u = w.u;
    u->status = 0;
    u->buffer_length = mCfg.inMaxPacket;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->iso_frame_desc[0].length = static_cast<unsigned>(mCfg.inMaxPacket);
    u->iso_frame_desc[0].actual_length = 0;
    u->iso_frame_desc[0].status = 0;
    w.queued = ioctl(mCfg.fd, USBDEVFS_SUBMITURB, u) == 0;
}

void UsbAudioOut::onIn(Urb& w, int64_t t) {
    const usbdevfs_iso_packet_desc& d = w.u->iso_frame_desc[0];
    const int frames = static_cast<int>(d.actual_length) / mCfg.inFrameBytes;
    // An ask is a whole number of frames that one OUT packet can carry. A failed
    // packet is not an ask for zero; it is kept as a 0 so that the sizes stay
    // aligned with the frames they belong to, and the stream holds on it.
    const bool ok = d.status == 0 && d.actual_length > 0 &&
                    d.actual_length % static_cast<unsigned>(mCfg.inFrameBytes) == 0 &&
                    frames <= mMaxFrames;
    mAsks[mAskW++ % kAskRing] = static_cast<uint16_t>(ok ? frames : 0);
    if (ok) mAskedFrames.fetch_add(frames, std::memory_order_relaxed);
    else mInBad.fetch_add(1, std::memory_order_relaxed);
    ++mInPackets;
    while (asks() > kAskCap) ++mAskR;
    clockObserve(t);
    submitIn(w);
}

void UsbAudioOut::unqueue(Urb& w) {
    Urb** pp = &mQ;                     // almost always the head
    while (*pp != &w) pp = &(*pp)->next;
    *pp = w.next;
    mQTail = nullptr;
    for (Urb* v = mQ; v; v = v->next) mQTail = v;
    --mQueued;
    w.queued = false;
}

void UsbAudioOut::onOut(Urb& w) {
    const usbdevfs_iso_packet_desc& d = w.u->iso_frame_desc[0];
    unqueue(w);
    mQueuedNow.store(mQueued, std::memory_order_relaxed);
    mOutBytes.fetch_add(d.actual_length, std::memory_order_relaxed);
    const bool lost = d.status != 0 || d.actual_length != d.length;
    if (lost) mOutLost.fetch_add(1, std::memory_order_relaxed);
    if (mState != kRun) return;         // a hold is already under way
    if (lost) {
        hold(kHoldLost);
        return;
    }
    // Between two contiguous OUT completions exactly one IN packet comes back.
    if (mVerifyArmed && static_cast<int64_t>(mInPackets - mVerifyMark) - 1 > 0) {
        hold(kHoldGap);
    }
    mVerifyMark = mInPackets;
    mVerifyArmed = mState == kRun;
}

// One completion, or none if `block` is false and nothing is ready.
// 1: handled, 0: nothing ready, <0: -errno.
int UsbAudioOut::reap(bool block) {
    void* p = nullptr;
    if (ioctl(mCfg.fd, block ? USBDEVFS_REAPURB : USBDEVFS_REAPURBNDELAY, &p) < 0) {
        return errno == EAGAIN ? 0 : -errno;
    }
    auto* u = static_cast<usbdevfs_urb*>(p);
    // NOT OURS: MIDI's, or a URB retired before this stream started. Handed to
    // whoever submitted it before any cast that would read it as one of ours.
    if (!ownsContext(u->usercontext)) {
        if (ForeignFn fn = mForeignFn.load(std::memory_order_acquire)) {
            fn(mForeignCtx.load(std::memory_order_relaxed), u);
        }
        return 1;
    }
    Urb& w = *static_cast<Urb*>(u->usercontext);
    if (w.out) onOut(w);
    else onIn(w, nowNs());
    return 1;
}

void UsbAudioOut::reapAll() {
    while (reap(false) > 0) {}
}

// ----------------------------------------------------------------- sending ---

int UsbAudioOut::zoneOf(int depth) const {
    for (int n = 0; n < mPlan.nZones; ++n) {
        if (depth >= mPlan.zoneShallow[n] && depth <= mPlan.zoneDeep[n]) return n;
    }
    return -1;
}

// The operating point (rule 6). The first `refill` packets after a restart go
// out as asked: the device resumes on the last of them, at a level computed
// from what was sent. After that each packet steps down towards the middle of
// the next deeper safe zone -- as far as one packet may go (it keeps at least
// one frame), which must still land inside that zone -- until the operating
// point. Returns how many frames to leave out of this packet.
int UsbAudioOut::descend(int ask) {
    const Plan& pl = mPlan;
    if (pl.opPoint <= 0) return 0;
    if (mSinceRestart < mCfg.profile.refill) {
        ++mSinceRestart;
        mRefillSum += ask;
        if (mSinceRestart == mCfg.profile.refill) mLevel = mCfg.profile.startFill - mRefillSum;
        return 0;
    }
    if (mLevel == kLevelUnknown || mLevel >= pl.opPoint) return 0;
    int target = pl.opPoint;                      // the deepest zone: the end
    for (int n = pl.nZones - 1; n > 0; --n) {     // shallow to deep
        if (mLevel < pl.zoneShallow[n]) {         // not yet inside zone n
            target = pl.zoneMid[n];
            break;
        }
    }
    int step = std::min(target - mLevel, ask - 1);   // never below one frame
    if (zoneOf(mLevel + step) < 0 && zoneOf(mLevel) >= 0) {
        // It would end in a band: stop at the deep edge of this zone instead.
        step = static_cast<int>(std::floor(pl.zoneDeep[zoneOf(mLevel)])) - 1 - mLevel;
    }
    if (step <= 0) return 0;
    mLevel += step;
    mDescent.fetch_add(step, std::memory_order_relaxed);
    return step;
}

// Fill and submit one OUT URB from the next ask. Null: no ask, a failed ask, or
// no free URB -- none of which may be papered over with a guess.
UsbAudioOut::Urb* UsbAudioOut::send() {
    Urb* w = nullptr;
    for (Urb& c : mOut) {
        if (!c.queued) { w = &c; break; }
    }
    if (w == nullptr || asks() < 1) return nullptr;
    const int ask = mAsks[mAskR++ % kAskRing];
    if (ask == 0) {
        hold(kHoldBadAsk);
        return nullptr;
    }
    const int n = ask - descend(ask);
    usbdevfs_urb* u = w->u;
    // One memcpy of bytes the render thread has already formatted.
    if (mPull) mPull(mCtx, static_cast<uint8_t*>(u->buffer), n);
    else std::memset(u->buffer, 0, static_cast<size_t>(n) * mOutFrameBytes);
    u->iso_frame_desc[0].length = static_cast<unsigned>(n * mOutFrameBytes);
    u->iso_frame_desc[0].actual_length = 0;
    u->iso_frame_desc[0].status = 0;
    u->buffer_length = n * mOutFrameBytes;
    u->status = 0;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    if (ioctl(mCfg.fd, USBDEVFS_SUBMITURB, u) < 0) return nullptr;
    w->queued = true;
    w->next = nullptr;
    if (mQTail) mQTail->next = w;
    else mQ = w;
    mQTail = w;
    ++mQueued;
    mQueuedNow.store(mQueued, std::memory_order_relaxed);
    mFrames.fetch_add(n, std::memory_order_relaxed);
    return w;
}

// Was `w` appended to a ring that still held frames? The URBs queued before it
// that are still not given back end no earlier than the last IN packet given
// back (completions come back in wire order), which is at most lag + slack
// packets ago. So they reach at least alive - 1 - lag - slack packets past now,
// and that must cover the lead.
bool UsbAudioOut::proven(const Urb& w) const {
    const int alive = mQueued - (w.queued ? 1 : 0);
    return alive >= 1 + kLead + kSlack + lag();
}

void UsbAudioOut::topUp() {
    while (mState == kRun && mQueued + 1 <= mDepth) {
        Urb* w = send();
        if (w == nullptr) return;
        reapAll();
        if (mState != kRun || !proven(*w)) {
            hold(kHoldProof);
            return;
        }
    }
}

// After the FIFO was emptied: the whole depth in one burst. If it took longer
// than the IST, its first URB may have started before its last was appended.
void UsbAudioOut::restart() {
    // Nothing is flowing, so which ask goes with which frame does not matter
    // here: a failed IN packet in hand can simply be forgotten.
    unsigned w = mAskR;
    for (unsigned r = mAskR; r != mAskW; ++r) {
        if (mAsks[r % kAskRing]) mAsks[w++ % kAskRing] = mAsks[r % kAskRing];
    }
    mAskW = w;
    if (asks() < static_cast<unsigned>(mDepth)) return;
    mState = kRun;
    mVerifyArmed = false;
    mSinceRestart = mRefillSum = 0;
    mLevel = kLevelUnknown;
    const int64_t t0 = nowNs();
    for (int i = 0; i < mDepth; ++i) {
        if (send() == nullptr) break;
    }
    const int64_t us = (nowNs() - t0) / 1000;
    mRestarts.fetch_add(1, std::memory_order_relaxed);
    if (us >= kIstUs || mQueued != mDepth) hold(kHoldSlowBurst);
}

// -------------------------------------------------------------------- loop ---

// Everything after one blocking reap: every completion the kernel holds, the IN
// queue kept whole, and then the state's one decision.
void UsbAudioOut::step() {
    reapAll();
    for (Urb& w : mIn) {
        if (!w.queued) submitIn(w);
    }
    if (mStopping.load(std::memory_order_acquire)) mState = kStopping;

    switch (mState) {
        case kDrain:
            if (mQ) break;
            resetOut();
            // The last OUT packet's frame is at most one frame after the last IN
            // given back; the restart lands at least one frame after the frame
            // we see. So one more IN packet, and the restart is clear of it.
            mAbsentUntil = mInPackets + 1;
            mState = kAbsent;
            break;
        case kAbsent:
            if (mReanchor) {
                // The FIFO is empty and nothing is queued, so nothing depends
                // on the old count any more: start the anchor afresh, and let it
                // settle over a window of IN packets before anything goes out.
                mReanchor = false;
                mAnchorT = 0;
                mAbsentUntil = mInPackets + kReanchorPackets;
                LOGI("usb out: IN queue ran dry, clock re-anchored over %d packets", kReanchorPackets);
                break;
            }
            if (mInPackets >= mAbsentUntil) restart();
            break;
        case kRun:
            topUp();
            break;
        case kStopping:
            break;
    }
}

// ------------------------------------------------------------ the host ---

bool UsbAudioOut::openHost() {
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDeviceId(b, mCfg.hostDeviceId);
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setDataCallback(
            b, [](AAudioStream* st, void* self, void* audio, int32_t n) -> aaudio_data_callback_result_t {
                return UsbAudioOut::hostData(st, self, audio, n);
            }, this);
    AAudioStreamBuilder_setErrorCallback(
            b, [](AAudioStream* st, void* self, aaudio_result_t e) {
                UsbAudioOut::hostError(st, self, e);
            }, this);
    AAudioStream* st = nullptr;
    const aaudio_result_t r = AAudioStreamBuilder_openStream(b, &st);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOGE("usb out: host stream refused: %s", AAudio_convertResultToText(r));
        return false;
    }
    if (AAudioStream_requestStart(st) != AAUDIO_OK) {
        AAudioStream_close(st);
        return false;
    }
    mHost = st;
    return true;
}

void UsbAudioOut::closeHost() {
    if (mHost == nullptr) return;
    auto* st = static_cast<AAudioStream*>(mHost);
    AAudioStream_requestStop(st);
    AAudioStream_close(st);             // waits for the callback to have returned
    mHost = nullptr;
}

// The first callback: silence into its buffer, then the whole reap loop on this
// thread -- SCHED_FIFO, which an app cannot get for a thread of its own --
// until stop(). Never called again after it returns STOP.
int UsbAudioOut::hostData(void* stream, void* self, void* audio, int32_t frames) {
    auto* st = static_cast<AAudioStream*>(stream);
    auto* x = static_cast<UsbAudioOut*>(self);
    const int bytes = AAudioStream_getFormat(st) == AAUDIO_FORMAT_PCM_FLOAT ? 4 : 2;
    std::memset(audio, 0, static_cast<size_t>(frames) * AAudioStream_getChannelCount(st) * bytes);
    int none = 0;
    if (x->mHostState.compare_exchange_strong(none, 1)) x->reapLoop();
    return AAUDIO_CALLBACK_RESULT_STOP;
}

void UsbAudioOut::hostError(void*, void*, int32_t error) {
    LOGI("usb out: host stream error %s (the loop runs on regardless)",
         AAudio_convertResultToText(error));
}

void UsbAudioOut::reapLoop() {
    // SCHED_FIFO when hosted on an AAudio callback thread; otherwise the
    // highest band an app is given, above the render thread. Logged either way.
    const int policy = sched_getscheduler(0);
    if ((policy & ~SCHED_RESET_ON_FORK) == SCHED_FIFO) {
        sched_param sp{};
        sched_getparam(0, &sp);
        LOGI("usb reap thread: hosted on an AAudio callback, SCHED_FIFO priority %d",
             sp.sched_priority);
    } else {
        bos::raiseRenderPriority("usb reap thread", bos::kTransportNice);
    }
    // The deadline is one packet.
    mHint.start({gettid()}, mPeriodNs);

    int64_t last = nowNs();
    for (;;) {
        const int r = reap(true);
        if (r == -EINTR) continue;
        if (r < 0) {
            setFatal(r == -ENODEV ? std::string("device disconnected")
                                  : std::string("REAPURB failed: ") + std::strerror(-r));
            break;
        }
        const int64_t t = nowNs();
        if (t - last > mMaxGapNs.load(std::memory_order_relaxed)) {
            mMaxGapNs.store(t - last, std::memory_order_relaxed);
        }
        // AWAY LONGER THAN THE IN QUEUE SPANS: it ran dry, and the device's
        // packets in between were never seen. lag() would read high for as long
        // as the anchor takes to rise (200 ppm: minutes), every restart would
        // fail its proof, and the stream would hold sixteen times a second --
        // measured on 2026-10-11 after a 537 ms stall (LOG.md). Hold, and
        // re-anchor once the FIFO is empty.
        if (t - last > static_cast<int64_t>(kInUrbs - kStarveMargin) * mPeriodNs) {
            mReanchor = true;
            hold(kHoldStarve);
        }
        last = t;
        if (const int ms = mTestStallMs.exchange(0, std::memory_order_relaxed)) {
            LOGI("usb out: TEST stall of %d ms", ms);
            usleep(static_cast<useconds_t>(ms) * 1000);
        }

        step();
        if (mState == kStopping && mQ == nullptr) break;
        // What the work cost, not the wait: reap returned at `t`.
        mHint.report(nowNs() - t);
    }
    mHint.stop();
    mRunning.store(false, std::memory_order_release);
}

UsbAudioOut::Stats UsbAudioOut::stats() const {
    Stats s;
    s.frames = mFrames.load(std::memory_order_relaxed);
    s.outBytes = mOutBytes.load(std::memory_order_relaxed);
    s.holds = mHolds.load(std::memory_order_relaxed);
    for (int i = 0; i < kHoldReasons; ++i) s.holdsBy[i] = mHoldsBy[i].load(std::memory_order_relaxed);
    s.restarts = mRestarts.load(std::memory_order_relaxed);
    s.altErrors = mAltErrors.load(std::memory_order_relaxed);
    s.outLost = mOutLost.load(std::memory_order_relaxed);
    s.inBad = mInBad.load(std::memory_order_relaxed);
    s.descentFrames = mDescent.load(std::memory_order_relaxed);
    s.queued = mQueuedNow.load(std::memory_order_relaxed);
    s.maxGapMs = mMaxGapNs.load(std::memory_order_relaxed) / 1e6f;
    const int64_t start = mStartNs.load(std::memory_order_relaxed);
    const int64_t now = nowNs();
    if (start != 0 && mOutFrameBytes > 0 && mCfg.rate > 0) {
        const double wallMs = (now - start) / 1e6;
        const double wireMs = (static_cast<double>(s.outBytes) / mOutFrameBytes) * 1000.0 / mCfg.rate;
        s.deficitMs = static_cast<float>(wallMs - wireMs);

        // The device's rate as it asks for frames, over the interval since the
        // previous call (or since the start).
        const int64_t asked = mAskedFrames.load(std::memory_order_relaxed);
        const int64_t prevAsked = mRateAsked.exchange(asked, std::memory_order_relaxed);
        int64_t prevNs = mRateNs.exchange(now, std::memory_order_relaxed);
        if (prevNs == 0 || prevNs < start) prevNs = start;
        if (now > prevNs && asked >= prevAsked) {
            s.feedbackRate = static_cast<float>((asked - prevAsked) * 1e9 / (now - prevNs));
        }
    }
    return s;
}
