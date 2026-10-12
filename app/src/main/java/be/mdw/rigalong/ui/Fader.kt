package be.mdw.rigalong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A stage fader that never jumps (REQUIREMENTS S17.4): a touch does nothing,
 * only a drag moves it, by the distance dragged and from where it already is.
 * A stray finger on the screen mid-song therefore cannot change a level.
 * Full travel is the fader's own height.
 */
@Composable
fun RelativeFader(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier,
                  color: Color = Color(0xFF3FB68B)) {
    val current by rememberUpdatedState(value)
    val emit by rememberUpdatedState(onValue)
    Canvas(modifier.fillMaxSize().pointerInput(Unit) {
        var v = 0f
        detectVerticalDragGestures(
            onDragStart = { v = current },
            onVerticalDrag = { change, dy ->
                change.consume()
                v = (v - dy / size.height).coerceIn(0f, 1f)
                emit(v)
            })
    }) {
        val w = size.width
        val h = size.height
        val track = w * 0.18f
        drawRoundRect(Color(0xFF2A2E36), Offset((w - track) / 2, 0f), Size(track, h), CornerRadius(track / 2))
        val filled = h * current
        drawRoundRect(color.copy(alpha = 0.55f), Offset((w - track) / 2, h - filled), Size(track, filled),
            CornerRadius(track / 2))
        val capH = 28f
        val y = (h - filled - capH / 2).coerceIn(0f, h - capH)
        drawRoundRect(Color(0xFFE6E9EF), Offset(w * 0.1f, y), Size(w * 0.8f, capH), CornerRadius(6f))
    }
}
