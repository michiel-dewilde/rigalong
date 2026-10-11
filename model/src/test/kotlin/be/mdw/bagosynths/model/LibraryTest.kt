package be.mdw.bagosynths.model

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryTest {

    private val lib = Library(Files.createTempDirectory("bos-lib").toFile(), "test")

    private fun song(level: Double) = RackSong(
        instances = listOf(RackInstance("sfizz", RackEngine.SFIZZ, RackSlot(level = level))),
        liveSets = listOf(RackLiveSet("Main", mapOf("sfizz" to RackPart()))),
    )

    private fun target(id: Digest) = lib.store.entry(id)!!.version.target!!.digest
    private fun held(setlist: Digest) = lib.setlist(setlist)!!.songs.map { it.toRef().digest }

    @Test
    fun anEditMovesTheSetlistsThatHeldTheLatest() {
        val s = lib.saveSong(null, "Song", song(0.5))
        val sl = lib.saveSetlist(null, "Set", listOf(s))
        lib.saveSong(s, "Song", song(0.6))
        assertEquals(listOf(target(s)), held(sl))
    }

    @Test
    fun restoreIsANewVersionAndTheSetlistsFollow() {
        val s = lib.saveSong(null, "Song", song(0.5))
        val first = target(s)
        val sl = lib.saveSetlist(null, "Set", listOf(s))
        lib.saveSong(s, "Song", song(0.6))
        val oldest = lib.history(s).last().first

        lib.restore(s, oldest)

        assertEquals(first, target(s))
        assertEquals(3, lib.history(s).size)       // nothing undone is lost
        assertEquals(listOf(first), held(sl))
    }

    @Test
    fun anOlderReferenceStaysUntilTheUserUpdatesIt() {
        val s = lib.saveSong(null, "Song", song(0.5))
        val sl = lib.saveSetlist(null, "Set", listOf(s))
        val first = target(s)
        // A setlist pinned to the first version, then the song edited: only the
        // setlist that held the latest moves (I8.5).
        val pinned = lib.saveSetlist(null, "Pinned", listOf(s))
        lib.saveSong(s, "Song", song(0.6))
        lib.store.update(pinned, "test", "t", target = Ref(lib.store.putTyped(
            Setlist(songs = listOf(RefJ.of(first, Song.TYPE)))), Setlist.TYPE))

        val uses = lib.usedBy(s).associate { it.setlist.id to it.latest }
        assertEquals(mapOf(sl to true, pinned to false), uses)

        lib.updateReference(pinned, s)
        assertEquals(listOf(target(s)), held(pinned))
        assertTrue(lib.usedBy(s).all { it.latest })
    }

    @Test
    fun aSongNoSetlistHoldsIsUsedByNone() {
        val s = lib.saveSong(null, "Alone", song(0.5))
        lib.saveSetlist(null, "Other", emptyList())
        assertTrue(lib.usedBy(s).isEmpty())
    }

    @Test
    fun renameKeepsTheContent() {
        val s = lib.saveSong(null, "Old", song(0.5))
        val t = target(s)
        lib.rename(s, "New")
        assertEquals("New", lib.store.entry(s)!!.version.name)
        assertEquals(t, target(s))
    }

    @Test
    fun aDeletedSongLeavesTheListsButStillPlaysInSetlists() {
        val s = lib.saveSong(null, "Gone", song(0.5))
        val sl = lib.saveSetlist(null, "Set", listOf(s))
        lib.deleteEntry(s)
        assertFalse(lib.songs().any { it.entry.id == s })
        // The setlist still holds the object, by hash.
        assertEquals(1, lib.songsOf(sl).size)
    }

    @Test
    fun cp88SoundsFromAFileAreOneChangeAndKeptOnce() {
        val slots = listOf(
            Cp88X9.Slot(1, 1, "Silent", Cp88.SILENT),
            Cp88X9.Slot(1, 2, "Init Sound", Cp88X9.INIT_SOUND),
            Cp88X9.Slot(1, 3, "Init Sound", Cp88X9.INIT_SOUND),
        )
        val before = lib.cp88Sounds().size
        assertEquals(2, lib.importCp88Sounds("backup.X9A", slots))
        assertEquals(before + 2, lib.cp88Sounds().size)
        assertEquals(0, lib.importCp88Sounds("again.X9A", slots), "nothing new the second time")
        val silent = lib.cp88Sounds().first { it.version.name == "Silent" }
        kotlin.test.assertContentEquals(Cp88.SILENT, lib.cp88Sound(silent.id))
        assertEquals("backup.X9A", lib.lastChange()?.name, "an import that added nothing is no change")
        lib.undo()          // the import: both sounds go
        assertEquals(before, lib.cp88Sounds().size)
    }
}
