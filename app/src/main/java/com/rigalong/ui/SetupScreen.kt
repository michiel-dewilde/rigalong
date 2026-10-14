package com.rigalong.ui

import com.rigalong.model.Library
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.rigalong.R

/** The routing the activity has in force, for the setup view. */
data class Routing(
    val audioDevice: String, val audioMode: String,
    val midiDevice: String, val midiMode: String,
    val usbBufferMs: Int, val usbBufferChoices: List<Int>,
    /** A MIDI input is there to play from (a device, its use not switched off): no on-screen keys needed. */
    val midiIn: Boolean = false,
)

/**
 * The setup view, first slice of the environment (REQUIREMENTS section 3):
 * which device carries audio and MIDI and in which mode, the USB buffer, the
 * CP-88's reserved page and channel, and every status figure (S19.3). Named
 * ports and several environments come with the rest of M3; this is the
 * routing the prototype screen had, without needing it.
 */
@Composable
fun SetupScreen(player: SongPlayer, host: EngineHost, lib: Library) {
    var tick by remember { mutableIntStateOf(0) }
    var routing by remember { mutableStateOf(host.routing()) }
    var status by remember { mutableStateOf(host.statusLines()) }
    var midi by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        while (true) {
            midi = runCatching { decodeMidi(com.rigalong.NativeBridge.nativeMidiRecent()) }.getOrDefault(emptyList())
            delay(300)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            routing = host.routing()
            status = host.statusLines()
            delay(700)
        }
    }
    val panel = Color(0xFF1A1D23)
    val dim = Color(0xFF8A919C)
    val accent = Color(0xFF3FB68B)
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
    AdaptivePair(560.dp, first = { m ->
        Column(m.background(panel, RoundedCornerShape(10.dp)).padding(12.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EnvironmentSection(player, host, lib, accent, dim)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.audio_out), fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(routing.audioDevice, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { host.cycleAudioDevice(); tick++ }) { Text(stringResource(R.string.next_device)) }
            }
            ModeRow(routing.audioMode, accent, dim) { host.setAudioMode(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.usb_buffer), color = dim)
                for (ms in routing.usbBufferChoices) {
                    TextButton(onClick = { host.setUsbBufferMs(ms) }) {
                        Text(stringResource(R.string.ms, ms), color = if (ms == routing.usbBufferMs) accent else dim)
                    }
                }
                Text(stringResource(R.string.usb_buffer_note), color = dim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.midi_in_out), fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(routing.midiDevice, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { host.cycleMidiDevice(); tick++ }) { Text(stringResource(R.string.next_device)) }
            }
            ModeRow(routing.midiMode, accent, dim) { host.setMidiMode(it) }
            Text(stringResource(R.string.usb_device_note), color = dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            // External devices (I10.1): each as its driver allows (model/devices).
            Text(stringResource(R.string.devices), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.devices_note), color = dim, fontSize = 12.sp)
            for (d in player.devices.devices.toList()) {
                val drv = player.devices.driver(d)
                // A change to a device rewrites what the song gives it.
                fun put(n: com.rigalong.model.ExternalSynth) { player.devices.replace(d, n); player.devices.forget(); player.reload(); tick++ }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(d.name, fontWeight = FontWeight.SemiBold)
                    Text("  " + drv.label, color = dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { player.devices.update(player.devices.devices - d); tick++ }) {
                        Text(stringResource(R.string.remove_device), color = Color(0xFFE5534B))
                    }
                }
                Stepper(stringResource(R.string.device_channel), d.channel, 1..16, dim) { put(d.copy(channel = it)) }
                drv.sounds?.let { s ->
                    val area = d.reservedAreas.firstOrNull() ?: s.area.range.last
                    val two = d.reservedAreas.distinct().size >= 2
                    // The second area is always the first one's neighbour (DeviceRig.pairOf).
                    fun areas(first: Int, both: Boolean) = if (both) DeviceRig.pairOf(first, s.area.range) else listOf(first)
                    Stepper(stringResource(R.string.reserved_area, s.area.label), area, s.area.range, dim,
                        note = stringResource(R.string.reserved_area_note)) { put(d.copy(reservedAreas = areas(it, two))) }
                    SetupToggle(stringResource(R.string.preload_area, s.area.label, DeviceRig.pairOf(area, s.area.range).last()),
                        two, accent, dim) { put(d.copy(reservedAreas = areas(area, it))) }
                    SetupToggle(stringResource(R.string.device_follows, s.area.label), d.selectsLiveSets, accent, dim) {
                        put(d.copy(selectsLiveSets = it))
                    }
                    OutlinedButton(onClick = { player.devices.forget(); player.reload() }) {
                        Text(stringResource(R.string.rewrite_area, s.area.label))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (drv in com.rigalong.model.devices.Devices.drivers) {
                    TextButton(onClick = { player.devices.add(drv); tick++ }) { Text(stringResource(R.string.add_device, drv.label)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.switching), fontWeight = FontWeight.SemiBold)
            for (action in SongPlayer.CONTROLLER_ACTIONS) {
                val label = stringResource(when (action) {
                    "next" -> R.string.cc_next
                    "previous" -> R.string.cc_previous
                    "tap" -> R.string.cc_tap
                    "countIn" -> R.string.cc_count_in
                    else -> R.string.cc_click
                })
                ControllerRow(label, player.controllers[action] ?: -1, player.learning == action, accent, dim,
                    onLearn = { player.learn(if (player.learning == action) null else action) },
                    onClear = { player.clearController(action) })
            }
            Text(stringResource(R.string.switching_note), color = dim, fontSize = 12.sp)
            Text(stringResource(R.string.keys_note), color = dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.stage), fontWeight = FontWeight.SemiBold)
            var dnd by remember { mutableStateOf(host.stageDnd()) }
            SetupToggle(stringResource(R.string.stage_dnd), dnd, accent, dim) { host.setStageDnd(it); dnd = host.stageDnd() }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.metronome), fontWeight = FontWeight.SemiBold)
            // The same options as the stage's tempo dialog.
            ClickOptions(player)
            SetupToggle(stringResource(R.string.midi_clock), player.midiClock, accent, dim) {
                player.midiClock = it; player.saveClickSettings()
            }
            Text(stringResource(R.string.click_note), color = dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.lead_sheets), fontWeight = FontWeight.SemiBold)
            SetupToggle(stringResource(R.string.two_up), player.sheetTwoUp, accent, dim) {
                player.sheetTwoUp = it; player.saveSheetSettings()
            }
            SetupToggle(stringResource(R.string.half_turn), player.sheetHalfTurn, accent, dim) {
                player.sheetHalfTurn = it; player.saveSheetSettings()
            }
            SetupToggle(stringResource(R.string.keys_turn_pages), player.keysTurnPages, accent, dim) {
                player.keysTurnPages = it; player.saveSheetSettings()
            }
            OutlinedButton(onClick = host::showPrototype) { Text(stringResource(R.string.open_prototype)) }
        }
    }, second = { m ->
        Column(m.background(panel, RoundedCornerShape(10.dp)).padding(12.dp)
            .verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.status), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                // Dropouts and peaks count from a moment the status names; this moves it to now.
                TextButton(onClick = { host.resetCounts(); status = host.statusLines() }) { Text(stringResource(R.string.reset_counts)) }
                OutlinedButton(onClick = host::measureLatency) { Text(stringResource(R.string.measure)) }
            }
            Text(stringResource(R.string.measure_note), color = dim, fontSize = 12.sp)
            val context = androidx.compose.ui.platform.LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    player.diagnostics?.share(context, listOf(host.routingSpec().toString()) + host.statusLines())
                }) { Text(stringResource(R.string.share_diagnostics)) }
                Text(stringResource(R.string.share_diagnostics_note), color = dim, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp))
            }
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.midi_monitor), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.midi_monitor_note), color = dim, fontSize = 12.sp)
            for (line in midi.takeLast(8).reversed()) Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            // Label, then the reading; a reading that needs attention in the warning colour.
            for (line in status) Row(Modifier.padding(vertical = 2.dp)) {
                Text(line.take(12).trim(), color = dim, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                val text = line.drop(12)
                Text(text, fontSize = 13.sp, fontFamily = if (line.startsWith("level")) FontFamily.Monospace else FontFamily.Default,
                    color = if (needsAttention(line)) Color(0xFFE3B341) else Color.Unspecified)
            }

        }
    })
    }
    }
    // Read so that the +/- buttons above, which change plain preferences, recompose.
    if (tick < 0) Text("")
}

private val NOTE = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** The monitor's entries (usb/MidiMonitor.h) as lines, oldest first: "← ch 1  Note on  C4  100". */
private fun decodeMidi(raw: IntArray): List<String> {
    if (raw.isEmpty()) return emptyList()
    return raw.dropLast(1).map { v ->
        val dir = if (v ushr 31 == 1) "→" else "←"
        val b0 = (v shr 16) and 0xFF
        val b1 = (v shr 8) and 0xFF
        val b2 = v and 0xFF
        val ch = (b0 and 0x0F) + 1
        fun note(n: Int) = NOTE[n % 12] + (n / 12 - 1)
        val what = when (b0 and 0xF0) {
            0x90 -> if (b2 > 0) "ch $ch  Note on   ${note(b1)}  $b2" else "ch $ch  Note off  ${note(b1)}"
            0x80 -> "ch $ch  Note off  ${note(b1)}"
            0xA0 -> "ch $ch  Aftertouch ${note(b1)}  $b2"
            0xB0 -> "ch $ch  CC $b1 = $b2"
            0xC0 -> "ch $ch  Program ${b1 + 1}"
            0xD0 -> "ch $ch  Pressure $b1"
            0xE0 -> "ch $ch  Bend ${((b2 shl 7) or b1) - 8192}"
            else -> if (b0 == 0xF0) "SysEx" else if (b0 == 0xF7) "SysEx end" else "%02X %02X %02X".format(b0, b1, b2)
        }
        "$dir $what"
    }
}

/** Environments (S3.8, S3.10): saved rigs to switch between, and which ones fit what is plugged in. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EnvironmentSection(player: SongPlayer, host: EngineHost, lib: Library, accent: Color, dim: Color) {
    val prefs = androidx.compose.ui.platform.LocalContext.current
        .getSharedPreferences("rigalong", android.content.Context.MODE_PRIVATE)
    var revision by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf(prefs.getString("env.current", null)) }
    var suggested by remember { mutableStateOf<Set<String>?>(null) }
    var newName by remember { mutableStateOf("") }
    val envs = remember(revision) { lib.environments() }
    Text(stringResource(R.string.environment), fontWeight = FontWeight.SemiBold)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (e in envs) {
            val isCurrent = e.id.hex == current
            val fits = suggested?.contains(e.id.hex) == true
            OutlinedButton(onClick = {
                lib.environment(e.id)?.let { Environments.apply(it, player, host) }
                current = e.id.hex; prefs.edit().putString("env.current", current).apply()
            }) { Text(e.version.name + (if (fits) "  ●" else ""), color = if (isCurrent) accent else Color.White) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
            label = { Text(stringResource(R.string.save_environment_as)) }, modifier = Modifier.weight(1f))
        TextButton(enabled = newName.isNotBlank(), onClick = {
            val id = lib.saveEnvironment(null, newName.trim(), Environments.capture(player, host))
            current = id.hex; prefs.edit().putString("env.current", current).apply()
            newName = ""; revision++
        }) { Text(stringResource(R.string.save)) }
    }
    Row {
        val cur = current
        if (cur != null && envs.any { it.id.hex == cur }) {
            TextButton(onClick = {
                val e = envs.first { it.id.hex == cur }
                lib.saveEnvironment(e.id, e.version.name, Environments.capture(player, host)); revision++
            }) { Text(stringResource(R.string.update_environment)) }
        }
        TextButton(onClick = {
            val available = host.availableDevices()
            suggested = envs.filter { e -> lib.environment(e.id)?.let { Environments.fits(it, available) } == true }
                .map { it.id.hex }.toSet()
        }) { Text(stringResource(R.string.suggest_environment)) }
    }
    if (suggested != null) Text(stringResource(if (suggested!!.isEmpty()) R.string.no_environment_fits
        else R.string.environments_fit), color = dim, fontSize = 12.sp)
}

@Composable
private fun ControllerRow(label: String, cc: Int, learning: Boolean, accent: Color, dim: Color,
                          onLearn: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = dim, modifier = Modifier.width(150.dp))
        Text(if (learning) stringResource(R.string.press_it) else if (cc >= 0) "CC $cc" else stringResource(R.string.none),
            color = if (learning) accent else Color.White, modifier = Modifier.width(140.dp))
        TextButton(onClick = onLearn) { Text(stringResource(if (learning) R.string.cancel else R.string.learn)) }
        if (cc >= 0) TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
    }
}

@Composable
private fun SetupToggle(label: String, on: Boolean, accent: Color, dim: Color, onSet: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Switch(checked = on, onCheckedChange = onSet)
        Text(label, color = if (on) accent else dim, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun ModeRow(current: String, accent: Color, dim: Color, onMode: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.mode), color = dim, modifier = Modifier.width(60.dp))
        for (mode in listOf("ALSA", "USB_INTERFACE", "USB_DEVICE", "NONE")) {
            val label = stringResource(when (mode) {
                "USB_INTERFACE" -> R.string.mode_usb_interface
                "USB_DEVICE" -> R.string.mode_usb_device
                "NONE" -> R.string.mode_off
                else -> R.string.mode_alsa
            })
            TextButton(onClick = { onMode(mode) }) { Text(label, color = if (mode == current) accent else dim) }
        }
    }
}

/** A status reading worth the warning colour: a fault word, a dropout in the last minute, an alt error. */
private fun needsAttention(line: String): Boolean {
    val faults = listOf("FROZEN", "NOT CONNECTED", "CLIPPING", "DOWN", "WRONG", "STALLED", "SWITCHING", "NO USB", "HOT", "THROTTLING", "SLOW")
    return faults.any { it in line } ||
        (line.startsWith("dropouts") && !line.drop(12).startsWith("none in the last minute")) ||
        Regex("""\b[1-9][0-9]* alt errors""").containsMatchIn(line)
}

/** A whole number with − and +, kept within [range]. */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, dim: Color, note: String = "", onSet: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = dim, modifier = Modifier.width(140.dp))
        TextButton(onClick = { onSet((value - 1).coerceIn(range)) }) { Text("−") }
        Text(value.toString(), modifier = Modifier.width(32.dp))
        TextButton(onClick = { onSet((value + 1).coerceIn(range)) }) { Text("+") }
        if (note.isNotEmpty()) Text(note, color = dim, fontSize = 12.sp)
    }
}
