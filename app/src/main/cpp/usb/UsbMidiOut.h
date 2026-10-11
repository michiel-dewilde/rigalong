#pragma once

// Direct-USB MIDI output: writes MIDI 1.0 bytes to the device's MIDI streaming
// OUT endpoint through usbfs, as USB-MIDI 1.0 event packets.
//
// Needed for the same reason as UsbMidiIn: in "USB device" mode the ALSA card
// is gone, so nothing but this process can talk to the device's MIDI function.
// It is how the app selects a CP-88 live set and reads and writes Live Set
// Sounds over SysEx (REQUIREMENTS R3.3).
//
// SYNCHRONOUS USBDEVFS_BULK, from whatever (non-realtime) thread calls send():
// a bulk ioctl never enters the async queue that the isochronous engine reaps
// on the shared descriptor. Calls are serialised by a mutex, so a SysEx dump
// is never interleaved with another message.
//
// MIDI 1.0 only. A UMP (MIDI 2.0) alternate setting is refused at start.

#include <cstdint>
#include <mutex>

class UsbMidiOut {
public:
    // `cable`: the USB-MIDI cable number (0 for a single-port device).
    bool start(int fd, int endpoint, int maxPacket, int cable);
    void stop();
    bool running() const { return mFd >= 0; }

    // Sends a stream of complete MIDI messages (channel, system common, SysEx;
    // running status is not accepted). Returns the bytes accepted, or a
    // negative errno from the first transfer that failed.
    int send(const uint8_t* bytes, int count);

private:
    int flush(const uint8_t* packets, int length);

    std::mutex mLock;
    int mFd = -1;
    int mEndpoint = 0;
    int mMaxPacket = 64;
    int mCable = 0;
};
