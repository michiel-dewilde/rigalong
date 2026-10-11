package be.mdw.bagosynths.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.mdw.bagosynths.R
import be.mdw.bagosynths.model.ExternalSynth
import be.mdw.bagosynths.model.Library
import be.mdw.bagosynths.model.RackDevicePart
import be.mdw.bagosynths.model.RackSong

/*
 * External devices in the song editor, drawn from what each device's driver
 * can do (model/devices): no device is special here. A plain MIDI module gets
 * leave / select with its program and bank; a CP-88 also gets its own sounds,
 * read from it or picked from the library, edited as text, and its page as a
 * file.
 */

/**
 * What a live set does to device [d] (I4.7, I6.7): leave it, select a stored
 * sound at the driver's address, or give it a sound of its own.
 */
@Composable
fun DevicePartRow(d: ExternalSynth, part: RackDevicePart?, player: SongPlayer, lib: Library, onPart: (RackDevicePart) -> Unit) {
    val drv = player.devices.driver(d)
    val p = part?.takeIf { it.kind == d.kind } ?: RackDevicePart(d.kind)
    var importing by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val reading = stringResource(R.string.reading_device, d.name)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text(d.name, modifier = Modifier.width(100.dp).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val modes = listOf("leave" to stringResource(R.string.device_leave), "select" to stringResource(R.string.device_select)) +
            if (drv.sounds != null) listOf("own" to stringResource(R.string.device_own)) else emptyList()
        for ((mode, label) in modes) {
            TextButton(onClick = { onPart(p.copy(mode = mode)) }) { Text(label, color = if (p.mode == mode) Accent else Dim) }
        }
        when (p.mode) {
            "select" -> for (f in drv.address) {
                val v = p.address[f.key]
                if (f.optional) {
                    Checkbox(checked = v != null, onCheckedChange = { on ->
                        onPart(p.copy(address = if (on) p.address + (f.key to f.range.first) else p.address - f.key))
                    })
                    if (v == null) Text(f.label, color = Dim, fontSize = 12.sp)
                }
                if (!f.optional || v != null) {
                    NumberField(f.label, v ?: f.range.first, f.range) { onPart(p.copy(address = p.address + (f.key to it))) }
                }
            }
            "own" -> {
                val s = drv.sounds!!
                Text(p.soundName?.ifEmpty { stringResource(R.string.unnamed) } ?: stringResource(R.string.no_sound_yet),
                    modifier = Modifier.padding(horizontal = 8.dp))
                OutlinedButton(onClick = {
                    importing = reading
                    Thread {
                        val r = runCatching { player.devices.importCurrent(d) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            r.onSuccess { (digest, n) ->
                                // Kept in the library too, to use again in another song.
                                lib.store.get(digest)?.let { lib.saveDeviceSound(null, n.ifBlank { d.name }, s.mediaType, it) }
                                onPart(p.copy(mode = "own", sound = digest, soundName = n)); importing = ""
                            }.onFailure { importing = it.message ?: "failed" }
                        }
                    }.start()
                }) { Text(stringResource(R.string.import_current_sound)) }
                // One the library has: from the device's files, or used in another song.
                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.from_library)) }
                // Its settings as text; the edit is this song's, the library keeps its own.
                val text = s.formats.firstOrNull { it.editable }
                if (text != null && p.sound != null) TextButton(onClick = { editing = true }) {
                    Text(stringResource(R.string.edit_as, text.label))
                }
                Text(importing, color = Warn, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    val s = drv.sounds
    if (picking && s != null) {
        val sounds = remember { lib.deviceSounds(s.mediaType) }
        AlertDialog(onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.device_sounds_of, drv.label)) },
            text = {
                if (sounds.isEmpty()) Text(stringResource(R.string.no_device_sounds), color = Dim)
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(sounds) { e ->
                        Text(e.version.name, modifier = Modifier.fillMaxWidth().clickable {
                            onPart(p.copy(mode = "own", sound = e.version.target!!.digest, soundName = e.version.name))
                            picking = false
                        }.padding(vertical = 10.dp))
                    }
                }
            })
    }
    val format = s?.formats?.firstOrNull { it.editable }
    if (editing && s != null && format != null) {
        val sound = remember(p.sound) { p.sound?.let { lib.store.get(it) } }
        if (sound == null) editing = false
        else DeviceSoundEditor(d.name, p.soundName ?: "", sound, drv, format,
            play = { player.devices.audition(d, it) }, onDismiss = { editing = false }, onSave = { edited ->
                onPart(p.copy(mode = "own", sound = lib.store.put(edited), soundName = s.name(edited).trim()))
            })
    }
}

/** Saves the song's area on device [d] as the device's own file (a CP-88's page as .X9P), for its USB stick. */
@Composable
fun DeviceAreaFileButton(song: RackSong, name: String, d: ExternalSynth, player: SongPlayer) {
    val context = LocalContext.current
    var note by remember { mutableStateOf("") }
    val file = remember(song, d) { player.devices.areaFile(d, song) } ?: return
    val area = player.devices.driver(d).sounds?.area?.label ?: ""
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) note = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(player.devices.areaFile(d, song)!!.second) }
        }.fold({ "" }, { it.message ?: "failed" })
    }
    OutlinedButton(onClick = { saver.launch("${name.ifBlank { "song" }}.${file.first}") }) {
        Text(stringResource(R.string.export_area, d.name, area, file.first) + if (note.isEmpty()) "" else " ($note)")
    }
}

private val Dim = Color(0xFF8A919C)
