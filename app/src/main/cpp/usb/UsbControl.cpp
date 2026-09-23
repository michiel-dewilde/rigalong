#include "UsbControl.h"

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <algorithm>
#include <cerrno>
#include <cstring>

#define LOG_TAG "bos-usb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace usbctl {

namespace {

constexpr uint8_t kUac2Cur = 0x01;
constexpr uint8_t kUac2Range = 0x02;
constexpr uint8_t kCsSamFreqControl = 0x01;

int control(int fd, uint8_t type, uint8_t request, uint16_t value, uint16_t index,
            void* data, uint16_t length) {
    usbdevfs_ctrltransfer c{};
    c.bRequestType = type;
    c.bRequest = request;
    c.wValue = value;
    c.wIndex = index;
    c.wLength = length;
    c.timeout = 1000;
    c.data = data;
    return ioctl(fd, USBDEVFS_CONTROL, &c);
}

uint32_t le32(const uint8_t* p) {
    return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

}  // namespace

std::vector<uint32_t> uac2Rates(int fd, int controlInterface, int clockId) {
    std::vector<uint32_t> rates;
    // Class request, to an entity on the AudioControl interface: wIndex is
    // entity id in the high byte, interface in the low byte.
    const uint16_t value = kCsSamFreqControl << 8;
    const uint16_t index = static_cast<uint16_t>((clockId << 8) | controlInterface);

    uint8_t head[2] = {};
    if (control(fd, 0xA1, kUac2Range, value, index, head, sizeof(head)) < 2) {
        LOGE("clock %d: GET RANGE failed: %s", clockId, std::strerror(errno));
        return rates;
    }
    const int n = head[0] | (head[1] << 8);
    if (n <= 0 || n > 64) return rates;
    std::vector<uint8_t> buf(2 + 12 * static_cast<size_t>(n));
    const int got = control(fd, 0xA1, kUac2Range, value, index, buf.data(),
                            static_cast<uint16_t>(buf.size()));
    if (got < 14) return rates;

    static constexpr uint32_t kCommon[] = {32000, 44100, 48000, 88200, 96000,
                                           176400, 192000};
    for (int i = 0; i < n && 2 + 12 * i + 12 <= got; ++i) {
        const uint8_t* r = buf.data() + 2 + 12 * i;
        const uint32_t lo = le32(r), hi = le32(r + 4);
        if (lo == hi) {
            rates.push_back(lo);
        } else {
            for (uint32_t c : kCommon) if (c >= lo && c <= hi) rates.push_back(c);
        }
    }
    std::sort(rates.begin(), rates.end());
    rates.erase(std::unique(rates.begin(), rates.end()), rates.end());
    return rates;
}

bool uac2SetRate(int fd, int controlInterface, int clockId, uint32_t rate) {
    uint8_t d[4] = {static_cast<uint8_t>(rate), static_cast<uint8_t>(rate >> 8),
                    static_cast<uint8_t>(rate >> 16), static_cast<uint8_t>(rate >> 24)};
    const int r = control(fd, 0x21, kUac2Cur, kCsSamFreqControl << 8,
                          static_cast<uint16_t>((clockId << 8) | controlInterface), d, 4);
    if (r < 0) LOGE("clock %d: SET CUR %u failed: %s", clockId, rate, std::strerror(errno));
    return r >= 0;
}

uint32_t uac2GetRate(int fd, int controlInterface, int clockId) {
    uint8_t d[4] = {};
    const int r = control(fd, 0xA1, kUac2Cur, kCsSamFreqControl << 8,
                          static_cast<uint16_t>((clockId << 8) | controlInterface), d, 4);
    return r == 4 ? le32(d) : 0;
}

bool uac1SetRate(int fd, int endpoint, uint32_t rate) {
    uint8_t d[3] = {static_cast<uint8_t>(rate), static_cast<uint8_t>(rate >> 8),
                    static_cast<uint8_t>(rate >> 16)};
    // Class request to an endpoint: SAMPLING_FREQ_CONTROL (0x01) in wValue.
    const int r = control(fd, 0x22, 0x01, 0x0100, static_cast<uint16_t>(endpoint), d, 3);
    if (r < 0) LOGE("ep 0x%02x: UAC1 SET CUR %u failed: %s", endpoint, rate, std::strerror(errno));
    return r >= 0;
}

int currentAltSetting(int fd, int interfaceNumber) {
    uint8_t alt = 0;
    const int r = control(fd, 0x81, 0x0A /* GET_INTERFACE */, 0,
                          static_cast<uint16_t>(interfaceNumber), &alt, 1);
    return r == 1 ? alt : -1;
}

bool reconnectKernelDriver(int fd, int interfaceNumber) {
    usbdevfs_ioctl cmd{};
    cmd.ifno = interfaceNumber;
    cmd.ioctl_code = USBDEVFS_CONNECT;
    cmd.data = nullptr;
    const int r = ioctl(fd, USBDEVFS_IOCTL, &cmd);
    if (r < 0) {
        if (errno == EBUSY) {
            // Already bound. snd-usb-audio takes the whole card when it probes
            // any one of its interfaces, so reconnecting them one at a time
            // means the later ones are claimed before their turn comes. That is
            // the wanted outcome, not a failure.
            LOGI("interface %d: already back with a kernel driver", interfaceNumber);
            return true;
        }
        LOGE("interface %d: reconnect kernel driver failed: %s", interfaceNumber,
             std::strerror(errno));
        return false;
    }
    LOGI("interface %d: handed back to the kernel", interfaceNumber);
    return true;
}

bool resetDevice(int fd) {
    const int r = ioctl(fd, USBDEVFS_RESET, nullptr);
    if (r < 0) LOGE("USBDEVFS_RESET failed: %s", std::strerror(errno));
    return r >= 0;
}

int endpointHalted(int fd, int endpointAddress) {
    uint8_t st[2] = {0, 0};
    // 0x82: device-to-host, standard, recipient endpoint.
    const int r = control(fd, 0x82, 0x00 /* GET_STATUS */, 0,
                          static_cast<uint16_t>(endpointAddress), st, 2);
    if (r < 2) return -1;
    return (st[0] & 0x01) ? 1 : 0;
}

int uac2ClockValid(int fd, int controlInterface, int clockId) {
    uint8_t valid = 0;
    // 0xA1: device-to-host, class, recipient interface. wValue is the control
    // selector in the high byte; wIndex is entity in the high byte and the
    // AudioControl interface in the low.
    constexpr uint8_t kCsClockValidControl = 0x02;
    const int r = control(fd, 0xA1, kUac2Cur,
                          static_cast<uint16_t>(kCsClockValidControl << 8),
                          static_cast<uint16_t>((clockId << 8) | controlInterface),
                          &valid, 1);
    if (r < 1) return -1;
    return valid ? 1 : 0;
}

}  // namespace usbctl
