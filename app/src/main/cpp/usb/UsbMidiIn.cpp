#include "UsbMidiIn.h"
#include "MidiMonitor.h"

#include "UsbAudioOut.h"

#include "../RealtimePriority.h"

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>

#include <cstdlib>
#include <cerrno>
#include <cstring>
#include <ctime>

namespace {
int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}
}  // namespace

#define LOG_TAG "rigalong-usb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool UsbMidiIn::start(int fd, int endpoint, int maxPacket, bool ump, int port,
                      UsbAudioOut* co, int urbs) {
    stop();
    mFd = fd;
    mEp = endpoint;
    mMaxPacket = maxPacket > 0 ? maxPacket : 64;
    mUmp = ump;
    mPort = port;
    mUmpAsm.reset();
    mWrite.store(0, std::memory_order_relaxed);
    mRead.store(0, std::memory_order_relaxed);
    mLastReturnNs = 0;
    mMaxGapUs.store(0, std::memory_order_relaxed);
    mMaxHoldUs.store(0, std::memory_order_relaxed);
    mRunning.store(true, std::memory_order_release);

    // ASYNC only when the isochronous engine is live on THIS descriptor, since
    // it is what reaps the completions. Otherwise -- MIDI on USB with audio on
    // ALSA, or audio not started yet -- the synchronous thread is the only
    // thing that works, and it is a fallback rather than an error.
    const int want = urbs > 0 ? (urbs < kMaxMidiUrbs ? urbs : kMaxMidiUrbs) : 0;
    if (co != nullptr && want > 0 && co->runningOn(fd)) {
        mUrbs.assign(static_cast<size_t>(want), MidiUrb{});
        bool ok = true;
        for (MidiUrb& b : mUrbs) {
            b.owner = this;
            b.u = static_cast<usbdevfs_urb*>(calloc(1, sizeof(usbdevfs_urb)));
            b.data = static_cast<uint8_t*>(calloc(1, static_cast<size_t>(mMaxPacket)));
            if (b.u == nullptr || b.data == nullptr) { ok = false; break; }
            b.u->type = USBDEVFS_URB_TYPE_BULK;
            b.u->endpoint = static_cast<unsigned char>(mEp);
            b.u->buffer = b.data;
            b.u->buffer_length = mMaxPacket;
            // Points at OUR record, which is how the iso engine tells a foreign
            // URB from one of its own.
            b.u->usercontext = &b;
        }
        if (ok) {
            mCo = co;
            co->setForeignReaper(&UsbMidiIn::reapThunk, this);
            if (submitAll()) {
                mAsync = true;
                LOGI("usb midi in: ep 0x%02x, %s, port %d, %d async URBs x %d B "
                     "(reaped by the iso engine)",
                     mEp, mUmp ? "UMP (MIDI 2.0)" : "MIDI 1.0", mPort, want, mMaxPacket);
                return true;
            }
        }
        // Anything went wrong: tear the half-built queue down and fall back.
        for (MidiUrb& b : mUrbs) { if (b.u) ioctl(mFd, USBDEVFS_DISCARDURB, b.u); }
        co->setForeignReaper(nullptr, nullptr);
        mCo = nullptr;
        freeUrbs();
        LOGE("usb midi in: async setup failed, falling back to the bulk ioctl");
    }

    mAsync = false;
    mThread = std::thread([this] { loop(); });
    LOGI("usb midi in: ep 0x%02x, %s, port %d, synchronous (no queue)",
         mEp, mUmp ? "UMP (MIDI 2.0)" : "MIDI 1.0", mPort);
    return true;
}

void UsbMidiIn::stop() {
    mRunning.store(false, std::memory_order_release);
    // The reader wakes within its 100 ms bulk timeout and sees the flag.
    if (mThread.joinable()) mThread.join();
    if (mAsync) {
        // Unhook FIRST, so a completion arriving mid-teardown cannot reach a
        // handler that is going away.
        if (mCo != nullptr) mCo->setForeignReaper(nullptr, nullptr);
        for (MidiUrb& b : mUrbs) { if (b.u) ioctl(mFd, USBDEVFS_DISCARDURB, b.u); }

        // AND THEN RETIRE THEM RATHER THAN FREE THEM.
        //
        // USBDEVFS_DISCARDURB CANCELS a URB; it does not COLLECT it. The
        // completion still has to be reaped, and until it is, the kernel holds
        // a pointer to this exact `usbdevfs_urb`. Freeing here leaves that live
        // pointer behind, and the next REAPURB on this descriptor hands it to
        // whoever reaps next -- the audio transport's loop, one route change
        // later.
        //
        // Caught by AddressSanitizer on 2026-09-25: heap-use-after-free, READ
        // of size 8 on the usb-routing thread, allocated at UsbMidiIn.cpp:56,
        // freed at freeUrbs, read in UsbAudioOut::start. A 20 ms sleep was
        // standing in for a handshake and, as ever, was not one.
        //
        // There is no safe moment to free them from here: reaping them back
        // ourselves would steal the isochronous engine's completions off the
        // same descriptor. So they are retired instead. The cost is bounded and
        // tiny -- one urb struct plus one wMaxPacketSize buffer per URB, per
        // MIDI restart -- and a retired URB handed to a reaper is now merely
        // ignored rather than fatal, because it is still valid memory and
        // UsbAudioOut::reap checks ownership before casting.
        //
        // They are released in the destructor, by which time this process has
        // closed the descriptor and the kernel can hold no reference at all.
        for (MidiUrb& b : mUrbs) mRetired.push_back(b);
        mUrbs.clear();
        mCo = nullptr;
        mAsync = false;
    }
}

void UsbMidiIn::push(const rigalong::usbmidi::Message& m) {
    if (mPort >= 0 && m.port != mPort) {
        mFiltered.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    for (int i = 0; i < m.length && i < 3; ++i) sysexByte(m.bytes[i]);
    if (m.length >= 1 && m.bytes[0] >= 0x80) midimon::note(false, m.length, m.bytes[0], m.bytes[1], m.bytes[2]);
    if (m.length >= 2) {
        const uint8_t type = m.bytes[0] & 0xF0u, ch = m.bytes[0] & 0x0Fu;
        if (type == 0xB0 && m.length >= 3 && m.bytes[1] == 0) mBankMsb[ch] = m.bytes[2];
        else if (type == 0xB0 && m.length >= 3 && m.bytes[1] == 32) mBankLsb[ch] = m.bytes[2];
        else if (type == 0xC0) {
            pushProgramRing((uint32_t(ch) << 24) | (uint32_t(mBankMsb[ch]) << 16) |
                            (uint32_t(mBankLsb[ch]) << 8) | m.bytes[1]);
        }
        if (type == 0xB0 && m.length >= 3) {
            const uint8_t cc = m.bytes[1];
            const uint64_t mask = cc < 64 ? mWatchLow.load(std::memory_order_relaxed) >> cc
                                          : mWatchHigh.load(std::memory_order_relaxed) >> (cc - 64);
            if ((mask & 1u) || mLearn.load(std::memory_order_relaxed)) {
                pushProgramRing((1u << 31) | (uint32_t(ch) << 24) | (uint32_t(cc) << 8) | m.bytes[2]);
            }
        }
    }
    if (m.length >= 1) {
        const uint8_t st = m.bytes[0];
        const bool note = (st & 0xF0u) == 0x80u || (st & 0xF0u) == 0x90u;
        if (!note && st >= 0xA0u && st < 0xF8u) {
            const uint32_t w = mMonitorWrite.load(std::memory_order_relaxed);
            const uint32_t r = mMonitorRead.load(std::memory_order_acquire);
            if (w - r < kMonitor) {
                mMonitor[w % kMonitor] = (uint32_t(m.length) << 24) | (uint32_t(st) << 16) |
                                         (uint32_t(m.bytes[1]) << 8) | m.bytes[2];
                mMonitorWrite.store(w + 1, std::memory_order_release);
            }
        }
    }
    // Counted BEFORE the ring, so "the wire delivered it" and "the engine got
    // it" are separate facts rather than one.
    if (m.length >= 3) {
        const uint8_t type = m.bytes[0] & 0xF0u;
        if (type == 0x90 && m.bytes[2] > 0) mNoteOn.fetch_add(1, std::memory_order_relaxed);
        else if (type == 0x80 || type == 0x90) mNoteOff.fetch_add(1, std::memory_order_relaxed);
    }
    const uint32_t w = mWrite.load(std::memory_order_relaxed);
    const uint32_t r = mRead.load(std::memory_order_acquire);
    if (w - r >= kRing) {
        mErrors.fetch_add(1, std::memory_order_relaxed);   // full: drop, say so
        mRingFull.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    mMessages.fetch_add(1, std::memory_order_relaxed);
    uint8_t* rec = mRing[w & (kRing - 1)];
    rec[0] = m.length;
    rec[1] = m.bytes[0];
    rec[2] = m.bytes[1];
    rec[3] = m.bytes[2];
    mWrite.store(w + 1, std::memory_order_release);
}

void UsbMidiIn::sysexByte(uint8_t b) {
    if (b == 0xF0) {
        mSysexLen = 0;
        mSysexOverflow = false;
    } else if (mSysexLen < 0 || b >= 0xF8) {
        return;                              // not in a SysEx, or realtime inside one
    } else if (b >= 0x80 && b != 0xF7) {
        mSysexLen = -1;                      // a status byte cut it short
        return;
    }
    if (mSysexLen < kSysexMax) mSysexBuf[mSysexLen++] = b;
    else mSysexOverflow = true;
    if (b == 0xF7) {
        sysexCommit();
        mSysexLen = -1;
    }
}

void UsbMidiIn::sysexCommit() {
    const uint32_t need = static_cast<uint32_t>(mSysexLen) + 2;
    const uint32_t w = mSysexWrite.load(std::memory_order_relaxed);
    const uint32_t r = mSysexRead.load(std::memory_order_acquire);
    if (mSysexOverflow || kSysexRing - (w - r) < need) {
        mSysexDropped.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    mSysexRing[w % kSysexRing] = static_cast<uint8_t>(mSysexLen & 0xFF);
    mSysexRing[(w + 1) % kSysexRing] = static_cast<uint8_t>(mSysexLen >> 8);
    for (int i = 0; i < mSysexLen; ++i) mSysexRing[(w + 2 + i) % kSysexRing] = mSysexBuf[i];
    mSysexWrite.store(w + need, std::memory_order_release);
}

int UsbMidiIn::drainSysex(uint8_t* out, int max) const {
    uint32_t r = mSysexRead.load(std::memory_order_relaxed);
    const uint32_t w = mSysexWrite.load(std::memory_order_acquire);
    if (r == w) return 0;
    const int len = mSysexRing[r % kSysexRing] | (mSysexRing[(r + 1) % kSysexRing] << 8);
    int result = -1;
    if (len <= max) {
        for (int i = 0; i < len; ++i) out[i] = mSysexRing[(r + 2 + i) % kSysexRing];
        result = len;
    }
    mSysexRead.store(r + 2 + static_cast<uint32_t>(len), std::memory_order_release);
    return result;
}

void UsbMidiIn::pushProgramRing(uint32_t v) {
    const uint32_t w = mProgramWrite.load(std::memory_order_relaxed);
    const uint32_t r = mProgramRead.load(std::memory_order_acquire);
    if (w - r < kPrograms) {
        mPrograms[w % kPrograms] = v;
        mProgramWrite.store(w + 1, std::memory_order_release);
    }
}

int UsbMidiIn::drainPrograms(uint32_t* out, int max) const {
    uint32_t r = mProgramRead.load(std::memory_order_relaxed);
    const uint32_t w = mProgramWrite.load(std::memory_order_acquire);
    int n = 0;
    while (r != w && n < max) out[n++] = mPrograms[r++ % kPrograms];
    mProgramRead.store(r, std::memory_order_release);
    return n;
}

int UsbMidiIn::drainMonitor(uint32_t* out, int max) const {
    uint32_t r = mMonitorRead.load(std::memory_order_relaxed);
    const uint32_t w = mMonitorWrite.load(std::memory_order_acquire);
    int n = 0;
    while (r != w && n < max) out[n++] = mMonitor[r++ % kMonitor];
    mMonitorRead.store(r, std::memory_order_release);
    return n;
}

int UsbMidiIn::drain(uint8_t* out, int max) {
    uint32_t r = mRead.load(std::memory_order_relaxed);
    const uint32_t w = mWrite.load(std::memory_order_acquire);
    int n = 0;
    while (r != w) {
        const uint8_t* rec = mRing[r & (kRing - 1)];
        const int len = rec[0];
        if (n + len > max) break;         // the rest waits for the next block
        for (int i = 0; i < len; ++i) out[n + i] = rec[1 + i];
        n += len;
        ++r;
    }
    mRead.store(r, std::memory_order_release);
    return n;
}

void UsbMidiIn::loop() {
    // Raised like the audio threads, and by the same rule: a note waiting
    // behind a low-priority reader is latency nobody can see in any counter.
    // Feeds the engine rather than the wire, so it sits with the render
    // thread. A MIDI byte read a millisecond late delays a note; a URB
    // submitted a millisecond late is audio that was never sent.
    // AT THE TRANSPORT'S OWN PRIORITY since 2026-09-25, not one band below.
    //
    // The old value was justified by "a MIDI byte read a millisecond late
    // delays a note; a URB submitted a millisecond late is audio that was never
    // sent", which is sound about LATENCY and wrong about this endpoint's
    // QUEUE. There is exactly one bulk request outstanding at a time, so while
    // this thread is descheduled nothing at all is queued on endpoint 0x82 and
    // the device hands over a burst when it returns. Being a band below the
    // reap thread makes that window longer precisely when the system is busy.
    //
    // It does not fix the shape -- a queue would -- but it bounds it, and it
    // costs nothing: this thread blocks in an ioctl and does a few microseconds
    // of decode per packet.
    rigalong::raiseRenderPriority("usb midi reader", rigalong::kTransportNice);
    mLastReturnNs = nowNs();

    uint8_t buf[512];
    const int len = mMaxPacket <= static_cast<int>(sizeof(buf)) ? mMaxPacket : static_cast<int>(sizeof(buf));
    while (mRunning.load(std::memory_order_acquire)) {
        usbdevfs_bulktransfer bt{};
        bt.ep = static_cast<unsigned>(mEp);
        bt.len = static_cast<unsigned>(len);
        bt.timeout = 100;        // ms
        bt.data = buf;
        const int64_t t0 = nowNs();
        // The window during which NOTHING was queued on this endpoint: from the
        // previous call returning to this one being submitted.
        if (mLastReturnNs != 0) {
            const int64_t gap = (t0 - mLastReturnNs) / 1000;
            int64_t seen = mMaxGapUs.load(std::memory_order_relaxed);
            while (gap > seen &&
                   !mMaxGapUs.compare_exchange_weak(seen, gap,
                                                    std::memory_order_relaxed)) {}
        }
        const int n = ioctl(mFd, USBDEVFS_BULK, &bt);
        mLastReturnNs = nowNs();
        {
            const int64_t held = (mLastReturnNs - t0) / 1000;
            int64_t seen = mMaxHoldUs.load(std::memory_order_relaxed);
            while (held > seen &&
                   !mMaxHoldUs.compare_exchange_weak(seen, held,
                                                     std::memory_order_relaxed)) {}
        }
        if (n < 0) {
            const int e = errno;
            if (e == ETIMEDOUT || e == EINTR) continue;
            if (e == ENODEV || e == ESHUTDOWN) {
                LOGE("usb midi in: device gone");
                break;
            }
            // Which error, not just how many. EOVERFLOW in particular would
            // mean the device sent more than we asked for and the kernel threw
            // the transfer away -- silent, and exactly the shape of "a note
            // goes missing now and then".
            mLastErrno.store(e, std::memory_order_relaxed);
            mErrors.fetch_add(1, std::memory_order_relaxed);
            LOGE("usb midi in: read failed: %s (%d)", strerror(e), e);
            usleep(10 * 1000);   // do not spin on a persistent error
            continue;
        }
        if (n == 0) continue;
        consume(buf, n);
    }
    mRunning.store(false, std::memory_order_release);
}

// The bytes of one transfer, whichever path delivered them.
void UsbMidiIn::consume(const uint8_t* buf, int n) {
    mPackets.fetch_add(1, std::memory_order_relaxed);
    // A USB-MIDI 1.0 transfer is always a whole number of 4-byte packets.
    // A remainder means the read was cut short and a packet was lost.
    if (!mUmp && (n % 4) != 0) mShortReads.fetch_add(1, std::memory_order_relaxed);

    if (mUmp) {
        mUmpAsm.feed(buf, static_cast<size_t>(n),
                     [this](const rigalong::usbmidi::Message& m) { push(m); });
    } else {
        for (int i = 0; i + 3 < n; i += 4) {
            rigalong::usbmidi::Message m;
            if (rigalong::usbmidi::decodePacket(buf + i, m)) {
                push(m);
            } else if (buf[i] != 0 || buf[i + 1] != 0) {
                // All-zero padding is normal and says nothing; anything
                // else we failed to decode is a message we threw away.
                mUndecoded.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }
}

// --------------------------------------------------------------- async ---
//
// One completed bulk URB, ON THE ISOCHRONOUS REAP THREAD. The work here is
// deliberately tiny -- at most one wMaxPacketSize of 4-byte packets decoded
// into a lock-free ring, then resubmit -- because this is the thread with the
// hard deadline. It is a few hundred nanoseconds per transfer, and it buys a
// queue that the synchronous path cannot have on a shared descriptor.
bool UsbMidiIn::onReaped(usbdevfs_urb* u) {
    if (!mRunning.load(std::memory_order_acquire)) return true;
    if (u->status == 0) {
        if (u->actual_length > 0) {
            consume(static_cast<const uint8_t*>(u->buffer), u->actual_length);
        }
    } else if (u->status == -ENODEV || u->status == -ESHUTDOWN) {
        LOGE("usb midi in: device gone");
        mRunning.store(false, std::memory_order_release);
        return true;
    } else if (u->status != -ENOENT && u->status != -ECONNRESET) {
        mLastErrno.store(-u->status, std::memory_order_relaxed);
        mErrors.fetch_add(1, std::memory_order_relaxed);
    }
    // Straight back out. An endpoint with a request always outstanding is the
    // whole point of this path.
    u->actual_length = 0;
    if (ioctl(mFd, USBDEVFS_SUBMITURB, u) < 0) {
        mLastErrno.store(errno, std::memory_order_relaxed);
        mErrors.fetch_add(1, std::memory_order_relaxed);
    }
    return true;
}

bool UsbMidiIn::reapThunk(void* ctx, usbdevfs_urb* u) {
    return static_cast<UsbMidiIn*>(ctx)->onReaped(u);
}

bool UsbMidiIn::submitAll() {
    for (MidiUrb& b : mUrbs) {
        b.u->actual_length = 0;
        if (ioctl(mFd, USBDEVFS_SUBMITURB, b.u) < 0) {
            LOGE("usb midi in: SUBMITURB failed: %s", strerror(errno));
            return false;
        }
    }
    return true;
}

void UsbMidiIn::freeUrbs() {
    for (MidiUrb& b : mUrbs) { free(b.u); free(b.data); }
    mUrbs.clear();
}

// Only safe once the descriptor itself is gone: see stop().
void UsbMidiIn::freeRetired() {
    for (MidiUrb& b : mRetired) { free(b.u); free(b.data); }
    mRetired.clear();
}
