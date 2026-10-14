package com.rigalong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
 * A stage fader (REQUIREMENTS S17.4): a drag moves it by the distance dragged,
 * from where it already is, so a slide never jumps; full travel is the fader's
 * own height. A tap sets it where it was tapped (Michiel, 2026-10-14: real
 * sliding is too hard on glass) -- a tap is a press and release that did not
 * move past the touch slop, so the start of a drag never jumps either.
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
    }.pointerInput(Unit) {
        detectTapGestures(onTap = { at -> if (size.height > 0) emit((1f - at.y / size.height).coerceIn(0f, 1f)) })
    }) {
        val w = size.width
        val h = size.height
        // A layout can squeeze a fader to nothing (a phone on its side): draw nothing, never throw.
        if (w <= 0f || h <= 0f) return@Canvas
        val track = w * 0.18f
        drawRoundRect(Color(0xFF2A2E36), Offset((w - track) / 2, 0f), Size(track, h), CornerRadius(track / 2))
        val filled = h * current
        drawRoundRect(color.copy(alpha = 0.55f), Offset((w - track) / 2, h - filled), Size(track, filled),
            CornerRadius(track / 2))
        val capH = minOf(28f, h)
        val y = (h - filled - capH / 2).coerceIn(0f, h - capH)
        drawRoundRect(Color(0xFFE6E9EF), Offset(w * 0.1f, y), Size(w * 0.8f, capH), CornerRadius(6f))
    }
}
