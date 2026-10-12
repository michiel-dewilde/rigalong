package be.mdw.rigalong.model

import be.mdw.rigalong.model.devices.Devices
import be.mdw.rigalong.model.devices.FoundSound
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
class Library(dir: File, private val author: String) {
    val store = Store(dir)

    private fun now() = Instant.now().toString()

    // ------------------------------------------------------------ change sets

    /**
     * What one action changed, to undo it as one: every entry it wrote, with
     * its latest version before (null: the action created it). A save that
     * moved a patch, three songs and two setlists is one change.
     * [kind] says what the action was (song, setlist, patch, ...), [name] on what.
     */
    data class ChangeSet(val kind: String, val name: String, val before: Map<Digest, Digest?>)

    private var open: LinkedHashMap<Digest, Digest?>? = null
    private val changesFile = File(dir, "changes.txt")

    /** Several library calls as one action, undone as one. */
    fun <T> inOneChange(kind: String, name: String, body: () -> T): T = change(kind, name, body)

    private fun <T> change(kind: String, name: String, body: () -> T): T {
        if (open != null) return body()   // part of an action already under way
        val rec = LinkedHashMap<Digest, Digest?>()
        open = rec
        try {
            return body()
        } finally {
            open = null
            if (rec.isNotEmpty()) pushChange(ChangeSet(kind, name, rec))
        }
    }

    private fun create(name: String, target: Ref, author: String, time: String, derivedFrom: Digest? = null): Digest =
        store.create(name, target, author, time, derivedFrom = derivedFrom).also { id -> open?.let { if (id !in it) it[id] = null } }

    private fun update(id: Digest, author: String, time: String, target: Ref? = null, name: String? = null,
                       delete: Boolean = false): Digest {
        open?.let { if (id !in it) it[id] = store.entry(id)?.latest }
        return store.update(id, author, time, target = target, name = name, delete = delete)
    }

    private fun readChanges(): MutableList<ChangeSet> {
        if (!changesFile.exists()) return mutableListOf()
        return changesFile.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val before = LinkedHashMap<Digest, Digest?>()
            for (p in parts[2].split(';').filter { it.isNotEmpty() }) {
                val (id, b) = p.split('=')
                before[Digest(id)] = if (b == "-") null else Digest(b)
            }
            ChangeSet(parts[0], parts[1], before)
        }.toMutableList()
    }

    private fun writeChanges(list: List<ChangeSet>) {
        changesFile.writeText(list.takeLast(MAX_CHANGES).joinToString("") { c ->
            val name = c.name.replace('\t', ' ').replace('\n', ' ')
            "${c.kind}\t$name\t" + c.before.entries.joinToString(";") { (id, b) -> "${id.hex}=${b?.hex ?: "-"}" } + "\n"
        })
    }

    private fun pushChange(c: ChangeSet) = writeChanges(readChanges() + c)

    /** The last action that can be undone, or null. */
    fun lastChange(): ChangeSet? = readChanges().lastOrNull()

    /**
     * Undoes the last action, all of it: every entry it wrote goes back to the
     * version it had before, as a new version (the history keeps the undone
     * one too); an entry it created is deleted. Returns what was undone.
     */
    fun undo(): ChangeSet? {
        val list = readChanges()
        val c = list.removeLastOrNull() ?: return null
        for ((id, before) in c.before.entries.reversed()) {
            if (store.entry(id) == null) continue
            if (before == null) {
                store.update(id, author, now(), delete = true)
            } else {
                val v = EntryVersion.fromJson(store.getJson(before))
                store.update(id, author, now(), target = v.target, name = v.name, delete = v.target == null)
            }
        }
        writeChanges(list)
        return c
    }

    data class SongEntry(val entry: Entry, val song: Song)

    fun songs(): List<SongEntry> = store.entries()
        .filter { it.version.target?.type == Song.TYPE }
        .map { SongEntry(it, store.getTyped(it.version.target!!.digest)) }
        .sortedBy { it.entry.version.name.lowercase() }

    fun setlists(): List<Entry> = store.entries()
        .filter { it.version.target?.type == Setlist.TYPE }
        .sortedBy { it.version.name.lowercase() }

    fun environments(): List<Entry> = store.entries()
        .filter { it.version.target?.type == Environment.TYPE }
        .sortedBy { it.version.name.lowercase() }

    fun environment(id: Digest): Environment? =
        store.entry(id)?.version?.target?.let { store.getTyped(it.digest) }

    fun saveEnvironment(entryId: Digest?, name: String, env: Environment): Digest = change("environment", name) {
        val target = Ref(store.putTyped(env), Environment.TYPE)
        if (entryId == null) create(name, target, author, now())
        else update(entryId, author, now(), target = target, name = name)
    }

    /**
     * External devices' own sounds kept in the library (I8.1) -- read from a
     * device, or from its files: every entry whose type is a driver's sound
     * type (model/devices), or only [mediaType]'s.
     */
    fun deviceSounds(mediaType: String? = null): List<Entry> = store.entries()
        .filter { e -> e.version.target?.type?.let { t -> if (mediaType != null) t == mediaType else Devices.forSounds(t) != null } == true }
        .sortedBy { it.version.name.lowercase() }

    /** The sound (the driver's stored form) entry [id] holds now. */
    fun deviceSound(id: Digest): ByteArray? = store.entry(id)?.version?.target?.let { store.get(it.digest) }

    fun saveDeviceSound(entryId: Digest?, name: String, mediaType: String, sound: ByteArray): Digest = change("device sound", name) {
        val target = Ref(store.put(sound), mediaType)
        if (entryId == null) create(name, target, author, now())
        else update(entryId, author, now(), target = target, name = name)
    }

    /**
     * The sounds of a device's file, as one change -- one Undo. A sound the
     * library already has, under any name, is not added again (a CP-88 backup
     * holds the same Init Sound many times). Returns how many were added.
     */
    fun importDeviceSounds(fileName: String, mediaType: String, sounds: List<FoundSound>): Int =
        change("import", fileName) { addDeviceSounds(mediaType, sounds) }

    /**
     * As [importDeviceSounds], but kept by the app on its own -- what a device's
     * reserved area held before the app first wrote there -- rather than
     * imported by the player: not an action of theirs, so Undo never takes it
     * back, nor does it come between their own changes and Undo.
     */
    fun keepDeviceSounds(mediaType: String, sounds: List<FoundSound>): Int {
        check(open == null) { "kept sounds are not part of an action" }
        return addDeviceSounds(mediaType, sounds)
    }

    private fun addDeviceSounds(mediaType: String, sounds: List<FoundSound>): Int {
        val have = deviceSounds(mediaType).mapNotNull { it.version.target?.digest }.toMutableSet()
        var added = 0
        for (s in sounds) {
            val d = store.put(s.sound)
            if (!have.add(d)) continue
            create(s.name.ifBlank { s.where }, Ref(d, mediaType), author, now())
            added++
        }
        return added
    }

    fun setlist(id: Digest): Setlist? =
        store.entry(id)?.version?.target?.let { store.getTyped<Setlist>(it.digest) }

    /** The song entries of a setlist, in order; a song whose entry is gone shows by its object. */
    fun songsOf(setlistId: Digest): List<Pair<Entry?, Song>> {
        val sl = setlist(setlistId) ?: return emptyList()
        val byObject = songs().associateBy { it.entry.version.target!!.digest }
        return sl.songs.mapIndexed { i, r ->
            val d = r.toRef().digest
            val named = sl.entryAt(i)?.let { store.entry(it) }?.takeIf { it.version.target != null }
            when {
                named != null -> named to store.getTyped<Song>(d)
                else -> byObject[d]?.let { it.entry to it.song } ?: (null to store.getTyped(d))
            }
        }
    }

    fun saveSong(entryId: Digest?, name: String, draft: RackSong): Digest = change("song", name) {
        val song = draft.toObjects(store)
        val target = Ref(store.putTyped(song), Song.TYPE)
        if (entryId == null) {
            create(name, target, author, now())
        } else {
            val previous = store.entry(entryId)?.version?.target?.digest
            update(entryId, author, now(), target = target, name = name)
            if (previous != null && previous != target.digest) rippleSetlists(previous, target.digest, entryId)
            entryId
        }
    }

    /**
     * Saves a song and, as the same change, the library patches it changed:
     * [patchMoves] maps a library patch to its new content and the OTHER songs
     * to move on to it (I8.13; which ones is the user's choice, I8.5).
     */
    fun saveSongAndPatches(entryId: Digest?, name: String, draft: RackSong,
                           patchMoves: Map<Digest, Pair<Patch, Collection<Digest>>>): Digest = change("song", name) {
        val id = saveSong(entryId, name, draft)
        for ((patchId, move) in patchMoves) updatePatch(patchId, move.first, move.second)
        id
    }

    /**
     * Moves the positions of song entry [entry] that held [from] on to [to].
     * A position that names another entry stays, even with equal content: two
     * songs that happen to be the same object are still two songs.
     */
    private fun rippleSetlists(from: Digest, to: Digest, entry: Digest) {
        for (e in setlists()) {
            val sl = store.getTyped<Setlist>(e.version.target!!.digest)
            fun mine(i: Int) = sl.songs[i].toRef().digest == from && (sl.entryAt(i) ?: entry) == entry
            if (sl.songs.indices.none(::mine)) continue
            val moved = sl.copy(songs = sl.songs.mapIndexed { i, r -> if (mine(i)) RefJ.of(to, Song.TYPE) else r })
            update(e.id, author, now(), target = Ref(store.putTyped(moved), Setlist.TYPE))
        }
    }

    fun saveSetlist(entryId: Digest?, name: String, songEntries: List<Digest>): Digest = change("setlist", name) {
        val known = songEntries.mapNotNull { id -> store.entry(id)?.version?.target?.let { id to it } }
        val refs = known.map { RefJ.of(it.second.digest, Song.TYPE) }
        val target = Ref(store.putTyped(Setlist(songs = refs, entries = known.map { it.first.toString() })), Setlist.TYPE)
        if (entryId == null) create(name, target, author, now())
        else update(entryId, author, now(), target = target, name = name)
    }

    fun deleteEntry(id: Digest) {
        change("delete", store.entry(id)?.version?.name.orEmpty()) { update(id, author, now(), delete = true) }
    }

    fun rename(id: Digest, name: String) {
        change("rename", name) { update(id, author, now(), name = name) }
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
        change("restore", v.name) {
            val before = store.entry(id)?.version?.target?.digest
            update(id, author, now(), target = v.target, name = v.name, delete = v.target == null)
            val after = v.target?.digest
            if (v.target?.type == Song.TYPE && before != null && after != null && before != after) rippleSetlists(before, after, id)
        }
    }

    /** A setlist holding some version of a song, and whether it is the latest (I8.5). */
    data class Use(val setlist: Entry, val latest: Boolean)

    /** The setlists that hold any version of song entry [songId]. */
    fun usedBy(songId: Digest): List<Use> {
        val versions = history(songId).mapNotNull { it.second.target?.digest }.toSet()
        val latest = store.entry(songId)?.version?.target?.digest
        return setlists().mapNotNull { e ->
            val sl = store.getTyped<Setlist>(e.version.target!!.digest)
            // Positions that are this song: naming it, or naming nothing and holding one of its versions.
            val held = sl.songs.indices.filter { i -> (sl.entryAt(i) ?: songId) == songId }
                .map { sl.songs[it].toRef().digest }
            if (held.none { it in versions }) null else Use(e, latest in held)
        }
    }

    /** Moves [setlistId] from older versions of song [songId] to its latest (I8.5): a choice, never automatic. */
    fun updateReference(setlistId: Digest, songId: Digest) {
        val latest = store.entry(songId)?.version?.target?.digest ?: return
        val older = history(songId).mapNotNull { it.second.target?.digest }.toSet() - latest
        val e = store.entry(setlistId) ?: return
        val sl = store.getTyped<Setlist>(e.version.target!!.digest)
        val moved = sl.copy(songs = sl.songs.mapIndexed { i, r ->
            if (r.toRef().digest in older && (sl.entryAt(i) ?: songId) == songId) RefJ.of(latest, Song.TYPE) else r
        })
        if (moved != sl) change("update", e.version.name) {
            update(setlistId, author, now(), target = Ref(store.putTyped(moved), Setlist.TYPE))
        }
    }

    /** Versions of [id] that came in by import and diverged from this tablet's (I8.31). */
    fun divergences(id: Digest): List<Digest> = store.divergences()[id].orEmpty()

    fun resolve(id: Digest, chosen: Digest) {
        change("resolve", store.entry(id)?.version?.name.orEmpty()) {
            open?.let { if (id !in it) it[id] = store.entry(id)?.latest }
            store.resolve(id, chosen, author, now())
        }
    }

    // ------------------------------------------------------- library patches

    /**
     * Patches as library entries, to share between songs. A song's engine
     * slot USES one when its patch object is one of the entry's versions --
     * by content, so nothing extra is stored in songs: picking a library patch
     * gives the slot exactly that object, and changing a value makes another.
     */
    fun patches(engine: String? = null): List<Pair<Entry, Patch>> = store.entries()
        .filter { it.version.target?.type == Patch.TYPE }
        .map { it to store.getTyped<Patch>(it.version.target!!.digest) }
        .filter { engine == null || it.second.engine == engine }
        .sortedBy { it.first.version.name.lowercase() }

    /** A library patch, new or a new version; a new one may name the patch version it was forked from. */
    fun savePatch(entryId: Digest?, name: String, patch: Patch, derivedFrom: Digest? = null): Digest = change("patch", name) {
        val target = Ref(store.putTyped(patch), Patch.TYPE)
        if (entryId == null) create(name, target, author, now(), derivedFrom)
        else update(entryId, author, now(), target = target, name = name)
    }

    /** Where entry [id] was forked from: the other entry and its version then, if known here. */
    fun origin(id: Digest): Pair<Entry, Digest>? {
        val first = history(id).lastOrNull()?.second ?: return null
        val from = first.derivedFrom ?: return null
        val v = runCatching { EntryVersion.fromJson(store.getJson(from)) }.getOrNull() ?: return null
        val src = store.entry(v.entry ?: from) ?: return null
        return src to from
    }

    /**
     * Stops dragging entry [id]'s history along (Store.forgetHistory): its
     * current content stays, its earlier versions no longer travel with it.
     * Not undoable, so it clears the undo steps that would need them.
     */
    fun forgetHistory(id: Digest) {
        store.forgetHistory(id, author, now())
        writeChanges(readChanges().filter { id !in it.before })
    }

    /**
     * Where a slot that links library patch [entryId] stands, with [patch] as
     * its content now: on the latest version, on an older one, or changed
     * from it (unsaved edits; [PatchLink.known] false). Null for no link, or
     * a link to an entry this library does not have.
     */
    data class PatchLink(val entry: Entry, val latest: Boolean, val known: Boolean)

    fun patchLink(entryId: Digest?, patch: Patch): PatchLink? {
        val e = entryId?.let { store.entry(it) }?.takeIf { it.version.target?.type == Patch.TYPE } ?: return null
        val d = store.putTyped(patch)
        if (e.version.target!!.digest == d) return PatchLink(e, latest = true, known = true)
        return PatchLink(e, latest = false, known = history(e.id).any { it.second.target?.digest == d })
    }

    /** A song using a library patch, and whether it uses the patch's latest version. */
    data class PatchUse(val song: Entry, val latest: Boolean)

    /** The songs whose slots link library patch [patchId] (picked or saved there, never equal content alone). */
    fun songsUsingPatch(patchId: Digest): List<PatchUse> {
        val link = patchId.toString()
        val latest = store.entry(patchId)?.version?.target?.digest
        return songs().mapNotNull { s ->
            val nodes = s.song.nodes.values.filter { it.patchEntry == link }
            if (nodes.isEmpty()) null else PatchUse(s.entry, nodes.all { it.patch?.toRef()?.digest == latest })
        }
    }

    /**
     * A new version of library patch [patchId], and the songs in [songs] moved
     * from its previous latest on to it -- each a new song version, its
     * setlists following (I8.13). Songs on an older version, or not chosen,
     * stay where they are (I8.5).
     */
    fun updatePatch(patchId: Digest, patch: Patch, songs: Collection<Digest>) {
        val entry = store.entry(patchId) ?: return
        change("patch", entry.version.name) {
            val old = entry.version.target!!.digest
            val new = store.putTyped(patch)
            if (new != old) update(patchId, author, now(), target = Ref(new, Patch.TYPE))
            for (sid in songs) {
                val se = store.entry(sid) ?: continue
                val before = se.version.target?.digest ?: continue
                val song = store.getTyped<Song>(before)
                val link = patchId.toString()
                fun mine(n: Node) = n.patchEntry == link && n.patch?.toRef()?.digest == old
                if (song.nodes.values.none(::mine)) continue
                val moved = song.copy(nodes = song.nodes.mapValues { (_, n) ->
                    if (mine(n)) n.copy(patch = RefJ.of(new, Patch.TYPE)) else n
                })
                val after = store.putTyped(moved)
                update(sid, author, now(), target = Ref(after, Song.TYPE))
                rippleSetlists(before, after, sid)
            }
        }
    }

    /** Moves song [songId] from older versions of library patch [patchId] to its latest (I8.5): a choice. */
    fun updatePatchReference(songId: Digest, patchId: Digest) {
        val latest = store.entry(patchId)?.version?.target?.digest ?: return
        val older = history(patchId).mapNotNull { it.second.target?.digest }.toSet() - latest
        val se = store.entry(songId) ?: return
        val before = se.version.target?.digest ?: return
        val song = store.getTyped<Song>(before)
        val link = patchId.toString()
        fun mine(n: Node) = n.patchEntry == link && n.patch?.toRef()?.digest in older
        if (song.nodes.values.none(::mine)) return
        change("update", se.version.name) {
            val moved = song.copy(nodes = song.nodes.mapValues { (_, n) ->
                if (mine(n)) n.copy(patch = RefJ.of(latest, Patch.TYPE)) else n
            })
            val after = store.putTyped(moved)
            update(songId, author, now(), target = Ref(after, Song.TYPE))
            rippleSetlists(before, after, songId)
        }
    }

    fun rackSong(song: Song): RackSong? = RackSong.fromObjects(store, song)

    companion object {
        /** How many actions Undo can go back. */
        const val MAX_CHANGES = 30
    }

    /** First run: a setlist with three songs, so the stage view has something to play (S14.2). */
    fun seedIfEmpty(setBfreePresets: List<String>) {
        if (setlists().isNotEmpty()) return
        val registration = setBfreePresets.firstOrNull { it.contains("JIMMY", true) } ?: setBfreePresets.firstOrNull()
        val sfizz = saveSong(null, "sfizz", RackSong(
            instances = listOf(RackInstance(RackEngine.SFIZZ.key, RackEngine.SFIZZ, RackSlot(level = 0.8))),
            liveSets = listOf(RackLiveSet("sfizz", mapOf(RackEngine.SFIZZ.key to RackPart()))),
        ))
        val setBfreeAndSfizz = saveSong(null, "setBfree and sfizz", RackSong(
            instances = listOf(
                RackInstance(RackEngine.SETBFREE.key, RackEngine.SETBFREE, RackSlot(preset = registration, level = 0.7)),
                RackInstance(RackEngine.SFIZZ.key, RackEngine.SFIZZ, RackSlot(level = 0.8)),
            ),
            liveSets = listOf(
                RackLiveSet("setBfree", mapOf(RackEngine.SETBFREE.key to RackPart(), RackEngine.SFIZZ.key to RackPart(plays = false))),
                RackLiveSet("sfizz", mapOf(RackEngine.SETBFREE.key to RackPart(plays = false), RackEngine.SFIZZ.key to RackPart())),
                RackLiveSet("Split", mapOf(
                    RackEngine.SETBFREE.key to RackPart(keyHigh = 59),
                    RackEngine.SFIZZ.key to RackPart(keyLow = 60))),
                RackLiveSet("Layer", mapOf(
                    RackEngine.SETBFREE.key to RackPart(level = 0.5),
                    RackEngine.SFIZZ.key to RackPart())),
            ),
        ))
        val surgeAndSfizz = saveSong(null, "Surge XT and sfizz", RackSong(
            instances = listOf(
                RackInstance(RackEngine.SURGE.key, RackEngine.SURGE, RackSlot(level = 0.6)),
                RackInstance(RackEngine.SFIZZ.key, RackEngine.SFIZZ, RackSlot(level = 0.8)),
            ),
            liveSets = listOf(
                RackLiveSet("Surge XT", mapOf(RackEngine.SURGE.key to RackPart(), RackEngine.SFIZZ.key to RackPart(plays = false))),
                RackLiveSet("Surge XT and sfizz", mapOf(RackEngine.SURGE.key to RackPart(), RackEngine.SFIZZ.key to RackPart())),
                RackLiveSet("sfizz up an octave", mapOf(
                    RackEngine.SURGE.key to RackPart(plays = false),
                    RackEngine.SFIZZ.key to RackPart(transpose = 12))),
            ),
        ))
        saveSetlist(null, "My set", listOf(sfizz, setBfreeAndSfizz, surgeAndSfizz))
    }
}
