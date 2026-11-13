package com.rigalong.model

/**
 * What the metronome plays on each beat (S12.6, S12.9, S12.10), as the
 * engine's Click takes it: [FIRST] the bar's first beat, high; [GROUP] the
 * start of a group of a grouped metre, a tone between; [BEAT] the other
 * beats, low; [SILENT] nothing.
 */
object ClickPattern {
    const val SILENT = 0
    const val BEAT = 1
    const val GROUP = 2
    const val FIRST = 3

    /**
     * One bar of [t]. With groups: the first beat, each group start and the
     * other beats -- or, [Tempo.groupStartsOnly], the first beat high, the
     * group starts low and the others silent (Michiel: then "the normal high
     * and low tones", not the one between).
     */
    fun bar(t: Tempo): IntArray {
        val n = t.beatsPerBar.coerceAtLeast(1)
        val starts = starts(t.barGroups)
        val only = t.groupStartsOnly && t.barGroups != null
        return IntArray(n) { i ->
            when {
                i == 0 -> FIRST
                i in starts -> if (only) BEAT else GROUP
                else -> if (only) SILENT else BEAT
            }
        }
    }

    /**
     * The count-in's lead bar of [t], or none: its group starts only, the
     * first high and the others low -- in 4/4 "1 . 2 ." on the half notes.
     * A metre without groups is taken in halves where it has an even number
     * of beats (4/4 as 2+2), else as one group. On by default in 4/4 only.
     */
    fun lead(t: Tempo): IntArray {
        val n = t.beatsPerBar.coerceAtLeast(1)
        val on = t.countInLead ?: (n == 4 && t.beatUnit == 4)
        if (!on) return IntArray(0)
        val groups = t.barGroups ?: if (n % 2 == 0 && n >= 4) listOf(n / 2, n / 2) else listOf(n)
        val starts = starts(groups)
        return IntArray(n) { i -> if (i == 0) FIRST else if (i in starts) BEAT else SILENT }
    }

    /** The number to show on beat [beat] of the lead (0-based): its group's ("1", "2"), as counted aloud. */
    fun leadCount(t: Tempo, beat: Int): Int {
        val n = t.beatsPerBar.coerceAtLeast(1)
        val groups = t.barGroups ?: if (n % 2 == 0 && n >= 4) listOf(n / 2, n / 2) else listOf(n)
        var at = 0
        for ((k, g) in groups.withIndex()) {
            if (beat < at + g) return k + 1
            at += g
        }
        return groups.size
    }

    /** Beat indexes where groups after the first start. */
    private fun starts(groups: List<Int>?): Set<Int> {
        if (groups == null) return emptySet()
        val out = HashSet<Int>()
        var at = 0
        for (g in groups.dropLast(1)) { at += g; out += at }
        return out
    }

    /** "3+2", "3 + 3 + 2 + 2 + 2" as groups; null if it is not a list of positive numbers. */
    fun parseGroups(text: String): List<Int>? {
        val parts = text.split('+').map { it.trim() }
        if (parts.size < 2) return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n in 1..32 } ?: return null }
    }
}
