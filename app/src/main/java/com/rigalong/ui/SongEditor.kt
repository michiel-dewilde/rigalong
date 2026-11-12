package com.rigalong.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rigalong.R
import com.rigalong.model.Library
import com.rigalong.model.RackEngine
import com.rigalong.model.RackLiveSet
import com.rigalong.model.RackPart
import com.rigalong.model.RackSong
import com.rigalong.play.SongPlayer
import com.rigalong.play.EngineHost

/* The song editor: its sounds, its live sets and what each sound does in them. */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SongEditor(song: RackSong, name: String, host: EngineHost, player: SongPlayer, lib: Library,
                       focus: SongPlayer.EditRequest?, unsaved: Boolean, hearing: Int?, onHear: (Int) -> Unit,
                       onName: (String) -> Unit,
                       onChange: (RackSong) -> Unit, onSave: () -> Unit, onSaveCopy: () -> Unit) {
    // Instance ids: which sound's preset list, patch or effect is open.
    var picker by remember { mutableStateOf<String?>(null) }
    // The sound being edited, and the live set it is edited for (null: the whole song).
    var soundEditor by remember { mutableStateOf<Pair<String, Int?>?>(null) }
    LaunchedEffect(focus) { focus?.instance?.let { if (song.instance(it) != null) soundEditor = it to focus.liveSet } }
    var reverbEditor by remember { mutableStateOf(false) }
    var fxEditor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var masterFxEditor by remember { mutableStateOf(false) }
    // A phone: the song's buttons wrap under its name, and each sound takes two lines.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val narrow = maxWidth < 700.dp
    Column(Modifier.fillMaxSize().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp)
        .verticalScroll(rememberScrollState())) {
        val nameField = @Composable { m: Modifier ->
            OutlinedTextField(value = name, onValueChange = onName, label = { Text(stringResource(R.string.song)) },
                singleLine = true, modifier = m)
        }
        val songButtons = @Composable {
            LeadSheetButton(song, player, onChange)
            OutlinedButton(onClick = { reverbEditor = true }) {
                Text(stringResource(R.string.reverb_button), color = if (song.reverb.isEmpty()) Color.White else Accent)
            }
            OutlinedButton(onClick = { masterFxEditor = true }) {
                Text(song.masterFx?.let { stringResource(R.string.master_fx_is, stringResource(fxLabel(it.type))) }
                    ?: stringResource(R.string.master_fx_button), color = if (song.masterFx == null) Color.White else Accent)
            }
            // The song as it is now, as a new song of its own (in no setlist), which is then open.
            OutlinedButton(onClick = onSaveCopy) { Text(stringResource(R.string.save_as_copy)) }
            // A device's song sounds as its own file (a CP-88 page as .X9P), for its USB stick.
            for (d in player.devices.withSounds()) if (song.liveSets.any { it.devices[d.name]?.mode == "own" }) {
                DeviceAreaFileButton(song, name, d, player)
            }
        }
        // "Save •" while there is something to save: a library patch picked or
        // saved in a dialog belongs to the song only once the song is saved.
        val saveButton = @Composable {
            Button(onClick = onSave, modifier = Modifier.height(56.dp)) {
                Text(stringResource(if (unsaved) R.string.save_unsaved else R.string.save))
            }
        }
        if (narrow) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                nameField(Modifier.weight(1f))
                saveButton()
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { songButtons() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                nameField(Modifier.weight(1f))
                songButtons()
                saveButton()
            }
        }
        // The song's tempo and metre (S12.1, S12.5); a live set may have its own.
        TempoFields(song.tempo ?: com.rigalong.model.Tempo(), countIn = true) { t ->
            onChange(song.copy(tempo = t.takeIf { it != com.rigalong.model.Tempo() }))
        }
        Spacer(Modifier.height(12.dp))
        // The song's sounds: instances, first class -- several of one engine if it wants (two sfizz instances).
        Text(stringResource(R.string.engines), fontWeight = FontWeight.SemiBold)
        for (inst in song.instances) {
            val slot = inst.slot
            // Its name: tap to rename ("Bright", "Pad").
            val label = @Composable { m: Modifier ->
                Text(song.displayName(inst), color = if (inst.label != null) Accent else Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = m.clickable { renaming = inst.id }.padding(vertical = 8.dp))
            }
            val preset = @Composable { m: Modifier ->
                OutlinedButton(onClick = { picker = inst.id }, modifier = m) {
                    Text(presetLabel(inst.engine, slot) ?: stringResource(R.string.as_loaded_paren), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val edits = @Composable {
                TextButton(onClick = { soundEditor = inst.id to null }) {
                    Text(stringResource(R.string.edit_patch) + if (slot.params.isNotEmpty()) " ●" else "")
                }
                TextButton(onClick = { fxEditor = inst.id }) {
                    Text(slot.fx?.let { stringResource(fxLabel(it.type)) } ?: stringResource(R.string.fx_button))
                }
                Text(stringResource(R.string.level), color = Dim)
            }
            val level = @Composable { m: Modifier ->
                Slider(value = slot.level.toFloat(), onValueChange = { v ->
                    onChange(song.withSlot(inst.id, slot.copy(level = v.toDouble())))
                }, modifier = m)
            }
            val remove = @Composable { TextButton(onClick = { onChange(song.minus(inst.id)) }) { Text("✕", color = Danger) } }
            if (narrow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    label(Modifier.weight(1f))
                    preset(Modifier.weight(1.5f))
                    remove()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    edits()
                    Spacer(Modifier.width(8.dp))
                    level(Modifier.weight(1f))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    label(Modifier.width(120.dp))
                    preset(Modifier.weight(1f))
                    edits()
                    level(Modifier.width(160.dp))
                    remove()
                }
            }
        }
        // Another sound, of any engine -- a second sfizz is a second instance.
        androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.add_sound), color = Dim, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
            for (e in RackEngine.entries) {
                TextButton(onClick = { onChange(song.plus(e)) }) { Text("+ " + engineLabel(e)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_sets), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val nextName = stringResource(R.string.live_set_n, song.liveSets.size + 1)
            OutlinedButton(onClick = {
                val parts = song.instances.associate { it.id to RackPart() }
                onChange(song.copy(liveSets = song.liveSets + RackLiveSet(nextName, parts)))
            }) { Text(stringResource(R.string.add_live_set)) }
        }
        song.liveSets.forEachIndexed { i, ls ->
            LiveSetCard(song, i, ls, player, lib, focused = focus?.liveSet == i, heard = hearing == i, onChange,
                onSound = { id -> soundEditor = id to i }, onHear = { onHear(i) })
        }
    }
    }
    fxEditor?.let { id ->
        song.instance(id)?.let { inst ->
            FxEditor(stringResource(R.string.fx_of, song.displayName(inst)), song, id, inst.slot.fx, player, onChange = { f ->
                onChange(song.withSlot(id, (song.instance(id)?.slot ?: inst.slot).copy(fx = f)))
            }, onDismiss = { fxEditor = null })
        }
    }
    if (masterFxEditor) {
        FxEditor(stringResource(R.string.master_fx_title), song, null, song.masterFx, player,
            onChange = { onChange(song.copy(masterFx = it)) }, onDismiss = { masterFxEditor = false })
    }
    renaming?.let { id ->
        song.instance(id)?.let { inst ->
            RenameDialog(song.displayName(inst), onRename = { name ->
                onChange(song.copy(instances = song.instances.map {
                    if (it.id == id) it.copy(label = name.trim().ifEmpty { null }) else it
                }))
                renaming = null
            }, onDismiss = { renaming = null })
        }
    }
    if (reverbEditor) {
        ReverbEditor(song, player, onChange = { onChange(song.copy(reverb = it)) },
            onDismiss = { reverbEditor = false })
    }
    soundEditor?.let { (id, ls) ->
        song.instance(id)?.let { inst ->
            SoundEditor(song, inst, ls, player, lib, onChange = onChange,
                onLayer = { scope ->
                    // A sound of its own for this live set: then straight to picking what it plays.
                    val (s, newId) = song.layerFor(scope, id)
                    onChange(s)
                    soundEditor = null
                    picker = newId
                }, onDismiss = { soundEditor = null })
        }
    }
    picker?.let { id ->
        song.instance(id)?.let { inst ->
            // Chosen by ear: the song is on the engine while the picker is open, at
            // a live set where this sound plays (the one being heard, if it does).
            LaunchedEffect(id) {
                fun plays(i: Int) = song.liveSets.getOrNull(i)?.parts?.get(id)?.plays == true
                val at = hearing?.takeIf { plays(it) } ?: song.liveSets.indices.firstOrNull { plays(it) } ?: hearing ?: 0
                if (at in song.liveSets.indices) onHear(at)
            }
            PresetPicker(inst.engine, inst.slot, host, lib,
                onAudition = { slot -> player.previewSlot(song, id, slot) },
                onKeep = { slot ->
                    // Still linked: a linked patch given another sound is a change to it, asked about at Save.
                    onChange(song.withSlot(id, slot))
                    picker = null
                },
                onCancel = {
                    // What it had, back on the engine.
                    player.previewSlot(song, id, inst.slot)
                    picker = null
                })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveSetCard(song: RackSong, i: Int, ls: RackLiveSet, player: SongPlayer, lib: Library, focused: Boolean,
                        heard: Boolean, onChange: (RackSong) -> Unit, onSound: (String) -> Unit, onHear: () -> Unit) {
    fun put(n: RackLiveSet) = onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it[i] = n }))
    val copyName = stringResource(R.string.copy_of, ls.name)
    // Opened from the stage on this live set: scrolled into view and outlined.
    val into = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) into.bringIntoView() }
    // A key still waited for is not waited for once the live set is off the screen.
    androidx.compose.runtime.DisposableEffect(i) { onDispose { player.cancelKeyLearn() } }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
        .bringIntoViewRequester(into)
        .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Line, RoundedCornerShape(8.dp))
        .padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = ls.name, onValueChange = { put(ls.copy(name = it)) }, singleLine = true,
                label = { Text(stringResource(R.string.live_set_n, i + 1)) }, modifier = Modifier.weight(1f))
            // The keys (and the on-screen ones) play this live set of this song, as edited.
            if (heard) Button(onClick = onHear) { Text(stringResource(R.string.hearing)) }
            else OutlinedButton(onClick = onHear) { Text(stringResource(R.string.hear)) }
            TextButton(onClick = {
                onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it.add(i + 1, ls.copy(name = copyName)) }))
            }) { Text(stringResource(R.string.copy)) }
            TextButton(enabled = i > 0, onClick = {
                onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it[i] = it[i - 1].also { _ -> it[i - 1] = ls } }))
            }) { Text("▲") }
            TextButton(enabled = i + 1 < song.liveSets.size, onClick = {
                onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it[i] = it[i + 1].also { _ -> it[i + 1] = ls } }))
            }) { Text("▼") }
            TextButton(enabled = song.liveSets.size > 1, onClick = {
                onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it.removeAt(i) }))
            }) { Text("✕", color = Danger) }
        }
        // Its own tempo and metre, in place of the song's while it is selected (S12.5).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ls.tempo != null, onCheckedChange = { on ->
                put(ls.copy(tempo = if (on) (song.tempo ?: com.rigalong.model.Tempo()) else null))
            })
            Text(stringResource(R.string.own_tempo), color = if (ls.tempo != null) Color.White else Dim)
        }
        ls.tempo?.let { t -> TempoFields(t, countIn = false) { put(ls.copy(tempo = it)) } }
        for (inst in song.instances) {
            val p = ls.parts[inst.id] ?: RackPart(plays = false)
            fun putPart(np: RackPart) = put(ls.copy(parts = ls.parts + (inst.id to np)))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Checkbox(checked = p.plays, onCheckedChange = { putPart(p.copy(plays = it)) })
                Text(song.displayName(inst), modifier = Modifier.width(100.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Its sound in this live set: values of its own over the song's patch.
                TextButton(onClick = { onSound(inst.id) }) {
                    Text(if (p.params.isEmpty()) stringResource(R.string.live_set_sound)
                        else stringResource(R.string.live_set_sound_n, p.params.size),
                        color = if (p.params.isEmpty()) Color.White else Warn)
                }
                // A split where the hands are: tap the note, play the key. Held, − and + move an octave.
                for ((which, label, now) in listOf(Triple("low", R.string.key_low, p.keyLow), Triple("high", R.string.key_high, p.keyHigh))) {
                    val field = "$i/${inst.id}/$which"
                    fun set(v: Int) = putPart(if (which == "low") p.copy(keyLow = v) else p.copy(keyHigh = v))
                    NumberField(stringResource(label), now, 0..127, noteName = true, bigStep = 12,
                        learning = player.learningKey == field,
                        onLearn = { if (player.learningKey == field) player.cancelKeyLearn() else player.learnKey(field, ::set) },
                        onValue = ::set)
                }
                NumberField(stringResource(R.string.transpose_short), p.transpose, -48..48, bigStep = 12) { putPart(p.copy(transpose = it)) }
                NumberField(stringResource(R.string.channel_in_short), p.channelIn, 0..16) { putPart(p.copy(channelIn = it)) }
                Checkbox(checked = p.level != null, onCheckedChange = { on ->
                    putPart(p.copy(level = if (on) inst.slot.level else null))
                })
                Text(stringResource(R.string.own_level), color = Dim, fontSize = 12.sp)
                p.level?.let { lv ->
                    Slider(value = lv.toFloat(), onValueChange = { putPart(p.copy(level = it.toDouble())) },
                        modifier = Modifier.width(140.dp))
                }
                // The engine's effect in this live set: on, or off with its tail ringing out.
                if (inst.slot.fx != null) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Switch(checked = p.fxAmount > 0.5,
                        onCheckedChange = { putPart(p.copy(fxAmount = if (it) 1.0 else 0.0)) })
                    Text(stringResource(R.string.fx_in_live_set), color = Dim, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        // The external devices (I4.7, I6.7, I10.1), each as its driver allows: model/devices.
        for (d in player.devices.devices) {
            DevicePartRow(d, ls.devices[d.name], player, lib) { part -> put(ls.copy(devices = ls.devices + (d.name to part))) }
        }
    }
}

/** A sound's name. */
@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.sound_name)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) })
}

/** Attach or remove the song's lead sheet: a PDF from the file picker, into the library (S9.3). */
@Composable
private fun LeadSheetButton(song: RackSong, player: SongPlayer, onChange: (RackSong) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            onChange(song.copy(leadSheet = player.storePdf(bytes)))
        }
    }
    if (song.leadSheet == null) {
        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text(stringResource(R.string.attach_lead_sheet)) }
    } else {
        OutlinedButton(onClick = { onChange(song.copy(leadSheet = null)) }) { Text(stringResource(R.string.remove_lead_sheet)) }
    }
}
