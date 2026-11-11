package com.rigalong.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rigalong.R
import com.rigalong.model.RackEngine

/* Small pieces several views share. */

/** An engine's name for people, from resources (the model's labels are for logs). */
@Composable
internal fun engineLabel(e: RackEngine): String = stringResource(when (e) {
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
