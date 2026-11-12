package com.rigalong.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rigalong.NativeBridge
import com.rigalong.R
import com.rigalong.model.RackEngine
import kotlinx.coroutines.delay
import com.rigalong.play.SongPlayer
import com.rigalong.play.EngineHost

/* The stage: the song, its live sets, the levels, setBfree's drawbars, the master and the tempo. */

@Composable
internal fun StageScreen(player: SongPlayer, host: EngineHost) {
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

    // The setlist, to go to any song of it: opened by the song's name.
    var songList by remember { mutableStateOf(false) }
    if (songList) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { songList = false },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { songList = false }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(player.setlistName) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    player.songNames.forEachIndexed { k, name ->
                        val here = k == player.songIndex
                        Text("${k + 1}.  $name", fontSize = 20.sp, color = if (here) Accent else Color.Unspecified,
                            fontWeight = if (here) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable {
                                songList = false
                                if (!here) player.selectSong(k)
                            }.padding(vertical = 14.dp, horizontal = 4.dp))
                    }
                }
            })
    }
    val songRow = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BigButton("◀", enabled = player.songIndex > 0, width = 88, scale = s) { player.previousSong() }
            Column(Modifier.weight(1f).clickable(enabled = player.songNames.size > 1) { songList = true }.padding(horizontal = d(12f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(player.songNames.getOrNull(player.songIndex) ?: "—", fontSize = (30f * s).sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (player.songNames.size > 1) Text("  ▾", color = Dim, fontSize = (22f * s).sp)
                }
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
                            .border(2.dp, if (sel) Accent else Line, RoundedCornerShape(d(12f)))
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

/** What [StagePanels] needs to put every panel in one row (dp), the drawbars squeezed. */
private fun stagePanelsMinWidth(player: SongPlayer, s: Float): Float {
    val sounds = player.rack?.instances.orEmpty()
    val organ = sounds.any { it.engine == RackEngine.SETBFREE }
    val count = sounds.size + (if (organ) 1 else 0) + 1
    return sounds.size * LEVEL_MIN * s + (if (organ) organSqueezed(s) else 0f) + MASTER_MIN * s +
        16f * s * (count - 1)
}

private const val LEVEL_MIN = 110f
private const val MASTER_MIN = 90f

/** setBfree's panel with drawbars [bar] wide (and 8, scaled, apart). */
private fun organWidth(bar: Float, s: Float) = 9 * bar + 8 * 8f * s + 2 * 10f * s

/** setBfree's panel with its drawbars [DRAWBAR_SQUEEZE_DP] apart, gaps and all. */
private fun organSqueezed(s: Float) = 9 * DRAWBAR_SQUEEZE_DP + 2 * 10f * s

/**
 * One panel below the live sets, with the widths it may take (dp): [minW] is
 * what it is packed at, [prefW] what it grows to with room to spare, and
 * [squeezeW] how far it may still give way alone in a row before that scrolls.
 */
private class StagePanel(val minW: Float, val prefW: Float, val squeezeW: Float = minW,
                         val content: @Composable (Modifier) -> Unit)

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
            // The level first: a narrow panel cuts off the word, which the name's colour says too.
            Text("%.0f %%".format(level * 100) + "  ·  " +
                stringResource(if (plays) R.string.engine_playing else R.string.engine_silent),
                fontSize = (13f * s).sp, color = Dim, maxLines = 1, overflow = TextOverflow.Clip)
            Spacer(Modifier.height((6f * s).dp))
            // Moves only by dragging, never by a touch (S17.4).
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                RelativeFader(level, { player.setLevel(inst.id, it) },
                    Modifier.heightIn(max = FADER_MAX).fillMaxHeight().fillMaxWidth())
            }
        }
    }
    if (sounds.any { it.engine == RackEngine.SETBFREE }) {
        // Nine drawbars, each a fingertip wide and 72 (scaled) at most; on a phone,
        // down to [DRAWBAR_SQUEEZE_DP] apart rather than scrolling or taking a row more.
        panels += StagePanel(organWidth(DRAWBAR_MIN_DP, s), organWidth(maxOf(DRAWBAR_MIN_DP, 72f * s), s),
            organSqueezed(s)) { m ->
            SetBfreePanel(player, s, m)
        }
    }
    val master = StagePanel(MASTER_MIN * s, 110f * s) { m -> MasterFader(host, s, m) }
    panels += master

    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        // First fit, in order: each panel in the first row that still has room at its least width --
        // or at what it may give way to, where that saves a row (setBfree beside the levels, a phone on its side).
        fun pack(least: (StagePanel) -> Float): List<MutableList<StagePanel>> {
            val rows = ArrayList<MutableList<StagePanel>>()
            for (p in panels) {
                val row = rows.firstOrNull { r -> r.sumOf { least(it).toDouble() } + gap * r.size + least(p) <= w }
                if (row != null) row += p else rows += mutableListOf(p)
            }
            return rows
        }
        val rows = pack { it.minW }.let { loose -> pack { it.squeezeW }.takeIf { it.size < loose.size } ?: loose }
        // A panel never taller than its fader's comfortable stroke and the text above it.
        val maxRowH = FADER_MAX.value + 70f * s + 2 * pad + 40f * s
        val rowH = ((maxHeight.value - gap * (rows.size - 1)) / rows.size).coerceAtMost(maxRowH)
        Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
            for (row in rows) {
                // The spare width goes to the panels up to their preferred widths, shared as they need it;
                // a shortfall is taken from what they may give way, as far as they can.
                val spare = w - gap * (row.size - 1) - row.sumOf { it.minW.toDouble() }.toFloat()
                val want = row.sumOf { (it.prefW - it.minW).toDouble() }.toFloat()
                val give = row.sumOf { (it.minW - it.squeezeW).toDouble() }.toFloat()
                fun width(p: StagePanel) = when {
                    spare >= 0f -> p.minW + (p.prefW - p.minW) * (if (want > 0f) minOf(1f, spare / want) else 0f)
                    else -> p.minW - (p.minW - p.squeezeW) * (if (give > 0f) minOf(1f, -spare / give) else 0f)
                }
                // Only a row wider than the screen even so scrolls.
                val over = spare < -give
                Row(Modifier.fillMaxWidth().height(rowH.dp).then(if (over) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                    for (p in row) {
                        // The master keeps the right-hand end of its row.
                        if (p === master && row.size > 1 && !over) Spacer(Modifier.weight(1f))
                        p.content(Modifier.width(width(p).dp).fillMaxHeight())
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

/**
 * ...except where nine of those take a scrolling row (a phone held upright) or
 * a row more (beside the levels, a phone on its side): then down to 36 apart
 * (5.7 mm), the whole column touchable. Measured on sw411 upright against the
 * alternatives: two rows of drawbars halve the stroke (to about 60 dp), and
 * every fader on its side leaves twelve lanes of about 38 dp; one squeezed row
 * keeps 41 dp apart and the full stroke.
 */
private const val DRAWBAR_SQUEEZE_DP = 36f

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
        // The nine share the panel's width; each column, gap and all, takes the touch
        // (the fader draws its own margin), so squeezed they lose no target.
        Row(Modifier.weight(1f).fillMaxWidth()) {
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
        .border(2.dp, if (selected) Accent else Line, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = (fontSize * scale).sp, color = if (enabled) Color.White else Color(0xFF3A3F48), maxLines = 1)
    }
}

/**
 * The tempo in force and its metre, a visual beat (I12.2), tap tempo (S12.3,
 * I12.3), and the metronome's start and stop (S12.6). A tap on the tempo opens
 * [TempoDialog]: typed, none, back to the stored one or saved, and the click's
 * options. While the click runs (or its beat goes on visually) the beat shows
 * its number in the bar, in step with what is heard.
 */
@Composable
private fun TempoBox(player: SongPlayer, s: Float = 1f) {
    var dialog by remember { mutableStateOf(false) }
    if (dialog) TempoDialog(player) { dialog = false }
    var now by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) { now = System.nanoTime(); delay(16) } }
    val bpm = player.bpm
    val beatNs = bpm?.let { (60e9 / it).toLong() }
    val phase = if (beatNs != null && beatNs > 0) ((now - player.beatOrigin).mod(beatNs)).toDouble() / beatNs else 1.0
    // The beat blinks only while the metronome runs (S12.6).
    val lit = player.clickRunning && now >= player.beatOrigin && phase < 0.15
    val count = player.clickBeat(now)
    // One framed group, so its start button is never taken for the next song's ▶ beside it.
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.border(1.dp, Line, RoundedCornerShape((12f * s).dp)).padding((6f * s).dp)) {
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
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { dialog = true }.padding(horizontal = (6f * s).dp, vertical = (4f * s).dp)) {
            Text(bpm?.let { bpmText(it) + " BPM" } ?: stringResource(R.string.no_tempo), fontSize = (18f * s).sp,
                color = if (player.bpmTapped) Warn else Color.White)
            Text(player.tempo?.metre ?: "", fontSize = (13f * s).sp, color = Dim)
        }
        Spacer(Modifier.width((8f * s).dp))
        BigButton(stringResource(R.string.tap), enabled = true, width = 88, scale = s) { player.tap() }
        Spacer(Modifier.width((8f * s).dp))
        // Play / Stop, and Count-in (S12.6); what they sound and show is the setup's.
        BigButton(stringResource(if (player.clickRunning) R.string.click_stop else R.string.metronome_start),
            enabled = bpm != null || player.clickRunning, width = 104, selected = player.clickRunning,
            fontSize = 22, scale = s) { player.toggleClick() }
        Spacer(Modifier.width((8f * s).dp))
        BigButton(stringResource(R.string.count_in), enabled = bpm != null, width = 104, selected = player.countingIn,
            fontSize = 22, scale = s) { player.countIn() }
    }
}
