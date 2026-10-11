package be.mdw.bagosynths.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.mdw.bagosynths.R
import be.mdw.bagosynths.model.Cp88
import be.mdw.bagosynths.model.Cp88Yaml

/**
 * A CP-88 Live Set Sound as text (I4.17): cp-liveset's YAML, which lists only
 * what differs from the standard sound, by name. Checked as it is typed;
 * heard on the keyboard before it is kept; saved as a .yaml cp-liveset reads.
 * The first editor for these sounds -- a visual one can come after.
 */
@Composable
fun Cp88YamlEditor(title: String, sound: ByteArray, player: SongPlayer, onSave: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val original = remember(sound) { Cp88Yaml.write(sound) }
    var text by remember(sound) { mutableStateOf(original) }
    var note by remember { mutableStateOf("") }
    val parsed = remember(text) { runCatching { Cp88Yaml.read(text) } }
    val edited = parsed.getOrNull()
    val changed = edited != null && !edited.contentEquals(sound)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val s = edited
        if (uri != null && s != null) note = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(Cp88Yaml.document(mapOf((1 to 1) to s)).toByteArray()) }
        }.fold({ context.getString(R.string.exported, 1) }, { context.getString(R.string.export_failed, it.message ?: "") })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.92f)
            .background(Color(0xFF1A1D23), RoundedCornerShape(12.dp)).padding(16.dp)) {
            Text(stringResource(R.string.cp88_yaml_title, title), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(stringResource(R.string.cp88_yaml_hint), color = Color(0xFF8A919C), fontSize = 12.sp)
            OutlinedTextField(value = text, onValueChange = { text = it },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp))
            // What the text is now: the sound it reads as, or where it goes wrong.
            Text(parsed.fold({ stringResource(R.string.cp88_yaml_ok, Cp88.name(it).trimEnd()) + if (changed) " •" else "" },
                    { it.message ?: "?" }),
                color = if (edited != null) Color(0xFF7EE787) else Color(0xFFE5534B), fontSize = 13.sp, maxLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(enabled = edited != null, onClick = {
                    note = context.getString(if (player.auditionCp88(edited!!)) R.string.cp88_playing else R.string.cp88_unreachable)
                }) { Text(stringResource(R.string.play_on_cp88)) }
                TextButton(enabled = edited != null, onClick = { saver.launch("${Cp88.name(edited!!).trim().ifEmpty { "CP-88 sound" }}.yaml") }) {
                    Text(stringResource(R.string.export_yaml))
                }
                TextButton(enabled = text != original, onClick = { text = original }) { Text(stringResource(R.string.revert)) }
                Text(note, color = Color(0xFF8A919C), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(enabled = changed, onClick = { onSave(edited!!); onDismiss() }) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}
