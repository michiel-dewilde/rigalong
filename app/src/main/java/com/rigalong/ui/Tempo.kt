package com.rigalong.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rigalong.R
import com.rigalong.model.Tempo

/** Taps to a tempo: four or more less than 2 s apart give one, as the stage's TAP does. */
class TapTempo {
    private val taps = ArrayDeque<Long>()

    /** One tap; the tempo it gives, or null while there are too few. */
    fun tap(): Double? {
        val now = System.nanoTime()
        if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
        taps.addLast(now)
        while (taps.size > 6) taps.removeFirst()
        if (taps.size < 4) return null
        val span = (taps.last() - taps.first()) / (taps.size - 1).toDouble()
        return Math.round(60e9 / span * 10) / 10.0
    }
}

/** "120", "92.5". */
fun bpmText(bpm: Double): String = if (bpm % 1.0 == 0.0) "%.0f".format(bpm) else "%.1f".format(bpm)

/** A BPM typed in: a number from 20 to 400, or null if it is none. */
private fun typedBpm(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }

/**
 * A tempo as the song (or a live set's own) has it (S12.1, S12.5): the BPM --
 * typed, stepped or tapped -- or none, and the metre, which stays meant without
 * a tempo; with [countIn], the count-in's bars.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TempoFields(tempo: Tempo, countIn: Boolean, onChange: (Tempo) -> Unit) {
    val tapper = remember { TapTempo() }
    androidx.compose.foundation.layout.FlowRow(verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = tempo.bpm != null, onCheckedChange = { on -> onChange(tempo.copy(bpm = if (on) 120.0 else null)) })
            Text(stringResource(R.string.tempo))
        }
        tempo.bpm?.let { b ->
            BpmField(b) { onChange(tempo.copy(bpm = it)) }
            OutlinedButton(onClick = { tapper.tap()?.let { onChange(tempo.copy(bpm = it)) } }) { Text(stringResource(R.string.tap)) }
        }
        NumberField(stringResource(R.string.beats_per_bar), tempo.beatsPerBar, 1..16) { onChange(tempo.copy(beatsPerBar = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.beat_unit), fontSize = 10.sp, color = Dim)
            for (u in listOf(2, 4, 8, 16)) {
                TextButton(onClick = { onChange(tempo.copy(beatUnit = u)) }) {
                    Text("/$u", color = if (tempo.beatUnit == u) Accent else androidx.compose.ui.graphics.Color.White,
                        fontWeight = if (tempo.beatUnit == u) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (countIn) NumberField(stringResource(R.string.count_in_bars), tempo.countInBars ?: 1, 1..8) {
            onChange(tempo.copy(countInBars = it))
        }
    }
}

/** A BPM to type, or step by one. */
@Composable
private fun BpmField(bpm: Double, onValue: (Double) -> Unit) {
    var text by remember(bpm) { mutableStateOf(bpmText(bpm)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onValue((bpm - 1).coerceAtLeast(20.0)) }) { Text("−") }
        OutlinedTextField(value = text, singleLine = true, label = { Text(stringResource(R.string.bpm_short)) },
            onValueChange = { s -> text = s; typedBpm(s)?.let(onValue) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.width(96.dp))
        TextButton(onClick = { onValue((bpm + 1).coerceAtMost(400.0)) }) { Text("+") }
    }
}

/**
 * The metronome's settings (S12.6), in Setup: the click and the bar count --
 * each always, during a count-in only, or never --, whether it keeps going
 * after a count-in, the count-in's bars where the song names none, and the
 * click's level and where it goes.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ClickOptions(player: SongPlayer) {
    @Composable
    fun Choice(label: Int, value: SongPlayer.Presence, onValue: (SongPlayer.Presence) -> Unit) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center) {
            Text(stringResource(label), modifier = Modifier.width(120.dp).padding(top = 14.dp))
            for ((p, l) in listOf(SongPlayer.Presence.ON to R.string.presence_on,
                    SongPlayer.Presence.COUNT_IN to R.string.presence_count_in,
                    SongPlayer.Presence.OFF to R.string.presence_off)) {
                FilterChip(selected = value == p, onClick = { onValue(p); player.saveClickSettings() },
                    label = { Text(stringResource(l)) })
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Choice(R.string.click_sound, player.clickSound) { player.clickSound = it }
        Choice(R.string.bar_count, player.barCount) { player.barCount = it }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.after_count_in), modifier = Modifier.width(120.dp).padding(top = 14.dp))
            FilterChip(selected = player.keepGoing, onClick = { player.keepGoing = true; player.saveClickSettings() },
                label = { Text(stringResource(R.string.keep_going)) })
            FilterChip(selected = !player.keepGoing, onClick = { player.keepGoing = false; player.saveClickSettings() },
                label = { Text(stringResource(R.string.stop_after)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField(stringResource(R.string.count_in_bars), player.countInBars, 1..8) {
                player.countInBars = it; player.saveClickSettings()
            }
            Text(stringResource(R.string.count_in_bars_note), color = Dim, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.click_volume), modifier = Modifier.width(120.dp))
            Slider(value = player.clickLevel, valueRange = 0.05f..1f, enabled = player.clickSound != SongPlayer.Presence.OFF,
                onValueChange = { player.clickLevel = it }, onValueChangeFinished = player::saveClickSettings,
                modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.click_goes_to_main), color = Dim, fontSize = 12.sp)
    }
}

/**
 * The stage's tempo, from wherever it is shown (S12.3, S12.7): typed or
 * tapped, none, back to what is stored, or saved where it belongs (the song,
 * or the live set that has its own). The metronome's settings are Setup's.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TempoDialog(player: SongPlayer, onDismiss: () -> Unit) {
    val tapper = remember { TapTempo() }
    var text by remember { mutableStateOf(player.bpm?.let(::bpmText).orEmpty()) }
    fun set() { typedBpm(text)?.let { player.typeTempo(it) } }
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = { set(); onDismiss() }) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(R.string.tempo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())) {
                val t = player.tempo
                Text((t?.bpm?.let { stringResource(R.string.bpm_metre, bpmText(it), t.metre) } ?: stringResource(R.string.no_tempo)) +
                    if (player.bpmTapped) "  ·  " + stringResource(R.string.tempo_session) else "",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = text, singleLine = true, label = { Text(stringResource(R.string.bpm_short)) },
                        onValueChange = { text = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { set() }),
                        modifier = Modifier.width(110.dp))
                    OutlinedButton(onClick = ::set, enabled = typedBpm(text) != null) { Text(stringResource(R.string.tempo_set)) }
                    OutlinedButton(onClick = {
                        tapper.tap()?.let { player.typeTempo(it); text = bpmText(it) }
                    }) { Text(stringResource(R.string.tap)) }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t?.bpm != null) TextButton(onClick = { player.typeTempo(null); text = "" }) {
                        Text(stringResource(R.string.tempo_none))
                    }
                    if (player.bpmTapped) {
                        TextButton(onClick = { player.revertTempo(); text = player.bpm?.let(::bpmText).orEmpty() }) {
                            Text(stringResource(R.string.tempo_back))
                        }
                        if (player.heard == null) TextButton(onClick = { player.saveTempo(); onDismiss() }) {
                            Text(stringResource(if (player.tempoIsLiveSets()) R.string.tempo_save_live_set else R.string.tempo_save_song))
                        }
                    }
                }
            }
        })
}
private val Dim = androidx.compose.ui.graphics.Color(0xFF8A919C)
