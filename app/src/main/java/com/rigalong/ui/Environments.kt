package com.rigalong.ui

import com.rigalong.model.Binding
import com.rigalong.model.DeviceMatch
import com.rigalong.model.Environment
import com.rigalong.model.PortDef
import com.rigalong.model.PortKind
import com.rigalong.model.SwitchSource

/**
 * Environments (REQUIREMENTS S3.8, S3.10, S8.2): the rig, as a library item.
 *
 * This first slice keeps the rack's fixed ports -- "Main Out" (audio), "Keys"
 * (MIDI in and out) -- and binds them to devices by their own names, with
 * their modes and the USB buffer; the external devices with their
 * channels, reserved areas and whether they select live sets; the
 * switching controllers; the lead-sheet options; Do Not Disturb; and the
 * master volume, which is a property of the room.
 */
object Environments {
    const val MAIN_OUT = "Main Out"
    const val KEYS = "Keys"

    fun capture(player: SongPlayer, host: EngineHost): Environment {
        val r = host.routingSpec()
        return Environment(
            ports = listOf(PortDef(MAIN_OUT, PortKind.AUDIO_OUT), PortDef(KEYS, PortKind.MIDI_IN)),
            bindings = mapOf(
                MAIN_OUT to Binding(mode = r.audioMode, device = DeviceMatch(name = r.audioName),
                    bufferMs = r.bufferMs.toDouble()),
                KEYS to Binding(mode = r.midiMode, device = DeviceMatch(name = r.midiName)),
            ),
            externalSynths = player.devices.devices.toList(),
            switching = SongPlayer.CONTROLLER_ACTIONS.mapNotNull { a ->
                player.controllers[a]?.takeIf { it >= 0 }?.let { SwitchSource(KEYS, "cc", number = it, action = a) }
            },
            twoUp = player.sheetTwoUp,
            halfTurn = player.sheetHalfTurn,
            keysTurnPages = player.keysTurnPages,
            clickPort = MAIN_OUT.takeIf { player.clickSound != SongPlayer.Presence.OFF },
            clickContinuous = player.clickSound == SongPlayer.Presence.ON,
            clickSound = player.clickSound.name,
            barCount = player.barCount.name,
            keepGoing = player.keepGoing,
            countInBars = player.countInBars,
            clickLevel = player.clickLevel.toDouble(),
            midiClockPort = KEYS.takeIf { player.midiClock },
            doNotDisturb = host.stageDnd(),
            masterDb = host.masterDb().toDouble(),
        )
    }

    fun apply(env: Environment, player: SongPlayer, host: EngineHost) {
        val out = env.bindings[MAIN_OUT]
        val keys = env.bindings[KEYS]
        host.applyRoutingSpec(RoutingSpec(
            audioName = out?.device?.name, audioMode = out?.mode ?: "ALSA",
            midiName = keys?.device?.name, midiMode = keys?.mode ?: "ALSA",
            bufferMs = out?.bufferMs?.toInt() ?: 6))
        player.devices.update(env.externalSynths)
        player.devices.forget()
        player.setControllers(env.switching.filter { it.kind == "cc" && it.number != null }
            .associate { it.action to it.number!! })
        player.sheetTwoUp = env.twoUp
        player.sheetHalfTurn = env.halfTurn
        player.keysTurnPages = env.keysTurnPages
        player.saveSheetSettings()
        // Older environments: a click port or none, continuous or a count-in (and on 2026-10-12, a mode).
        val legacy = env.clickMode ?: if (env.clickContinuous) "CONTINUOUS" else "COUNT_IN"
        player.clickSound = env.clickSound?.let { runCatching { SongPlayer.Presence.valueOf(it) }.getOrNull() } ?: when {
            env.clickPort == null -> SongPlayer.Presence.OFF
            legacy == "CONTINUOUS" -> SongPlayer.Presence.ON
            else -> SongPlayer.Presence.COUNT_IN
        }
        player.barCount = env.barCount?.let { runCatching { SongPlayer.Presence.valueOf(it) }.getOrNull() } ?: SongPlayer.Presence.ON
        player.keepGoing = env.keepGoing ?: (legacy != "COUNT_IN")
        player.countInBars = env.countInBars ?: 1
        player.clickLevel = env.clickLevel.toFloat()
        player.midiClock = env.midiClockPort != null
        player.saveClickSettings()
        host.setStageDnd(env.doNotDisturb)
        env.masterDb?.let { host.setMasterDb(it.toFloat()) }
        // After the reroute has settled -- it runs on its own thread and takes
        // the USB device away and back -- or the song would be set up on the
        // session being closed. The devices' reserved areas are rewritten in full
        // as soon as MIDI out comes back (DeviceRig.midiOutReady); this reload then
        // finds it in place and writes only what the new settings change.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ player.reload() }, 2500)
    }

    /** Every device the environment names is plugged in (S3.10); "automatic" always is. */
    fun fits(env: Environment, available: Set<String>): Boolean =
        env.bindings.values.mapNotNull { it.device.name }.all { it in available }
}
