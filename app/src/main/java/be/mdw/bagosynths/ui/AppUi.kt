package be.mdw.bagosynths.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.mdw.bagosynths.NativeBridge
import be.mdw.bagosynths.model.Digest
import be.mdw.bagosynths.model.RackEngine
import be.mdw.bagosynths.model.RackLiveSet
import be.mdw.bagosynths.model.RackPart
import be.mdw.bagosynths.model.RackSlot
import be.mdw.bagosynths.model.RackSong
import kotlinx.coroutines.delay

/*
 * The new UI, first slice (PLAN.md M3): the stage view and a form-based song
 * editor over the rack. Dark, large targets (S17.1), stage faders that move
 * only by dragging (S17.4). Still to come in M3: strings in resources, the
 * edit lock, the environment view. This slice is about getting a setlist onto the
 * stage.
 */

private val Bg = Color(0xFF101216)
private val Panel = Color(0xFF1A1D23)
private val Accent = Color(0xFF3FB68B)
private val Warn = Color(0xFFE0A030)
private val Danger = Color(0xFFD9534F)
private val Dim = Color(0xFF8A919C)

@Composable
fun BagoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Panel, onSurface = Color(0xFFE6E9EF),
        onBackground = Color(0xFFE6E9EF), secondary = Warn, error = Danger,
    )) { Surface(color = Bg, modifier = Modifier.fillMaxSize()) { content() } }
}

@Composable
fun AppUi(player: SongPlayer, lib: AppLibrary, host: EngineHost) {
    var screen by remember { mutableIntStateOf(0) }
    var health by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            health = runCatching { host.healthLine() }.getOrDefault("")
            delay(500)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            player.pollSelections()
            delay(50)
        }
    }
    BagoTheme {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            TopBar(player, health, screen, onScreen = { screen = it }, onPrototype = host::showPrototype)
            Spacer(Modifier.height(8.dp))
            when (screen) {
                0 -> StageScreen(player)
                1 -> SongsScreen(player, lib, host)
                else -> LibraryScreen(player, lib, onOpened = { screen = 0 })
            }
        }
    }
}

@Composable
private fun TopBar(player: SongPlayer, health: String, screen: Int, onScreen: (Int) -> Unit, onPrototype: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()) {
        Tab("Stage", screen == 0) { onScreen(0) }
        Tab("Songs", screen == 1) { onScreen(1) }
        Tab("Library", screen == 2) { onScreen(2) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(player.setlistName.ifEmpty { "No setlist" }, color = Dim, fontSize = 14.sp, maxLines = 1)
            Text(health, color = if (health.startsWith("!")) Warn else Dim, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onPrototype) { Text("Prototype", color = Dim) }
        Button(onClick = player::panic, colors = ButtonDefaults.buttonColors(containerColor = Danger),
            modifier = Modifier.height(48.dp)) { Text("PANIC", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.background(if (selected) Accent.copy(alpha = 0.25f) else Panel, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(label, color = if (selected) Accent else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------ stage

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageScreen(player: SongPlayer) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BigButton("◀", enabled = player.songIndex > 0, width = 88) { player.previousSong() }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(player.songNames.getOrNull(player.songIndex) ?: "—", fontSize = 30.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val next = player.songNames.getOrNull(player.songIndex + 1)
                val sel = player.lastSelection.let { if (it.isEmpty()) "" else "   ·   last from the keys: $it" }
                Text((if (next != null) "next: $next" else "last song") + sel, color = Dim, fontSize = 15.sp, maxLines = 1)
            }
            BigButton("▶", enabled = player.songIndex + 1 < player.songNames.size, width = 88) { player.nextSong() }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()) {
            player.liveSetNames.forEachIndexed { i, name ->
                val sel = i == player.liveSetIndex
                Box(Modifier.size(width = 190.dp, height = 84.dp)
                    .background(if (sel) Accent else Panel, RoundedCornerShape(12.dp))
                    .border(2.dp, if (sel) Accent else Color(0xFF2A2E36), RoundedCornerShape(12.dp))
                    .clickable { player.selectLiveSet(i) }.padding(10.dp),
                    contentAlignment = Alignment.Center) {
                    Text(name, color = if (sel) Color.Black else Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Levels", color = Dim, fontSize = 14.sp)
        val engines = player.rack?.slots?.keys?.sorted().orEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            for (e in engines) {
                val plays = player.playing[e] ?: false
                Column(Modifier.width(140.dp).fillMaxHeight().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(e.label, fontWeight = FontWeight.SemiBold, color = if (plays) Accent else Dim)
                    Text(if (plays) "playing" else "silent", fontSize = 12.sp, color = Dim)
                    Spacer(Modifier.height(8.dp))
                    val level = player.levels[e] ?: 0f
                    Text("%.0f %%".format(level * 100), fontSize = 18.sp)
                    // Moves only by dragging, never by a touch (S17.4).
                    RelativeFader(level, { player.setLevel(e, it) }, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, width: Int, onClick: () -> Unit) {
    Box(Modifier.size(width = width.dp, height = 72.dp)
        .background(if (enabled) Panel else Bg, RoundedCornerShape(12.dp))
        .border(2.dp, Color(0xFF2A2E36), RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 30.sp, color = if (enabled) Color.White else Color(0xFF3A3F48))
    }
}

// ------------------------------------------------------------------ songs

@Composable
private fun SongsScreen(player: SongPlayer, lib: AppLibrary, host: EngineHost) {
    var revision by remember { mutableIntStateOf(0) }
    val setlistId = player.setlistId
    var selected by remember { mutableStateOf<Digest?>(null) }
    var editing by remember { mutableStateOf<Pair<Digest?, RackSong>?>(null) }
    var editName by remember { mutableStateOf("") }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        val list = @Composable {
            SetlistPanel(lib, setlistId, revision, selected,
                onSelect = { id ->
                    selected = id
                    val e = lib.store.entry(id)
                    val song = lib.songs().firstOrNull { it.entry.id == id }?.song
                    val rack = song?.let { lib.rackSong(it) }
                    if (e != null && rack != null) { editing = id to rack; editName = e.version.name }
                },
                onNew = {
                    editing = null to RackSong.starter(preset = host.currentInstrument())
                    editName = "New song"
                    selected = null
                },
                onChanged = { revision++; player.reload() })
        }
        val editor = @Composable {
            val ed = editing
            if (ed == null) {
                Text("Pick a song, or add one.", color = Dim, modifier = Modifier.padding(24.dp))
            } else {
                SongEditor(ed.second, editName, host, onName = { editName = it },
                    onChange = { editing = ed.first to it },
                    onSave = {
                        val id = lib.saveSong(ed.first, editName, ed.second)
                        if (ed.first == null && setlistId != null) {
                            val ids = lib.songsOf(setlistId).mapNotNull { it.first?.id } + id
                            lib.saveSetlist(setlistId, player.setlistName, ids)
                        }
                        editing = id to ed.second
                        selected = id
                        revision++
                        player.reload()
                    })
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(340.dp).fillMaxHeight()) { list() }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) { editor() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(260.dp)) { list() }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f)) { editor() }
            }
        }
    }
}

@Composable
private fun SetlistPanel(lib: AppLibrary, setlistId: Digest?, revision: Int, selected: Digest?,
                         onSelect: (Digest) -> Unit, onNew: () -> Unit, onChanged: () -> Unit) {
    val songs = remember(setlistId, revision) { setlistId?.let { lib.songsOf(it) }.orEmpty() }
    val name = remember(setlistId, revision) { setlistId?.let { lib.store.entry(it)?.version?.name }.orEmpty() }
    Column(Modifier.fillMaxSize().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text("Setlist: $name", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(songs) { i, (entry, _) ->
                val id = entry?.id
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .background(if (id != null && id == selected) Accent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable(enabled = id != null) { id?.let(onSelect) }.padding(vertical = 6.dp)) {
                    Text("${i + 1}. ${entry?.version?.name ?: "(removed)"}", modifier = Modifier.weight(1f))
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
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Add a song") }
    }
}

private fun move(lib: AppLibrary, setlistId: Digest, ids: List<Digest>, i: Int, by: Int) {
    val m = ids.toMutableList()
    val j = i + by
    if (j !in m.indices) return
    m[i] = m[j].also { m[j] = m[i] }
    lib.saveSetlist(setlistId, lib.store.entry(setlistId)?.version?.name ?: "Setlist", m)
}

@Composable
private fun SongEditor(song: RackSong, name: String, host: EngineHost, onName: (String) -> Unit,
                       onChange: (RackSong) -> Unit, onSave: () -> Unit) {
    var picker by remember { mutableStateOf<RackEngine?>(null) }
    Column(Modifier.fillMaxSize().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp)
        .verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = name, onValueChange = onName, label = { Text("Song") },
                singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Button(onClick = onSave, modifier = Modifier.height(56.dp)) { Text("Save") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Engines", fontWeight = FontWeight.SemiBold)
        for (e in RackEngine.entries) {
            val slot = song.slots[e]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = slot != null, onCheckedChange = { on ->
                    val slots = song.slots.toMutableMap()
                    if (on) slots[e] = RackSlot() else slots.remove(e)
                    onChange(song.copy(slots = slots, liveSets = song.liveSets.map { ls ->
                        val parts = ls.parts.toMutableMap()
                        if (on) parts.putIfAbsent(e, RackPart()) else parts.remove(e)
                        ls.copy(parts = parts)
                    }))
                })
                Text(e.label, modifier = Modifier.width(80.dp))
                if (slot != null) {
                    OutlinedButton(onClick = { picker = e }, modifier = Modifier.weight(1f)) {
                        Text(slot.preset ?: "(as loaded)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("level", color = Dim)
                    Slider(value = slot.level.toFloat(), onValueChange = { v ->
                        onChange(song.copy(slots = song.slots + (e to slot.copy(level = v.toDouble()))))
                    }, modifier = Modifier.width(180.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live sets", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                val parts = song.slots.keys.associateWith { RackPart() }
                onChange(song.copy(liveSets = song.liveSets + RackLiveSet("Live set ${song.liveSets.size + 1}", parts)))
            }) { Text("Add live set") }
        }
        song.liveSets.forEachIndexed { i, ls ->
            LiveSetCard(song, i, ls, onChange)
        }
    }
    picker?.let { e ->
        PresetPicker(e, host, onPick = { preset, program ->
            val slot = song.slots[e] ?: RackSlot()
            onChange(song.copy(slots = song.slots + (e to slot.copy(preset = preset, program = program))))
            picker = null
        }, onDismiss = { picker = null })
    }
}

@Composable
private fun LiveSetCard(song: RackSong, i: Int, ls: RackLiveSet, onChange: (RackSong) -> Unit) {
    fun put(n: RackLiveSet) = onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it[i] = n }))
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, Color(0xFF2A2E36), RoundedCornerShape(8.dp))
        .padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = ls.name, onValueChange = { put(ls.copy(name = it)) }, singleLine = true,
                label = { Text("Live set ${i + 1}") }, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it.add(i + 1, ls.copy(name = ls.name + " copy")) }))
            }) { Text("Copy") }
            TextButton(enabled = i > 0, onClick = {
                onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it[i] = it[i - 1].also { _ -> it[i - 1] = ls } }))
            }) { Text("▲") }
            TextButton(enabled = song.liveSets.size > 1, onClick = {
                onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it.removeAt(i) }))
            }) { Text("✕", color = Danger) }
        }
        for (e in song.slots.keys.sorted()) {
            val p = ls.parts[e] ?: RackPart(plays = false)
            fun putPart(np: RackPart) = put(ls.copy(parts = ls.parts + (e to np)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = p.plays, onCheckedChange = { putPart(p.copy(plays = it)) })
                Text(e.label, modifier = Modifier.width(80.dp))
                NumberField("low", p.keyLow, 0..127, noteName = true) { putPart(p.copy(keyLow = it)) }
                NumberField("high", p.keyHigh, 0..127, noteName = true) { putPart(p.copy(keyHigh = it)) }
                NumberField("transp.", p.transpose, -48..48) { putPart(p.copy(transpose = it)) }
                NumberField("ch in", p.channelIn, 0..16) { putPart(p.copy(channelIn = it)) }
                Checkbox(checked = p.level != null, onCheckedChange = { on ->
                    putPart(p.copy(level = if (on) song.slots[e]?.level ?: 0.8 else null))
                })
                Text("own level", color = Dim, fontSize = 12.sp)
                p.level?.let { lv ->
                    Slider(value = lv.toFloat(), onValueChange = { putPart(p.copy(level = it.toDouble())) },
                        modifier = Modifier.width(140.dp))
                }
            }
        }
    }
}

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun noteLabel(n: Int) = NOTE_NAMES[n % 12] + (n / 12 - 1)

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, noteName: Boolean = false, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        TextButton(onClick = { onValue((value - 1).coerceIn(range)) }) { Text("−") }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
            Text(label, fontSize = 10.sp, color = Dim)
            Text(if (noteName) noteLabel(value) else value.toString(), fontSize = 15.sp)
        }
        TextButton(onClick = { onValue((value + 1).coerceIn(range)) }) { Text("+") }
    }
}

@Composable
private fun PresetPicker(e: RackEngine, host: EngineHost, onPick: (String?, Int?) -> Unit, onDismiss: () -> Unit) {
    val choices = remember(e) { presetChoices(e, host) }
    var filter by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(onClick = { onPick(null, null) }) { Text("As loaded") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("${e.label}: patch") },
        text = {
            Column(Modifier.height(420.dp)) {
                OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                    label = { Text("Search") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                val shown = choices.filter { filter.isEmpty() || it.second.contains(filter, true) }.take(300)
                LazyColumn {
                    itemsIndexed(shown) { _, (value, label) ->
                        Text(label, modifier = Modifier.fillMaxWidth().clickable { onPick(value, null) }
                            .padding(vertical = 10.dp))
                    }
                }
            }
        })
}

/** Installed content an engine can play: value stored in the patch, label shown. */
private fun presetChoices(e: RackEngine, host: EngineHost): List<Pair<String, String>> = runCatching {
    when (e) {
        RackEngine.SFIZZ -> host.instruments()
        RackEngine.HAMMOND -> NativeBridge.nativeOrganPresetNames().map { it to it }
        RackEngine.SURGE -> (0 until NativeBridge.nativeSurgePatchCount()).map {
            NativeBridge.nativeSurgePatchName(it).let { n -> n to n }
        }
        RackEngine.DEXED -> (0 until NativeBridge.nativeDexedVoiceCount()).map {
            NativeBridge.nativeDexedVoiceName(it).let { n -> n to n }
        }
        RackEngine.FLUID -> (0 until NativeBridge.nativeFluidFontCount()).map {
            NativeBridge.nativeFluidFontName(it).let { n -> n to n }
        }
        RackEngine.PLAITS -> NativeBridge.nativePlaitsEngineNames().map { it to it }
    }
}.getOrDefault(emptyList())
