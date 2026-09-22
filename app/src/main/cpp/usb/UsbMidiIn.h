#pragma once

// Direct-USB MIDI input: reads the device's MIDI streaming IN endpoint through
// usbfs and hands plain MIDI 1.0 bytes to MidiInput, which parses them exactly
// as it parses what AMidi delivers.
//
// Needed because in "USB device" mode the app owns every interface, the ALSA
// card disappears, and with it the kernel's MIDI device -- measured: MidiManager
// kept a stale entry that delivered nothing. In "USB interface" mode for MIDI
// the app owns just the MIDI streaming interface, for the same effect on MIDI
// alone.
//
// USB MIDI 1.0 (4-byte event packets) and 2.0 (UMP, translated down to 1.0 by
// engine/UsbMidi.h) are both handled; which one arrives is decided by the
// alternate setting the Kotlin side selected.
//
// SYNCHRONOUS USBDEVFS_BULK on its own thread, on purpose. The isochronous
// engine reaps every completed ASYNC URB on the shared usbfs descriptor, so an
// async read here would have its completions stolen by that loop. A bulk ioctl
// never enters that queue. (The same ioctl serves interrupt endpoints, which
// MIDI 2.0 permits.)

#include <atomic>
#include <cstdint>
#include <thread>

#include <bagosynths/UsbMidi.h>

class UsbMidiIn {
public:
    ~UsbMidiIn() { stop(); }

    // `port`: the cable (1.0) or group (2.0) to listen to; -1 for all.
    bool start(int fd, int endpoint, int maxPacket, bool ump, int port);
    void stop();
    bool running() const { return mRunning.load(std::memory_order_acquire); }

    // Called from the render thread through MidiInput::poll: copies out pending
    // MIDI 1.0 bytes, oldest first, up to `max` -- and only WHOLE messages.
    // MidiInput's parser drops a message cut short at the end of a buffer
    // (AMidi never delivers one), so a drain that split a note-on across two
    // blocks would lose it. Lock-free, single consumer.
    int drain(uint8_t* out, int max);

    uint64_t packets() const { return mPackets.load(std::memory_order_relaxed); }
    uint64_t errors() const { return mErrors.load(std::memory_order_relaxed); }

    // --- where a note went missing ------------------------------------------
    //
    // Michiel loses roughly one note-on or note-off in thirty, playing single
    // notes, on any engine -- so it is not voice stealing and not the sampler's
    // note-on queue. Reading the code did not find it, so the code counts
    // instead: every stage that CAN drop something now says how often it did.
    //
    // Compare against MidiInput's own note counters. If these agree with the
    // keys played, the loss is downstream; if they do not, it is here, and
    // which counter moved says whether it was the wire, the decoder, the cable
    // filter or the ring.
    uint64_t messages() const { return mMessages.load(std::memory_order_relaxed); }
    uint64_t noteOns() const { return mNoteOn.load(std::memory_order_relaxed); }
    uint64_t noteOffs() const { return mNoteOff.load(std::memory_order_relaxed); }
    uint64_t ringFull() const { return mRingFull.load(std::memory_order_relaxed); }
    uint64_t undecoded() const { return mUndecoded.load(std::memory_order_relaxed); }
    uint64_t filtered() const { return mFiltered.load(std::memory_order_relaxed); }
    uint64_t shortReads() const { return mShortReads.load(std::memory_order_relaxed); }
    int lastErrno() const { return mLastErrno.load(std::memory_order_relaxed); }

private:
    void loop();
    void push(const bagosynths::usbmidi::Message& m);

    int mFd = -1;
    int mEp = 0;
    int mMaxPacket = 64;
    bool mUmp = false;
    int mPort = -1;

    std::thread mThread;
    std::atomic<bool> mRunning{false};
    bagosynths::usbmidi::UmpAssembler mUmpAsm;

    // Single producer (the reader thread), single consumer (the render
    // thread). Fixed 4-byte records, [length, b0, b1, b2], so a message's
    // boundaries survive the trip and drain() can stop between messages.
    static constexpr uint32_t kRing = 1024;   // records; power of two
    uint8_t mRing[kRing][4] = {};
    std::atomic<uint32_t> mWrite{0};
    std::atomic<uint32_t> mRead{0};

    std::atomic<uint64_t> mPackets{0};
    std::atomic<uint64_t> mErrors{0};

    std::atomic<uint64_t> mMessages{0};     // pushed into the ring
    std::atomic<uint64_t> mNoteOn{0};
    std::atomic<uint64_t> mNoteOff{0};      // 0x80, plus 0x90 with velocity 0
    std::atomic<uint64_t> mRingFull{0};     // consumer too slow
    std::atomic<uint64_t> mUndecoded{0};    // a 4-byte packet we made nothing of
    std::atomic<uint64_t> mFiltered{0};     // right device, wrong cable
    std::atomic<uint64_t> mShortReads{0};   // transfer not a multiple of 4
    std::atomic<int> mLastErrno{0};
};
