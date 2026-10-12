package be.mdw.rigalong.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Two panels side by side when there is room, one above the other when there
 * is not -- a phone, or a tablet in portrait (REQUIREMENTS S18.4, S20.2).
 */
@Composable
fun AdaptivePair(firstWidth: Dp, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= firstWidth + 320.dp) {
            Row(Modifier.fillMaxSize()) {
                first(Modifier.width(firstWidth).fillMaxHeight())
                Spacer(Modifier.width(12.dp))
                second(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                first(Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(12.dp))
                second(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
