package com.rigalong.play

/** Taps to a tempo: four or more less than 2 s apart give one, as the stage's TAP does. */
class TapTempo {
    private val taps = ArrayDeque<Long>()

    /** One tap; the tempo it gives, or null while there are too few. */
    fun tap(): Double? {
        val now = System.nanoTime()
        if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
        taps.addLast(now)
        while (taps.size > 6) taps.removeFirst()
        if (taps.size < 4) return null
        val span = (taps.last() - taps.first()) / (taps.size - 1).toDouble()
        return Math.round(60e9 / span * 10) / 10.0
    }
}
