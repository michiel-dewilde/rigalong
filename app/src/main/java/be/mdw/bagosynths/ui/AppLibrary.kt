package be.mdw.bagosynths.ui

import be.mdw.bagosynths.model.Digest
import be.mdw.bagosynths.model.Entry
import be.mdw.bagosynths.model.EntryVersion
import be.mdw.bagosynths.model.RackEngine
import be.mdw.bagosynths.model.RackLiveSet
import be.mdw.bagosynths.model.RackPart
import be.mdw.bagosynths.model.RackSlot
import be.mdw.bagosynths.model.RackSong
import be.mdw.bagosynths.model.Ref
import be.mdw.bagosynths.model.RefJ
import be.mdw.bagosynths.model.Setlist
import be.mdw.bagosynths.model.Song
import be.mdw.bagosynths.model.Store
import be.mdw.bagosynths.model.getTyped
import be.mdw.bagosynths.model.putTyped
import java.io.File
import java.time.Instant

/**
 * The app's library on the device (REQUIREMENTS section 8): songs and setlists
 * as entries in the content-addressed store (model/Store.kt).
 *
 * A setlist refers to song OBJECTS, by hash. Editing a song makes a new
 * object, and this moves every setlist that held the song's previous version
 * on to the new one -- the edit rippling upward (I8.13). Only setlists holding
 * exactly that previous version move; nothing else changes by surprise (I8.5).
 */
class AppLibrary(dir: File, private val author: String) {
    val store = Store(dir)

    private fun now() = Instant.now().toString()

    data class SongEntry(val entry: Entry, val song: Song)

    fun songs(): List<SongEntry> = store.entries()
        .filter { it.version.target?.type == Song.TYPE }
        .map { SongEntry(it, store.getTyped(it.version.target!!.digest)) }
        .sortedBy { it.entry.version.name.lowercase() }

    fun setlists(): List<Entry> = store.entries()
        .filter { it.version.target?.type == Setlist.TYPE }
        .sortedBy { it.version.name.lowercase() }

    fun environments(): List<Entry> = store.entries()
        .filter { it.version.target?.type == be.mdw.bagosynths.model.Environment.TYPE }
        .sortedBy { it.version.name.lowercase() }

    fun environment(id: Digest): be.mdw.bagosynths.model.Environment? =
        store.entry(id)?.version?.target?.let { store.getTyped(it.digest) }

    fun saveEnvironment(entryId: Digest?, name: String, env: be.mdw.bagosynths.model.Environment): Digest {
        val target = Ref(store.putTyped(env), be.mdw.bagosynths.model.Environment.TYPE)
        return if (entryId == null) store.create(name, target, author, now())
        else store.update(entryId, author, now(), target = target, name = name)
    }

    fun setlist(id: Digest): Setlist? =
        store.entry(id)?.version?.target?.let { store.getTyped<Setlist>(it.digest) }

    /** The song entries of a setlist, in order; a song whose entry is gone shows by its object. */
    fun songsOf(setlistId: Digest): List<Pair<Entry?, Song>> {
        val sl = setlist(setlistId) ?: return emptyList()
        val byObject = songs().associateBy { it.entry.version.target!!.digest }
        return sl.songs.map { r ->
            val d = r.toRef().digest
            byObject[d]?.let { it.entry to it.song } ?: (null to store.getTyped(d))
        }
    }

    fun saveSong(entryId: Digest?, name: String, draft: RackSong): Digest {
        val song = draft.toObjects(store)
        val target = Ref(store.putTyped(song), Song.TYPE)
        if (entryId == null) return store.create(name, target, author, now())
        val previous = store.entry(entryId)?.version?.target?.digest
        store.update(entryId, author, now(), target = target, name = name)
        if (previous != null && previous != target.digest) rippleSetlists(previous, target.digest)
        return entryId
    }

    private fun rippleSetlists(from: Digest, to: Digest) {
        for (e in setlists()) {
            val sl = store.getTyped<Setlist>(e.version.target!!.digest)
            if (sl.songs.none { it.toRef().digest == from }) continue
            val moved = sl.copy(songs = sl.songs.map { if (it.toRef().digest == from) RefJ.of(to, Song.TYPE) else it })
            store.update(e.id, author, now(), target = Ref(store.putTyped(moved), Setlist.TYPE))
        }
    }

    fun saveSetlist(entryId: Digest?, name: String, songEntries: List<Digest>): Digest {
        val refs = songEntries.mapNotNull { id -> store.entry(id)?.version?.target?.let { RefJ.of(it.digest, Song.TYPE) } }
        val target = Ref(store.putTyped(Setlist(songs = refs)), Setlist.TYPE)
        return if (entryId == null) store.create(name, target, author, now())
        else store.update(entryId, author, now(), target = target, name = name)
    }

    fun deleteEntry(id: Digest) {
        store.update(id, author, now(), delete = true)
    }

    fun rename(id: Digest, name: String) {
        store.update(id, author, now(), name = name)
    }

    /** An entry's versions, newest first (I8.3). */
    fun history(id: Digest): List<Pair<Digest, EntryVersion>> =
        store.entry(id)?.let { store.history(it.latest) } ?: emptyList()

    /**
     * Makes an earlier version the latest again -- as a NEW version, so the
     * history keeps what was undone. A song restored moves the setlists that
     * held its latest version along with it, as an edit does (I8.13).
     */
    fun restore(id: Digest, version: Digest) {
        val v = EntryVersion.fromJson(store.getJson(version))
        val before = store.entry(id)?.version?.target?.digest
        store.update(id, author, now(), target = v.target, name = v.name, delete = v.target == null)
        val after = v.target?.digest
        if (v.target?.type == Song.TYPE && before != null && after != null && before != after) rippleSetlists(before, after)
    }

    /** A setlist holding some version of a song, and whether it is the latest (I8.5). */
    data class Use(val setlist: Entry, val latest: Boolean)

    /** The setlists that hold any version of song entry [songId]. */
    fun usedBy(songId: Digest): List<Use> {
        val versions = history(songId).mapNotNull { it.second.target?.digest }.toSet()
        val latest = store.entry(songId)?.version?.target?.digest
        return setlists().mapNotNull { e ->
            val held = store.getTyped<Setlist>(e.version.target!!.digest).songs.map { it.toRef().digest }
            if (held.none { it in versions }) null else Use(e, latest in held)
        }
    }

    /** Moves [setlistId] from older versions of song [songId] to its latest (I8.5): a choice, never automatic. */
    fun updateReference(setlistId: Digest, songId: Digest) {
        val latest = store.entry(songId)?.version?.target?.digest ?: return
        val older = history(songId).mapNotNull { it.second.target?.digest }.toSet() - latest
        val e = store.entry(setlistId) ?: return
        val sl = store.getTyped<Setlist>(e.version.target!!.digest)
        val moved = sl.copy(songs = sl.songs.map { if (it.toRef().digest in older) RefJ.of(latest, Song.TYPE) else it })
        if (moved != sl) store.update(setlistId, author, now(), target = Ref(store.putTyped(moved), Setlist.TYPE))
    }

    /** Versions of [id] that came in by import and diverged from this tablet's (I8.31). */
    fun divergences(id: Digest): List<Digest> = store.divergences()[id].orEmpty()

    fun resolve(id: Digest, chosen: Digest) {
        store.resolve(id, chosen, author, now())
    }

    fun rackSong(song: Song): RackSong? = RackSong.fromObjects(store, song)

    /** First run: a setlist with three songs, so the stage view has something to play (S14.2). */
    fun seedIfEmpty(organRegistrations: List<String>) {
        if (setlists().isNotEmpty()) return
        val organ = organRegistrations.firstOrNull { it.contains("JIMMY", true) } ?: organRegistrations.firstOrNull()
        val piano = saveSong(null, "Piano", RackSong(
            slots = mapOf(RackEngine.SFIZZ to RackSlot(level = 0.8)),
            liveSets = listOf(RackLiveSet("Piano", mapOf(RackEngine.SFIZZ to RackPart()))),
        ))
        val organAndPiano = saveSong(null, "Organ and piano", RackSong(
            slots = mapOf(
                RackEngine.HAMMOND to RackSlot(preset = organ, level = 0.7),
                RackEngine.SFIZZ to RackSlot(level = 0.8),
            ),
            liveSets = listOf(
                RackLiveSet("Organ", mapOf(RackEngine.HAMMOND to RackPart(), RackEngine.SFIZZ to RackPart(plays = false))),
                RackLiveSet("Piano", mapOf(RackEngine.HAMMOND to RackPart(plays = false), RackEngine.SFIZZ to RackPart())),
                RackLiveSet("Split", mapOf(
                    RackEngine.HAMMOND to RackPart(keyHigh = 59),
                    RackEngine.SFIZZ to RackPart(keyLow = 60))),
                RackLiveSet("Layer", mapOf(
                    RackEngine.HAMMOND to RackPart(level = 0.5),
                    RackEngine.SFIZZ to RackPart())),
            ),
        ))
        val pad = saveSong(null, "Pad and piano", RackSong(
            slots = mapOf(
                RackEngine.SURGE to RackSlot(level = 0.6),
                RackEngine.SFIZZ to RackSlot(level = 0.8),
            ),
            liveSets = listOf(
                RackLiveSet("Pad", mapOf(RackEngine.SURGE to RackPart(), RackEngine.SFIZZ to RackPart(plays = false))),
                RackLiveSet("Pad and piano", mapOf(RackEngine.SURGE to RackPart(), RackEngine.SFIZZ to RackPart())),
                RackLiveSet("Piano up an octave", mapOf(
                    RackEngine.SURGE to RackPart(plays = false),
                    RackEngine.SFIZZ to RackPart(transpose = 12))),
            ),
        ))
        saveSetlist(null, "My set", listOf(piano, organAndPiano, pad))
    }
}
