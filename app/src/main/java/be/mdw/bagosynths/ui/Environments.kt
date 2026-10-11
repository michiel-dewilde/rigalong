package be.mdw.bagosynths.ui

import be.mdw.bagosynths.model.Binding
import be.mdw.bagosynths.model.DeviceMatch
import be.mdw.bagosynths.model.Environment
import be.mdw.bagosynths.model.ExternalSynth
import be.mdw.bagosynths.model.PortDef
import be.mdw.bagosynths.model.PortKind
import be.mdw.bagosynths.model.SwitchSource

/**
 * Environments (REQUIREMENTS S3.8, S3.10, S8.2): the rig, as a library item.
 *
 * This first slice keeps the rack's fixed ports -- "Main Out" (audio), "Keys"
 * (MIDI in and out) -- and binds them to devices by their own names, with
 * their modes and the USB buffer; the CP-88 as an external synth with its
 * reserved page, channel and whether its buttons select live sets; the
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
            externalSynths = listOf(ExternalSynth(
                name = "CP-88", kind = "cp88", midiOut = KEYS, midiIn = KEYS, channel = player.cp88Channel,
                reservedPages = listOf(player.reservedPage), selectsLiveSets = player.cp88Follows)),
            switching = listOfNotNull(
                player.ccNext.takeIf { it >= 0 }?.let { SwitchSource(KEYS, "cc", number = it, action = "next") },
                player.ccPrevious.takeIf { it >= 0 }?.let { SwitchSource(KEYS, "cc", number = it, action = "previous") },
            ),
            twoUp = player.sheetTwoUp,
            halfTurn = player.sheetHalfTurn,
            keysTurnPages = player.keysTurnPages,
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
        env.externalSynths.firstOrNull { it.kind == "cp88" }?.let { cp ->
            player.cp88Channel = cp.channel
            cp.reservedPages.firstOrNull()?.let { player.reservedPage = it }
            player.cp88Follows = cp.selectsLiveSets
        }
        player.setControllers(
            next = env.switching.firstOrNull { it.kind == "cc" && it.action == "next" }?.number ?: -1,
            previous = env.switching.firstOrNull { it.kind == "cc" && it.action == "previous" }?.number ?: -1)
        player.sheetTwoUp = env.twoUp
        player.sheetHalfTurn = env.halfTurn
        player.keysTurnPages = env.keysTurnPages
        player.saveSheetSettings()
        host.setStageDnd(env.doNotDisturb)
        env.masterDb?.let { host.setMasterDb(it.toFloat()) }
        // After the reroute has settled -- it runs on its own thread and takes
        // the USB device away and back -- or the song would be set up on the
        // session being closed. The CP-88's page is rewritten in full as soon
        // as MIDI out comes back (SongPlayer.watchMidiOut); this reload then
        // finds it in place and writes only what the new settings change.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ player.reload() }, 2500)
    }

    /** Every device the environment names is plugged in (S3.10); "automatic" always is. */
    fun fits(env: Environment, available: Set<String>): Boolean =
        env.bindings.values.mapNotNull { it.device.name }.all { it in available }
}
