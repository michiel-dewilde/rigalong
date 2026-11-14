package com.rigalong.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rigalong.NativeBridge

/**
 * MIDI learn for the editors: the next controller moved becomes a sound
 * parameter's knob, the next key played a key range's end (REVIEW S3).
 */
class MidiLearn(private val note: (String) -> Unit) {

    /** The "<instance>.<parameter>" waiting for a controller to be moved, or null. */
    var learningControl by mutableStateOf<String?>(null); private set
    private var onControl: ((Int) -> Unit)? = null

    /** The next controller moved goes to [key]: [done] gets its number. */
    fun control(key: String, done: (Int) -> Unit) {
        learningControl = key
        onControl = done
        runCatching { NativeBridge.nativeControlLearn(true) }
    }

    fun cancelControl() {
        if (learningControl == null) return
        learningControl = null
        onControl = null
        runCatching { NativeBridge.nativeControlLearn(false) }
    }

    /** A key range being set by playing the key (which field asked), or null. */
    var learningKey by mutableStateOf<String?>(null); private set
    private var onKey: ((Int) -> Unit)? = null

    /** The next key played goes to [field]: [done] gets its note number. */
    fun key(field: String, done: (Int) -> Unit) {
        learningKey = field
        onKey = done
        runCatching { NativeBridge.nativeNoteLearn(true) }
    }

    fun cancelKey() {
        if (learningKey == null) return
        learningKey = null
        onKey = null
        runCatching { NativeBridge.nativeNoteLearn(false) }
    }

    /** What was learned since the last poll, to whoever asked. */
    fun poll() {
        if (learningKey != null) {
            val n = runCatching { NativeBridge.nativeTakeLearnedNote() }.getOrDefault(-1)
            if (n >= 0) {
                val done = onKey
                note("key learned: $n -> $learningKey")
                cancelKey()
                done?.invoke(n)
            }
        }
        if (learningControl == null) return
        val cc = runCatching { NativeBridge.nativeTakeLearnedControl() }.getOrDefault(-1)
        if (cc < 0) return
        val done = onControl
        note("control learned: CC $cc -> $learningControl")
        cancelControl()
        done?.invoke(cc)
    }
}
