package be.mdw.bagosynths.model

import be.mdw.bagosynths.model.LiveSetSwitcher.Change
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RackTest {

    private val store = Store(Files.createTempDirectory("bos-rack").toFile())

    private val draft = RackSong(
        slots = mapOf(
            RackEngine.SFIZZ to RackSlot(preset = "salamander-grand", level = 0.9),
            RackEngine.HAMMOND to RackSlot(preset = "Jimmy Smith", level = 0.7),
        ),
        liveSets = listOf(
            RackLiveSet("Verse", mapOf(RackEngine.SFIZZ to RackPart(), RackEngine.HAMMOND to RackPart(plays = false))),
            RackLiveSet("Split", mapOf(
                RackEngine.HAMMOND to RackPart(keyHigh = 59, transpose = 12),
                RackEngine.SFIZZ to RackPart(keyLow = 60, level = 1.0))),
        ),
    )

    @Test
    fun aRackSongRoundTrips() {
        val song = draft.toObjects(store)
        val back = assertNotNull(RackSong.fromObjects(store, song))
        assertEquals(draft.slots, back.slots)
        assertEquals(draft.liveSets.map { it.name }, back.liveSets.map { it.name })
        assertEquals(draft.liveSets[1].parts[RackEngine.HAMMOND], back.liveSets[1].parts[RackEngine.HAMMOND])
        assertEquals(false, back.liveSets[0].parts[RackEngine.HAMMOND]?.plays)
    }

    @Test
    fun aRackSongSwitchesLikeAnySong() {
        val song = draft.toObjects(store)
        val liveSets = song.liveSets.map { store.getTyped<LiveSet>(it.toRef().digest) }
        val s = LiveSetSwitcher(song, liveSets)
        s.select(0)
        val cs = s.select(1)
        assertTrue(Change.WireSet("hammond.midi", true, 1.0) in cs, "the organ plays in the split")
        val p = cs.filterIsInstance<Change.Param>().associate { it.key to it.value }
        assertEquals(59.0, p["hammond.zone.keyHigh"])
        assertEquals(12.0, p["hammond.zone.transpose"])
        assertEquals(60.0, p["sfizz.zone.keyLow"])
        assertEquals(1.0, p["sfizz.Level"])
    }

    @Test
    fun cp88ActionsRoundTrip() {
        val sound = store.put(byteArrayOf(1, 2, 3))
        val d = draft.copy(liveSets = listOf(
            draft.liveSets[0].copy(cp88 = RackCp88("select", page = 3, set = 5)),
            draft.liveSets[1].copy(cp88 = RackCp88("own", sound = sound)),
        ))
        val back = RackSong.fromObjects(store, d.toObjects(store))!!
        assertEquals(RackCp88("select", 3, 5), back.liveSets[0].cp88)
        assertEquals("own", back.liveSets[1].cp88.mode)
        assertEquals(sound, back.liveSets[1].cp88.sound)
        // The song reaches its CP-88 sound, so export carries it.
        val c = store.closure(listOf(Ref(store.putTyped(d.toObjects(store)))))
        assertTrue(sound in c.types.keys)
    }

    @Test
    fun theSameDraftIsTheSameSong() {
        assertEquals(store.putTyped(draft.toObjects(store)), store.putTyped(draft.toObjects(store)))
    }
}
