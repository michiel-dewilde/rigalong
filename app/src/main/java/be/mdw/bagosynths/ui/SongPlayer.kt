package be.mdw.bagosynths.ui

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import be.mdw.bagosynths.NativeBridge
import be.mdw.bagosynths.model.Digest
import be.mdw.bagosynths.model.LiveSet
import be.mdw.bagosynths.model.LiveSetSwitcher
import be.mdw.bagosynths.model.LiveSetSwitcher.Change
import be.mdw.bagosynths.model.RackEngine
import be.mdw.bagosynths.model.RackSong
import be.mdw.bagosynths.model.Song
import be.mdw.bagosynths.model.getTyped

/** What the new UI needs from the activity that owns the engine and the routing. */
interface EngineHost {
    /** Installed SFZ instruments: id to display name. */
    fun instruments(): List<Pair<String, String>>
    fun selectInstrument(id: String)
    fun currentInstrument(): String?
    fun showPrototype()
    /** One line on the state of the audio and MIDI path. */
    fun healthLine(): String
}

/**
 * Plays a setlist on the rack (model/Rack.kt): selecting a song sets up its
 * engines and patches, selecting a live set applies what [LiveSetSwitcher]
 * says, through the zones and the switchable wires (AudioEngine.h).
 *
 * Remembers setlist, song and live set in preferences on every move, so a
 * restart comes back where it was (S13.2).
 */
class SongPlayer(private val lib: AppLibrary, private val host: EngineHost, private val prefs: SharedPreferences) {

    var setlistId by mutableStateOf<Digest?>(null); private set
    var setlistName by mutableStateOf(""); private set
    val songNames = mutableStateListOf<String>()
    var songIndex by mutableIntStateOf(-1); private set
    var song: Song? by mutableStateOf(null); private set
    var rack: RackSong? by mutableStateOf(null); private set
    val liveSetNames = mutableStateListOf<String>()
    var liveSetIndex by mutableIntStateOf(-1); private set
    /** Engine levels as they are now, for the stage faders. */
    val levels = androidx.compose.runtime.mutableStateMapOf<RackEngine, Float>()
    val playing = androidx.compose.runtime.mutableStateMapOf<RackEngine, Boolean>()

    private var songs: List<Song> = emptyList()
    private var switcher: LiveSetSwitcher? = null
    private val midiOn = BooleanArray(RackEngine.entries.size) { true }
    private val audioOn = BooleanArray(RackEngine.entries.size) { true }
    private val gain = FloatArray(RackEngine.entries.size) { 1f }

    fun resume() {
        val id = prefs.getString("stage.setlist", null)?.let { runCatching { Digest(it) }.getOrNull() }
            ?: lib.setlists().firstOrNull()?.id ?: return
        openSetlist(id, prefs.getInt("stage.song", 0), prefs.getInt("stage.liveSet", 0))
    }

    fun openSetlist(id: Digest, song: Int = 0, liveSet: Int = 0) {
        val entry = lib.store.entry(id) ?: return
        setlistId = id
        setlistName = entry.version.name
        val pairs = lib.songsOf(id)
        songs = pairs.map { it.second }
        songNames.clear()
        songNames.addAll(pairs.map { it.first?.version?.name ?: "(removed song)" })
        prefs.edit().putString("stage.setlist", id.hex).apply()
        if (songs.isNotEmpty()) selectSong(song.coerceIn(0, songs.size - 1), liveSet)
    }

    fun reload() {
        setlistId?.let { openSetlist(it, songIndex.coerceAtLeast(0), liveSetIndex.coerceAtLeast(0)) }
    }

    fun nextSong() { if (songIndex + 1 < songs.size) selectSong(songIndex + 1) }
    fun previousSong() { if (songIndex > 0) selectSong(songIndex - 1) }

    fun selectSong(index: Int, liveSet: Int = 0) {
        val s = songs.getOrNull(index) ?: return
        songIndex = index
        song = s
        rack = lib.rackSong(s)
        val liveSets = s.liveSets.map { lib.store.getTyped<LiveSet>(it.toRef().digest) }
        liveSetNames.clear()
        liveSetNames.addAll(liveSets.map { it.name })
        switcher = LiveSetSwitcher(s, liveSets)
        prefs.edit().putInt("stage.song", index).apply()
        Log.i(TAG, "song ${index + 1} '${songNames.getOrNull(index)}': engines ${rack?.slots?.keys?.map { it.key }}, " +
                "live sets ${liveSets.map { it.name }}")
        setUpEngines()
        if (liveSets.isNotEmpty()) selectLiveSet(liveSet.coerceIn(0, liveSets.size - 1))
    }

    fun selectLiveSet(index: Int) {
        val sw = switcher ?: return
        if (index !in liveSetNames.indices) return
        val changes = sw.select(index)
        apply(changes)
        liveSetIndex = index
        Log.i(TAG, "live set ${index + 1} '${liveSetNames[index]}': ${changes.size} changes: " +
                changes.joinToString("; ") { c -> when (c) {
                    is Change.Param -> "${c.key}=${"%.2f".format(c.value)}"
                    is Change.WireSet -> "${c.wire}=${if (c.on) "on" else "off"}"
                    else -> c.toString()
                } })
        prefs.edit().putInt("stage.liveSet", index).apply()
    }

    /** What the last external selection was, for the stage view. */
    var lastSelection by mutableStateOf(""); private set

    /**
     * Follows the CP-88's own live set buttons (V1, LOG.md 2026-10-11): a
     * selection arrives as Bank Select MSB 63, LSB page - 1, Program Change
     * set - 1. Set n selects live set n of the current song, whatever the
     * page. Called by the UI every 50 ms.
     */
    fun pollSelections() {
        val pcs = runCatching { NativeBridge.nativeDrainPrograms() }.getOrNull() ?: return
        for (v in pcs) {
            val msb = (v shr 16) and 0x7F
            val lsb = (v shr 8) and 0x7F
            val pc = v and 0x7F
            if (msb == 63) {
                lastSelection = "CP-88 ${lsb + 1}:${pc + 1}"
                if (pc in liveSetNames.indices) selectLiveSet(pc)
            } else {
                lastSelection = "PC ${pc + 1} (bank $msb/$lsb, ch ${(v ushr 24) + 1})"
            }
        }
    }

    fun nextLiveSet() { if (liveSetIndex + 1 < liveSetNames.size) selectLiveSet(liveSetIndex + 1) }
    fun previousLiveSet() { if (liveSetIndex > 0) selectLiveSet(liveSetIndex - 1) }

    /** A stage fader moved. */
    fun setLevel(e: RackEngine, v: Float) {
        setNodeParam(e, "Level", v)
        levels[e] = v
        switcher?.noteValue(e.levelKey, v.toDouble())
    }

    fun panic() = NativeBridge.nativePanic()

    /**
     * Hands the rack back neutral -- every wire on, every zone open -- so the
     * prototype's part tabs, which know nothing of zones and wires, work as
     * they always did. [reload] takes it back.
     */
    fun releaseRack() {
        for (e in RackEngine.entries) {
            midiOn[e.rackIndex] = true; audioOn[e.rackIndex] = true; gain[e.rackIndex] = 1f
            NativeBridge.nativeRackWire(e.rackIndex, true, true, 1f)
            NativeBridge.nativeZoneParam(e.rackIndex, ZONE_PARAMS.indexOf("keyLow"), 0f)
            NativeBridge.nativeZoneParam(e.rackIndex, ZONE_PARAMS.indexOf("keyHigh"), 127f)
            NativeBridge.nativeZoneParam(e.rackIndex, ZONE_PARAMS.indexOf("transpose"), 0f)
            NativeBridge.nativeZoneParam(e.rackIndex, ZONE_PARAMS.indexOf("channelIn"), 0f)
        }
    }

    // ------------------------------------------------------------- applying

    /** The song's engines and patches; engines it does not use are silenced and parked. */
    private fun setUpEngines() {
        val r = rack
        for (e in RackEngine.entries) {
            val slot = r?.slots?.get(e)
            if (slot == null) {
                // Not in this song: no new notes, and level 0 lets the engine park.
                setWire(e, midi = false)
                setNodeParam(e, "Level", 0f)
                levels.remove(e)
                playing.remove(e)
                continue
            }
            loadPreset(e, slot.preset, slot.program)
        }
    }

    private fun loadPreset(e: RackEngine, preset: String?, program: Int?) {
        if (preset.isNullOrEmpty()) return
        runCatching {
            when (e) {
                RackEngine.SFIZZ -> if (host.currentInstrument() != preset) host.selectInstrument(preset)
                RackEngine.HAMMOND -> {
                    val i = NativeBridge.nativeOrganPresetNames().indexOf(preset)
                    if (i >= 0) NativeBridge.nativeOrganPreset(i, 0)
                }
                RackEngine.SURGE ->
                    if (NativeBridge.nativeSurgeReady() && NativeBridge.nativeSurgeCurrentPatchName() != preset) {
                        NativeBridge.nativeSurgeLoadPatchNamed(preset)
                    }
                RackEngine.DEXED -> if (NativeBridge.nativeDexedReady()) NativeBridge.nativeDexedSelectVoiceNamed(preset)
                RackEngine.FLUID -> if (NativeBridge.nativeFluidReady()) {
                    if (NativeBridge.nativeFluidCurrentFont() != preset) NativeBridge.nativeFluidLoadFontNamed(preset)
                    if (program != null) Thread {
                        for (i in 0 until 200) {
                            if (!NativeBridge.nativeFluidBusy()) break
                            Thread.sleep(50)
                        }
                        NativeBridge.nativeFluidSelectPreset(program)
                    }.start()
                }
                RackEngine.PLAITS -> {
                    val i = NativeBridge.nativePlaitsEngineNames().indexOf(preset)
                    if (i >= 0) NativeBridge.nativeSetPlaitsParam(1, i.toFloat())
                }
            }
        }.onFailure { Log.e("bos-ui", "preset $preset for ${e.key}: $it") }
    }

    private fun apply(changes: List<Change>) {
        for (c in changes) when (c) {
            is Change.Param -> applyParam(c.key, c.value.toFloat())
            is Change.WireSet -> {
                val dot = c.wire.lastIndexOf('.')
                val e = RackEngine.ofKey(c.wire.substring(0, dot)) ?: continue
                when (c.wire.substring(dot + 1)) {
                    "midi" -> setWire(e, midi = c.on)
                    "audio" -> setWire(e, audio = c.on, g = c.gain.toFloat())
                }
            }
            is Change.Enable, is Change.Send, is Change.Cp88 -> Unit   // not in the rack slice yet
        }
        for (e in RackEngine.entries) if (rack?.slots?.containsKey(e) == true) playing[e] = midiOn[e.rackIndex]
    }

    private fun applyParam(key: String, v: Float) {
        val first = key.indexOf('.')
        val e = RackEngine.ofKey(key.substring(0, first)) ?: return
        val rest = key.substring(first + 1)
        if (rest.startsWith("zone.")) {
            val i = ZONE_PARAMS.indexOf(rest.removePrefix("zone."))
            if (i >= 0) NativeBridge.nativeZoneParam(e.rackIndex, i, v)
        } else {
            setNodeParam(e, rest, v)
            if (rest == "Level") levels[e] = v
        }
    }

    private fun setWire(e: RackEngine, midi: Boolean? = null, audio: Boolean? = null, g: Float? = null) {
        val i = e.rackIndex
        midi?.let { midiOn[i] = it }
        audio?.let { audioOn[i] = it }
        g?.let { gain[i] = it }
        NativeBridge.nativeRackWire(i, midiOn[i], audioOn[i], gain[i])
    }

    private fun setNodeParam(e: RackEngine, param: String, v: Float) {
        val node = nodeIndex(e) ?: return
        val p = (0 until NativeBridge.nativeNodeParamCount(node))
            .firstOrNull { NativeBridge.nativeNodeParamName(node, it) == param } ?: return
        NativeBridge.nativeSetNodeParam(node, p, v)
    }

    private val nodeIndexCache = HashMap<RackEngine, Int>()
    private fun nodeIndex(e: RackEngine): Int? = nodeIndexCache[e] ?: (0 until NativeBridge.nativeNodeCount())
        .firstOrNull { NativeBridge.nativeNodeType(it) == e.nodeType }?.also { nodeIndexCache[e] = it }

    companion object {
        private const val TAG = "bos-stage"

        /** engine/include/bagosynths/MidiZone.h, `Param`, in order. */
        val ZONE_PARAMS = listOf(
            "channelIn", "channelOut", "keyLow", "keyHigh", "velLow", "velHigh", "transpose",
            "passNotes", "passCC", "passBend", "passPressure", "passProgram",
            "ccFromA", "ccToA", "ccFromB", "ccToB")
    }
}
