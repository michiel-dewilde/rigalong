package com.rigalong

import android.os.SystemClock

/**
 * Dropouts by cause, as the player would have heard them (S19.3).
 *
 * The engine's counters each start somewhere else -- render underruns at the
 * last peak reset, USB holds at the transport's last start, xruns at the
 * stream's -- so a raw figure on screen never said "since when". Here they
 * are accumulated as increases from one moment the screen names ("since
 * 19:23") and Reset moves. A counter that goes back has started again; its
 * new value is the increase.
 *
 * The first 10 s of a running stream count towards the totals but not
 * towards "the last minute": a start settles (instruments loading, the
 * transport finding its operating point), and the stage line should not
 * warn about that.
 */
class AudioHealth {
    enum class Cause { RENDER, USB_HOLD, USB_ERROR, XRUN }

    /** Wall-clock time the totals count from. */
    var since: Long = System.currentTimeMillis()
        private set

    private val totals = LongArray(Cause.entries.size)
    private var last: IntArray? = null
    private val events = ArrayDeque<Pair<Long, IntArray>>()
    private var warmUntil = 0L

    fun update(s: NativeBridge.Stats, now: Long = SystemClock.elapsedRealtime()) {
        if (!s.running) {
            last = null
            return
        }
        val cur = intArrayOf(s.ringUnderruns, s.usbHolds, s.usbErrors + s.usbAltErrors, s.xRuns)
        val prev = last
        last = cur
        if (prev == null) {
            warmUntil = now + 10_000
            return
        }
        val d = IntArray(cur.size) { i -> if (cur[i] >= prev[i]) cur[i] - prev[i] else cur[i] }
        if (d.all { it == 0 }) return
        for (i in d.indices) totals[i] += d[i].toLong()
        if (now >= warmUntil) events.addLast(now to d)
    }

    /** Per cause, in the last 60 s (warm-up excluded). */
    fun lastMinute(now: Long = SystemClock.elapsedRealtime()): IntArray {
        while (events.isNotEmpty() && now - events.first().first > 60_000) events.removeFirst()
        val sum = IntArray(Cause.entries.size)
        for ((_, d) in events) for (i in d.indices) sum[i] += d[i]
        return sum
    }

    /** Per cause, since [since]. */
    fun total(): LongArray = totals.copyOf()

    fun reset() {
        totals.fill(0)
        events.clear()
        since = System.currentTimeMillis()
    }
}
