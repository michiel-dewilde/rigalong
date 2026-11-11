package com.rigalong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/* The colours and the Material theme every view shares: dark, for a stage. */

internal val Bg = Color(0xFF101216)
internal val Panel = Color(0xFF1A1D23)
internal val Accent = Color(0xFF3FB68B)
internal val Warn = Color(0xFFE0A030)
internal val Danger = Color(0xFFD9534F)
internal val Dim = Color(0xFF8A919C)
/** Outlines: a button's edge, a card's, a fader's track. */
internal val Line = Color(0xFF2A2E36)

@Composable
fun RigAlongTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        // Dark text on the green, everywhere Material puts text or a thumb on it.
        primary = Accent, onPrimary = Color(0xFF0B0D10), primaryContainer = Accent,
        onPrimaryContainer = Color(0xFF0B0D10), background = Bg, surface = Panel, onSurface = Color(0xFFE6E9EF),
        onBackground = Color(0xFFE6E9EF), secondary = Warn, error = Danger,
    )) { Surface(color = Bg, modifier = Modifier.fillMaxSize()) { content() } }
}
