#include "UsbMidiIn.h"

#include "../RealtimePriority.h"

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

#define LOG_TAG "bos-usb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool UsbMidiIn::start(int fd, int endpoint, int maxPacket, bool ump, int port) {
    stop();
    mFd = fd;
    mEp = endpoint;
    mMaxPacket = maxPacket > 0 ? maxPacket : 64;
    mUmp = ump;
    mPort = port;
    mUmpAsm.reset();
    mWrite.store(0, std::memory_order_relaxed);
    mRead.store(0, std::memory_order_relaxed);
    mRunning.store(true, std::memory_order_release);
    mThread = std::thread([this] { loop(); });
    LOGI("usb midi in: ep 0x%02x, %s, port %d", mEp, mUmp ? "UMP (MIDI 2.0)" : "MIDI 1.0", mPort);
    return true;
}

void UsbMidiIn::stop() {
    mRunning.store(false, std::memory_order_release);
    // The reader wakes within its 100 ms bulk timeout and sees the flag.
    if (mThread.joinable()) mThread.join();
}

void UsbMidiIn::push(const bagosynths::usbmidi::Message& m) {
    if (mPort >= 0 && m.port != mPort) {
        mFiltered.fetch_add(1, std::memory_order_relaxed);
        return;
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
    bos::raiseRenderPriority("usb midi reader", bos::kRenderNice);

    uint8_t buf[512];
    const int len = mMaxPacket <= static_cast<int>(sizeof(buf)) ? mMaxPacket : static_cast<int>(sizeof(buf));
    while (mRunning.load(std::memory_order_acquire)) {
        usbdevfs_bulktransfer bt{};
        bt.ep = static_cast<unsigned>(mEp);
        bt.len = static_cast<unsigned>(len);
        bt.timeout = 100;        // ms
        bt.data = buf;
        const int n = ioctl(mFd, USBDEVFS_BULK, &bt);
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
        mPackets.fetch_add(1, std::memory_order_relaxed);
        // A USB-MIDI 1.0 transfer is always a whole number of 4-byte packets.
        // A remainder means the read was cut short and a packet was lost.
        if (!mUmp && (n % 4) != 0) mShortReads.fetch_add(1, std::memory_order_relaxed);

        if (mUmp) {
            mUmpAsm.feed(buf, static_cast<size_t>(n),
                         [this](const bagosynths::usbmidi::Message& m) { push(m); });
        } else {
            for (int i = 0; i + 3 < n; i += 4) {
                bagosynths::usbmidi::Message m;
                if (bagosynths::usbmidi::decodePacket(buf + i, m)) {
                    push(m);
                } else if (buf[i] != 0 || buf[i + 1] != 0) {
                    // All-zero padding is normal and says nothing; anything
                    // else we failed to decode is a message we threw away.
                    mUndecoded.fetch_add(1, std::memory_order_relaxed);
                }
            }
        }
    }
    mRunning.store(false, std::memory_order_release);
}
