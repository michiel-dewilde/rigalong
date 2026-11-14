package com.rigalong.play

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rigalong.NativeBridge

/**
 * The switching controllers (I10.4, I12.3): a footswitch's controller number
 * -> an action, learned by pressing it. [press] says which action a press
 * asks for; [SongPlayer.onController] does it (REVIEW S3).
 */
class SwitchControllers(private val prefs: SharedPreferences) {

    /**
     * Controller -> action: "next" and "previous" step through live sets (or
     * turn pages), "tap" taps the tempo, "countIn" counts in, "click" starts
     * and stops the click. -1 = none.
     */
    val controllers = androidx.compose.runtime.mutableStateMapOf<String, Int>().apply {
        for (a in ACTIONS) put(a, prefs.getInt(key(a), -1))
    }
    /** Which action the next controller press is learned into, or null. */
    var learning by mutableStateOf<String?>(null); private set
    private val ccDown = BooleanArray(128)

    private fun key(action: String) = "switch.cc" + action.replaceFirstChar { it.uppercase() }

    private fun save() {
        val e = prefs.edit()
        for (a in ACTIONS) e.putInt(key(a), controllers[a] ?: -1)
        e.apply()
    }

    /** The engine passes on the controllers in use (and, while learning, any). */
    fun watch() {
        var low = 0L
        var high = 0L
        for (cc in controllers.values) {
            if (cc in 0..63) low = low or (1L shl cc) else if (cc in 64..127) high = high or (1L shl (cc - 64))
        }
        runCatching { NativeBridge.nativeWatchControllers(low, high, learning != null) }
    }

    fun learn(which: String?) { learning = which; watch() }

    /** All of them at once, as an environment brings them; an action left out has none. */
    fun set(map: Map<String, Int>) {
        for (a in ACTIONS) controllers[a] = map[a] ?: -1
        save()
        watch()
    }

    fun clear(which: String) {
        controllers[which] = -1
        save()
        watch()
    }

    /**
     * Controller [cc] at [value]: the action to do, on the press and once, or
     * null (a release, a controller no action has, or one being learned).
     */
    fun press(cc: Int, value: Int): String? {
        val down = value >= 64
        val pressed = down && !ccDown[cc]          // act on the press, once
        ccDown[cc] = down
        if (!pressed) return null
        val l = learning
        if (l != null) {
            // One controller, one action: learning it here takes it from any other.
            for (a in ACTIONS) if (controllers[a] == cc) controllers[a] = -1
            controllers[l] = cc
            save()
            learning = null
            watch()
            return null
        }
        return ACTIONS.firstOrNull { controllers[it] == cc }
    }

    companion object {
        /** What a switching controller can be taught (I10.4, I12.3). */
        val ACTIONS = listOf("next", "previous", "tap", "click", "countIn")
    }
}
