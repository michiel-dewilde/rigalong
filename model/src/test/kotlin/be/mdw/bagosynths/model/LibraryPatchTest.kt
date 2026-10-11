package be.mdw.bagosynths.model

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Library patches shared by songs, the edit's ripple as a choice, undo, and forgetting history. */
class LibraryPatchTest {

    private val dir: File = Files.createTempDirectory("bos-libpatch").toFile()
    private val lib = Library(dir, "test")

    private val rhodes = Patch(engine = "sfizz", preset = "rhodes", params = mapOf("Tone" to 0.4))

    private fun songWith(patch: Patch) = RackSong(
        slots = mapOf(RackEngine.SFIZZ to RackSlot().withPatch(patch)),
        liveSets = listOf(RackLiveSet("Main", mapOf(RackEngine.SFIZZ to RackPart()))),
    )

    private fun target(id: Digest) = lib.store.entry(id)!!.version.target!!.digest
    private fun patchOf(songId: Digest): Patch {
        val song = lib.store.getTyped<Song>(target(songId))
        return lib.store.getTyped(song.nodes["sfizz"]!!.patch!!.toRef().digest)
    }
    private fun held(setlist: Digest) = lib.setlist(setlist)!!.songs.map { it.toRef().digest }

    /** A library patch, two songs using it, each in a setlist. */
    private fun scene(): List<Digest> {
        val p = lib.savePatch(null, "Rhodes", rhodes)
        val a = lib.saveSong(null, "A", songWith(rhodes))
        val b = lib.saveSong(null, "B", songWith(rhodes))
        val set = lib.saveSetlist(null, "Set", listOf(a, b))
        return listOf(p, a, b, set)
    }

    @Test
    fun aSongUsesALibraryPatchByItsContent() {
        val (p, a, b) = scene()
        assertEquals(p, lib.patchEntryOf(rhodes)?.first?.id)
        assertEquals(true, lib.patchEntryOf(rhodes)?.second)
        assertEquals(setOf(a, b), lib.songsUsingPatch(p).filter { it.latest }.map { it.song.id }.toSet())
        assertNull(lib.patchEntryOf(rhodes.copy(params = mapOf("Tone" to 0.9))))
    }

    @Test
    fun updatingEverywhereMovesEverySongAndTheirSetlists() {
        val (p, a, b, set) = scene()
        val brighter = rhodes.copy(params = mapOf("Tone" to 0.8))
        // Song A edited, and the patch updated for the others too: one save.
        lib.saveSongAndPatches(a, "A", songWith(brighter), mapOf(p to (brighter to listOf(b))))
        assertEquals(brighter, patchOf(a))
        assertEquals(brighter, patchOf(b))
        assertEquals(brighter, lib.store.getTyped<Patch>(target(p)))
        assertEquals(listOf(target(a), target(b)), held(set))
        assertTrue(lib.songsUsingPatch(p).all { it.latest })
    }

    @Test
    fun onlyTheChosenSongsMoveTheRestKeepTheOlderVersion() {
        val (p, a, b, set) = scene()
        val c = lib.saveSong(null, "C", songWith(rhodes))
        val brighter = rhodes.copy(params = mapOf("Tone" to 0.8))
        lib.saveSongAndPatches(a, "A", songWith(brighter), mapOf(p to (brighter to listOf(c))))
        assertEquals(brighter, patchOf(c))
        assertEquals(rhodes, patchOf(b))                            // not chosen: unchanged
        val uses = lib.songsUsingPatch(p).associate { it.song.id to it.latest }
        assertEquals(mapOf(a to true, b to false, c to true), uses)
        assertEquals(target(b), held(set)[1])
    }

    @Test
    fun twoSongsWithEqualContentStayTwoSongsInASetlist() {
        // A duplicated song is the same object as its original until edited.
        val a = lib.saveSong(null, "Original", songWith(rhodes))
        val b = lib.saveSong(null, "Copy", songWith(rhodes))
        assertEquals(target(a), target(b))
        val set = lib.saveSetlist(null, "Set", listOf(a, b))
        assertEquals(listOf("Original", "Copy"), lib.songsOf(set).map { it.first?.version?.name })

        lib.saveSong(b, "Copy", songWith(rhodes.copy(params = mapOf("Tone" to 0.9))))
        assertEquals(listOf(target(a), target(b)), held(set))
        assertTrue(target(a) != target(b))
        assertEquals(listOf("Original", "Copy"), lib.songsOf(set).map { it.first?.version?.name })
    }

    @Test
    fun onlyThisSongLeavesTheLibraryPatchAlone() {
        val (p, a, b) = scene()
        val darker = rhodes.copy(params = mapOf("Tone" to 0.1))
        lib.saveSongAndPatches(a, "A", songWith(darker), emptyMap())
        assertEquals(rhodes, lib.store.getTyped<Patch>(target(p)))
        assertEquals(rhodes, patchOf(b))
        assertEquals(listOf(b), lib.songsUsingPatch(p).map { it.song.id })
    }

    @Test
    fun oneUndoPutsBackEverythingOneSaveMoved() {
        val (p, a, b, set) = scene()
        val before = listOf(p, a, b, set).associateWith { target(it) }
        val brighter = rhodes.copy(params = mapOf("Tone" to 0.8))
        lib.saveSongAndPatches(a, "A", songWith(brighter), mapOf(p to (brighter to listOf(b))))
        val change = assertNotNull(lib.lastChange())
        assertEquals("song", change.kind)
        assertEquals(setOf(p, a, b, set), change.before.keys)

        assertEquals(change, lib.undo())
        assertEquals(before, listOf(p, a, b, set).associateWith { target(it) })
        // The undone versions stay in the history.
        assertTrue(lib.history(a).size >= 3)
    }

    @Test
    fun undoingACreationDeletesIt() {
        val s = lib.saveSong(null, "New", songWith(rhodes))
        lib.undo()
        assertFalse(lib.songs().any { it.entry.id == s })
    }

    @Test
    fun undoStepsSurviveARestartAndRunNewestFirst() {
        val (_, a) = scene()
        lib.rename(a, "A2")
        lib.rename(a, "A3")
        val again = Library(dir, "test")
        assertEquals("A3", again.lastChange()?.name)
        again.undo()
        assertEquals("A2", again.store.entry(a)!!.version.name)
        again.undo()
        assertEquals("A", again.store.entry(a)!!.version.name)
    }

    @Test
    fun aForkRemembersWhereItCameFromWithoutDraggingItAlong() {
        val (p) = scene()
        val darker = rhodes.copy(params = mapOf("Tone" to 0.1))
        val fork = lib.savePatch(null, "Rhodes (dark)", darker, derivedFrom = lib.store.entry(p)!!.latest)
        assertEquals(p, lib.origin(fork)?.first?.id)
        // The fork's closure holds its own content, not the patch it came from.
        val c = lib.store.closure(listOf(Ref(lib.store.entry(fork)!!.latest)))
        assertFalse(target(p) in c.types.keys)
    }

    @Test
    fun forgettingHistoryOrphansTheEarlierVersions() {
        val (p) = scene()
        val v1 = target(p)
        lib.savePatch(p, "Rhodes", rhodes.copy(params = mapOf("Tone" to 0.6)))
        assertEquals(2, lib.history(p).size)
        assertTrue(v1 in lib.store.closure(listOf(Ref(lib.store.entry(p)!!.latest))).types.keys)

        lib.forgetHistory(p)
        assertEquals(1, lib.history(p).size)
        assertEquals(rhodes.copy(params = mapOf("Tone" to 0.6)), lib.store.getTyped<Patch>(target(p)))
        // Nothing before it travels with the entry any more.
        assertFalse(v1 in lib.store.closure(listOf(Ref(lib.store.entry(p)!!.latest))).types.keys)
        // And no undo step points into the forgotten history.
        assertTrue(lib.lastChange()?.before?.containsKey(p) != true)
    }
}
