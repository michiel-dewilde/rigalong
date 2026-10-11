package be.mdw.bagosynths.ui

import be.mdw.bagosynths.model.Library
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.res.stringResource
import be.mdw.bagosynths.R
import be.mdw.bagosynths.model.Digest
import be.mdw.bagosynths.model.RackCp88
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
        // Dark text on the green, everywhere Material puts text or a thumb on it.
        primary = Accent, onPrimary = Color(0xFF0B0D10), primaryContainer = Accent,
        onPrimaryContainer = Color(0xFF0B0D10), background = Bg, surface = Panel, onSurface = Color(0xFFE6E9EF),
        onBackground = Color(0xFFE6E9EF), secondary = Warn, error = Danger,
    )) { Surface(color = Bg, modifier = Modifier.fillMaxSize()) { content() } }
}

@Composable
fun AppUi(player: SongPlayer, lib: Library, host: EngineHost) {
    var screen by remember { mutableIntStateOf(0) }
    var health by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            health = runCatching { host.healthLine() }.getOrDefault("")
            player.diagnostics?.tick(host)
            delay(500)
        }
    }
    LaunchedEffect(screen) { player.onStage = screen == 0 }
    LaunchedEffect(Unit) { player.backToStage = { screen = 0 } }
    LaunchedEffect(Unit) { player.showSongs = { screen = 1 } }
    LaunchedEffect(Unit) { player.watchControllers() }
    LaunchedEffect(Unit) {
        while (true) {
            player.pollSelections()
            delay(50)
        }
    }
    BagoTheme {
        // Edge to edge is the rule at target SDK 36: keep clear of the status
        // and navigation bars, and of a display cutout, ourselves.
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            TopBar(player, health, screen, onScreen = { screen = it }, onPrototype = host::showPrototype)
            Spacer(Modifier.height(8.dp))
            when (screen) {
                0 -> StageScreen(player, host)
                1 -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { SongsScreen(player, lib, host) }
                    Spacer(Modifier.height(8.dp))
                    TestKeys()
                }
                2 -> LibraryScreen(player, lib, host, onOpened = { screen = 0 })
                else -> SetupScreen(player, host, lib)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(player: SongPlayer, health: String, screen: Int, onScreen: (Int) -> Unit, onPrototype: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()) {
        // EDIT LOCK (S17.3): from the stage, the editors open only on a long
        // press, so a stray tap mid-song never leaves the stage.
        val locked = screen == 0
        Tab(stringResource(R.string.tab_stage), screen == 0) { onScreen(0) }
        Tab(stringResource(R.string.tab_songs), screen == 1, locked) { onScreen(1) }
        Tab(stringResource(R.string.tab_library), screen == 2, locked) { onScreen(2) }
        Tab(stringResource(R.string.tab_setup), screen == 3, locked) { onScreen(3) }
        if (locked) Text(stringResource(R.string.hold_to_edit), color = Dim, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        // The health line opens the figures (S19.3): Setup, where they are -- on
        // a long press from the stage, like the tabs.
        Column(Modifier.weight(1f).combinedClickable(onClick = { if (!locked) onScreen(3) }, onLongClick = { onScreen(3) })) {
            Text(player.setlistName.ifEmpty { stringResource(R.string.no_setlist) }, color = Dim, fontSize = 14.sp, maxLines = 1)
            Text(health, color = if (health.startsWith("!")) Warn else Dim, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Tab(stringResource(R.string.tab_prototype), false, locked) { onPrototype() }
        Button(onClick = player::panic, colors = ButtonDefaults.buttonColors(containerColor = Danger),
            modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.panic), fontWeight = FontWeight.Bold, color = Color.White) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tab(label: String, selected: Boolean, longPressOnly: Boolean = false, onClick: () -> Unit) {
    val press = if (longPressOnly) Modifier.combinedClickable(onClick = {}, onLongClick = onClick)
    else Modifier.clickable(onClick = onClick)
    Box(Modifier.background(if (selected) Accent.copy(alpha = 0.25f) else Panel, RoundedCornerShape(8.dp))
        .then(press).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(label, color = if (selected) Accent else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------ stage

@Composable
private fun StageScreen(player: SongPlayer, host: EngineHost) {
    // With a lead sheet the screen is split between it and the controls (S9.1, S15.3).
    val sheet = player.sheetFile
    if (sheet == null) {
        StageControls(player, host, Modifier.fillMaxSize())
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val sheetView = @Composable { m: Modifier ->
            LeadSheetView(sheet, player.sheetPage, player.sheetTwoUp && !portrait, { player.sheetPages = it }, m)
        }
        if (portrait) {
            // Portrait: the sheet above, the controls below (S15.4).
            Column(Modifier.fillMaxSize()) {
                sheetView(Modifier.fillMaxWidth().weight(0.6f))
                Spacer(Modifier.height(8.dp))
                StageControls(player, host, Modifier.fillMaxWidth().weight(0.4f))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                sheetView(Modifier.fillMaxHeight().weight(0.6f))
                Spacer(Modifier.width(10.dp))
                StageControls(player, host, Modifier.fillMaxHeight().weight(0.4f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun StageControls(player: SongPlayer, host: EngineHost, modifier: Modifier) {
    Column(modifier) {
        // Beside a lead sheet the stage column is narrow: the tempo then gets a
        // row of its own, so the song's name is not squeezed to a few letters.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 760.dp
        Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BigButton("◀", enabled = player.songIndex > 0, width = 88) { player.previousSong() }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(player.songNames.getOrNull(player.songIndex) ?: "—", fontSize = 30.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val next = player.songNames.getOrNull(player.songIndex + 1)
                val sel = (if (player.lastSelection.isEmpty()) "" else stringResource(R.string.from_keys, player.lastSelection)) +
                    player.cp88Status.let { if (it.isEmpty()) "" else "   ·   $it" }
                Text((if (next != null) stringResource(R.string.next_song, next) else stringResource(R.string.last_song)) + sel,
                    color = Dim, fontSize = 15.sp, maxLines = 1)
            }
            if (!narrow) {
                TempoBox(player)
                Spacer(Modifier.width(10.dp))
            }
            BigButton("▶", enabled = player.songIndex + 1 < player.songNames.size, width = 88) { player.nextSong() }
        }
        if (narrow) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { TempoBox(player) }
        }
        }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()) {
            player.liveSetNames.forEachIndexed { i, name ->
                val sel = i == player.liveSetIndex
                Box(Modifier.size(width = 190.dp, height = 84.dp)
                    .background(if (sel) Accent else Panel, RoundedCornerShape(12.dp))
                    .border(2.dp, if (sel) Accent else Color(0xFF2A2E36), RoundedCornerShape(12.dp))
                    // A tap selects it; a long press edits it (quick edit from the stage).
                    .combinedClickable(onClick = { player.selectLiveSet(i) }, onLongClick = { player.requestEdit(liveSet = i) })
                    .padding(10.dp),
                    contentAlignment = Alignment.Center) {
                    Text(name, color = if (sel) Color.Black else Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.levels), color = Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Tab(stringResource(R.string.store_levels), false, longPressOnly = true) { player.storeLevels() }
        }
        val engines = player.rack?.slots?.keys?.sorted().orEmpty()
        Row(Modifier.fillMaxWidth().weight(1f)) {
        // Scrolls sideways when the panels do not fit (a phone, portrait: S18.4, S20.2).
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            // The levels first: where the row is narrower than its panels (beside
            // a lead sheet), the organ's drawbars scroll into view, not the faders.
            for (e in engines) {
                val plays = player.playing[e] ?: false
                Column(Modifier.width(140.dp).fillMaxHeight().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    // A long press on its name opens its patch in the editor.
                    Text(engineLabel(e), fontWeight = FontWeight.SemiBold, color = if (plays) Accent else Dim,
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { player.requestEdit(engine = e) }))
                    Text(stringResource(if (plays) R.string.engine_playing else R.string.engine_silent), fontSize = 12.sp, color = Dim)
                    Spacer(Modifier.height(8.dp))
                    val level = player.levels[e] ?: 0f
                    Text("%.0f %%".format(level * 100), fontSize = 18.sp)
                    // Moves only by dragging, never by a touch (S17.4).
                    RelativeFader(level, { player.setLevel(e, it) }, Modifier.weight(1f).fillMaxWidth())
                }
            }
            if (RackEngine.HAMMOND in engines) OrganPanel(player)
        }
        Spacer(Modifier.width(16.dp))
        MasterFader(host)
        }
    }
}

/**
 * The master volume, at the end of the chain (README "Master volume"): a global
 * control, never recalled by a live set (I6.8). -40 to +12 dB; a long press on
 * its label returns it to 0 dB. Moves only by dragging (S17.4).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MasterFader(host: EngineHost) {
    var db by remember { mutableStateOf(host.masterDb()) }
    val min = NativeBridge.MASTER_MIN_DB
    val max = NativeBridge.MASTER_MAX_DB
    Column(Modifier.width(110.dp).fillMaxHeight().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.master), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { host.setMasterDb(0f); db = 0f }))
        Text("%+.1f dB".format(db), fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        RelativeFader((db - min) / (max - min), { f ->
            val v = Math.round((min + f * (max - min)) * 2f) / 2f
            host.setMasterDb(v); db = v
        }, Modifier.weight(1f).fillMaxWidth(), color = Warn)
    }
}

private val DRAWBAR_LABELS = listOf("16'", "5⅓'", "8'", "4'", "2⅔'", "2'", "1⅗'", "1⅓'", "1'")
private val DRAWBAR_COLOURS = listOf(Color(0xFF8B4A2B), Color(0xFF8B4A2B), Color(0xFFE6E2D6), Color(0xFFE6E2D6),
    Color(0xFF2B2B2B), Color(0xFFE6E2D6), Color(0xFF2B2B2B), Color(0xFF2B2B2B), Color(0xFFE6E2D6))

/** The organ's upper drawbars, Leslie and percussion, on the stage (S6.1's example). */
@Composable
private fun OrganPanel(player: SongPlayer) {
    Column(Modifier.fillMaxHeight().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.organ), fontWeight = FontWeight.SemiBold)
            val leslie = player.organ[SongPlayer.ORGAN_LESLIE] ?: 1f
            for ((v, label) in listOf(0f to stringResource(R.string.leslie_stop), 1f to stringResource(R.string.leslie_slow),
                    2f to stringResource(R.string.leslie_fast))) {
                Box(Modifier.background(if (leslie == v) Accent else Bg, RoundedCornerShape(8.dp))
                    .clickable { player.setOrgan(SongPlayer.ORGAN_LESLIE, v) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(label, color = if (leslie == v) Color.Black else Color.White)
                }
            }
            val perc = (player.organ[SongPlayer.ORGAN_PERC] ?: 0f) > 0.5f
            Box(Modifier.background(if (perc) Accent else Bg, RoundedCornerShape(8.dp))
                .clickable { player.setOrgan(SongPlayer.ORGAN_PERC, if (perc) 0f else 1f) }
                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.percussion), color = if (perc) Color.Black else Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..8) {
                val v = (player.organ[i] ?: 0f) / 8f
                Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(DRAWBAR_LABELS[i], fontSize = 11.sp, color = Dim)
                    // Whole steps, as on the instrument: 0..8.
                    RelativeFader(v, { player.setOrgan(i, Math.round(it * 8f).toFloat()) },
                        Modifier.weight(1f).fillMaxWidth(), color = DRAWBAR_COLOURS[i])
                    Text(((player.organ[i] ?: 0f).toInt()).toString(), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, width: Int, selected: Boolean = false,
                      fontSize: Int = 30, onClick: () -> Unit) {
    Box(Modifier.size(width = width.dp, height = 72.dp)
        .background(if (enabled) Panel else Bg, RoundedCornerShape(12.dp))
        .border(2.dp, if (selected) Accent else Color(0xFF2A2E36), RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = fontSize.sp, color = if (enabled) Color.White else Color(0xFF3A3F48))
    }
}

// ------------------------------------------------------------------ songs

@Composable
private fun SongsScreen(player: SongPlayer, lib: Library, host: EngineHost) {
    var revision by remember { mutableIntStateOf(0) }
    val setlistId = player.setlistId
    var selected by remember { mutableStateOf<Digest?>(null) }
    var editing by remember { mutableStateOf<Pair<Digest?, RackSong>?>(null) }
    var editName by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf<SongPlayer.EditRequest?>(null) }
    val newSongName = stringResource(R.string.new_song)
    fun open(id: Digest) {
        selected = id
        val e = lib.store.entry(id)
        val song = lib.songs().firstOrNull { it.entry.id == id }?.song
        val rack = song?.let { lib.rackSong(it) }
        if (e != null && rack != null) { editing = id to rack; editName = e.version.name }
    }
    // Quick edit from the stage: the song, scrolled to its live set or with its patch open.
    LaunchedEffect(player.editRequest) {
        player.editRequest?.let { r -> open(r.song); focus = r; player.editRequest = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        val list = @Composable {
            SetlistPanel(lib, setlistId, revision, selected,
                onSelect = { id -> focus = null; open(id) },
                onNew = {
                    editing = null to RackSong.starter(preset = host.currentInstrument())
                    editName = newSongName
                    selected = null
                },
                onChanged = { revision++; player.reload() })
        }
        val editor = @Composable {
            val ed = editing
            if (ed == null) {
                Text(stringResource(R.string.pick_song), color = Dim, modifier = Modifier.padding(24.dp))
            } else {
                SongEditor(ed.second, editName, host, player, focus?.takeIf { it.song == ed.first },
                    onName = { editName = it },
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
private fun SetlistPanel(lib: Library, setlistId: Digest?, revision: Int, selected: Digest?,
                         onSelect: (Digest) -> Unit, onNew: () -> Unit, onChanged: () -> Unit) {
    val songs = remember(setlistId, revision) { setlistId?.let { lib.songsOf(it) }.orEmpty() }
    val name = remember(setlistId, revision) { setlistId?.let { lib.store.entry(it)?.version?.name }.orEmpty() }
    Column(Modifier.fillMaxSize().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(stringResource(R.string.setlist_named, name), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(songs) { i, (entry, _) ->
                val id = entry?.id
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .background(if (id != null && id == selected) Accent.copy(alpha = 0.2f) else Color.Transparent)
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
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_song)) }
    }
}

private fun move(lib: Library, setlistId: Digest, ids: List<Digest>, i: Int, by: Int) {
    val m = ids.toMutableList()
    val j = i + by
    if (j !in m.indices) return
    m[i] = m[j].also { m[j] = m[i] }
    lib.saveSetlist(setlistId, lib.store.entry(setlistId)?.version?.name ?: "Setlist", m)
}

@Composable
private fun SongEditor(song: RackSong, name: String, host: EngineHost, player: SongPlayer,
                       focus: SongPlayer.EditRequest?, onName: (String) -> Unit,
                       onChange: (RackSong) -> Unit, onSave: () -> Unit) {
    var picker by remember { mutableStateOf<RackEngine?>(null) }
    var patchEditor by remember { mutableStateOf<RackEngine?>(null) }
    LaunchedEffect(focus) { focus?.engine?.let { if (song.slots.containsKey(it)) patchEditor = it } }
    var reverbEditor by remember { mutableStateOf(false) }
    var fxEditor by remember { mutableStateOf<RackEngine?>(null) }
    var masterFxEditor by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp)
        .verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = name, onValueChange = onName, label = { Text(stringResource(R.string.song)) },
                singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            LeadSheetButton(song, player, onChange)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { reverbEditor = true }) {
                Text(stringResource(R.string.reverb_button), color = if (song.reverb.isEmpty()) Color.White else Accent)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { masterFxEditor = true }) {
                Text(song.masterFx?.let { stringResource(R.string.master_fx_is, stringResource(fxLabel(it.type))) }
                    ?: stringResource(R.string.master_fx_button), color = if (song.masterFx == null) Color.White else Accent)
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onSave, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.save)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = song.tempo != null, onCheckedChange = { on ->
                onChange(song.copy(tempo = if (on) be.mdw.bagosynths.model.Tempo(bpm = 120.0) else null))
            })
            Text(stringResource(R.string.tempo))
            song.tempo?.let { t ->
                NumberField(stringResource(R.string.bpm_short), t.bpm.toInt(), 20..300) {
                    onChange(song.copy(tempo = t.copy(bpm = it.toDouble())))
                }
                NumberField(stringResource(R.string.beats_per_bar), t.beatsPerBar, 1..16) {
                    onChange(song.copy(tempo = t.copy(beatsPerBar = it)))
                }
                NumberField(stringResource(R.string.count_in_bars), t.countInBars ?: 1, 1..8) {
                    onChange(song.copy(tempo = t.copy(countInBars = it)))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.engines), fontWeight = FontWeight.SemiBold)
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
                Text(engineLabel(e), modifier = Modifier.width(80.dp))
                if (slot != null) {
                    OutlinedButton(onClick = { picker = e }, modifier = Modifier.weight(1f)) {
                        Text(slot.preset ?: stringResource(R.string.as_loaded_paren), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { patchEditor = e }) {
                        Text(stringResource(R.string.edit_patch) + if (slot.params.isNotEmpty()) " ●" else "")
                    }
                    TextButton(onClick = { fxEditor = e }) {
                        Text(slot.fx?.let { stringResource(fxLabel(it.type)) } ?: stringResource(R.string.fx_button))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.level), color = Dim)
                    Slider(value = slot.level.toFloat(), onValueChange = { v ->
                        onChange(song.copy(slots = song.slots + (e to slot.copy(level = v.toDouble()))))
                    }, modifier = Modifier.width(180.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_sets), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val nextName = stringResource(R.string.live_set_n, song.liveSets.size + 1)
            OutlinedButton(onClick = {
                val parts = song.slots.keys.associateWith { RackPart() }
                onChange(song.copy(liveSets = song.liveSets + RackLiveSet(nextName, parts)))
            }) { Text(stringResource(R.string.add_live_set)) }
        }
        song.liveSets.forEachIndexed { i, ls ->
            LiveSetCard(song, i, ls, player, focused = focus?.liveSet == i, onChange)
        }
    }
    fxEditor?.let { e ->
        song.slots[e]?.let { slot ->
            FxEditor(stringResource(R.string.fx_of, engineLabel(e)), e.ordinal, slot.fx, player, onChange = { f ->
                onChange(song.copy(slots = song.slots + (e to (song.slots[e] ?: slot).copy(fx = f))))
            }, onDismiss = { fxEditor = null })
        }
    }
    if (masterFxEditor) {
        FxEditor(stringResource(R.string.master_fx_title), SongPlayer.MASTER_FX, song.masterFx, player,
            onChange = { onChange(song.copy(masterFx = it)) }, onDismiss = { masterFxEditor = false })
    }
    if (reverbEditor) {
        ReverbEditor(song, player, onChange = { onChange(song.copy(reverb = it)) },
            onDismiss = { reverbEditor = false })
    }
    patchEditor?.let { e ->
        song.slots[e]?.let { slot ->
            PatchEditor(e, slot, player, onChange = { params ->
                onChange(song.copy(slots = song.slots + (e to slot.copy(params = params))))
            }, onDismiss = { patchEditor = null })
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveSetCard(song: RackSong, i: Int, ls: RackLiveSet, player: SongPlayer, focused: Boolean,
                        onChange: (RackSong) -> Unit) {
    fun put(n: RackLiveSet) = onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it[i] = n }))
    var importing by remember { mutableStateOf("") }
    val copyName = stringResource(R.string.copy_of, ls.name)
    val reading = stringResource(R.string.reading_cp88)
    // Opened from the stage on this live set: scrolled into view and outlined.
    val into = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) into.bringIntoView() }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
        .bringIntoViewRequester(into)
        .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color(0xFF2A2E36), RoundedCornerShape(8.dp))
        .padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = ls.name, onValueChange = { put(ls.copy(name = it)) }, singleLine = true,
                label = { Text(stringResource(R.string.live_set_n, i + 1)) }, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it.add(i + 1, ls.copy(name = copyName)) }))
            }) { Text(stringResource(R.string.copy)) }
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Checkbox(checked = p.plays, onCheckedChange = { putPart(p.copy(plays = it)) })
                Text(engineLabel(e), modifier = Modifier.width(80.dp))
                NumberField(stringResource(R.string.key_low), p.keyLow, 0..127, noteName = true) { putPart(p.copy(keyLow = it)) }
                NumberField(stringResource(R.string.key_high), p.keyHigh, 0..127, noteName = true) { putPart(p.copy(keyHigh = it)) }
                NumberField(stringResource(R.string.transpose_short), p.transpose, -48..48) { putPart(p.copy(transpose = it)) }
                NumberField(stringResource(R.string.channel_in_short), p.channelIn, 0..16) { putPart(p.copy(channelIn = it)) }
                Checkbox(checked = p.level != null, onCheckedChange = { on ->
                    putPart(p.copy(level = if (on) song.slots[e]?.level ?: 0.8 else null))
                })
                Text(stringResource(R.string.own_level), color = Dim, fontSize = 12.sp)
                p.level?.let { lv ->
                    Slider(value = lv.toFloat(), onValueChange = { putPart(p.copy(level = it.toDouble())) },
                        modifier = Modifier.width(140.dp))
                }
                // The engine's effect in this live set: on, or off with its tail ringing out.
                if (song.slots[e]?.fx != null) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Switch(checked = p.fxAmount > 0.5,
                        onCheckedChange = { putPart(p.copy(fxAmount = if (it) 1.0 else 0.0)) })
                    Text(stringResource(R.string.fx_in_live_set), color = Dim, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        // Any other keyboard or module (I6.7): a program change, with an optional bank, on select.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val pc = ls.program
            Checkbox(checked = pc != null, onCheckedChange = { on ->
                put(ls.copy(program = if (on) be.mdw.bagosynths.model.RackProgram() else null))
            })
            Text(stringResource(R.string.program_change), modifier = Modifier.width(150.dp))
            if (pc != null) {
                fun putPc(n: be.mdw.bagosynths.model.RackProgram) = put(ls.copy(program = n))
                NumberField(stringResource(R.string.channel_short), pc.channel, 1..16) { putPc(pc.copy(channel = it)) }
                NumberField(stringResource(R.string.program_short), pc.program, 1..128) { putPc(pc.copy(program = it)) }
                Checkbox(checked = pc.bankMsb != null, onCheckedChange = { on ->
                    putPc(pc.copy(bankMsb = if (on) 0 else null, bankLsb = if (on) pc.bankLsb ?: 0 else null))
                })
                Text(stringResource(R.string.bank), color = Dim, fontSize = 12.sp)
                pc.bankMsb?.let { msb ->
                    NumberField(stringResource(R.string.bank_msb), msb, 0..127) { putPc(pc.copy(bankMsb = it)) }
                    NumberField(stringResource(R.string.bank_lsb), pc.bankLsb ?: 0, 0..127) { putPc(pc.copy(bankLsb = it)) }
                }
            }
        }
        // The CP-88 (I4.7): leave it, select one of its sounds, or own one.
        val cp = ls.cp88
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cp88), modifier = Modifier.width(80.dp).padding(start = 12.dp))
            for ((mode, label) in listOf("leave" to stringResource(R.string.cp88_leave), "select" to stringResource(R.string.cp88_select),
                    "own" to stringResource(R.string.cp88_own))) {
                TextButton(onClick = { put(ls.copy(cp88 = cp.copy(mode = mode))) }) {
                    Text(label, color = if (cp.mode == mode) Accent else Dim)
                }
            }
            when (cp.mode) {
                "select" -> {
                    NumberField(stringResource(R.string.page), cp.page, 1..40) { put(ls.copy(cp88 = cp.copy(page = it))) }
                    NumberField(stringResource(R.string.set), cp.set, 1..8) { put(ls.copy(cp88 = cp.copy(set = it))) }
                }
                "own" -> {
                    Text(cp.soundName?.ifEmpty { stringResource(R.string.unnamed) } ?: stringResource(R.string.no_sound_yet),
                        modifier = Modifier.padding(horizontal = 8.dp))
                    OutlinedButton(onClick = {
                        importing = reading
                        Thread {
                            val r = runCatching { player.importCurrentCp88Sound() }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                r.onSuccess { (d, n) ->
                                    put(ls.copy(cp88 = cp.copy(mode = "own", sound = d, soundName = n))); importing = ""
                                }.onFailure { importing = it.message ?: "failed" }
                            }
                        }.start()
                    }) { Text(stringResource(R.string.import_current_sound)) }
                    Text(importing, color = Warn, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * The song's tempo, a visual beat (I12.2), tap tempo and saving a tapped one
 * (S12.3, I12.3), and the click: a count-in, or on and off (S12.2). While the
 * click runs the beat shows its number in the bar, in step with what is heard.
 */
@Composable
private fun TempoBox(player: SongPlayer) {
    var now by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) { now = System.nanoTime(); delay(16) } }
    val bpm = player.bpm
    val beatNs = bpm?.let { (60e9 / it).toLong() }
    val phase = if (beatNs != null && beatNs > 0) ((now - player.beatOrigin).mod(beatNs)).toDouble() / beatNs else 1.0
    val lit = phase < 0.15
    val count = player.clickBeat(now)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (count > 0) {
            Box(Modifier.size(44.dp).background(if (lit) Accent else Panel, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center) {
                Text(count.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = if (lit) Color.Black else Color.White)
            }
        } else {
            Box(Modifier.size(22.dp).background(if (lit) Accent else Panel, RoundedCornerShape(11.dp)))
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bpm?.let { stringResource(R.string.bpm, it) } ?: stringResource(R.string.no_tempo), fontSize = 18.sp)
            if (player.bpmTapped) TextButton(onClick = player::saveTempo) { Text(stringResource(R.string.save_tempo)) }
        }
        Spacer(Modifier.width(8.dp))
        BigButton(stringResource(R.string.tap), enabled = true, width = 88) { player.tap() }
        Spacer(Modifier.width(8.dp))
        BigButton(stringResource(when {
            player.clickRunning -> R.string.click_stop
            player.clickContinuous -> R.string.click_on
            else -> R.string.count_in
        }), enabled = bpm != null, width = 104, selected = player.clickRunning,
            fontSize = 22) { player.toggleClick() }
    }
}

/**
 * An engine's patch (M8, first slice): every parameter but the level, with a
 * slider over its real range (whole numbers where only those mean anything).
 * Moving a slider plays it at once; the values are kept in the song, over the
 * preset. "Take current" stores what the engine has now -- after shaping it on
 * the prototype's panels, for instance; "Clear" goes back to the plain preset.
 */
@Composable
private fun PatchEditor(e: RackEngine, slot: RackSlot, player: SongPlayer,
                        onChange: (Map<String, Double>) -> Unit, onDismiss: () -> Unit) =
    ParamEditor(stringResource(R.string.patch_of, engineLabel(e)), e, slot.params, player,
        load = { player.previewSlot(e, slot); player.engineParams(e) },
        audition = { name, v -> player.auditionParam(e, name, v) }, onChange = onChange, onDismiss = onDismiss)

/** The song's reverb, where its engines meet, edited like a patch. */
@Composable
private fun ReverbEditor(song: RackSong, player: SongPlayer, onChange: (Map<String, Double>) -> Unit,
                         onDismiss: () -> Unit) =
    ParamEditor(stringResource(R.string.reverb), "reverb", song.reverb, player,
        load = { player.previewReverb(song.reverb); player.reverbParams() },
        audition = player::auditionReverb, onChange = onChange, onDismiss = onDismiss)

@Composable
private fun ParamEditor(title: String, key: Any, current: Map<String, Double>, player: SongPlayer,
                        load: () -> List<SongPlayer.ParamDesc>, audition: (String, Float) -> Unit,
                        onChange: (Map<String, Double>) -> Unit, onDismiss: () -> Unit) {
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
            LazyColumn(Modifier.height(480.dp)) {
                itemsIndexed(params) { _, p ->
                    ParamRow(p, values[p.name]?.toFloat() ?: p.value, values.containsKey(p.name), display = { null }) { x ->
                        audition(p.name, x)
                        put(values + (p.name to x.toDouble()))
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
private fun ParamRow(p: SongPlayer.ParamDesc, v: Float, set: Boolean, display: (Float) -> String?, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, modifier = Modifier.width(150.dp), fontSize = 14.sp, color = if (set) Accent else Color.White)
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
    }
}

/** An engine's insert effect (M6): which one, and its parameters, heard as they move. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FxEditor(title: String, e: Int, current: be.mdw.bagosynths.model.RackFx?, player: SongPlayer,
                     onChange: (be.mdw.bagosynths.model.RackFx?) -> Unit, onDismiss: () -> Unit) {
    var fx by remember(e) { mutableStateOf(current ?: be.mdw.bagosynths.model.RackFx("off")) }
    val params = remember(e, fx.type) { player.applyFx(e, fx); player.fxParams(e) }
    fun put(f: be.mdw.bagosynths.model.RackFx) { fx = f; onChange(f.takeIf { it.type != "off" }) }
    AlertDialog(onDismissRequest = onDismiss, modifier = WideDialog, properties = WideDialogProperties,
        confirmButton = { TextButton(onClick = { player.reload(); onDismiss() }) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(onClick = { put(fx.copy(params = emptyMap())); player.applyFx(e, fx) }) {
                Text(stringResource(R.string.clear))
            }
        },
        title = { Text(title) },
        text = {
            Column {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in be.mdw.bagosynths.model.RackFx.TYPES) {
                        val label = stringResource(fxLabel(t))
                        if (t == fx.type) Button(onClick = {}) { Text(label) }
                        else OutlinedButton(onClick = { put(be.mdw.bagosynths.model.RackFx(t)) }) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(420.dp)) {
                    itemsIndexed(params, key = { _, p -> fx.type + p.name }) { _, p ->
                        ParamRow(p, fx.params[p.name]?.toFloat() ?: p.value, fx.params.containsKey(p.name),
                            display = { player.fxDisplay(e, p.name, it) }) { x ->
                            player.auditionFx(e, p.name, x)
                            put(fx.copy(params = fx.params + (p.name to x.toDouble())))
                        }
                    }
                }
            }
        })
}

private fun fxLabel(type: String) = when (type) {
    "delay" -> R.string.fx_delay
    "phaser" -> R.string.fx_phaser
    "flanger" -> R.string.fx_flanger
    "rotary" -> R.string.fx_rotary
    "reverb" -> R.string.fx_reverb
    else -> R.string.fx_off
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

/** An engine's name for people, from resources (the model's labels are for logs). */
@Composable
private fun engineLabel(e: RackEngine): String = stringResource(when (e) {
    RackEngine.SFIZZ -> R.string.engine_sfizz
    RackEngine.HAMMOND -> R.string.engine_hammond
    RackEngine.SURGE -> R.string.engine_surge
    RackEngine.DEXED -> R.string.engine_dexed
    RackEngine.PLAITS -> R.string.engine_plaits
    RackEngine.FLUID -> R.string.engine_fluid
})

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
        TextButton(onClick = { onPick(null, null) }) { Text(stringResource(R.string.as_loaded)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.patch_of, engineLabel(e))) },
        text = {
            Column(Modifier.height(420.dp)) {
                OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
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
