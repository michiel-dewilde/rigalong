package be.mdw.bagosynths.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import be.mdw.bagosynths.NativeBridge
import be.mdw.bagosynths.TouchKeyboardView

/**
 * The on-screen test keyboard (REQUIREMENTS S20.4): notes enter the engine as
 * MIDI from the keys does, so they pass the zones and wires and play what the
 * current live set plays -- a song or a binding can be checked with no
 * keyboard attached. The prototype's view, reused.
 */
@Composable
fun TestKeys(modifier: Modifier = Modifier) {
    AndroidView(factory = { ctx -> TouchKeyboardView(ctx) { note, velocity -> NativeBridge.nativeTestNote(note, velocity) } },
        modifier = modifier.fillMaxWidth().height(130.dp))
}
