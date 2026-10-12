package com.rigalong.ui

import com.rigalong.model.Library
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
import com.rigalong.model.Entry
import com.rigalong.model.getTyped
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.heightIn
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
import com.rigalong.NativeBridge
import androidx.compose.ui.res.stringResource
import com.rigalong.R
import com.rigalong.model.Digest
import com.rigalong.model.RackEngine
import com.rigalong.model.RackLiveSet
import com.rigalong.model.RackPart
import com.rigalong.model.RackSlot
import com.rigalong.model.RackSong
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
internal val Accent = Color(0xFF3FB68B)
internal val Warn = Color(0xFFE0A030)
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
    // EDIT LOCK (S17.3): from the stage, the editors open only on a long
    // press, so a stray tap mid-song never leaves the stage.
    val locked = screen == 0
    val tabs: @Composable () -> Unit = {
        Tab(stringResource(R.string.tab_stage), screen == 0) { onScreen(0) }
        Tab(stringResource(R.string.tab_songs), screen == 1, locked) { onScreen(1) }
        Tab(stringResource(R.string.tab_library), screen == 2, locked) { onScreen(2) }
        Tab(stringResource(R.string.tab_setup), screen == 3, locked) { onScreen(3) }
    }
    // The health line opens the figures (S19.3): Setup, where they are -- on
    // a long press from the stage, like the tabs.
    val status: @Composable (Modifier) -> Unit = { m ->
        Column(m.combinedClickable(onClick = { if (!locked) onScreen(3) }, onLongClick = { onScreen(3) })) {
            Text(player.setlistName.ifEmpty { stringResource(R.string.no_setlist) }, color = Dim, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(health, color = if (health.startsWith("!")) Warn else Dim, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
    val panic: @Composable () -> Unit = {
        Button(onClick = player::panic, colors = ButtonDefaults.buttonColors(containerColor = Danger),
            modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.panic), fontWeight = FontWeight.Bold, color = Color.White) }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()) {
                tabs()
                if (locked) Text(stringResource(R.string.hold_to_edit), color = Dim, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                status(Modifier.weight(1f))
                Tab(stringResource(R.string.tab_prototype), false, locked) { onPrototype() }
                panic()
            }
        } else {
            // A phone, upright: the tabs on a row of their own, and PANIC always on screen beside the status.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    tabs()
                    Tab(stringResource(R.string.tab_prototype), false, locked) { onPrototype() }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    status(Modifier.weight(1f))
                    if (locked) Text(stringResource(R.string.hold_to_edit), color = Dim, fontSize = 11.sp)
                    panic()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tab(label: String, selected: Boolean, longPressOnly: Boolean = false, scale: Float = 1f, onClick: () -> Unit) {
    val press = if (longPressOnly) Modifier.combinedClickable(onClick = {}, onLongClick = onClick)
    else Modifier.clickable(onClick = onClick)
    Box(Modifier.background(if (selected) Accent.copy(alpha = 0.25f) else Panel, RoundedCornerShape(8.dp))
        .then(press).padding(horizontal = (18f * scale).dp, vertical = (12f * scale).dp)) {
        Text(label, color = if (selected) Accent else Color.White, fontSize = (16f * scale).sp, fontWeight = FontWeight.SemiBold)
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

/**
 * The stage's controls, sized for the device rather than at fixed sizes:
 * everything fits on any screen, a phone on its side included, and a tablet
 * gets big targets. One scale [s] belongs to the device -- its smallest
 * width (Android's "sw"), so turning it rearranges but never resizes:
 * sw640 is 1, from 0.55 up to 1.3. It is held down only where the stage
 * would not fit (the height, or the song row's width). Live sets are buttons
 * of bounded size, wrapping; the levels are panels of bounded size, in as
 * many rows as the width needs ([StagePanels]). Short and wide (a phone on
 * its side), the song and live sets take a column at the left and the levels
 * get the full height beside them -- when they fit there in one row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StageControls(player: SongPlayer, host: EngineHost, modifier: Modifier) {
    BoxWithConstraints(modifier) {
    val w = maxWidth.value
    val h = maxHeight.value
    val device = (androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp / 640f).coerceIn(0.55f, 1.3f)
    val fit = minOf(device, h / 450f)
    val sideLeft = 420f * fit
    val side = h < 420f && w >= 1.5f * h && stagePanelsMinWidth(player, fit) <= w - sideLeft - 16f * fit
    // The tempo gets a row of its own where the song row would not hold it.
    val narrow = side || w < 760f * fit
    val s = if (side) fit else minOf(fit, w / if (narrow) 420f else 760f).coerceAtLeast(0.5f)
    fun d(v: Float) = (v * s).dp

    val songRow = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BigButton("◀", enabled = player.songIndex > 0, width = 88, scale = s) { player.previousSong() }
            Column(Modifier.weight(1f).padding(horizontal = d(12f))) {
                Text(player.songNames.getOrNull(player.songIndex) ?: "—", fontSize = (30f * s).sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val next = player.songNames.getOrNull(player.songIndex + 1)
                val sel = (if (player.lastSelection.isEmpty()) "" else stringResource(R.string.from_keys, player.lastSelection)) +
                    player.devices.status.let { if (it.isEmpty()) "" else "   ·   $it" }
                Text((if (next != null) stringResource(R.string.next_song, next) else stringResource(R.string.last_song)) + sel,
                    color = Dim, fontSize = (15f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!narrow) {
                TempoBox(player, s)
                Spacer(Modifier.width(d(10f)))
            }
            BigButton("▶", enabled = player.songIndex + 1 < player.songNames.size, width = 88, scale = s) { player.nextSong() }
        }
        if (narrow) {
            Spacer(Modifier.height(d(8f)))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { TempoBox(player, s) }
        }
    }
    // Live sets: buttons 150 to 260 (scaled) wide, as many to a row as fit in [width], wrapping.
    val liveSets = @Composable { width: Float ->
        val names = player.liveSetNames
        val gapLs = 10f * s
        val columns = ((width + gapLs) / (150f * s + gapLs)).toInt().coerceIn(1, maxOf(1, names.size))
        val buttonW = ((width - gapLs * (columns - 1)) / columns).coerceAtMost(260f * s)
        Column(verticalArrangement = Arrangement.spacedBy(gapLs.dp)) {
            for (row in names.indices.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gapLs.dp)) {
                    for (i in row) {
                        val sel = i == player.liveSetIndex
                        Box(Modifier.size(width = buttonW.dp, height = d(84f))
                            .background(if (sel) Accent else Panel, RoundedCornerShape(d(12f)))
                            .border(2.dp, if (sel) Accent else Color(0xFF2A2E36), RoundedCornerShape(d(12f)))
                            // A tap selects it; a long press edits it (quick edit from the stage).
                            .combinedClickable(onClick = { player.selectLiveSet(i) }, onLongClick = { player.requestEdit(liveSet = i) })
                            .padding(d(8f)),
                            contentAlignment = Alignment.Center) {
                            Text(names[i], color = if (sel) Color.Black else Color.White, fontSize = (20f * s).sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    val levelsHeader = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.levels), color = Dim, fontSize = (14f * s).sp, modifier = Modifier.weight(1f))
            Tab(stringResource(R.string.store_levels), false, longPressOnly = true, scale = s) { player.storeInLiveSet() }
        }
        Spacer(Modifier.height(d(6f)))
    }

    if (side) {
        Row(Modifier.fillMaxSize()) {
            // Many live sets may not fit the column's height: it scrolls, the levels never do.
            Column(Modifier.width(sideLeft.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                songRow()
                Spacer(Modifier.height(d(10f)))
                liveSets(sideLeft)
            }
            Spacer(Modifier.width(d(16f)))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                levelsHeader()
                StagePanels(player, host, s, Modifier.fillMaxWidth().weight(1f))
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            songRow()
            Spacer(Modifier.height(d(12f)))
            liveSets(w)
            Spacer(Modifier.height(d(12f)))
            levelsHeader()
            StagePanels(player, host, s, Modifier.fillMaxWidth().weight(1f))
        }
    }
    }
}

/** What [StagePanels] needs to put every panel in one row (dp). */
private fun stagePanelsMinWidth(player: SongPlayer, s: Float): Float {
    val sounds = player.rack?.instances.orEmpty()
    val organ = sounds.any { it.engine == RackEngine.SETBFREE }
    val count = sounds.size + (if (organ) 1 else 0) + 1
    return sounds.size * LEVEL_MIN * s + (if (organ) organWidth(DRAWBAR_MIN_DP, s) else 0f) + MASTER_MIN * s +
        16f * s * (count - 1)
}

private const val LEVEL_MIN = 110f
private const val MASTER_MIN = 90f

/** setBfree's panel with drawbars [bar] wide. */
private fun organWidth(bar: Float, s: Float) = 9 * bar + 8 * 8f * s + 2 * 10f * s

/** One panel below the live sets, with the widths it may take (dp). */
private class StagePanel(val minW: Float, val prefW: Float, val content: @Composable (Modifier) -> Unit)

/**
 * The levels, setBfree's drawbars and the master, as panels of bounded size
 * (S18.4): each between its least and its preferred width, never taller than
 * a comfortable fader stroke allows. They are placed in order, each in the
 * first row with room, so a narrow screen gets more rows rather than squeezed
 * or stretched panels; a row's spare width goes to its panels up to their
 * preferred widths, and the master keeps the right-hand end of its row. A
 * panel wider than the screen (the drawbars, on a phone held upright) scrolls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StagePanels(player: SongPlayer, host: EngineHost, s: Float, modifier: Modifier) {
    val rackNow = player.rack
    val sounds = rackNow?.instances.orEmpty()
    val gap = 16f * s
    val pad = 10f * s
    val panels = ArrayList<StagePanel>()
    for (inst in sounds) panels += StagePanel(LEVEL_MIN * s, 160f * s) { m ->
        val plays = player.playing[inst.id] ?: false
        Column(m.background(Panel, RoundedCornerShape((10f * s).dp)).padding(pad.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // A long press on its name opens its patch in the editor.
            Text(rackNow!!.displayName(inst), fontWeight = FontWeight.SemiBold, color = if (plays) Accent else Dim,
                fontSize = (16f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { player.requestEdit(instance = inst.id) }))
            val level = player.levels[inst.id] ?: 0f
            Text(stringResource(if (plays) R.string.engine_playing else R.string.engine_silent) +
                "  ·  " + "%.0f %%".format(level * 100), fontSize = (13f * s).sp, color = Dim, maxLines = 1)
            Spacer(Modifier.height((6f * s).dp))
            // Moves only by dragging, never by a touch (S17.4).
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                RelativeFader(level, { player.setLevel(inst.id, it) },
                    Modifier.heightIn(max = FADER_MAX).fillMaxHeight().fillMaxWidth())
            }
        }
    }
    if (sounds.any { it.engine == RackEngine.SETBFREE }) {
        // Nine drawbars, each a fingertip wide at least and 72 (scaled) at most.
        panels += StagePanel(organWidth(DRAWBAR_MIN_DP, s), organWidth(maxOf(DRAWBAR_MIN_DP, 72f * s), s)) { m ->
            SetBfreePanel(player, s, m)
        }
    }
    val master = StagePanel(MASTER_MIN * s, 110f * s) { m -> MasterFader(host, s, m) }
    panels += master

    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        // First fit, in order: each panel in the first row that still has room at least widths.
        val rows = ArrayList<MutableList<StagePanel>>()
        for (p in panels) {
            val row = rows.firstOrNull { r -> r.sumOf { it.minW.toDouble() } + gap * r.size + p.minW <= w }
            if (row != null) row += p else rows += mutableListOf(p)
        }
        // A panel never taller than its fader's comfortable stroke and the text above it.
        val maxRowH = FADER_MAX.value + 70f * s + 2 * pad + 40f * s
        val rowH = ((maxHeight.value - gap * (rows.size - 1)) / rows.size).coerceAtMost(maxRowH)
        Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
            for (row in rows) {
                // The spare width goes to the panels up to their preferred widths, shared as they need it.
                val spare = (w - gap * (row.size - 1) - row.sumOf { it.minW.toDouble() }.toFloat()).coerceAtLeast(0f)
                val want = row.sumOf { (it.prefW - it.minW).toDouble() }.toFloat()
                val share = if (want > 0f) minOf(1f, spare / want) else 0f
                // Only a row wider than the screen (one panel too wide for it) scrolls.
                val over = row.sumOf { it.minW.toDouble() } + gap * (row.size - 1) > w
                Row(Modifier.fillMaxWidth().height(rowH.dp).then(if (over) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                    for (p in row) {
                        // The master keeps the right-hand end of its row.
                        if (p === master && row.size > 1 && !over) Spacer(Modifier.weight(1f))
                        p.content(Modifier.width((p.minW + (p.prefW - p.minW) * share).dp).fillMaxHeight())
                    }
                }
            }
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
private fun MasterFader(host: EngineHost, s: Float, modifier: Modifier) {
    var db by remember { mutableStateOf(host.masterDb()) }
    val min = NativeBridge.MASTER_MIN_DB
    val max = NativeBridge.MASTER_MAX_DB
    Column(modifier.background(Panel, RoundedCornerShape((10f * s).dp)).padding((10f * s).dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.master), fontWeight = FontWeight.SemiBold, fontSize = (16f * s).sp,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { host.setMasterDb(0f); db = 0f }))
        Text("%+.1f dB".format(db), fontSize = (14f * s).sp, color = Dim, maxLines = 1)
        Spacer(Modifier.height((6f * s).dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            RelativeFader((db - min) / (max - min), { f ->
                val v = Math.round((min + f * (max - min)) * 2f) / 2f
                host.setMasterDb(v); db = v
            }, Modifier.heightIn(max = FADER_MAX).fillMaxHeight().fillMaxWidth(), color = Warn)
        }
    }
}

/** The longest a stage fader gets: a comfortable full stroke (6-7 cm), fine enough that a finger's jitter does not tell. */
private val FADER_MAX = 400.dp

/** The narrowest a drawbar gets: a fingertip (Material's smallest touch target). */
private const val DRAWBAR_MIN_DP = 48f

private val DRAWBAR_LABELS = listOf("16'", "5⅓'", "8'", "4'", "2⅔'", "2'", "1⅗'", "1⅓'", "1'")
private val DRAWBAR_COLOURS = listOf(Color(0xFF8B4A2B), Color(0xFF8B4A2B), Color(0xFFE6E2D6), Color(0xFFE6E2D6),
    Color(0xFF2B2B2B), Color(0xFFE6E2D6), Color(0xFF2B2B2B), Color(0xFF2B2B2B), Color(0xFFE6E2D6))

/** setBfree's upper drawbars, Leslie and percussion, on the stage (S6.1's example). */
@Composable
private fun SetBfreePanel(player: SongPlayer, s: Float, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape((10f * s).dp)).padding((10f * s).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8f * s).dp)) {
            // Which setBfree, where the song has several: the one playing in this live set.
            val r = player.rack
            val shown = player.setBfreeId?.let { r?.instance(it) }
            Text(if (r != null && shown != null && r.instances.count { it.engine == RackEngine.SETBFREE } > 1)
                r.displayName(shown) else stringResource(R.string.engine_setbfree), fontWeight = FontWeight.SemiBold,
                fontSize = (16f * s).sp)
            val leslie = player.setBfree[SongPlayer.SETBFREE_LESLIE] ?: 1f
            for ((v, label) in listOf(0f to stringResource(R.string.leslie_stop), 1f to stringResource(R.string.leslie_slow),
                    2f to stringResource(R.string.leslie_fast))) {
                Box(Modifier.background(if (leslie == v) Accent else Bg, RoundedCornerShape(8.dp))
                    .clickable { player.setSetBfree(SongPlayer.SETBFREE_LESLIE, v) }
                    .padding(horizontal = (14f * s).dp, vertical = (8f * s).dp)) {
                    Text(label, color = if (leslie == v) Color.Black else Color.White, fontSize = (14f * s).sp)
                }
            }
            val perc = (player.setBfree[SongPlayer.SETBFREE_PERC] ?: 0f) > 0.5f
            Box(Modifier.background(if (perc) Accent else Bg, RoundedCornerShape(8.dp))
                .clickable { player.setSetBfree(SongPlayer.SETBFREE_PERC, if (perc) 0f else 1f) }
                .padding(horizontal = (14f * s).dp, vertical = (8f * s).dp)) {
                Text(stringResource(R.string.percussion), color = if (perc) Color.Black else Color.White, fontSize = (14f * s).sp)
            }
        }
        Spacer(Modifier.height((8f * s).dp))
        // The nine share the panel's width; each column, gap and all, takes the touch.
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((8f * s).dp)) {
            for (i in 0..8) {
                val v = (player.setBfree[i] ?: 0f) / 8f
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(DRAWBAR_LABELS[i], fontSize = (13f * s).sp, color = Dim)
                    // Whole steps, as on the instrument: 0..8.
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RelativeFader(v, { player.setSetBfree(i, Math.round(it * 8f).toFloat()) },
                            Modifier.heightIn(max = FADER_MAX).fillMaxHeight().fillMaxWidth(),
                            color = DRAWBAR_COLOURS[i])
                    }
                    Text(((player.setBfree[i] ?: 0f).toInt()).toString(), fontSize = (13f * s).sp)
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, width: Int, selected: Boolean = false,
                      fontSize: Int = 30, scale: Float = 1f, onClick: () -> Unit) {
    Box(Modifier.size(width = (width * scale).dp, height = (72 * scale).dp)
        .background(if (enabled) Panel else Bg, RoundedCornerShape(12.dp))
        .border(2.dp, if (selected) Accent else Color(0xFF2A2E36), RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = (fontSize * scale).sp, color = if (enabled) Color.White else Color(0xFF3A3F48), maxLines = 1)
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
    // The song as it was opened: what its slots' library patches were before editing.
    var original by remember { mutableStateOf<RackSong?>(null) }
    var originalName by remember { mutableStateOf("") }
    var decisions by remember { mutableStateOf<List<PatchDecision>>(emptyList()) }
    val newSongName = stringResource(R.string.new_song)
    fun open(id: Digest) {
        selected = id
        val e = lib.store.entry(id)
        val song = lib.songs().firstOrNull { it.entry.id == id }?.song
        val rack = song?.let { lib.rackSong(it) }
        if (e != null && rack != null) { editing = id to rack; editName = e.version.name; original = rack; originalName = editName }
    }
    fun save(moves: Map<Digest, Pair<com.rigalong.model.Patch, Collection<Digest>>>) {
        val ed = editing ?: return
        val id = lib.inOneChange("song", editName) {
            val id = lib.saveSongAndPatches(ed.first, editName, ed.second, moves)
            if (ed.first == null && setlistId != null) {
                val ids = lib.songsOf(setlistId).mapNotNull { it.first?.id } + id
                lib.saveSetlist(setlistId, player.setlistName, ids)
            }
            id
        }
        editing = id to ed.second
        original = ed.second
        originalName = editName
        selected = id
        revision++
        player.reload()
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
                    original = null
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
                Column(Modifier.fillMaxSize()) {
                    // Undo: the last action, whole (a save that moved patches, songs and setlists).
                    val last = remember(revision) { lib.lastChange() }
                    if (last != null) {
                        TextButton(onClick = {
                            lib.undo()
                            revision++
                            ed.first?.let { open(it) }
                            player.reload()
                        }) { Text(stringResource(R.string.undo_named, last.name)) }
                    }
                    SongEditor(ed.second, editName, host, player, lib, focus?.takeIf { it.song == ed.first },
                        unsaved = ed.first == null || ed.second != original || editName != originalName,
                        onName = { editName = it },
                        onChange = { editing = ed.first to it },
                        onSave = {
                            // A changed library patch: everywhere, only here, or chosen songs (I8.5, I8.13).
                            val d = patchDecisions(lib, original, ed.second, ed.first)
                            if (d.isEmpty()) save(emptyMap()) else decisions = d
                        })
                }
            }
        }
        if (decisions.isNotEmpty()) {
            PatchSaveDialog(decisions, onSave = { moves, only ->
                decisions = emptyList()
                // "Only in this song": the slot lets go of the library patch and keeps its own.
                if (only.isNotEmpty()) editing = editing?.let { (id, r) ->
                    id to r.copy(instances = r.instances.map {
                        if (it.slot.libraryPatch in only) it.copy(slot = it.slot.copy(libraryPatch = null)) else it
                    })
                }
                save(moves)
            },
                onDismiss = { decisions = emptyList() })
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongEditor(song: RackSong, name: String, host: EngineHost, player: SongPlayer, lib: Library,
                       focus: SongPlayer.EditRequest?, unsaved: Boolean, onName: (String) -> Unit,
                       onChange: (RackSong) -> Unit, onSave: () -> Unit) {
    // Instance ids: which sound's preset list, patch or effect is open.
    var picker by remember { mutableStateOf<String?>(null) }
    // The sound being edited, and the live set it is edited for (null: the whole song).
    var soundEditor by remember { mutableStateOf<Pair<String, Int?>?>(null) }
    LaunchedEffect(focus) { focus?.instance?.let { if (song.instance(it) != null) soundEditor = it to focus.liveSet } }
    var reverbEditor by remember { mutableStateOf(false) }
    var fxEditor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
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
            // A device's song sounds as its own file (a CP-88 page as .X9P), for its USB stick.
            for (d in player.devices.withSounds()) if (song.liveSets.any { it.devices[d.name]?.mode == "own" }) {
                Spacer(Modifier.width(12.dp))
                DeviceAreaFileButton(song, name, d, player)
            }
            Spacer(Modifier.width(12.dp))
            // "Save •" while there is something to save: a library patch picked or
            // saved in a dialog belongs to the song only once the song is saved.
            Button(onClick = onSave, modifier = Modifier.height(56.dp)) {
                Text(stringResource(if (unsaved) R.string.save_unsaved else R.string.save))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = song.tempo != null, onCheckedChange = { on ->
                onChange(song.copy(tempo = if (on) com.rigalong.model.Tempo(bpm = 120.0) else null))
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
        // The song's sounds: instances, first class -- several of one engine if it wants (two sfizz instances).
        Text(stringResource(R.string.engines), fontWeight = FontWeight.SemiBold)
        for (inst in song.instances) {
            val slot = inst.slot
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Its name: tap to rename ("Bright", "Pad").
                Text(song.displayName(inst), color = if (inst.label != null) Accent else Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp).clickable { renaming = inst.id }.padding(vertical = 8.dp))
                OutlinedButton(onClick = { picker = inst.id }, modifier = Modifier.weight(1f)) {
                    Text(slot.preset ?: stringResource(R.string.as_loaded_paren), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { soundEditor = inst.id to null }) {
                    Text(stringResource(R.string.edit_patch) + if (slot.params.isNotEmpty()) " ●" else "")
                }
                TextButton(onClick = { fxEditor = inst.id }) {
                    Text(slot.fx?.let { stringResource(fxLabel(it.type)) } ?: stringResource(R.string.fx_button))
                }
                Text(stringResource(R.string.level), color = Dim)
                Slider(value = slot.level.toFloat(), onValueChange = { v ->
                    onChange(song.withSlot(inst.id, slot.copy(level = v.toDouble())))
                }, modifier = Modifier.width(160.dp))
                TextButton(onClick = { onChange(song.minus(inst.id)) }) { Text("✕", color = Danger) }
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
            LiveSetCard(song, i, ls, player, lib, focused = focus?.liveSet == i, onChange,
                onSound = { id -> soundEditor = id to i })
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
            PresetPicker(inst.engine, host, lib, onPick = { preset, program ->
                // Still linked: a linked patch given another sound is a change to it, asked about at Save.
                onChange(song.withSlot(id, inst.slot.copy(preset = preset, program = program)))
                picker = null
            }, onPatch = { entry, p ->
                onChange(song.withSlot(id, inst.slot.withPatch(p, entry)))
                picker = null
            }, onDismiss = { picker = null })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveSetCard(song: RackSong, i: Int, ls: RackLiveSet, player: SongPlayer, lib: Library, focused: Boolean,
                        onChange: (RackSong) -> Unit, onSound: (String) -> Unit) {
    fun put(n: RackLiveSet) = onChange(song.copy(liveSets = song.liveSets.toMutableList().also { it[i] = n }))
    val copyName = stringResource(R.string.copy_of, ls.name)
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
                NumberField(stringResource(R.string.key_low), p.keyLow, 0..127, noteName = true) { putPart(p.copy(keyLow = it)) }
                NumberField(stringResource(R.string.key_high), p.keyHigh, 0..127, noteName = true) { putPart(p.copy(keyHigh = it)) }
                NumberField(stringResource(R.string.transpose_short), p.transpose, -48..48) { putPart(p.copy(transpose = it)) }
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

/**
 * The song's tempo, a visual beat (I12.2), tap tempo and saving a tapped one
 * (S12.3, I12.3), and the click: a count-in, or on and off (S12.2). While the
 * click runs the beat shows its number in the bar, in step with what is heard.
 */
@Composable
private fun TempoBox(player: SongPlayer, s: Float = 1f) {
    var now by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) { now = System.nanoTime(); delay(16) } }
    val bpm = player.bpm
    val beatNs = bpm?.let { (60e9 / it).toLong() }
    val phase = if (beatNs != null && beatNs > 0) ((now - player.beatOrigin).mod(beatNs)).toDouble() / beatNs else 1.0
    val lit = phase < 0.15
    val count = player.clickBeat(now)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (count > 0) {
            Box(Modifier.size((44f * s).dp).background(if (lit) Accent else Panel, RoundedCornerShape((22f * s).dp)),
                contentAlignment = Alignment.Center) {
                Text(count.toString(), fontSize = (24f * s).sp, fontWeight = FontWeight.Bold,
                    color = if (lit) Color.Black else Color.White)
            }
        } else {
            Box(Modifier.size((22f * s).dp).background(if (lit) Accent else Panel, RoundedCornerShape((11f * s).dp)))
        }
        Spacer(Modifier.width((8f * s).dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bpm?.let { stringResource(R.string.bpm, it) } ?: stringResource(R.string.no_tempo), fontSize = (18f * s).sp)
            if (player.bpmTapped) TextButton(onClick = player::saveTempo) { Text(stringResource(R.string.save_tempo)) }
        }
        Spacer(Modifier.width((8f * s).dp))
        BigButton(stringResource(R.string.tap), enabled = true, width = 88, scale = s) { player.tap() }
        Spacer(Modifier.width((8f * s).dp))
        BigButton(stringResource(when {
            player.clickRunning -> R.string.click_stop
            player.clickContinuous -> R.string.click_on
            else -> R.string.count_in
        }), enabled = bpm != null, width = 104, selected = player.clickRunning,
            fontSize = 22, scale = s) { player.toggleClick() }
    }
}

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
private fun SoundEditor(song: RackSong, inst: com.rigalong.model.RackInstance, liveSet: Int?, player: SongPlayer,
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
private data class PatchDecision(val sound: String, val patch: Entry, val newContent: com.rigalong.model.Patch,
                                 val others: List<Entry>)

/** The library patches [now] changed (still linked, content edited), each with the other songs on its latest version. */
private fun patchDecisions(lib: Library, before: RackSong?, now: RackSong, songId: Digest?): List<PatchDecision> {
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
private fun PatchSaveDialog(decisions: List<PatchDecision>,
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
private fun ReverbEditor(song: RackSong, player: SongPlayer, onChange: (Map<String, Double>) -> Unit,
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
private fun FxEditor(title: String, song: RackSong, target: String?, current: com.rigalong.model.RackFx?,
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
    RackEngine.SETBFREE -> R.string.engine_setbfree
    RackEngine.SURGE -> R.string.engine_surge
    RackEngine.DEXED -> R.string.engine_dexed
    RackEngine.PLAITS -> R.string.engine_plaits
    RackEngine.FLUID -> R.string.engine_fluid
})

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun noteLabel(n: Int) = NOTE_NAMES[n % 12] + (n / 12 - 1)

@Composable
internal fun NumberField(label: String, value: Int, range: IntRange, noteName: Boolean = false, onValue: (Int) -> Unit) {
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
private fun PresetPicker(e: RackEngine, host: EngineHost, lib: Library, onPick: (String?, Int?) -> Unit,
                         onPatch: (Digest, com.rigalong.model.Patch) -> Unit, onDismiss: () -> Unit) {
    val choices = remember(e) { presetChoices(e, host) }
    val shared = remember(e) { lib.patches(e.key) }
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
                    // The library's own patches first: picking one links the slot to it (by content).
                    val mine = shared.filter { filter.isEmpty() || it.first.version.name.contains(filter, true) }
                    itemsIndexed(mine) { _, (entry, patch) ->
                        Row(Modifier.fillMaxWidth().clickable { onPatch(entry.id, patch) }.padding(vertical = 10.dp)) {
                            Text(entry.version.name, color = Accent, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.library_tag), color = Dim, fontSize = 12.sp)
                        }
                    }
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
        RackEngine.SETBFREE -> NativeBridge.nativeSetBfreePresetNames().map { it to it }
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
