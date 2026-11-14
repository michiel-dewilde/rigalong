package com.rigalong.play

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
            switching = SwitchControllers.ACTIONS.mapNotNull { a ->
                player.switches.controllers[a]?.takeIf { it >= 0 }?.let { SwitchSource(KEYS, "cc", number = it, action = a) }
            },
            twoUp = player.sheet.twoUp,
            halfTurn = player.sheet.halfTurn,
            keysTurnPages = player.sheet.keysTurnPages,
            clickPort = MAIN_OUT.takeIf { player.metronome.clickSound != Metronome.Presence.OFF },
            clickContinuous = player.metronome.clickSound == Metronome.Presence.ON,
            clickSound = player.metronome.clickSound.name,
            barCount = player.metronome.barCount.name,
            keepGoing = player.metronome.keepGoing,
            countInBars = player.metronome.countInBars,
            clickLevel = player.metronome.clickLevel.toDouble(),
            midiClockPort = KEYS.takeIf { player.metronome.midiClock },
            clickOutput = player.metronome.clickOutput,
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
            bufferMs = out?.bufferMs?.toInt() ?: com.rigalong.UsbBuffers.DEFAULT_MS))
        player.devices.update(env.externalSynths)
        player.devices.forget()
        player.switches.set(env.switching.filter { it.kind == "cc" && it.number != null }
            .associate { it.action to it.number!! })
        player.sheet.twoUp = env.twoUp
        player.sheet.halfTurn = env.halfTurn
        player.sheet.keysTurnPages = env.keysTurnPages
        player.sheet.saveSettings()
        // Older environments: a click port or none, continuous or a count-in (and on 2026-10-12, a mode).
        val legacy = env.clickMode ?: if (env.clickContinuous) "CONTINUOUS" else "COUNT_IN"
        player.metronome.clickSound = env.clickSound?.let { runCatching { Metronome.Presence.valueOf(it) }.getOrNull() } ?: when {
            env.clickPort == null -> Metronome.Presence.OFF
            legacy == "CONTINUOUS" -> Metronome.Presence.ON
            else -> Metronome.Presence.COUNT_IN
        }
        player.metronome.barCount = env.barCount?.let { runCatching { Metronome.Presence.valueOf(it) }.getOrNull() } ?: Metronome.Presence.ON
        player.metronome.keepGoing = env.keepGoing ?: (legacy != "COUNT_IN")
        player.metronome.countInBars = env.countInBars ?: 1
        player.metronome.clickLevel = env.clickLevel.toFloat()
        player.metronome.midiClock = env.midiClockPort != null
        player.metronome.clickOutput = env.clickOutput
        player.metronome.saveClickSettings()
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
