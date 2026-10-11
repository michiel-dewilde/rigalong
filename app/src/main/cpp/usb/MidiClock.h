#pragma once

// MIDI clock out (REQUIREMENTS I12.2): 24 Timing Clock messages (F8) a beat
// to the direct-USB MIDI out, so an external delay or arpeggiator follows the
// song's tempo.
//
// The clock runs whenever it is enabled and the song has a tempo -- a delay
// synced to it needs the ticks, not a transport. Start (FA) and Stop (FC) go
// out with the click, and a Start re-phases the ticks onto the click's first
// beat as it is HEARD: the click is rendered ahead of playback by the ring and
// the device, so the caller passes that delay and the Start waits for it.
//
// Its own thread, on absolute deadlines (CLOCK_MONOTONIC), so a late wake-up
// does not push every later tick back; a tempo change applies from the next
// tick. Sends go through UsbMidiOut, whose mutex keeps a tick out of the middle
// of a SysEx dump -- a page write delays the clock by its few milliseconds, and
// happens only at song selection.

#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>

class UsbMidiOut;

class MidiClock {
public:
    explicit MidiClock(UsbMidiOut& out) : mOut(out) {}
    ~MidiClock() { setTempo(0.0); }

    // bpm <= 0 stops the ticks (and the thread).
    void setTempo(double bpm);
    // Start (FA) after `delayNs`, ticks re-phased to it; Stop (FC) at once.
    void start(int64_t delayNs);
    void stop();

    int64_t ticksSent() const { return mTicks.load(std::memory_order_relaxed); }

private:
    void run();
    void sendByte(uint8_t b);

    UsbMidiOut& mOut;
    std::mutex mLock;
    std::thread mThread;
    bool mQuit = false;
    double mBpm = 0.0;
    int64_t mStartAt = -1;  // CLOCK_MONOTONIC ns of a pending Start, or -1
    std::atomic<int64_t> mTicks{0};
};
