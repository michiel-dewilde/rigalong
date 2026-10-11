#include "UsbMidiOut.h"
#include "MidiMonitor.h"

#include <android/log.h>
#include <cerrno>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <vector>

#define LOG_TAG "bos-usb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Bytes in a channel or system-common message, by status byte; 0 for SysEx
// and for anything that is not a status byte.
int messageLength(uint8_t status) {
    if (status < 0x80) return 0;
    if (status < 0xF0) {
        const uint8_t type = status & 0xF0;
        return (type == 0xC0 || type == 0xD0) ? 2 : 3;
    }
    switch (status) {
        case 0xF1: case 0xF3: return 2;
        case 0xF2: return 3;
        case 0xF0: return 0;
        default: return 1;   // F6, F8-FF
    }
}

}  // namespace

bool UsbMidiOut::start(int fd, int endpoint, int maxPacket, int cable) {
    std::lock_guard<std::mutex> g(mLock);
    mFd = fd;
    mEndpoint = endpoint;
    mMaxPacket = maxPacket >= 4 ? maxPacket & ~3 : 64;
    mCable = cable & 0x0F;
    LOGI("usb midi out: ep 0x%02x, MIDI 1.0, cable %d, %d B packets", endpoint, mCable, mMaxPacket);
    return true;
}

void UsbMidiOut::stop() {
    std::lock_guard<std::mutex> g(mLock);
    mFd = -1;
}

int UsbMidiOut::flush(const uint8_t* packets, int length) {
    int done = 0;
    while (done < length) {
        const int chunk = length - done < mMaxPacket ? length - done : mMaxPacket;
        usbdevfs_bulktransfer t{};
        t.ep = static_cast<unsigned>(mEndpoint);
        t.len = static_cast<unsigned>(chunk);
        t.timeout = 1000;
        t.data = const_cast<uint8_t*>(packets + done);
        const int r = ioctl(mFd, USBDEVFS_BULK, &t);
        if (r < 0) {
            const int e = errno;
            LOGE("usb midi out: bulk transfer failed: errno %d", e);
            return -e;
        }
        done += r;
    }
    return done;
}

int UsbMidiOut::send(const uint8_t* bytes, int count) {
    std::lock_guard<std::mutex> g(mLock);
    if (mFd < 0) return -ENODEV;

    // USB-MIDI 1.0 event packets (table 4-1): the high nibble is the cable,
    // the low nibble the Code Index Number.
    std::vector<uint8_t> out;
    out.reserve(static_cast<size_t>(count) * 2 + 8);
    const uint8_t cable = static_cast<uint8_t>(mCable << 4);
    auto packet = [&](uint8_t cin, uint8_t a, uint8_t b, uint8_t c) {
        out.push_back(cable | cin);
        out.push_back(a);
        out.push_back(b);
        out.push_back(c);
    };

    int i = 0;
    while (i < count) {
        const uint8_t s = bytes[i];
        if (s == 0xF0) {
            midimon::note(true, 1, 0xF0, 0, 0);
            // SysEx: CIN 4 for each run of three bytes that does not end it,
            // CIN 5/6/7 for the run that holds the F7.
            int j = i;
            for (;;) {
                const int left = count - j;
                int end = -1;
                for (int k = 0; k < 3 && k < left; ++k) {
                    if (bytes[j + k] == 0xF7) { end = k; break; }
                }
                if (end >= 0) {
                    const uint8_t b0 = bytes[j];
                    const uint8_t b1 = end >= 1 ? bytes[j + 1] : 0;
                    const uint8_t b2 = end >= 2 ? bytes[j + 2] : 0;
                    packet(static_cast<uint8_t>(0x5 + end), b0, b1, b2);
                    j += end + 1;
                    break;
                }
                if (left < 3) return -EINVAL;   // no F7 before the end of the buffer
                packet(0x4, bytes[j], bytes[j + 1], bytes[j + 2]);
                j += 3;
            }
            i = j;
            continue;
        }
        const int n = messageLength(s);
        if (n == 0 || i + n > count) return -EINVAL;
        uint8_t cin;
        if (s < 0xF0) cin = static_cast<uint8_t>(s >> 4);
        else cin = static_cast<uint8_t>(n == 1 ? 0xF : n == 2 ? 0x2 : 0x3);
        packet(cin, s, n > 1 ? bytes[i + 1] : 0, n > 2 ? bytes[i + 2] : 0);
        midimon::note(true, n, s, n > 1 ? bytes[i + 1] : 0, n > 2 ? bytes[i + 2] : 0);
        i += n;
    }

    const int r = flush(out.data(), static_cast<int>(out.size()));
    return r < 0 ? r : count;
}
