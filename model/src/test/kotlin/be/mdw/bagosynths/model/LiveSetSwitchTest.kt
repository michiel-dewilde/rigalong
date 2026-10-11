package be.mdw.bagosynths.model

import be.mdw.bagosynths.model.LiveSetSwitcher.Change
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSetSwitchTest {

    private val song = Song(
        nodes = mapOf(
            "organ" to Node(kind = "hammond", params = mapOf("level" to 0.8, "leslie" to 0.0, "drawbar.16" to 0.5)),
            "piano" to Node(kind = "sfizz", params = mapOf("level" to 0.9)),
        ),
        wires = mapOf("organ.audio" to Wire("organ:out", "mix:in"), "piano.audio" to Wire("piano:out", "mix:in")),
        recall = mapOf("organ.leslie" to Recall.KEEP, "organ.drawbar.16" to Recall.RESUME),
    )
    private val verse = LiveSet(name = "Verse", wires = mapOf("piano.audio" to WireState(on = false)))
    private val chorus = LiveSet(name = "Chorus", overrides = mapOf("organ.level" to 1.0, "organ.drawbar.16" to 1.0),
        onSelect = listOf(MidiSend("Keys", "C0 05")))

    private fun params(cs: List<Change>) = cs.filterIsInstance<Change.Param>().associate { it.key to it.value }

    @Test
    fun firstSelectionSetsEverythingItKnows() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        val cs = s.select(0)
        assertEquals(mapOf("organ.level" to 0.8, "organ.leslie" to 0.0, "organ.drawbar.16" to 0.5, "piano.level" to 0.9),
            params(cs))
        assertTrue(Change.WireSet("piano.audio", false, 1.0) in cs)
        assertTrue(Change.WireSet("organ.audio", true, 1.0) in cs)
    }

    @Test
    fun recallSetsTheStoredValueEveryTime() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(0)
        s.noteValue("organ.level", 0.3)          // the player rides it
        val cs = s.select(1)
        assertEquals(1.0, params(cs)["organ.level"])
        val back = s.select(0)
        assertEquals(0.8, params(back)["organ.level"])
    }

    @Test
    fun keepLeavesTheHandsAlone() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(0)
        s.noteValue("organ.leslie", 1.0)         // fast Leslie, by hand
        val cs = s.select(1)
        assertTrue("organ.leslie" !in params(cs))
        assertEquals(1.0, s.currentValue("organ.leslie"))
    }

    @Test
    fun resumeRestoresWhatTheLiveSetWasLeftWith() {
        val s = LiveSetSwitcher(song, listOf(verse, chorus))
        s.select(1)                                  // chorus: drawbar 1.0
        s.noteValue("organ.drawbar.16", 0.7)         // tweaked in the chorus
        s.select(0)                                  // verse: its own stored 0.5
        assertEquals(0.5, s.currentValue("organ.drawbar.16"))
        val back = s.select(1)
        assertEquals(0.7, params(back)["organ.drawbar.16"], "the chorus tweak survives a trip to the verse")
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
        s.noteValue("organ.level", 0.4)
        val ls = s.snapshot(0)
        assertEquals(mapOf("organ.level" to 0.4), ls.overrides)
    }
}
