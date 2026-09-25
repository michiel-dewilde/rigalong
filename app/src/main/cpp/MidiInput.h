#pragma once

#include <amidi/AMidi.h>
#include <atomic>
#include <cstdint>
#include <jni.h>

#include <bagosynths/Graph.h>
#include "usb/UsbMidiIn.h"

// Native MIDI input via AMidi (API 29+).
//
// The port is polled from the audio callback rather than from a reader thread.
// That is what Google's own native-midi sample does, and the reason is latency:
// AMidiOutputPort_receive is non-blocking, so a separate thread would have to
// spin (burning a core) or sleep (adding a wake-up's worth of delay). Polling at
// the top of each 2 ms block costs a handful of microseconds and means a note
// arriving mid-block is heard at the start of the next one.
//
// The cost is that MIDI timing is quantised to the block boundary -- up to 2 ms
// of jitter. Sample-accurate event scheduling is an M5 refinement; at 2 ms it is
// well under what anyone can feel.
class MidiInput {
public:
    // Called from the JNI thread. midiDeviceObj is a Java android.media.midi.MidiDevice.
    bool open(JNIEnv* env, jobject midiDeviceObj, int portIndex);
    void close();

    // The direct-USB source: the app reads the MIDI streaming endpoint itself
    // (see usb/UsbMidiIn.h) and this drains it through the same parser the
    // AMidi bytes go through. Exclusive with the AMidi port -- opening either
    // closes the other, so a note can never arrive twice.
    bool openUsb(int fd, int endpoint, int maxPacket, bool ump, int port,
                 class UsbAudioOut* co, int urbs);
    bool usbOpen() const { return mUsbOpen.load(std::memory_order_acquire); }

    bool isOpen() const {
        return mPort.load(std::memory_order_acquire) != nullptr ||
               (mUsbOpen.load(std::memory_order_acquire) && mUsb.running());
    }

    // Called from the audio thread. Drains pending messages into a MidiBuffer,
    // which the graph then carries to whichever nodes are connected.
    // Bounded: never processes more than kMaxMessagesPerBlock, so a flood of MIDI
    // cannot blow the callback deadline.
    void poll(bagosynths::MidiBuffer& out);

    uint64_t messageCount() const { return mMessageCount.load(std::memory_order_relaxed); }

    // Notes that reached the GRAPH, whatever the source. Compared against the
    // USB reader's own counts, these say which side of the ring lost an event.
    uint64_t noteOnsOut() const { return mNoteOnOut.load(std::memory_order_relaxed); }
    uint64_t noteOffsOut() const { return mNoteOffOut.load(std::memory_order_relaxed); }
    uint64_t sinkFull() const { return mSinkFull.load(std::memory_order_relaxed); }

    /** The direct-USB reader, for its counters. Null-safe to call when closed. */
    const UsbMidiIn& usb() const { return mUsb; }

private:
    static constexpr int kMaxMessagesPerBlock = 64;
    static constexpr int kBufferBytes = 256;

    void dispatch(const uint8_t* data, size_t numBytes, bagosynths::MidiBuffer& out);

    AMidiDevice* mDevice = nullptr;
    std::atomic<AMidiOutputPort*> mPort{nullptr};

    UsbMidiIn mUsb;
    std::atomic<bool> mUsbOpen{false};
    std::atomic<uint64_t> mMessageCount{0};
    std::atomic<uint64_t> mNoteOnOut{0};
    std::atomic<uint64_t> mNoteOffOut{0};
    std::atomic<uint64_t> mSinkFull{0};     // the block's MidiBuffer overflowed

    // Running status: a stream may omit the status byte on repeated messages of
    // the same type. Rare over USB but legal, and cheap to support.
    uint8_t mRunningStatus = 0;
};
