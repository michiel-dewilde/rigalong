#pragma once

#include <amidi/AMidi.h>
#include <atomic>
#include <cstdint>
#include <jni.h>

#include <bagosynths/Graph.h>

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

    bool isOpen() const { return mPort.load(std::memory_order_acquire) != nullptr; }

    // Called from the audio thread. Drains pending messages into a MidiBuffer,
    // which the graph then carries to whichever nodes are connected.
    // Bounded: never processes more than kMaxMessagesPerBlock, so a flood of MIDI
    // cannot blow the callback deadline.
    void poll(bagosynths::MidiBuffer& out);

    uint64_t messageCount() const { return mMessageCount.load(std::memory_order_relaxed); }

private:
    static constexpr int kMaxMessagesPerBlock = 64;
    static constexpr int kBufferBytes = 256;

    void dispatch(const uint8_t* data, size_t numBytes, bagosynths::MidiBuffer& out);

    AMidiDevice* mDevice = nullptr;
    std::atomic<AMidiOutputPort*> mPort{nullptr};
    std::atomic<uint64_t> mMessageCount{0};

    // Running status: a stream may omit the status byte on repeated messages of
    // the same type. Rare over USB but legal, and cheap to support.
    uint8_t mRunningStatus = 0;
};
