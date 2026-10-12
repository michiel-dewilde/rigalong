package be.mdw.rigalong.ui

import be.mdw.rigalong.model.Library
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import be.mdw.rigalong.R
import be.mdw.rigalong.model.Digest
import be.mdw.rigalong.model.Exchange
import be.mdw.rigalong.model.Store

/*
 * The library, first slice (REQUIREMENTS section 8): setlists and songs as
 * library entries, and exchange through Android's file picker as an OCI Image
 * Layout archive (I8.8, I8.27). Import never conflicts: an object that exists
 * is the same object, and a divergent entry is kept beside the local one.
 */

private val PanelColor = Color(0xFF1A1D23)
private val DimColor = Color(0xFF8A919C)

@Composable
fun LibraryScreen(player: SongPlayer, lib: Library, host: EngineHost, onOpened: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    val setlists = remember(revision) { lib.setlists() }
    val songs = remember(revision) { lib.songs() }
    val patches = remember(revision) { lib.patches() }
    val deviceSounds = remember(revision) { lib.deviceSounds() }
    var exportSound by remember { mutableStateOf<Pair<Digest, be.mdw.rigalong.model.devices.SoundFormat>?>(null) }
    var editSound by remember { mutableStateOf<Digest?>(null) }
    val last = remember(revision) { lib.lastChange() }
    var exportIds by remember { mutableStateOf<List<Digest>>(emptyList()) }
    var detail by remember { mutableStateOf<Digest?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri ->
        if (uri != null) message = runCatching { exportTo(context, lib.store, exportIds, uri) }
            .fold({ context.getString(R.string.exported, exportIds.size) },
                { context.getString(R.string.export_failed, it.message ?: "") })
    }
    // One Import for everything, told apart by what the file IS (its first bytes), not its name.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            message = context.getString(R.string.installing)
            val name = displayName(context, uri)
            Thread {
                val r = runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    importFile(context, lib, host, name, bytes)
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    message = r.fold({ it }, { context.getString(R.string.import_failed, it.message ?: "") })
                    revision++
                }
            }.start()
        }
    }
    val soundSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val (id, format) = exportSound ?: return@rememberLauncherForActivityResult
        if (uri != null) message = runCatching {
            val sound = lib.deviceSound(id) ?: error("no sound")
            val file = format.writeOne(sound) ?: error("${format.label} writes no single sounds")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(file.second) }
        }.fold({ context.getString(R.string.exported, 1) }, { context.getString(R.string.export_failed, it.message ?: "") })
    }

    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_any)) }
            OutlinedButton(enabled = player.setlistId != null, onClick = {
                val sl = player.setlistId ?: return@OutlinedButton
                exportIds = listOf(sl) + lib.songsOf(sl).mapNotNull { it.first?.id }
                exporter.launch("${player.setlistName}.rigalong.tar")
            }) { Text(stringResource(R.string.export_setlist)) }
            // The last action, whole: a save that moved patches, songs and setlists is one Undo.
            if (last != null) TextButton(onClick = { lib.undo(); revision++; player.reload() }) {
                Text(stringResource(R.string.undo_named, last.name))
            }
            Text(message, color = DimColor, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        AdaptivePair(380.dp, first = { m ->
            Column(m.background(PanelColor, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(stringResource(R.string.setlists), fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.weight(1f)) {
                    items(setlists) { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { player.openSetlist(e.id); onOpened() }.padding(vertical = 8.dp)) {
                            Text(e.version.name + if (e.id == player.setlistId) stringResource(R.string.on_stage) else "",
                                modifier = Modifier.weight(1f))
                            Text(e.id.short.take(7), color = DimColor, fontSize = 11.sp)
                            TextButton(onClick = { detail = e.id }) { Text(stringResource(R.string.details)) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                        label = { Text(stringResource(R.string.new_setlist)) }, modifier = Modifier.weight(1f))
                    Button(enabled = newName.isNotBlank(), onClick = {
                        lib.saveSetlist(null, newName.trim(), emptyList()); newName = ""; revision++
                    }) { Text(stringResource(R.string.create)) }
                }
            }
        }, second = { m ->
            Column(m.background(PanelColor, RoundedCornerShape(10.dp)).padding(10.dp)) {
                // One list for the panel, its sections one after the other: a library with
                // many device sounds or patches can still reach every one of them.
                LazyColumn(Modifier.weight(1f)) {
                    item { Text(stringResource(R.string.all_songs), fontWeight = FontWeight.SemiBold) }
                    items(songs) { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)) {
                            Text(s.entry.version.name, modifier = Modifier.weight(1f))
                            val n = lib.store.history(s.entry.latest).size
                            TextButton(onClick = { detail = s.entry.id }) {
                                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.versions, n, n), fontSize = 12.sp)
                            }
                            TextButton(enabled = player.setlistId != null, onClick = {
                                val sl = player.setlistId ?: return@TextButton
                                val ids = lib.songsOf(sl).mapNotNull { it.first?.id } + s.entry.id
                                lib.saveSetlist(sl, player.setlistName, ids)
                                player.reload(); revision++
                            }) { Text(stringResource(R.string.add_to_setlist)) }
                        }
                    }
                    // External devices' own sounds (model/devices), by driver: from the device or its
                    // files; heard on it, saved as its files, edited as text where its driver can.
                    if (deviceSounds.isNotEmpty()) {
                        item { Text(stringResource(R.string.device_sounds), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                        for ((drv, entries) in deviceSounds.groupBy { be.mdw.rigalong.model.devices.Devices.forSounds(it.version.target?.type ?: "") }) {
                            if (drv == null) continue
                            val sounds = drv.sounds!!
                            item { Text(stringResource(R.string.device_sounds_of, drv.label), color = DimColor, fontSize = 12.sp) }
                            items(entries) { e ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(e.version.name, modifier = Modifier.weight(1f))
                                    player.devices.withSounds(sounds.mediaType).firstOrNull()?.let { d ->
                                        TextButton(onClick = {
                                            val ok = lib.deviceSound(e.id)?.let { player.devices.audition(d, it) } ?: false
                                            message = context.getString(if (ok) R.string.device_playing else R.string.device_unreachable, d.name)
                                        }) { Text(stringResource(R.string.play_on, d.name)) }
                                    }
                                    for (f in sounds.formats) {
                                        val ext = remember(f) { f.writeOne(sounds.silent)?.first } ?: continue
                                        TextButton(onClick = { exportSound = e.id to f; soundSaver.launch("${e.version.name.trim()}.$ext") }) {
                                            Text(stringResource(R.string.save_ext, ext))
                                        }
                                    }
                                    sounds.formats.firstOrNull { it.editable }?.let { f ->
                                        TextButton(onClick = { editSound = e.id }) { Text(stringResource(R.string.edit_as, f.label)) }
                                    }
                                    TextButton(onClick = { detail = e.id }) { Text(stringResource(R.string.details)) }
                                }
                            }
                        }
                    }
                    // Library patches, shared between songs (M9).
                    if (patches.isNotEmpty()) {
                        item { Text(stringResource(R.string.patches), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                        items(patches) { (e, p) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(e.version.name, modifier = Modifier.weight(1f))
                                Text(p.engine, color = DimColor, fontSize = 12.sp)
                                TextButton(onClick = { detail = e.id }) { Text(stringResource(R.string.details)) }
                            }
                        }
                    }
                }
            }
        })
    }
    detail?.let { id ->
        EntryDialog(id, lib, player, onChanged = { revision++ }, onDismiss = { detail = null })
    }
    editSound?.let { id ->
        val entry = remember(id, revision) { lib.store.entry(id) }
        val sound = remember(id, revision) { lib.deviceSound(id) }
        val drv = entry?.version?.target?.type?.let { be.mdw.rigalong.model.devices.Devices.forSounds(it) }
        val format = drv?.sounds?.formats?.firstOrNull { it.editable }
        if (entry == null || sound == null || drv == null || format == null) { editSound = null; return@let }
        val sounds = drv.sounds!!
        val device = player.devices.withSounds(sounds.mediaType).firstOrNull()
        DeviceSoundEditor(device?.name ?: drv.defaultName, entry.version.name, sound, drv, format,
            play = device?.let { d -> { s: ByteArray -> player.devices.audition(d, s) } },
            onDismiss = { editSound = null }, onSave = { edited ->
                // The entry follows the sound's name, unless it was given a name of its own.
                val named = entry.version.name.trim() == sounds.name(sound).trim()
                val name = if (named) sounds.name(edited).trim().ifEmpty { entry.version.name } else entry.version.name
                lib.saveDeviceSound(id, name, sounds.mediaType, edited)
                revision++
            })
    }
}

/**
 * One entry (M9, I8.3-I8.5, I8.31): its name, its history with restore, the
 * setlists that use a song (and whether they hold its latest version, with
 * the update as a choice), versions that diverged on import, and delete.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryDialog(id: Digest, lib: Library, player: SongPlayer, onChanged: () -> Unit, onDismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val entry = remember(revision) { lib.store.entry(id) } ?: run { onDismiss(); return }
    val history = remember(revision) { lib.history(id) }
    val isSong = entry.version.target?.type == be.mdw.rigalong.model.Song.TYPE
    val uses = remember(revision) { if (isSong) lib.usedBy(id) else emptyList() }
    val isPatch = entry.version.target?.type == be.mdw.rigalong.model.Patch.TYPE
    val patchUses = remember(revision) { if (isPatch) lib.songsUsingPatch(id) else emptyList() }
    val origin = remember(revision) { lib.origin(id) }
    val diverged = remember(revision) { lib.divergences(id) }
    var name by remember(revision) { mutableStateOf(entry.version.name) }
    fun changed() {
        revision++; onChanged()
        // What is on stage may be what just changed.
        player.reload()
    }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            // Held, not tapped: a delete must not happen by accident (I8.4).
            Text(stringResource(R.string.delete_hold), color = Color(0xFFE5534B),
                modifier = Modifier.padding(12.dp).combinedClickable(onClick = {}, onLongClick = { lib.deleteEntry(id); onChanged(); onDismiss() }))
        },
        title = { Text(entry.version.name) },
        text = {
            LazyColumn(Modifier.height(460.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                            label = { Text(stringResource(R.string.name)) }, modifier = Modifier.weight(1f))
                        TextButton(enabled = name.isNotBlank() && name != entry.version.name,
                            onClick = { lib.rename(id, name.trim()); changed() }) { Text(stringResource(R.string.rename)) }
                    }
                    Text(stringResource(R.string.entry_id, id.short), color = DimColor, fontSize = 12.sp)
                    origin?.let { Text(stringResource(R.string.forked_from, it.first.version.name), color = DimColor, fontSize = 12.sp) }
                }
                if (diverged.isNotEmpty()) {
                    item { Section(stringResource(R.string.diverged)) }
                    items(diverged) { d ->
                        val v = be.mdw.rigalong.model.EntryVersion.fromJson(lib.store.getJson(d))
                        VersionRow(v, stringResource(R.string.use_this)) { lib.resolve(id, d); changed() }
                    }
                    item {
                        TextButton(onClick = { lib.resolve(id, entry.latest); changed() }) {
                            Text(stringResource(R.string.keep_mine))
                        }
                    }
                }
                if (isSong) {
                    item { Section(stringResource(R.string.used_by)) }
                    if (uses.isEmpty()) item { Text(stringResource(R.string.used_by_none), color = DimColor, fontSize = 13.sp) }
                    items(uses) { u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.setlist.version.name, modifier = Modifier.weight(1f))
                            if (u.latest) Text(stringResource(R.string.latest_version), color = DimColor, fontSize = 12.sp)
                            else TextButton(onClick = { lib.updateReference(u.setlist.id, id); changed() }) {
                                Text(stringResource(R.string.update_reference))
                            }
                        }
                    }
                }
                if (isPatch) {
                    item { Section(stringResource(R.string.used_by)) }
                    if (patchUses.isEmpty()) item { Text(stringResource(R.string.used_by_no_song), color = DimColor, fontSize = 13.sp) }
                    items(patchUses) { u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.song.version.name, modifier = Modifier.weight(1f))
                            if (u.latest) Text(stringResource(R.string.latest_version), color = DimColor, fontSize = 12.sp)
                            else TextButton(onClick = { lib.updatePatchReference(u.song.id, id); changed() }) {
                                Text(stringResource(R.string.update_reference))
                            }
                        }
                    }
                }
                item { Section(stringResource(R.string.history)) }
                items(history.size) { i ->
                    val (d, v) = history[i]
                    VersionRow(v, if (i == 0) null else stringResource(R.string.restore)) { lib.restore(id, d); changed() }
                }
                // Stop dragging the history along (exports, sync): held, and not undoable.
                if (history.size > 1) item {
                    Text(stringResource(R.string.forget_history_hold), color = Color(0xFFE5534B),
                        modifier = Modifier.padding(top = 10.dp)
                            .combinedClickable(onClick = {}, onLongClick = { lib.forgetHistory(id); changed() }))
                    Text(stringResource(R.string.forget_history_note), color = DimColor, fontSize = 12.sp)
                }
            }
        })
}

@Composable
private fun Section(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun VersionRow(v: be.mdw.rigalong.model.EntryVersion, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(v.name + if (v.target == null) "  ✕" else "", fontSize = 14.sp)
            // Local time; and the content's hash, so two versions with the same
            // content (an undone change) are seen to be the same.
            val time = runCatching {
                java.time.Instant.parse(v.time).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }.getOrDefault(v.time)
            Text(time + "  ·  " + v.author + (v.target?.let { "  ·  " + it.digest.short.take(7) } ?: ""),
                color = DimColor, fontSize = 12.sp)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

private fun exportTo(context: Context, store: Store, ids: List<Digest>, uri: Uri) {
    context.contentResolver.openOutputStream(uri)!!.use { Exchange.export(store, ids, it) }
}

/** The file's own name as the picker shows it. */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

/**
 * Imports one file, whatever it is (I8.8): told apart by its first bytes.
 * A library archive (.tar), an SFZ instrument pack (.zip), or a device's own
 * file of sounds, recognised by its driver (model/devices: a CP-88's .X9* or
 * cp-liveset YAML). Returns what it did, for the screen. Blocking.
 */
private fun importFile(context: android.content.Context, lib: Library, host: EngineHost, name: String, bytes: ByteArray): String {
    fun starts(s: String, at: Int = 0) = bytes.size >= at + s.length &&
        String(bytes, at, s.length, Charsets.ISO_8859_1) == s
    return when {
        starts("ustar", 257) -> {
            val r = java.io.ByteArrayInputStream(bytes).use { Exchange.import(lib.store, it) }
            val counts = r.results.values.groupingBy { it::class.simpleName }.eachCount()
            context.getString(R.string.imported, r.objects, counts.toString())
        }
        starts("PK\u0003\u0004") ->
            context.getString(R.string.installed_instrument, java.io.ByteArrayInputStream(bytes).use { host.importInstrumentZip(it, name) })
        // An engine's own file: a SoundFont, a DX7 bank, a Surge XT patch.
        be.mdw.rigalong.model.EngineFiles.kind(bytes) != null -> {
            val kind = be.mdw.rigalong.model.EngineFiles.kind(bytes)!!
            if (kind == be.mdw.rigalong.model.EngineFiles.Kind.DX7_VOICE) error(context.getString(R.string.dx7_single_voice))
            host.installEngineFile(kind, name, bytes)?.let { context.getString(R.string.engine_file_installed, it, kind.engine) }
                ?: context.getString(R.string.engine_file_present, kind.engine)
        }
        // A device's own file, as its driver recognises it.
        else -> {
            val (drv, sounds) = be.mdw.rigalong.model.devices.Devices.readFile(name, bytes)
                ?: error(context.getString(R.string.unknown_file, name))
            val added = lib.importDeviceSounds(name, drv.sounds!!.mediaType, sounds)
            context.getString(R.string.imported_device_sounds, sounds.size, drv.label, added)
        }
    }
}
