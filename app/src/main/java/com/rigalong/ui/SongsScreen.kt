package com.rigalong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rigalong.R
import com.rigalong.model.Digest
import com.rigalong.model.Library
import com.rigalong.model.RackSong
import com.rigalong.model.getTyped
import com.rigalong.model.putTyped
import kotlinx.coroutines.delay
import com.rigalong.play.SongPlayer
import com.rigalong.play.EngineHost

/* Songs: the setlists and every song, and the song open in the editor (SongEditor.kt). */

/**
 * The song open in Songs, kept above the screens: switching to the stage and
 * back finds it as it was, unsaved edits and all ("Songs · unsaved" says
 * there are some). Kept on disk too while it has unsaved edits ([persist],
 * [restore]): closing the app or a crash loses nothing (Michiel, 2026-11-13);
 * "Back to saved" lets the edits go.
 */
class SongsEdit(private val lib: Library, private val player: SongPlayer) {
    var selected by mutableStateOf<Digest?>(null)
    var editing by mutableStateOf<Pair<Digest?, RackSong>?>(null)
    var editName by mutableStateOf("")
    /** A new song goes into the setlist when saved, unless it was made under All songs. */
    var newIntoSetlist by mutableStateOf(true)
    /** The song as it was opened: what its slots' library patches were before editing. */
    var original by mutableStateOf<RackSong?>(null)
    var originalName by mutableStateOf("")
    /** The setlist the Songs panel shows, chosen there; null: the one on stage. */
    var setlist by mutableStateOf<Digest?>(null)
    val unsaved: Boolean get() = editing?.let { (id, song) -> id == null || song != original || editName != originalName } ?: false
    /** Counts what was changed in the library from here, so the lists read it again. */
    var revision by mutableIntStateOf(0)
        private set

    /** The setlist edited here: the one chosen in the panel, else the one on stage. */
    val setlistId: Digest? get() = setlist?.takeIf { lib.store.entry(it) != null } ?: player.setlistId

    /** The library was changed from here: the lists and the stage read it again. */
    fun changed() { revision++; player.reload() }

    fun pickSetlist(id: Digest) { setlist = id; revision++ }
    fun putOnStage(id: Digest) { player.openSetlist(id); revision++ }

    /** Song [id] as saved, in the editor; edits to what was open are let go. */
    fun open(id: Digest) {
        selected = id
        val e = lib.store.entry(id)
        val rack = lib.song(id)?.let { lib.rackSong(it) }
        if (e != null && rack != null) { editing = id to rack; editName = e.version.name; original = rack; originalName = editName }
    }

    /** A song that is not in the library yet, in the editor. */
    fun startNew(song: RackSong, name: String, intoSetlist: Boolean) {
        newIntoSetlist = intoSetlist
        editing = null to song
        original = null
        editName = name
        selected = null
    }

    /** The library patches saving the open song would change, each a choice for the player (I8.5, I8.13). */
    fun patchDecisions(): List<Library.PatchDecision> =
        editing?.let { (id, song) -> lib.patchDecisions(original, song, id) }.orEmpty()

    /**
     * Saves the open song, as one change with all that goes with it: [moves]
     * (per library patch its new content and the other songs that move on to
     * it), and a new song's place at the end of the setlist. The sounds whose
     * library patch is in [only] let go of it and keep their own ("only in
     * this song"). The stage reads the library again; a live set that was
     * being heard is heard again. A saved song without edits is left alone.
     */
    fun save(moves: Map<Digest, Pair<com.rigalong.model.Patch, Collection<Digest>>> = emptyMap(), only: Set<Digest> = emptySet()) {
        val (was, edited) = editing ?: return
        // Nothing to save: no version, and no Undo step, for a song that is as the library has it.
        if (was != null && !unsaved && lib.song(was) != null) return
        val song = if (only.isEmpty()) edited else edited.copy(instances = edited.instances.map {
            if (it.slot.libraryPatch in only) it.copy(slot = it.slot.copy(libraryPatch = null)) else it
        })
        val into = setlistId
        val id = lib.inOneChange("song", editName) {
            val id = lib.saveSongAndPatches(was, editName, song, moves)
            if (was == null && into != null && newIntoSetlist) {
                val ids = lib.songsOf(into).mapNotNull { it.first?.id } + id
                lib.saveSetlist(into, lib.store.entry(into)?.version?.name ?: player.setlistName, ids)
            }
            id
        }
        editing = id to song
        original = song
        originalName = editName
        selected = id
        revision++
        val wasHeard = player.heard?.takeIf { it.first == was }?.second
        player.reload()
        wasHeard?.let { player.hear(id, song, it) }
    }

    /** The open song saved as another one, [name], and that one opened. */
    fun saveCopy(name: String) {
        val song = editing?.second ?: return
        val id = lib.saveSong(null, name, song)
        revision++
        open(id)
    }

    /** The library's last action undone, whole; the open song as it is after that. */
    fun undo() {
        lib.undo()
        revision++
        editing?.first?.let { open(it) }
        player.reload()
    }

    /** The saved song back, the edits let go (a song that has been saved). */
    fun backToSaved() {
        editing?.first?.let { open(it); player.reload() }
    }

    /**
     * Writes the open song to [Library.draftFile] while it has unsaved edits,
     * and removes the file when it has none. The song goes into the store as
     * objects (content-addressed, unreferenced until saved); the file holds
     * their digests and what the editor needs around them. Off the main thread.
     */
    fun persist() {
        val ed = editing
        if (ed == null || !unsaved) { lib.draftFile.delete(); return }
        val j = org.json.JSONObject()
        j.put("entry", ed.first?.hex ?: org.json.JSONObject.NULL)
        j.put("song", lib.store.putTyped(ed.second.toObjects(lib.store)).hex)
        j.put("name", editName)
        original?.let { j.put("original", lib.store.putTyped(it.toObjects(lib.store)).hex) }
        j.put("originalName", originalName)
        j.put("newIntoSetlist", newIntoSetlist)
        setlist?.let { j.put("setlist", it.hex) }
        val tmp = java.io.File(lib.draftFile.path + ".tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(lib.draftFile)
    }

    /** The song that had unsaved edits when the app last stopped, open again as it was. */
    fun restore() {
        val f = lib.draftFile
        if (!f.exists()) return
        runCatching {
            val j = org.json.JSONObject(f.readText())
            fun rack(key: String) = j.optString(key).takeIf { it.isNotEmpty() }?.let { hex ->
                lib.rackSong(lib.store.getTyped<com.rigalong.model.Song>(Digest(hex)))
            }
            val song = rack("song") ?: return
            val entry = if (j.isNull("entry")) null else Digest(j.getString("entry")).takeIf { lib.store.entry(it) != null }
            editing = entry to song
            selected = entry
            editName = j.optString("name")
            original = if (entry == null) null else rack("original")
            originalName = j.optString("originalName")
            newIntoSetlist = j.optBoolean("newIntoSetlist", true)
            setlist = j.optString("setlist").takeIf { it.isNotEmpty() }?.let { Digest(it) }?.takeIf { lib.store.entry(it) != null }
        }.onFailure { android.util.Log.w("rigalong-ui", "the unsaved song could not be read back: $it") }
    }
}

@Composable
internal fun SongsScreen(player: SongPlayer, lib: Library, host: EngineHost, st: SongsEdit) {
    val revision = st.revision
    val setlistId = st.setlistId
    val selected = st.selected
    val editing = st.editing
    val editName = st.editName
    var focus by remember { mutableStateOf<SongPlayer.EditRequest?>(null) }
    // A phone shows the setlist or the editor: whether the list is back in front of an open song.
    var listShown by remember { mutableStateOf(false) }
    var decisions by remember { mutableStateOf<List<Library.PatchDecision>>(emptyList()) }
    // Another song asked for while this one has unsaved edits: what to do once the player says.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guarded(action: () -> Unit) { if (st.unsaved) pending = action else action() }
    val newSongName = stringResource(R.string.new_song)
    val copyName = stringResource(R.string.copy_of, editName)
    fun open(id: Digest) { listShown = false; st.open(id) }
    // While a live set of the open song is heard, the engine follows the edits (after a pause).
    LaunchedEffect(editing) {
        val ed = editing ?: return@LaunchedEffect
        val h = player.heard ?: return@LaunchedEffect
        // Another song opened: the stage's comes back until one of its live sets is heard.
        if (h.first != ed.first) { player.stopHearing(); return@LaunchedEffect }
        val i = h.second
        delay(300)
        player.hear(ed.first, ed.second, i.coerceAtMost(ed.second.liveSets.size - 1))
    }
    // Quick edit from the stage: the song, scrolled to its live set or with its patch open.
    LaunchedEffect(player.editRequest) {
        player.editRequest?.let { r -> open(r.song); focus = r; player.editRequest = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        val list = @Composable {
            SetlistPanel(lib, setlistId, player.setlistId, revision, selected,
                // On a phone the song still open is only brought back, unsaved changes and all.
                onSelect = { id ->
                    focus = null
                    if (id == editing?.first) listShown = false else guarded { open(id) }
                },
                onNew = { intoSetlist ->
                    guarded {
                        listShown = false
                        st.startNew(RackSong.starter(preset = host.currentInstrument()), newSongName, intoSetlist)
                    }
                },
                onChanged = st::changed,
                onPickSetlist = st::pickSetlist,
                onPutOnStage = st::putOnStage)
        }
        val editor = @Composable {
            val ed = editing
            if (ed == null) {
                Text(stringResource(R.string.pick_song), color = Dim, modifier = Modifier.padding(24.dp))
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Undo: the last action, whole (a save that moved patches, songs and setlists).
                    val last = remember(revision) { lib.lastChange() }
                    if (last != null) {
                        TextButton(onClick = st::undo) { Text(stringResource(R.string.undo_named, last.name)) }
                    }
                    SongEditor(ed.second, editName, host, player, lib, focus?.takeIf { it.song == ed.first },
                        unsaved = st.unsaved,
                        // The saved song back, the edits let go (only for a song that has been saved).
                        onBackToSaved = if (ed.first != null && st.unsaved) st::backToSaved else null,
                        hearing = player.heard?.takeIf { it.first == ed.first }?.second,
                        onHear = { i -> player.hear(ed.first, ed.second, i) },
                        onName = { st.editName = it },
                        onChange = { st.editing = ed.first to it },
                        onSave = {
                            // A changed library patch: everywhere, only here, or chosen songs (I8.5, I8.13).
                            val d = st.patchDecisions()
                            if (d.isEmpty()) st.save() else decisions = d
                        },
                        onSaveCopy = { st.saveCopy(copyName) })
                }
            }
        }
        pending?.let { go ->
            androidx.compose.material3.AlertDialog(onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.unsaved_title, editName)) },
                text = { Text(stringResource(R.string.unsaved_text)) },
                confirmButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.keep_editing)) } },
                dismissButton = { TextButton(onClick = { pending = null; go() }) { Text(stringResource(R.string.discard_changes), color = Danger) } })
        }
        if (decisions.isNotEmpty()) {
            PatchSaveDialog(decisions, onSave = { moves, only -> decisions = emptyList(); st.save(moves, only) },
                onDismiss = { decisions = emptyList() })
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(340.dp).fillMaxHeight()) { list() }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) { editor() }
            }
        } else if (listShown || editing == null) {
            // A phone: the setlist, or the song open in the editor -- each the whole height.
            Box(Modifier.fillMaxSize()) { list() }
        } else {
            Column(Modifier.fillMaxSize()) {
                // Back to the list; the song stays open as it is, unsaved changes and all.
                TextButton(onClick = { listShown = true }) { Text(stringResource(R.string.back_to_setlist)) }
                Box(Modifier.weight(1f)) { editor() }
            }
        }
    }
}

@Composable
private fun SetlistPanel(lib: Library, setlistId: Digest?, stageSetlist: Digest?, revision: Int, selected: Digest?,
                         onSelect: (Digest) -> Unit, onNew: (intoSetlist: Boolean) -> Unit, onChanged: () -> Unit,
                         onPickSetlist: (Digest) -> Unit, onPutOnStage: (Digest) -> Unit) {
    val songs = remember(setlistId, revision) { setlistId?.let { lib.songsOf(it) }.orEmpty() }
    val name = remember(setlistId, revision) { setlistId?.let { lib.store.entry(it)?.version?.name }.orEmpty() }
    // Songs exist on their own (Michiel, 2026-10-14): the setlist, or every song, to open, make or add from.
    var all by remember { mutableStateOf(setlistId == null) }
    var browsing by remember { mutableStateOf(false) }
    val inSetlist = songs.mapNotNull { it.first?.id }
    fun addToSetlist(id: Digest) {
        setlistId?.let { lib.saveSetlist(it, name, inSetlist + id); onChanged() }
    }
    Column(Modifier.fillMaxSize().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        // Which setlist is edited here -- any, not only the one on stage --, a new one, or a copy.
        var menu by remember { mutableStateOf(false) }
        val newSetlistName = stringResource(R.string.new_setlist_name)
        val copyName = stringResource(R.string.copy_of, name)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                androidx.compose.material3.FilterChip(selected = !all && setlistId != null,
                    onClick = { if (all && setlistId != null) all = false else menu = true },
                    label = { Text((if (setlistId != null) stringResource(R.string.setlist_named, name) else stringResource(R.string.setlists)) + " ▾",
                        maxLines = 1, overflow = TextOverflow.Ellipsis) })
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (e in remember(revision, menu) { lib.setlists() }) {
                        androidx.compose.material3.DropdownMenuItem(onClick = { menu = false; all = false; onPickSetlist(e.id) },
                            text = { Text(e.version.name + if (e.id == stageSetlist) stringResource(R.string.on_stage) else "",
                                fontWeight = if (e.id == setlistId) FontWeight.Bold else FontWeight.Normal) })
                    }
                    androidx.compose.material3.HorizontalDivider()
                    androidx.compose.material3.DropdownMenuItem(onClick = {
                        menu = false; all = false; onPickSetlist(lib.saveSetlist(null, newSetlistName, emptyList()))
                    }, text = { Text(stringResource(R.string.new_setlist)) })
                    if (setlistId != null) {
                        androidx.compose.material3.DropdownMenuItem(onClick = {
                            menu = false; all = false; onPickSetlist(lib.saveSetlist(null, copyName, inSetlist))
                        }, text = { Text(stringResource(R.string.duplicate_setlist, name)) })
                        if (setlistId != stageSetlist) androidx.compose.material3.DropdownMenuItem(
                            onClick = { menu = false; onPutOnStage(setlistId) }, text = { Text(stringResource(R.string.put_on_stage)) })
                    }
                }
            }
            androidx.compose.material3.FilterChip(selected = all, onClick = { all = true },
                label = { Text(stringResource(R.string.all_songs)) })
        }
        Spacer(Modifier.height(6.dp))
        if (all) {
            SongList(lib, revision, selected, inSetlist, Modifier.weight(1f), onOpen = onSelect,
                onAdd = if (setlistId != null) ::addToSetlist else null)
            Button(onClick = { onNew(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_song_alone)) }
            return@Column
        }
        // A long press and a drag moves a song to another place; ▲ ▼ move it by one.
        var dragged by remember { mutableStateOf(-1) }
        var dragBy by remember { mutableStateOf(0f) }
        var rowPx by remember { mutableStateOf(1f) }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(songs) { i, (entry, _) ->
                val id = entry?.id
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .onGloballyPositioned { rowPx = it.size.height.toFloat().coerceAtLeast(1f) }
                    .graphicsLayer { if (dragged == i) { translationY = dragBy; shadowElevation = 8f } }
                    .zIndex(if (dragged == i) 1f else 0f)
                    .background(if (dragged == i) Panel else if (id != null && id == selected) Accent.copy(alpha = 0.2f) else Color.Transparent)
                    .pointerInput(setlistId, songs) {
                        if (setlistId == null) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragged = i; dragBy = 0f },
                            onDrag = { c, d -> c.consume(); dragBy += d.y },
                            onDragEnd = {
                                val to = (i + Math.round(dragBy / rowPx)).coerceIn(0, songs.size - 1)
                                dragged = -1; dragBy = 0f
                                if (to != i) { moveTo(lib, setlistId, songs.mapNotNull { it.first?.id }, i, to); onChanged() }
                            },
                            onDragCancel = { dragged = -1; dragBy = 0f })
                    }
                    .clickable(enabled = id != null) { id?.let(onSelect) }.padding(vertical = 6.dp)) {
                    Text("${i + 1}. ${entry?.version?.name ?: stringResource(R.string.removed_song)}", modifier = Modifier.weight(1f))
                    if (setlistId != null) {
                        TextButton(onClick = { move(lib, setlistId, songs.mapNotNull { it.first?.id }, i, -1); onChanged() },
                            enabled = i > 0) { Text("▲") }
                        TextButton(onClick = { move(lib, setlistId, songs.mapNotNull { it.first?.id }, i, +1); onChanged() },
                            enabled = i + 1 < songs.size) { Text("▼") }
                        TextButton(onClick = {
                            val ids = songs.mapNotNull { it.first?.id }.toMutableList().also { it.removeAt(i) }
                            lib.saveSetlist(setlistId, name, ids); onChanged()
                        }) { Text("✕", color = Danger) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNew(true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.add_song)) }
            OutlinedButton(onClick = { browsing = true }) { Text(stringResource(R.string.add_songs)) }
        }
    }
    // The songs to add to the setlist from: every song, searched, a tap adds one.
    if (browsing) androidx.compose.material3.AlertDialog(onDismissRequest = { browsing = false },
        confirmButton = { TextButton(onClick = { browsing = false }) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(R.string.add_songs_to, name)) },
        text = { SongList(lib, revision, null, inSetlist, Modifier.height(420.dp), onOpen = null, onAdd = ::addToSetlist) })
}

/**
 * Every song in the library, by name, with a search (Michiel, 2026-10-14:
 * songs exist as such, and making a setlist means browsing them). A tap opens
 * one ([onOpen]) or, without that, adds it ([onAdd]); "+" adds one to the
 * setlist; those already in it are marked.
 */
@Composable
private fun SongList(lib: Library, revision: Int, selected: Digest?, inSetlist: List<Digest>, modifier: Modifier,
                     onOpen: ((Digest) -> Unit)?, onAdd: ((Digest) -> Unit)?) {
    var filter by remember { mutableStateOf("") }
    val all = remember(revision) { lib.songs().sortedBy { it.entry.version.name.lowercase() } }
    Column(modifier) {
        OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
            label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
        val shown = all.filter { filter.isBlank() || it.entry.version.name.contains(filter.trim(), true) }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(shown) { _, s ->
                val id = s.entry.id
                val there = id in inSetlist
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .background(if (id == selected) Accent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { if (onOpen != null) onOpen(id) else if (!there) onAdd?.invoke(id) }
                    .padding(vertical = 6.dp)) {
                    Text(s.entry.version.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (there) Text(stringResource(R.string.in_setlist), color = Dim, fontSize = 12.sp)
                    else if (onAdd != null) TextButton(onClick = { onAdd(id) }) { Text("+") }
                }
            }
        }
    }
}

/** Song [from] of the setlist to place [to], the others closing up. */
private fun moveTo(lib: Library, setlistId: Digest, ids: List<Digest>, from: Int, to: Int) {
    if (from !in ids.indices || to !in ids.indices) return
    val m = ids.toMutableList()
    m.add(to, m.removeAt(from))
    lib.saveSetlist(setlistId, lib.store.entry(setlistId)?.version?.name ?: "Setlist", m)
}

internal fun move(lib: Library, setlistId: Digest, ids: List<Digest>, i: Int, by: Int) {
    val m = ids.toMutableList()
    val j = i + by
    if (j !in m.indices) return
    m[i] = m[j].also { m[j] = m[i] }
    lib.saveSetlist(setlistId, lib.store.entry(setlistId)?.version?.name ?: "Setlist", m)
}
