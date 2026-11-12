package com.rigalong.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
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

/**
 * A number with − and +. [bigStep]: holding − or + moves that far (an octave
 * for a key or a transposition). [onLearn]: the value itself can be tapped,
 * to be set from outside -- a key range by playing the key -- and reads
 * "play…" while [learning].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NumberField(label: String, value: Int, range: IntRange, noteName: Boolean = false, bigStep: Int = 0,
                         learning: Boolean = false, onLearn: (() -> Unit)? = null, onValue: (Int) -> Unit) {
    @Composable
    fun step(text: String, by: Int) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.defaultMinSize(44.dp, 44.dp).combinedClickable(
            onClick = { onValue((value + by).coerceIn(range)) },
            onLongClick = if (bigStep > 0) { { onValue((value + by * bigStep).coerceIn(range)) } } else null)) {
            Text(text, color = Accent, fontSize = 18.sp)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        step("−", -1)
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(52.dp).then(if (onLearn != null) Modifier.clickable(onClick = onLearn) else Modifier)) {
            Text(label, fontSize = 10.sp, color = Dim)
            if (learning) Text(stringResource(R.string.play_a_key), fontSize = 15.sp, color = Accent)
            else Text(if (noteName) noteLabel(value) else value.toString(), fontSize = 15.sp)
        }
        step("+", 1)
    }
}
