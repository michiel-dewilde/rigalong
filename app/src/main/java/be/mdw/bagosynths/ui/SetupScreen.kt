package be.mdw.bagosynths.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import be.mdw.bagosynths.R

/** The routing the activity has in force, for the setup view. */
data class Routing(
    val audioDevice: String, val audioMode: String,
    val midiDevice: String, val midiMode: String,
    val usbBufferMs: Int, val usbBufferChoices: List<Int>,
)

/**
 * The setup view, first slice of the environment (REQUIREMENTS section 3):
 * which device carries audio and MIDI and in which mode, the USB buffer, the
 * CP-88's reserved page and channel, and every status figure (S19.3). Named
 * ports and several environments come with the rest of M3; this is the
 * routing the prototype screen had, without needing it.
 */
@Composable
fun SetupScreen(player: SongPlayer, host: EngineHost) {
    var tick by remember { mutableIntStateOf(0) }
    var routing by remember { mutableStateOf(host.routing()) }
    var status by remember { mutableStateOf(host.statusLines()) }
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
    AdaptivePair(560.dp, first = { m ->
        Column(m.background(panel, RoundedCornerShape(10.dp)).padding(12.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Text(stringResource(R.string.cp88), fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reserved_page), color = dim, modifier = Modifier.width(140.dp))
                TextButton(onClick = { player.reservedPage = player.reservedPage - 1; tick++ }) { Text("−") }
                Text(player.reservedPage.toString(), modifier = Modifier.width(32.dp))
                TextButton(onClick = { player.reservedPage = player.reservedPage + 1; tick++ }) { Text("+") }
                Text(stringResource(R.string.reserved_page_note), color = dim, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.midi_channel), color = dim, modifier = Modifier.width(140.dp))
                TextButton(onClick = { player.cp88Channel = player.cp88Channel - 1; tick++ }) { Text("−") }
                Text(player.cp88Channel.toString(), modifier = Modifier.width(32.dp))
                TextButton(onClick = { player.cp88Channel = player.cp88Channel + 1; tick++ }) { Text("+") }
            }
            Text(stringResource(R.string.keys_note), color = dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.stage), fontWeight = FontWeight.SemiBold)
            var dnd by remember { mutableStateOf(host.stageDnd()) }
            SetupToggle(stringResource(R.string.stage_dnd), dnd, accent, dim) { host.setStageDnd(it); dnd = host.stageDnd() }
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
            Text(stringResource(R.string.status), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            for (line in status) Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    })
    // Read so that the +/- buttons above, which change plain preferences, recompose.
    if (tick < 0) Text("")
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
