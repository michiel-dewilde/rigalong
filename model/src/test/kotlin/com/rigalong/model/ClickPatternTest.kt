package com.rigalong.model

import com.rigalong.model.ClickPattern.BEAT
import com.rigalong.model.ClickPattern.FIRST
import com.rigalong.model.ClickPattern.GROUP
import com.rigalong.model.ClickPattern.SILENT
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClickPatternTest {

    @Test
    fun aPlainBarIsTheFirstBeatAndBeats() {
        assertContentEquals(intArrayOf(FIRST, BEAT, BEAT, BEAT), ClickPattern.bar(Tempo()))
        assertEquals("4/4", Tempo().metre)
    }

    @Test
    fun groupsMarkTheirStartsBetweenFirstAndBeats() {
        val t = Tempo(beatsPerBar = 5, groups = listOf(3, 2))
        assertContentEquals(intArrayOf(FIRST, BEAT, BEAT, GROUP, BEAT), ClickPattern.bar(t))
        assertEquals("3+2/4", t.metre)
        // I want to be in America: 3+3+2+2+2 eighths.
        val america = Tempo(beatsPerBar = 12, beatUnit = 8, groups = listOf(3, 3, 2, 2, 2))
        assertContentEquals(intArrayOf(FIRST, BEAT, BEAT, GROUP, BEAT, BEAT, GROUP, BEAT, GROUP, BEAT, GROUP, BEAT),
            ClickPattern.bar(america))
        assertEquals("3+3+2+2+2/8", america.metre)
    }

    @Test
    fun groupStartsOnlyUseTheTwoOrdinaryTones() {
        val t = Tempo(beatsPerBar = 6, beatUnit = 8, groups = listOf(3, 3), groupStartsOnly = true)
        assertContentEquals(intArrayOf(FIRST, SILENT, SILENT, BEAT, SILENT, SILENT), ClickPattern.bar(t))
    }

    @Test
    fun groupsThatDoNotMakeTheBarAreIgnored() {
        val t = Tempo(beatsPerBar = 5, groups = listOf(3, 3), groupStartsOnly = true)
        assertNull(t.barGroups)
        assertContentEquals(intArrayOf(FIRST, BEAT, BEAT, BEAT, BEAT), ClickPattern.bar(t))
        assertEquals("5/4", t.metre)
    }

    @Test
    fun theFourFourCountInLeadsWithHalfNotes() {
        // "1" and "2" on the middle tone; the bar after has the high and low.
        assertContentEquals(intArrayOf(GROUP, SILENT, GROUP, SILENT), ClickPattern.lead(Tempo()))
        assertEquals(listOf(1, 1, 2, 2), (0..3).map { ClickPattern.leadCount(Tempo(), it) })
        // Off where asked, and by default in other metres.
        assertEquals(0, ClickPattern.lead(Tempo(countInLead = false)).size)
        assertEquals(0, ClickPattern.lead(Tempo(beatsPerBar = 3)).size)
        assertEquals(0, ClickPattern.lead(Tempo(beatsPerBar = 6, beatUnit = 8)).size)
        // Asked for in 3+2/4: "1 . . 2 .".
        val t = Tempo(beatsPerBar = 5, groups = listOf(3, 2), countInLead = true)
        assertContentEquals(intArrayOf(GROUP, SILENT, SILENT, GROUP, SILENT), ClickPattern.lead(t))
    }

    @Test
    fun groupsAreTypedWithPluses() {
        assertEquals(listOf(3, 2), ClickPattern.parseGroups("3+2"))
        assertEquals(listOf(3, 3, 2, 2, 2), ClickPattern.parseGroups(" 3 + 3 + 2 + 2 + 2 "))
        assertNull(ClickPattern.parseGroups("5"))
        assertNull(ClickPattern.parseGroups("3+x"))
        assertNull(ClickPattern.parseGroups("3+0"))
    }
}
