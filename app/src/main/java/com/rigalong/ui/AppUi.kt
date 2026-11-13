package com.rigalong.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rigalong.R
import com.rigalong.model.Library
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.rigalong.play.SongPlayer
import com.rigalong.play.EngineHost

/*
 * The app's screen: the top bar and its four views -- the stage, the songs
 * (setlists and the song editor), the library and the setup -- over the rack
 * (model/Rack.kt). Dark, large targets (S17.1); from the stage the other views
 * open only on a long press (the edit lock, S17.3).
 */

@Composable
fun AppUi(player: SongPlayer, lib: Library, host: EngineHost) {
    var screen by remember { mutableIntStateOf(0) }
    var health by remember { mutableStateOf("") }
    // The song open in Songs outlives the screen -- and the app: unsaved edits are kept on disk.
    val songsEdit = remember { SongsEdit().also { it.restore(lib) } }
    LaunchedEffect(songsEdit.editing, songsEdit.editName) {
        delay(400)
        withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { songsEdit.persist(lib) } }
    }
    // The on-screen keys whenever there is no MIDI input to play from (S20.4), on every screen.
    var keys by remember { mutableStateOf(false) }
    // A keyboard to be driven directly, if the player says so: asked once, on whatever screen.
    var directOffer by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            health = runCatching { host.healthLine() }.getOrDefault("")
            player.diagnostics?.tick(host)
            if (n++ % 2 == 0) keys = runCatching { !host.routing().midiIn }.getOrDefault(true)
            directOffer = runCatching { host.directOffer() }.getOrNull()
            delay(500)
        }
    }
    LaunchedEffect(screen) {
        player.onStage = screen == 0
        // The editor's song is heard only while the editor is open.
        if (screen != 1) player.stopHearing()
    }
    LaunchedEffect(Unit) { player.backToStage = { screen = 0 } }
    LaunchedEffect(Unit) { player.showSongs = { screen = 1 } }
    LaunchedEffect(Unit) { player.watchControllers() }
    LaunchedEffect(Unit) {
        while (true) {
            player.pollSelections()
            delay(50)
        }
    }
    RigAlongTheme {
        directOffer?.let { name ->
            androidx.compose.material3.AlertDialog(onDismissRequest = {},
                title = { androidx.compose.material3.Text(stringResource(R.string.direct_offer_title, name)) },
                text = { androidx.compose.material3.Text(stringResource(R.string.direct_offer_text)) },
                confirmButton = {
                    androidx.compose.material3.Button(onClick = { host.answerDirectOffer(true); directOffer = null }) {
                        androidx.compose.material3.Text(stringResource(R.string.direct_offer_yes))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { host.answerDirectOffer(false); directOffer = null }) {
                        androidx.compose.material3.Text(stringResource(R.string.direct_offer_no))
                    }
                })
        }
        // Edge to edge is the rule at target SDK 36: keep clear of the status
        // and navigation bars, and of a display cutout, ourselves.
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            TopBar(player, health, screen, songsUnsaved = songsEdit.unsaved, onScreen = { screen = it })
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f)) {
                when (screen) {
                    0 -> StageScreen(player, host)
                    1 -> SongsScreen(player, lib, host, songsEdit)
                    2 -> LibraryScreen(player, lib, host, onOpened = { screen = 0 })
                    else -> SetupScreen(player, host, lib)
                }
            }
            if (keys) {
                Spacer(Modifier.height(8.dp))
                TestKeys()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(player: SongPlayer, health: String, screen: Int, songsUnsaved: Boolean, onScreen: (Int) -> Unit) {
    // EDIT LOCK (S17.3): from the stage, the editors open only on a long
    // press, so a stray tap mid-song never leaves the stage.
    val locked = screen == 0
    val tabs: @Composable () -> Unit = {
        Tab(stringResource(R.string.tab_stage), screen == 0) { onScreen(0) }
        Tab(if (songsUnsaved) stringResource(R.string.tab_songs_unsaved) else stringResource(R.string.tab_songs), screen == 1, locked) { onScreen(1) }
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
                panic()
            }
        } else {
            // A phone, upright: the tabs on a row of their own, and PANIC always on screen beside the status.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    tabs()
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
internal fun Tab(label: String, selected: Boolean, longPressOnly: Boolean = false, scale: Float = 1f, onClick: () -> Unit) {
    val press = if (longPressOnly) Modifier.combinedClickable(onClick = {}, onLongClick = onClick)
    else Modifier.clickable(onClick = onClick)
    Box(Modifier.background(if (selected) Accent.copy(alpha = 0.25f) else Panel, RoundedCornerShape(8.dp))
        .then(press).padding(horizontal = (18f * scale).dp, vertical = (12f * scale).dp)) {
        Text(label, color = if (selected) Accent else Color.White, fontSize = (16f * scale).sp, fontWeight = FontWeight.SemiBold)
    }
}
