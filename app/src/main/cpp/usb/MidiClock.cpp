#include "MidiClock.h"
#include "UsbMidiOut.h"

#include <android/log.h>
#include <algorithm>
#include <ctime>

#define LOG_TAG "bos-clock"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

timespec toTimespec(int64_t ns) {
    timespec ts{};
    ts.tv_sec = static_cast<time_t>(ns / 1000000000LL);
    ts.tv_nsec = static_cast<long>(ns % 1000000000LL);
    return ts;
}

}  // namespace

void MidiClock::setTempo(double bpm) {
    std::thread old;
    {
        std::lock_guard<std::mutex> g(mLock);
        mBpm = bpm;
        if (bpm > 0.0 && !mThread.joinable()) {
            mQuit = false;
            mThread = std::thread(&MidiClock::run, this);
            LOGI("midi clock: %.1f bpm", bpm);
        } else if (bpm <= 0.0 && mThread.joinable()) {
            mQuit = true;
            old = std::move(mThread);
            LOGI("midi clock: off");
        }
    }
    if (old.joinable()) old.join();
}

void MidiClock::start(int64_t delayNs) {
    {
        std::lock_guard<std::mutex> g(mLock);
        if (!mThread.joinable()) return;
        mStartAt = nowNs() + (delayNs > 0 ? delayNs : 0);
    }
}

void MidiClock::stop() {
    {
        std::lock_guard<std::mutex> g(mLock);
        if (!mThread.joinable()) return;
        mStartAt = -1;
    }
    sendByte(0xFC);
}

void MidiClock::sendByte(uint8_t b) {
    mOut.send(&b, 1);   // fails quietly while MIDI out is away; the clock goes on
}

void MidiClock::run() {
    int64_t next = nowNs();
    std::unique_lock<std::mutex> g(mLock);
    while (!mQuit) {
        // A pending Start comes before the next tick, or replaces it: the
        // ticks restart on the Start, so the first one after it is beat one.
        const bool starting = mStartAt >= 0 && mStartAt <= next;
        const int64_t at = starting ? mStartAt : next;
        const int64_t now = nowNs();
        if (now < at) {
            // Short steps, so a Start, a tempo or quitting is seen within 5 ms.
            const timespec ts = toTimespec(std::min<int64_t>(at, now + 5000000));
            g.unlock();
            clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr);
            g.lock();
            continue;
        }
        const double bpm = mBpm;
        if (starting) mStartAt = -1;
        g.unlock();
        if (starting) {
            const uint8_t msg[2] = {0xFA, 0xF8};
            mOut.send(msg, 2);
        } else {
            sendByte(0xF8);
        }
        g.lock();
        mTicks.fetch_add(1, std::memory_order_relaxed);
        const int64_t period = static_cast<int64_t>(60e9 / (bpm * 24.0));
        next = at + period;
        // Asleep far too long (a stalled transfer): resume from now rather than
        // firing the missed ticks in a burst, which a delay would hear as a jump.
        const int64_t after = nowNs();
        if (after > next + period) next = after;
    }
}
