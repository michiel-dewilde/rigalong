#include "MidiInput.h"

#include <android/log.h>


#define LOG_TAG "rigalong-midi"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool MidiInput::open(JNIEnv* env, jobject midiDeviceObj, int portIndex) {
    close();

    AMidiDevice* device = nullptr;
    const media_status_t status = AMidiDevice_fromJava(env, midiDeviceObj, &device);
    if (status != AMEDIA_OK || device == nullptr) {
        LOGE("AMidiDevice_fromJava failed: %d", status);
        return false;
    }

    AMidiOutputPort* port = nullptr;
    // "Output port" is from the device's point of view: it is what we read from.
    const media_status_t portStatus = AMidiOutputPort_open(device, portIndex, &port);
    if (portStatus != AMEDIA_OK || port == nullptr) {
        LOGE("AMidiOutputPort_open(%d) failed: %d", portIndex, portStatus);
        AMidiDevice_release(device);
        return false;
    }

    mDevice = device;
    mRunningStatus = 0;
    // Publish last, with release ordering: the audio thread must not see a
    // non-null port before mDevice is fully set up.
    mPort.store(port, std::memory_order_release);

    LOGI("MIDI input port %d open", portIndex);
    return true;
}

bool MidiInput::openUsb(int fd, int endpoint, int maxPacket, bool ump, int port,
                        UsbAudioOut* co, int urbs) {
    close();
    mRunningStatus = 0;
    if (!mUsb.start(fd, endpoint, maxPacket, ump, port, co, urbs)) return false;
    mUsbOpen.store(true, std::memory_order_release);
    return true;
}

void MidiInput::close() {
    if (mUsbOpen.exchange(false, std::memory_order_acq_rel)) mUsb.stop();
    mUsbOut.stop();
    AMidiOutputPort* port = mPort.exchange(nullptr, std::memory_order_acq_rel);
    if (port != nullptr) {
        AMidiOutputPort_close(port);
    }
    if (mDevice != nullptr) {
        AMidiDevice_release(mDevice);
        mDevice = nullptr;
    }
}

void MidiInput::poll(rigalong::MidiBuffer& out) {
    if (mUsbOpen.load(std::memory_order_acquire)) {
        // Bounded like the AMidi path: at most kBufferBytes per block, so a
        // flood cannot blow the render deadline; the rest waits a block.
        uint8_t bytes[kBufferBytes];
        const int n = mUsb.drain(bytes, kBufferBytes);
        if (n > 0) {
            dispatch(bytes, static_cast<size_t>(n), out);
            mMessageCount.fetch_add(1, std::memory_order_relaxed);
        }
        return;
    }

    AMidiOutputPort* port = mPort.load(std::memory_order_acquire);
    if (port == nullptr) return;

    uint8_t buffer[kBufferBytes];
    int32_t opcode = 0;
    size_t numBytes = 0;
    int64_t timestamp = 0;

    for (int i = 0; i < kMaxMessagesPerBlock; ++i) {
        const ssize_t numMessages = AMidiOutputPort_receive(
                port, &opcode, buffer, sizeof(buffer), &numBytes, &timestamp);

        if (numMessages <= 0) break;   // 0 = nothing pending, <0 = error

        if (opcode == AMIDI_OPCODE_DATA && numBytes > 0) {
            dispatch(buffer, numBytes, out);
            mMessageCount.fetch_add(1, std::memory_order_relaxed);
        }
    }
}

void MidiInput::dispatch(const uint8_t* data, size_t numBytes, rigalong::MidiBuffer& out) {
    size_t i = 0;

    while (i < numBytes) {
        uint8_t byte = data[i];

        if (byte >= 0xF8) {
            // Real-time messages (clock, start, stop...) can be interleaved
            // anywhere, even inside another message. Skip without disturbing
            // running status.
            ++i;
            continue;
        }

        uint8_t statusByte;
        if (byte & 0x80) {
            statusByte = byte;
            ++i;
            if (statusByte < 0xF0) {
                mRunningStatus = statusByte;
            } else {
                mRunningStatus = 0;    // system messages cancel running status
            }
        } else if (mRunningStatus != 0) {
            statusByte = mRunningStatus;
        } else {
            ++i;                       // stray data byte with no status; drop it
            continue;
        }

        const uint8_t type = statusByte & 0xF0u;

        // System exclusive and other system common messages: skip to the end of
        // the sysex, or just bail on this buffer.
        if (statusByte >= 0xF0) {
            if (statusByte == 0xF0) {
                while (i < numBytes && data[i] != 0xF7) ++i;
                if (i < numBytes) ++i;   // consume the 0xF7
            } else {
                ++i;
            }
            continue;
        }

        const size_t dataBytesNeeded = (type == 0xC0 || type == 0xD0) ? 1 : 2;
        if (i + dataBytesNeeded > numBytes) break;   // truncated; wait for more

        const uint8_t d1 = data[i];
        const uint8_t d2 = (dataBytesNeeded == 2) ? data[i + 1] : 0;
        i += dataBytesNeeded;

        // Events are timestamped at frame 0: they are applied at the start of
        // the block. Sample-accurate placement within the block is an M5
        // refinement; at 2 ms the quantisation is well below perception.
        rigalong::MidiEvent ev;
        ev.frame = 0;
        ev.status = statusByte;
        ev.data1 = d1;
        ev.data2 = d2;

        // The far end of the count that starts in UsbMidiIn::push. These two
        // bracket every stage a note can disappear in -- the ring, the drain,
        // this parser -- so a session of playing says which half to look at.
        const int before = out.size();
        out.push(ev);
        if (out.size() == before) {
            mSinkFull.fetch_add(1, std::memory_order_relaxed);
        } else if (type == 0x90 && d2 > 0) {
            mNoteOnOut.fetch_add(1, std::memory_order_relaxed);
        } else if (type == 0x80 || type == 0x90) {
            mNoteOffOut.fetch_add(1, std::memory_order_relaxed);
        }
    }
}
