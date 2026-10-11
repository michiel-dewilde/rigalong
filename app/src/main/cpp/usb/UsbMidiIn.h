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
#include <vector>

#include <bagosynths/UsbMidi.h>

class UsbMidiIn {
public:
    ~UsbMidiIn() { stop(); freeRetired(); }

    // `port`: the cable (1.0) or group (2.0) to listen to; -1 for all.
    //
    // `co` is the isochronous engine on the SAME descriptor, and passing a
    // running one switches this reader from a synchronous ioctl with no queue
    // to `urbs` async URBs of one wMaxPacketSize each, reaped by that engine's
    // loop and handed back here. Pass nullptr, or urbs <= 0, for the old
    // single-request path -- which is also the fallback when audio is not on
    // this descriptor at all (MIDI on USB while audio is on ALSA).
    bool start(int fd, int endpoint, int maxPacket, bool ump, int port,
               class UsbAudioOut* co = nullptr, int urbs = 0);
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
    // notes, on any engine -- so it is not voice stealing and not sfizz's
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
    // Reset on read: each sample describes its own interval.
    int64_t maxGapUs() const { return mMaxGapUs.exchange(0, std::memory_order_relaxed); }
    int64_t maxHoldUs() const { return mMaxHoldUs.exchange(0, std::memory_order_relaxed); }
    int lastErrno() const { return mLastErrno.load(std::memory_order_relaxed); }

    // A MIDI MONITOR for the log: every channel message that is not a note,
    // and every SysEx start, as (length << 24 | b0 << 16 | b1 << 8 | b2).
    // Realtime (clock, active sensing) is left out, so the CP-88's heartbeat
    // does not drown what a button sends. One producer (push), one consumer
    // (the health logger); when full, the newest are dropped.
    int drainMonitor(uint32_t* out, int max) const;

    // SYSEX CAPTURE: every complete SysEx message received (F0 .. F7), for
    // reading Live Set Sounds back. One producer (push), one consumer.
    // Returns the length of one message copied into `out`, 0 if none is
    // waiting. A message longer than `max` is skipped and reported as -1.
    int drainSysex(uint8_t* out, int max) const;
    uint64_t sysexDropped() const { return mSysexDropped.load(std::memory_order_relaxed); }

    // PROGRAM SELECTIONS: every Program Change, with the Bank Select MSB and LSB
    // last seen on its channel, as (channel << 24 | msb << 16 | lsb << 8 | pc).
    // How the app follows the CP-88's own live set buttons (V1: MSB 63, LSB
    // page - 1, PC set - 1). One producer (push), one consumer (the UI's poll).
    int drainPrograms(uint32_t* out, int max) const;

    // SWITCHING CONTROLLERS (I10.4): a controller in the watched set goes into
    // the same ring as (1 << 31 | channel << 24 | cc << 8 | value), so a
    // footswitch can step through live sets. Only watched numbers, so the mod
    // wheel cannot flood the ring; in learn mode every controller goes in.
    void watchControllers(uint64_t low, uint64_t high, bool learn) {
        mWatchLow.store(low, std::memory_order_relaxed);
        mWatchHigh.store(high, std::memory_order_relaxed);
        mLearn.store(learn, std::memory_order_relaxed);
    }

private:
    void loop();
    void consume(const uint8_t* buf, int n);
    bool onReaped(struct usbdevfs_urb* u);
    static bool reapThunk(void* ctx, struct usbdevfs_urb* u);
    bool submitAll();
    void freeUrbs();
    void freeRetired();

    // The async path: `kMaxMidiUrbs` buffers of one wMaxPacketSize, so a
    // request is ALWAYS outstanding on this endpoint. The synchronous ioctl
    // leaves it unarmed between the kernel completing one transfer and the
    // next being submitted -- a window the user-space `gap` instrument cannot
    // even see, because it measures from ioctl return to ioctl entry.
    static constexpr int kMaxMidiUrbs = 8;
    struct MidiUrb {
        struct usbdevfs_urb* u = nullptr;
        uint8_t* data = nullptr;
        UsbMidiIn* owner = nullptr;   // usercontext points here
    };
    std::vector<MidiUrb> mUrbs;
    // Cancelled but not yet collected: the kernel may still hand any of these
    // to a reaper, so they outlive stop() and are freed only in the destructor.
    // See the note in UsbMidiIn::stop().
    std::vector<MidiUrb> mRetired;
    class UsbAudioOut* mCo = nullptr;
    bool mAsync = false;
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

    static constexpr uint32_t kMonitor = 64;
    uint32_t mMonitor[kMonitor] = {};
    std::atomic<uint32_t> mMonitorWrite{0};
    mutable std::atomic<uint32_t> mMonitorRead{0};

    void pushProgramRing(uint32_t v);
    std::atomic<uint64_t> mWatchLow{0}, mWatchHigh{0};
    std::atomic<bool> mLearn{false};
    static constexpr uint32_t kPrograms = 64;
    uint32_t mPrograms[kPrograms] = {};
    std::atomic<uint32_t> mProgramWrite{0};
    mutable std::atomic<uint32_t> mProgramRead{0};
    uint8_t mBankMsb[16] = {};
    uint8_t mBankLsb[16] = {};

    void sysexByte(uint8_t b);
    void sysexCommit();
    static constexpr int kSysexMax = 4096;
    static constexpr uint32_t kSysexRing = 1u << 15;
    uint8_t mSysexBuf[kSysexMax] = {};
    int mSysexLen = -1;                     // -1: not inside a SysEx
    bool mSysexOverflow = false;
    uint8_t mSysexRing[kSysexRing] = {};
    std::atomic<uint32_t> mSysexWrite{0};
    mutable std::atomic<uint32_t> mSysexRead{0};
    std::atomic<uint64_t> mSysexDropped{0};

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

    // HOW LONG THIS ENDPOINT WENT WITH NO REQUEST OUTSTANDING, which is the
    // one thing the MIDI path has never measured about itself.
    //
    // USBDEVFS_BULK is synchronous, so exactly ONE request exists at a time and
    // between the return of one and the submission of the next there is nothing
    // queued on endpoint 0x82 at all. The audio IN side queues 512 URBs for
    // precisely this reason; this side queues none. Normally the window is
    // microseconds -- but this thread runs at kRenderNice, BELOW the reap
    // thread, and a deschedule makes the window unbounded. The device then
    // holds its MIDI and hands it over in a BURST when we come back, and a
    // burst of bulk transactions is exactly what section 28 measured as a
    // shrinker of the device's own margin (13 frames with a bulk transfer in
    // flight, and the bulk and iso buffers share one controller RAM window --
    // section 29).
    //
    // So: measure it before changing anything. Reset on read, like the audio
    // side's interval peaks.
    mutable std::atomic<int64_t> mMaxGapUs{0};
    mutable std::atomic<int64_t> mMaxHoldUs{0};   // longest single BULK call
    int64_t mLastReturnNs = 0;                    // reader thread only
};
