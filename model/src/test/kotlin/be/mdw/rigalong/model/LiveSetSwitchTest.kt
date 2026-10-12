package be.mdw.rigalong.model

import be.mdw.rigalong.model.LiveSetSwitcher.Change
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSetSwitchTest {

    private val song = Song(
        nodes = mapOf(
            "setbfree" to Node(kind = "setBfree", params = mapOf("level" to 0.8, "leslie" to 0.0, "drawbar.16" to 0.5)),
            "sfizz" to Node(kind = "sfizz", params = mapOf("level" to 0.9)),
        ),
        wires = mapOf("setbfree.audio" to Wire("setbfree:out", "mix:in"), "sfizz.audio" to Wire("sfizz:out", "mix:in")),
        recall = mapOf("setbfree.leslie" to Recall.KEEP, "setbfree.drawbar.16" to Recall.RESUME),
    )
    private val verse = LiveSet(name = "Verse", wires = mapOf("sfizz.audio" to WireState(on = false)))
    private val chorus = LiveSet(name = "Chorus", overrides = mapOf("setbfree.level" to 1.0, "setbfree.drawbar.16" to 1.0),
        onSelect = listOf(MidiSend("Keys", "C0 05")))

    private fun params(cs: List<Change>) = cs.filterIsInstance<Change.Param>().associate { it.key to it.value }

    @Test
    fun firstSelectionSetsEverythingItKnows() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        val cs = s.select(0)
        assertEquals(mapOf("setbfree.level" to 0.8, "setbfree.leslie" to 0.0, "setbfree.drawbar.16" to 0.5, "sfizz.level" to 0.9),
            params(cs))
        assertTrue(Change.WireSet("sfizz.audio", false, 1.0) in cs)
        assertTrue(Change.WireSet("setbfree.audio", true, 1.0) in cs)
    }

    @Test
    fun recallSetsTheStoredValueEveryTime() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(0)
        s.noteValue("setbfree.level", 0.3)          // the player rides it
        val cs = s.select(1)
        assertEquals(1.0, params(cs)["setbfree.level"])
        val back = s.select(0)
        assertEquals(0.8, params(back)["setbfree.level"])
    }

    @Test
    fun keepLeavesTheHandsAlone() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(0)
        s.noteValue("setbfree.leslie", 1.0)         // fast Leslie, by hand
        val cs = s.select(1)
        assertTrue("setbfree.leslie" !in params(cs))
        assertEquals(1.0, s.currentValue("setbfree.leslie"))
    }

    @Test
    fun resumeRestoresWhatTheLiveSetWasLeftWith() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(1)                                  // chorus: drawbar 1.0
        s.noteValue("setbfree.drawbar.16", 0.7)         // tweaked in the chorus
        s.select(0)                                  // verse: its own stored 0.5
        assertEquals(0.5, s.currentValue("setbfree.drawbar.16"))
        val back = s.select(1)
        assertEquals(0.7, params(back)["setbfree.drawbar.16"], "the chorus tweak survives a trip to the verse")
    }

    @Test
    fun onlyChangesAreEmitted() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(0)
        val again = s.select(0)
        assertTrue(again.none { it is Change.Param || it is Change.WireSet || it is Change.Enable })
    }

    @Test
    fun onSelectMidiComesWithTheLiveSet() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(0)
        assertTrue(Change.Send("Keys", "C0 05") in s.select(1))
    }

    @Test
    fun snapshotStoresOnlyWhatDiffersFromTheSong() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(0)
        s.noteValue("setbfree.level", 0.4)
        val ls = s.snapshot(0)
        assertEquals(mapOf("setbfree.level" to 0.4), ls.overrides)
    }
}
