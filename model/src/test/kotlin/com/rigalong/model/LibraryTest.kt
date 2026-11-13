package com.rigalong.model

import com.rigalong.model.devices.FoundSound
import com.rigalong.model.devices.cp88.Cp88
import com.rigalong.model.devices.cp88.Cp88X9
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryTest {

    private val lib = Library(Files.createTempDirectory("rigalong-lib").toFile(), "test")

    private fun song(level: Double) = RackSong(
        instances = listOf(RackInstance("sfizz", RackEngine.SFIZZ, RackSlot(level = level))),
        liveSets = listOf(RackLiveSet("Main", mapOf("sfizz" to RackPart()))),
    )

    private fun target(id: Digest) = lib.store.entry(id)!!.version.target!!.digest
    private fun held(setlist: Digest) = lib.setlist(setlist)!!.songs.map { it.toRef().digest }

    @Test
    fun theFirstSetlistPlaysSfizzWhereThereIsAnInstrument() {
        lib.seedIfEmpty(listOf("Procol", "JIMMY"), surgePatch = "Keys/EP 1")
        val songs = lib.songsOf(lib.setlists().single().id)
        assertEquals(listOf("sfizz", "setBfree and sfizz", "Surge XT and sfizz"), songs.map { it.first!!.version.name })
        val third = lib.rackSong(songs[2].second)!!
        assertEquals("Keys/EP 1", third.instances.single { it.engine == RackEngine.SURGE }.slot.preset)
        assertEquals("JIMMY", lib.rackSong(songs[1].second)!!.instances.single { it.engine == RackEngine.SETBFREE }.slot.preset)
        // Seeded once: a library that has a setlist is left alone.
        lib.seedIfEmpty(listOf("Procol"), RackEngine.DEXED to "X")
        assertEquals(1, lib.setlists().size)
    }

    @Test
    fun theFirstSetlistNeedsNoSamplesWhereThereIsNoInstrument() {
        lib.seedIfEmpty(listOf("Procol"), RackEngine.DEXED to "Dexed_01/E.PIANO 1", "Keys/EP 1")
        val songs = lib.songsOf(lib.setlists().single().id)
        assertEquals(listOf("Dexed", "setBfree and Dexed", "Surge XT and Dexed"), songs.map { it.first!!.version.name })
        for ((_, song) in songs) {
            val r = lib.rackSong(song)!!
            assertTrue(r.instances.none { it.engine == RackEngine.SFIZZ })
            assertEquals("Dexed_01/E.PIANO 1", r.instances.single { it.engine == RackEngine.DEXED }.slot.preset)
        }
        val split = lib.rackSong(songs[1].second)!!.liveSets.single { it.name == "Split" }
        assertEquals(59, split.parts["setbfree"]!!.keyHigh)
        assertEquals(60, split.parts["dexed"]!!.keyLow)
    }

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
    fun aLiveSetsDeviceSoundIsItsOwnUntilItFollowsALibrarySound() {
        val type = Cp88.MEDIA_TYPE
        val a = Cp88.rename(Cp88.SILENT, "Mine")
        val b = Cp88.rename(Cp88.SILENT, "Edited")
        fun songWith(part: RackDevicePart) = RackSong(
            instances = listOf(RackInstance("sfizz", RackEngine.SFIZZ, RackSlot())),
            liveSets = listOf(RackLiveSet("Main", mapOf("sfizz" to RackPart()), mapOf("CP-88" to part))))
        // Anonymous: the live set's own copy, no library entry.
        val own = lib.store.put(a)
        val anon = songWith(RackDevicePart("yamaha.cp88", "own", sound = own, soundName = "Mine"))
        val back = RackSong.fromObjects(lib.store, anon.toObjects(lib.store))!!.liveSets[0].devices["CP-88"]!!
        assertEquals(null, back.soundEntry)
        kotlin.test.assertContentEquals(a, lib.deviceSoundNow(back.soundEntry, back.sound))
        assertTrue(lib.deviceSounds(type).isEmpty())
        // Promoted: linked, and the link survives the song's objects.
        val entry = lib.saveDeviceSound(null, "Mine", type, a)
        val linked = songWith(RackDevicePart("yamaha.cp88", "own", sound = own, soundName = "Mine", soundEntry = entry))
        val part = RackSong.fromObjects(lib.store, linked.toObjects(lib.store))!!.liveSets[0].devices["CP-88"]!!
        assertEquals(entry, part.soundEntry)
        // Renamed and edited in the library: the song follows both.
        lib.rename(entry, "My nifty piano")
        lib.saveDeviceSound(entry, "My nifty piano", type, b)
        assertEquals("My nifty piano", lib.deviceSoundName(part.soundEntry, part.soundName))
        kotlin.test.assertContentEquals(b, lib.deviceSoundNow(part.soundEntry, part.sound))
        // Gone from the library: the song's own copy plays on.
        lib.deleteEntry(entry)
        kotlin.test.assertContentEquals(a, lib.deviceSoundNow(part.soundEntry, part.sound))
        assertEquals("Mine", lib.deviceSoundName(part.soundEntry, part.soundName))
    }

    @Test
    fun deviceSoundsFromAFileAreOneChangeAndKeptOnce() {
        val type = Cp88.MEDIA_TYPE
        val sounds = listOf(
            FoundSound("Silent", Cp88.SILENT, "1:1"),
            FoundSound("Init Sound", Cp88X9.INIT_SOUND, "1:2"),
            FoundSound("Init Sound", Cp88X9.INIT_SOUND, "1:3"),
        )
        val before = lib.deviceSounds().size
        assertEquals(2, lib.importDeviceSounds("backup.X9A", type, sounds))
        assertEquals(before + 2, lib.deviceSounds().size)
        assertEquals(before + 2, lib.deviceSounds(type).size)
        assertEquals(0, lib.importDeviceSounds("again.X9A", type, sounds), "nothing new the second time")
        val silent = lib.deviceSounds().first { it.version.name == "Silent" }
        kotlin.test.assertContentEquals(Cp88.SILENT, lib.deviceSound(silent.id))
        assertEquals("backup.X9A", lib.lastChange()?.name, "an import that added nothing is no change")
        lib.undo()          // the import: both sounds go
        assertEquals(before, lib.deviceSounds().size)
    }

    @Test
    fun soundsKeptByTheAppAreNotForUndo() {
        val type = Cp88.MEDIA_TYPE
        lib.importDeviceSounds("mine.X9S", type, listOf(FoundSound("Init Sound", Cp88X9.INIT_SOUND, "1:1")))
        val before = lib.deviceSounds().size
        assertEquals(1, lib.keepDeviceSounds(type, listOf(FoundSound("Silent", Cp88.SILENT, "40:1"))))
        assertEquals(before + 1, lib.deviceSounds().size)
        assertEquals("mine.X9S", lib.lastChange()?.name, "the player's own import is still the last change")
        lib.undo()
        assertEquals(before, lib.deviceSounds().size, "Undo took back the import, not the kept sound")
        assertTrue(lib.deviceSounds().any { it.version.name == "Silent" })
    }
}
