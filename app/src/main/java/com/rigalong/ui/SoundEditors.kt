package com.rigalong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rigalong.NativeBridge
import com.rigalong.R
import com.rigalong.model.Digest
import com.rigalong.model.Entry
import com.rigalong.model.Library
import com.rigalong.model.RackEngine
import com.rigalong.model.RackPart
import com.rigalong.model.RackSlot
import com.rigalong.model.RackSong
import com.rigalong.model.getTyped
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rigalong.play.SongPlayer
import com.rigalong.play.EngineHost

/* The dialogs a sound is edited in: its preset, its parameters, its effect, the reverb, and its library patch. */

/**
 * A sound's parameters (M8): every one but the level, with a slider over its
 * real range, heard as it moves -- for the whole song (its patch, over the
 * preset) or for one live set (values of its own, over the song's: I6.3).
 * Where the change goes is chosen at the top, never asked afterwards; opened
 * from the stage or a live set it starts on that live set. A live set's own
 * value can go back to the song's, or become the song's; a different preset
 * for one live set is a sound of its own ("New sound for this live set").
 */
@Composable
internal fun SoundEditor(song: RackSong, inst: com.rigalong.model.RackInstance, liveSet: Int?, player: SongPlayer,
                        lib: Library, onChange: (RackSong) -> Unit, onLayer: (Int) -> Unit, onDismiss: () -> Unit) {
    val id = inst.id
    val slot = inst.slot
    var scope by remember(id) { mutableStateOf(liveSet?.takeIf { it in song.liveSets.indices }) }
    val part = scope?.let { song.liveSets.getOrNull(it) }?.parts?.get(id) ?: RackPart(plays = false)
    // Read with the song's patch on, so each value shown under a live set's own is the song's --
    // once a preset loading in the background has landed (it resets the engine), and the patch is on again.
    var params by remember(id) { mutableStateOf<List<SongPlayer.ParamDesc>>(emptyList()) }
    var loaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        player.previewSlot(song, id, slot)
        for (k in 0 until 200) { if (!player.loading(song, id)) break; delay(50) }
        player.previewSlot(song, id, slot)
        params = player.engineParams(song, id)
        loaded = true
    }
    // The song's value: its patch's, one it keeps for its live sets, or as heard from the preset.
    fun songValue(p: SongPlayer.ParamDesc) = song.songValue(id, p.name)?.toFloat() ?: p.value
    // Every parameter some live set has a value of its own for.
    val ownSomewhere = song.liveSets.flatMap { l -> l.parts[id]?.params?.keys.orEmpty() }.toSet()
    // Heard as edited: this live set's own values on, or off again for the whole song.
    LaunchedEffect(scope, loaded) {
        for (p in params) if (p.name in ownSomewhere) {
            player.auditionParam(song, id, p.name, part.params[p.name]?.toFloat() ?: songValue(p))
        }
    }
    // A control still waiting to be learned is not learned after the dialog is gone.
    androidx.compose.runtime.DisposableEffect(id) { onDispose { player.cancelLearn() } }
    AlertDialog(onDismissRequest = onDismiss, modifier = WideDialog, properties = WideDialogProperties,
        confirmButton = { TextButton(onClick = { player.reload(); onDismiss() }) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            Row {
                val sc = scope
                if (sc == null) {
                    TextButton(onClick = {
                        val now = player.engineParams(song, id).associate { it.name to it.value.toDouble() }
                        onChange(song.withSlot(id, slot.copy(params = now)))
                    }) { Text(stringResource(R.string.take_current)) }
                    TextButton(onClick = { onChange(song.withSlot(id, slot.copy(params = emptyMap()))) }) {
                        Text(stringResource(R.string.clear))
                    }
                } else {
                    // Every value of this live set's own back to the song's.
                    TextButton(enabled = part.params.isNotEmpty(), onClick = {
                        for (p in params) if (p.name in part.params) player.auditionParam(song, id, p.name, songValue(p))
                        onChange(song.withPart(sc, id, part.copy(params = emptyMap())))
                    }) { Text(stringResource(R.string.clear)) }
                }
            }
        },
        title = { Text(stringResource(R.string.patch_of, song.displayName(inst))) },
        text = {
            Column {
                liveSet?.takeIf { it in song.liveSets.indices }?.let { here ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        for ((target, label) in listOf(null to stringResource(R.string.scope_song),
                                here to stringResource(R.string.scope_live_set, song.liveSets[here].name))) {
                            if (scope == target) Button(onClick = {}) { Text(label) }
                            else OutlinedButton(onClick = { scope = target }) { Text(label) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val sc = scope
                if (sc == null) {
                    LibraryPatchLine(inst.engine, slot, slot.params, lib) { p, link -> onChange(song.withSlot(id, slot.withPatch(p, link))) }
                    if (ownSomewhere.isNotEmpty()) Text(stringResource(R.string.own_in_a_live_set), color = Dim, fontSize = 13.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.live_set_scope_hint), color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onLayer(sc) }) { Text(stringResource(R.string.new_sound_for_live_set)) }
                    }
                }
                if (!loaded) Text(stringResource(R.string.loading), color = Dim, modifier = Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.height(420.dp)) {
                    itemsIndexed(params) { _, p ->
                        val own = sc != null && p.name in part.params
                        val v = (if (sc != null) part.params[p.name]?.toFloat() else null) ?: songValue(p)
                        ParamRow(p, v, set = slot.params.containsKey(p.name), display = { null },
                            mark = when {
                                own -> Warn
                                sc == null && p.name in ownSomewhere -> Warn.copy(alpha = 0.6f)
                                else -> null
                            },
                            trailing = {
                                // A knob, wheel or pedal for it (MIDI learn): tap, then move the control.
                                val cc = inst.controls[p.name]
                                val learning = player.learningControl == "$id.${p.name}"
                                TextButton(onClick = {
                                    when {
                                        learning -> player.cancelLearn()
                                        cc != null -> onChange(song.withControl(id, p.name, null))
                                        else -> player.learnControl("$id.${p.name}") { got ->
                                            onChange(song.withControl(id, p.name, got))
                                        }
                                    }
                                }) {
                                    Text(when {
                                        learning -> stringResource(R.string.move_a_control)
                                        cc != null -> stringResource(R.string.control_cc, cc)
                                        else -> stringResource(R.string.learn_control)
                                    }, color = if (cc != null || learning) Accent else Dim, fontSize = 13.sp)
                                }
                                if (own && sc != null) {
                                    // Back to the song's value, or make this the song's.
                                    TextButton(onClick = {
                                        player.auditionParam(song, id, p.name, songValue(p))
                                        onChange(song.withPart(sc, id, part.copy(params = part.params - p.name)))
                                    }) { Text("↺") }
                                    TextButton(onClick = { onChange(song.useInSong(sc, id, p.name)) }) {
                                        Text(stringResource(R.string.use_in_song))
                                    }
                                }
                            }) { x ->
                            player.auditionParam(song, id, p.name, x)
                            if (sc == null) onChange(song.withSlot(id, slot.copy(params = slot.params + (p.name to x.toDouble()))))
                            else onChange(song.withLiveSetValue(sc, id, p.name, x.toDouble(), songValue(p).toDouble()))
                        }
                    }
                }
            }
        })
}

/**
 * Where the patch stands in the library (M9), by the slot's link: a library
 * patch and how many songs use it; an older version of one, the latest a tap
 * away; changed from one (unsaved edits, decided at Save); or not in the
 * library, with "Save as library patch" -- which links the slot to it.
 */
@Composable
private fun LibraryPatchLine(e: RackEngine, slot: RackSlot, values: Map<String, Double>, lib: Library,
                             onReplace: (com.rigalong.model.Patch, Digest?) -> Unit) {
    val now = slot.copy(params = values).patch(e)
    val link = remember(now, slot.libraryPatch) { lib.patchLink(slot.libraryPatch, now) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.padding(bottom = 8.dp)) {
        when {
            link != null && link.latest -> {
                val n = remember(link) { lib.songsUsingPatch(link.entry.id).size }
                Text(stringResource(R.string.patch_is_library, link.entry.version.name) + "  ·  " +
                    pluralStringResource(R.plurals.used_by_songs, n, n), color = Accent, fontSize = 14.sp)
            }
            link != null && link.known -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.patch_older_version, link.entry.version.name), color = Warn, fontSize = 14.sp)
                TextButton(onClick = {
                    onReplace(lib.store.getTyped(link.entry.version.target!!.digest), link.entry.id)
                }) { Text(stringResource(R.string.use_latest)) }
            }
            else -> {
                if (link != null) {
                    Text(stringResource(R.string.patch_changed_from, link.entry.version.name), color = Dim, fontSize = 13.sp)
                }
                if (!naming) {
                    TextButton(onClick = { naming = true; name = link?.entry?.version?.name?.let { "$it 2" } ?: "" }) {
                        Text(stringResource(if (link != null) R.string.save_as_new_library_patch else R.string.save_as_library_patch))
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text(stringResource(R.string.patch_name)) }, modifier = Modifier.weight(1f))
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        val id = lib.savePatch(null, name.trim(), now, derivedFrom = link?.entry?.latest)
                        naming = false
                        onReplace(now, id)   // the slot now uses it
                    }) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

/** A library patch the song being saved changed, and what to do about the other songs using it. */
internal data class PatchDecision(val sound: String, val patch: Entry, val newContent: com.rigalong.model.Patch,
                                 val others: List<Entry>)

/** The library patches [now] changed (still linked, content edited), each with the other songs on its latest version. */
internal fun patchDecisions(lib: Library, before: RackSong?, now: RackSong, songId: Digest?): List<PatchDecision> {
    if (before == null) return emptyList()
    return now.instances.mapNotNull { inst ->
        val e = inst.engine
        val slot = inst.slot
        val was = before.instance(inst.id)?.slot ?: return@mapNotNull null
        val id = slot.libraryPatch ?: return@mapNotNull null
        if (was.libraryPatch != id || was.patch(e) == slot.patch(e)) return@mapNotNull null
        val link = lib.patchLink(id, was.patch(e)) ?: return@mapNotNull null
        if (!link.latest) return@mapNotNull null
        val others = lib.songsUsingPatch(id).filter { it.latest && it.song.id != songId }.map { it.song }
        PatchDecision(now.displayName(inst), link.entry, slot.patch(e), others)
    }.distinctBy { it.patch.id }
}

/**
 * Saving a song whose library patch changed: update it everywhere (the
 * default), only in this song, or in the songs chosen -- the change rippling
 * on to their setlists. One save, one Undo.
 */
@Composable
internal fun PatchSaveDialog(decisions: List<PatchDecision>,
                            onSave: (Map<Digest, Pair<com.rigalong.model.Patch, Collection<Digest>>>, Set<Digest>) -> Unit,
                            onDismiss: () -> Unit) {
    // Per patch: the songs that will move with it; all of them to begin with.
    var chosen by remember { mutableStateOf(decisions.associate { d -> d.patch.id to d.others.map { it.id }.toSet() }) }
    var only by remember { mutableStateOf(emptySet<Digest>()) }
    var picking by remember { mutableStateOf(emptySet<Digest>()) }
    AlertDialog(onDismissRequest = onDismiss, modifier = WideDialog, properties = WideDialogProperties,
        confirmButton = {
            Button(onClick = {
                onSave(decisions.filter { it.patch.id !in only }.associate { d ->
                    d.patch.id to (d.newContent to (chosen[d.patch.id] ?: emptySet()))
                }, only)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.patch_save_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(decisions.size) { k ->
                    val d = decisions[k]
                    val id = d.patch.id
                    Text(stringResource(R.string.patch_save_question, d.patch.version.name, d.sound) + " " +
                        pluralStringResource(R.plurals.other_songs_use, d.others.size, d.others.size),
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    val mode = when {
                        id in only -> 1
                        id in picking -> 2
                        else -> 0
                    }
                    for ((m, label) in listOf(0 to stringResource(R.string.update_everywhere),
                            1 to stringResource(R.string.only_this_song), 2 to stringResource(R.string.choose_songs))) {
                        if (m == 2 && d.others.isEmpty()) continue
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            only = if (m == 1) only + id else only - id
                            picking = if (m == 2) picking + id else picking - id
                            if (m == 0) chosen = chosen + (id to d.others.map { it.id }.toSet())
                        }) {
                            androidx.compose.material3.RadioButton(selected = mode == m, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (mode == 2) {
                        for (s in d.others) {
                            val on = s.id in (chosen[id] ?: emptySet())
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 32.dp)
                                .clickable {
                                    val cur = chosen[id] ?: emptySet()
                                    chosen = chosen + (id to (if (on) cur - s.id else cur + s.id))
                                }) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Text(s.version.name, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        })
}

/** The song's reverb, where its engines meet, edited like a patch. */
@Composable
internal fun ReverbEditor(song: RackSong, player: SongPlayer, onChange: (Map<String, Double>) -> Unit,
                         onDismiss: () -> Unit) =
    ParamEditor(stringResource(R.string.reverb), "reverb", song.reverb, player,
        load = { player.previewReverb(song.reverb); player.reverbParams() },
        audition = player::auditionReverb, onChange = onChange, onDismiss = onDismiss)

@Composable
private fun ParamEditor(title: String, key: Any, current: Map<String, Double>, player: SongPlayer,
                        load: () -> List<SongPlayer.ParamDesc>, audition: (String, Float) -> Unit,
                        onChange: (Map<String, Double>) -> Unit, onDismiss: () -> Unit,
                        header: (@Composable (Map<String, Double>) -> Unit)? = null) {
    val params = remember(key) { load() }
    var values by remember(key) { mutableStateOf(current) }
    fun put(v: Map<String, Double>) { values = v; onChange(v) }
    AlertDialog(onDismissRequest = onDismiss, modifier = WideDialog, properties = WideDialogProperties,
        confirmButton = { TextButton(onClick = { player.reload(); onDismiss() }) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { put(params.associate { it.name to it.value.toDouble() }) }) {
                    Text(stringResource(R.string.take_current))
                }
                TextButton(onClick = { put(emptyMap()) }) { Text(stringResource(R.string.clear)) }
            }
        },
        title = { Text(title) },
        text = {
            Column {
            header?.invoke(values)
            LazyColumn(Modifier.height(440.dp)) {
                itemsIndexed(params) { _, p ->
                    ParamRow(p, values[p.name]?.toFloat() ?: p.value, values.containsKey(p.name), display = { null }) { x ->
                        audition(p.name, x)
                        put(values + (p.name to x.toDouble()))
                    }
                }
            }
            }
        })
}

// Parameter dialogs are wider than Android's default, which clipped values
// such as "25087.71 Hz" and "1/4 note".
private val WideDialog = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.9f)
private val WideDialogProperties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)

/**
 * One parameter: a switch where it is on or off, otherwise a slider over its
 * real range, with its value as the player reads it where the node can say
 * ("250 ms", "1/8") and the number where it cannot.
 */
@Composable
private fun ParamRow(p: SongPlayer.ParamDesc, v: Float, set: Boolean, display: (Float) -> String?,
                     mark: Color? = null, trailing: (@Composable () -> Unit)? = null, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(p.name + if (mark != null) " ●" else "", modifier = Modifier.width(150.dp), fontSize = 14.sp,
            color = mark ?: if (set) Accent else Color.White)
        if (p.integer && p.min == 0f && p.max == 1f) {
            androidx.compose.material3.Switch(checked = v > 0.5f, onCheckedChange = { onValue(if (it) 1f else 0f) })
            Spacer(Modifier.weight(1f))
        } else {
            Slider(value = v.coerceIn(p.min, p.max), valueRange = p.min..p.max,
                steps = if (p.integer) ((p.max - p.min).toInt() - 1).coerceAtLeast(0) else 0,
                onValueChange = { nv -> onValue(if (p.integer) Math.round(nv).toFloat() else nv) },
                modifier = Modifier.weight(1f))
        }
        Text(display(v) ?: if (p.integer) v.toInt().toString() else "%.2f".format(v), modifier = Modifier.width(96.dp),
            textAlign = TextAlign.End, fontSize = 13.sp, maxLines = 1)
        trailing?.invoke()
    }
}

/** An engine's insert effect (M6): which one, and its parameters, heard as they move. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FxEditor(title: String, song: RackSong, target: String?, current: com.rigalong.model.RackFx?,
                     player: SongPlayer, onChange: (com.rigalong.model.RackFx?) -> Unit, onDismiss: () -> Unit) {
    val key = target ?: "mix"
    var fx by remember(key) { mutableStateOf(current ?: com.rigalong.model.RackFx("off")) }
    val params = remember(key, fx.type) { player.applyFx(song, target, fx); player.fxParams(song, target) }
    fun put(f: com.rigalong.model.RackFx) { fx = f; onChange(f.takeIf { it.type != "off" }) }
    AlertDialog(onDismissRequest = onDismiss, modifier = WideDialog, properties = WideDialogProperties,
        confirmButton = { TextButton(onClick = { player.reload(); onDismiss() }) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(onClick = { put(fx.copy(params = emptyMap())); player.applyFx(song, target, fx) }) {
                Text(stringResource(R.string.clear))
            }
        },
        title = { Text(title) },
        text = {
            Column {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in com.rigalong.model.RackFx.TYPES) {
                        val label = stringResource(fxLabel(t))
                        if (t == fx.type) Button(onClick = {}) { Text(label) }
                        else OutlinedButton(onClick = { put(com.rigalong.model.RackFx(t)) }) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(420.dp)) {
                    itemsIndexed(params, key = { _, p -> fx.type + p.name }) { _, p ->
                        ParamRow(p, fx.params[p.name]?.toFloat() ?: p.value, fx.params.containsKey(p.name),
                            display = { player.fxDisplay(song, target, p.name, it) }) { x ->
                            player.auditionFx(song, target, p.name, x)
                            put(fx.copy(params = fx.params + (p.name to x.toDouble())))
                        }
                    }
                }
            }
        })
}

internal fun fxLabel(type: String) = when (type) {
    "delay" -> R.string.fx_delay
    "phaser" -> R.string.fx_phaser
    "flanger" -> R.string.fx_flanger
    "rotary" -> R.string.fx_rotary
    "reverb" -> R.string.fx_reverb
    else -> R.string.fx_off
}

/** One thing a sound can play: what goes into its slot, and how it reads in the list. */
internal class PresetChoice(val preset: String, val program: Int?, val label: String, val from: String = "")

/** One row of the picker: how it reads, and what the slot becomes if it is kept. */
private class PickRow(val label: String, val tag: String, val library: Boolean, val slot: (RackSlot) -> RackSlot)

/**
 * What a sound plays, chosen BY EAR: a tap puts a preset on the engine at
 * once ([onAudition]) and the picker stays open; ◀ ▶ step through the list as
 * it is filtered; Keep (or a second tap on the row) takes it into the song,
 * Cancel puts back what the sound had. The library's own patches come first:
 * keeping one links the slot to it. For FluidSynth every preset of every
 * SoundFont is a row.
 */
@Composable
internal fun PresetPicker(e: RackEngine, slot: RackSlot, host: EngineHost, lib: Library,
                         onAudition: (RackSlot) -> Unit, onKeep: (RackSlot) -> Unit, onCancel: () -> Unit) {
    val libraryTag = stringResource(R.string.library_tag)
    val shared = remember(e) { lib.patches(e.key) }
    // Off the main thread: a SoundFont's presets are read from its file.
    val choices by androidx.compose.runtime.produceState<List<PresetChoice>?>(null, e) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { presetChoices(e, host) }
    }
    val rows = remember(shared, choices) {
        shared.map { (entry, patch) -> PickRow(entry.version.name, libraryTag, true) { it.withPatch(patch, entry.id) } } +
            choices.orEmpty().map { c -> PickRow(c.label, c.from, false) { it.copy(preset = c.preset, program = c.program) } }
    }
    var filter by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<PickRow?>(null) }
    val shown = remember(rows, filter) {
        rows.filter { filter.isEmpty() || it.label.contains(filter, true) || it.tag.contains(filter, true) }
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun audition(r: PickRow) { picked = r; onAudition(r.slot(slot)) }
    fun step(by: Int) {
        if (shown.isEmpty()) return
        val at = shown.indexOf(picked)
        val next = (if (at < 0) (if (by > 0) 0 else shown.size - 1) else at + by).coerceIn(0, shown.size - 1)
        audition(shown[next])
        scope.launch { list.animateScrollToItem((next - 2).coerceAtLeast(0)) }
    }
    AlertDialog(onDismissRequest = onCancel,
        confirmButton = {
            Button(enabled = picked != null, onClick = { picked?.let { onKeep(it.slot(slot)) } }) { Text(stringResource(R.string.keep)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onKeep(slot.copy(preset = null, program = null)) }) { Text(stringResource(R.string.as_loaded)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        },
        title = { Text(stringResource(R.string.patch_of, engineLabel(e))) },
        text = {
            Column(Modifier.height(460.dp)) {
                OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { step(-1) }) { Text("◀") }
                    TextButton(onClick = { step(1) }) { Text("▶") }
                    // A note for when no hand is on the keys.
                    TextButton(onClick = {
                        scope.launch {
                            NativeBridge.nativeTestNote(60, 100)
                            delay(500)
                            NativeBridge.nativeTestNote(60, 0)
                        }
                    }) { Text(stringResource(R.string.play_note)) }
                    Text(picked?.label ?: stringResource(if (choices == null) R.string.loading else R.string.tap_to_hear),
                        color = if (picked != null) Accent else Dim, fontSize = 13.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                LazyColumn(state = list) {
                    itemsIndexed(shown) { _, r ->
                        val on = picked === r
                        Row(Modifier.fillMaxWidth().background(if (on) Accent.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { if (on) onKeep(r.slot(slot)) else audition(r) }.padding(vertical = 10.dp, horizontal = 4.dp)) {
                            Text(r.label, color = if (r.library) Accent else Color.Unspecified, modifier = Modifier.weight(1f))
                            if (r.tag.isNotEmpty()) Text(r.tag, color = Dim, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        })
}

/** Installed content an engine can play: what goes into the slot, and how it reads. */
private fun presetChoices(e: RackEngine, host: EngineHost): List<PresetChoice> = runCatching {
    fun plain(names: List<String>) = names.map { PresetChoice(it, null, it) }
    when (e) {
        RackEngine.SFIZZ -> host.instruments().map { (id, label) -> PresetChoice(id, null, label) }
        RackEngine.SETBFREE -> plain(NativeBridge.nativeSetBfreePresetNames().toList())
        RackEngine.SURGE -> plain((0 until NativeBridge.nativeSurgePatchCount()).map { NativeBridge.nativeSurgePatchName(it) })
        RackEngine.DEXED -> plain((0 until NativeBridge.nativeDexedVoiceCount()).map { NativeBridge.nativeDexedVoiceName(it) })
        RackEngine.FLUID -> (0 until NativeBridge.nativeFluidFontCount()).flatMap { k ->
            val font = NativeBridge.nativeFluidFontName(k)
            fluidPresets(font).let { presets ->
                // A font of one preset is that preset; otherwise each, in the order FluidSynth has them.
                if (presets.size <= 1) listOf(PresetChoice(font, null, font))
                else presets.mapIndexed { i, label -> PresetChoice(font, i, label, font) }
            }
        }
        RackEngine.PLAITS -> plain(NativeBridge.nativePlaitsEngineNames().toList())
    }
}.getOrDefault(emptyList())

/** The presets of an installed SoundFont, by bank and program as FluidSynth indexes them: "0:012 Marimba". */
internal fun fluidPresets(font: String): List<String> = runCatching {
    val path = NativeBridge.nativeFluidFontPath(font)
    if (path.isEmpty()) emptyList()
    else com.rigalong.model.SoundFontFile.presets(java.io.File(path)).map { "%d:%03d %s".format(it.bank, it.program, it.name) }
}.getOrDefault(emptyList())

/** How a slot's preset reads on its button: a SoundFont's preset by name, behind its font. */
@Composable
internal fun presetLabel(e: RackEngine, slot: RackSlot): String? {
    val preset = slot.preset ?: return null
    val program = slot.program
    if (e != RackEngine.FLUID || program == null) return preset
    val name by androidx.compose.runtime.produceState<String?>(null, preset, program) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            fluidPresets(preset).getOrNull(program)?.substringAfter(' ')
        }
    }
    return name?.let { "$it  ·  $preset" } ?: preset
}
