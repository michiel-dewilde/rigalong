package com.rigalong.play

import android.util.Log
import com.rigalong.NativeBridge
import com.rigalong.model.LiveSetSwitcher.Change
import com.rigalong.model.RackEngine
import com.rigalong.model.RackFx
import com.rigalong.model.RackLiveSet
import com.rigalong.model.RackSlot
import com.rigalong.model.RackSong

/**
 * The calls into the engine (AudioEngine.h) for the songs: which engine
 * instance each song instance plays on, their presets, parameters, insert
 * effects, wires and controller maps, the reverb and the effect on the whole
 * mix. Knows nothing of setlists or live sets; [SongPlayer] says what to put
 * on (REVIEW S3).
 */
class EngineRack(private val host: EngineHost, private val note: (String) -> Unit) {

    /** Instance levels as they are now, by song instance id, for the stage faders. */
    val levels = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    /** Whether each song instance gets the keys now (its MIDI wire), for the stage. */
    val playing = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    /** The stage song's instances on the engine's: song instance id -> engine instance. */
    var onEngine: Map<String, Int> = emptyMap(); private set

    // Per ENGINE instance (AudioEngine::kMaxInstances).
    private val midiOn = BooleanArray(MAX_INSTANCES) { true }
    private val audioOn = BooleanArray(MAX_INSTANCES) { true }
    private val gain = FloatArray(MAX_INSTANCES) { 1f }

    /**
     * The engine instance each of [song]'s instances plays on: the k-th of an
     * engine in the song on the k-th engine instance of that type (the one of
     * the default patch first), more added when the song has more. The same
     * song always gets the same ones, so the stage and the editor agree.
     */
    private fun assignment(song: RackSong?): Map<String, Int> {
        if (song == null) return emptyMap()
        val count = runCatching { NativeBridge.nativeInstanceCount() }.getOrDefault(0)
        val byType = HashMap<Int, MutableList<Int>>()
        for (i in 0 until count) byType.getOrPut(NativeBridge.nativeInstanceType(i)) { mutableListOf() } += i
        val seen = HashMap<RackEngine, Int>()
        val out = LinkedHashMap<String, Int>()
        for (inst in song.instances) {
            val k = seen.merge(inst.engine, 1, Int::plus)!! - 1
            val pool = byType.getOrPut(inst.engine.ordinal) { mutableListOf() }
            while (pool.size <= k) {
                val n = NativeBridge.nativeAddInstance(inst.engine.ordinal)
                if (n < 0) break
                pool += n
                note("instance $n added for ${inst.engine.label}")
            }
            pool.getOrNull(k)?.let { out[inst.id] = it }
        }
        return out
    }

    private fun busy(i: Int) = runCatching { NativeBridge.nativeInstanceBusy(i) }.getOrDefault(false)

    /** A preset of the stage song is still loading in the background. */
    fun anyBusy(): Boolean = onEngine.values.any { busy(it) }

    private val nodeCache = HashMap<Int, Int>()
    private fun nodeOf(i: Int): Int? = nodeCache[i] ?: NativeBridge.nativeInstanceNode(i).takeIf { it >= 0 }
        ?.also { nodeCache[i] = it }

    /** The graph node of the stage song's instance [id], or null. */
    fun nodeOfInstance(id: String): Int? = onEngine[id]?.let { nodeOf(it) }

    private fun paramIndex(node: Int, name: String): Int? = (0 until NativeBridge.nativeNodeParamCount(node))
        .firstOrNull { NativeBridge.nativeNodeParamName(node, it) == name }

    private fun setParamAt(i: Int, param: String, v: Float) {
        val node = nodeOf(i) ?: return
        paramIndex(node, param)?.let { NativeBridge.nativeSetNodeParam(node, it, v) }
    }

    /** The engine's value of "<instance>.<parameter>" now. */
    fun engineValue(key: String): Double? {
        val dot = key.indexOf('.')
        val node = nodeOfInstance(key.substring(0, dot)) ?: return null
        val p = paramIndex(node, key.substring(dot + 1)) ?: return null
        return NativeBridge.nativeGetNodeParam(node, p).toDouble()
    }

    /** One parameter of an engine, as the patch editor shows it (PLAN.md D6). */
    data class ParamDesc(val name: String, val min: Float, val max: Float, val integer: Boolean, val value: Float)

    private fun paramsOf(node: Int, from: Int = 0, skip: Set<String> = emptySet()): List<ParamDesc> =
        (from until NativeBridge.nativeNodeParamCount(node)).mapNotNull { p ->
            val name = NativeBridge.nativeNodeParamName(node, p)
            if (name.isEmpty() || name in skip) return@mapNotNull null
            val r = NativeBridge.nativeNodeParamRange(node, p)
            ParamDesc(name, r[0], r[1], r[2] > 0.5f, NativeBridge.nativeGetNodeParam(node, p))
        }

    // ------------------------------------------------------------ the editors

    /** Every parameter of [song]'s instance [id] but its level, with its range and current value. */
    fun engineParams(song: RackSong, id: String): List<ParamDesc> {
        val node = assignment(song)[id]?.let { nodeOf(it) } ?: return emptyList()
        return paramsOf(node, skip = setOf("Level"))
    }

    /**
     * Puts [song]'s instance [id] with [slot] on its engine instance, so the
     * patch editor shows -- and the keys play -- this song's patch, not
     * whatever was on stage. [SongPlayer.reload] puts the stage's song back.
     */
    fun previewSlot(song: RackSong, id: String, slot: RackSlot) {
        val inst = song.instance(id) ?: return
        val i = assignment(song)[id] ?: return
        loadPresetAt(i, inst.engine, slot.preset, slot.program)
        applyFxAt(fxNodeOf(i), slot.fx)
        // The song's sound: its patch, and the values it keeps for its live sets.
        for ((name, v) in slot.songValues + slot.params) setParamAt(i, name, v.toFloat())
    }

    /** Whether [song]'s instance [id] is loading a preset in the background (its values not yet its own). */
    fun loading(song: RackSong, id: String): Boolean = assignment(song)[id]?.let { busy(it) } ?: false

    /** Audition one parameter while editing a patch. */
    fun auditionParam(song: RackSong, id: String, name: String, v: Float) {
        assignment(song)[id]?.let { setParamAt(it, name, v) }
    }

    /** The reverb's parameters, with their ranges and current values. */
    fun reverbParams(): List<ParamDesc> = reverbIndex()?.let { paramsOf(it) } ?: emptyList()

    /** Puts a song's reverb on the engine: its own values over the defaults. */
    fun previewReverb(values: Map<String, Double>) {
        for ((name, v) in REVERB_DEFAULTS + values) auditionReverb(name, v.toFloat())
    }

    fun auditionReverb(name: String, v: Float) {
        val node = reverbIndex() ?: return
        paramIndex(node, name)?.let { NativeBridge.nativeSetNodeParam(node, it, v) }
    }

    private var reverbNode: Int? = null
    private fun reverbIndex(): Int? = reverbNode ?: (0 until NativeBridge.nativeNodeCount())
        .firstOrNull { NativeBridge.nativeNodeType(it) == "Reverb" }?.also { reverbNode = it }

    // ---------------------------------------------------------- insert effects

    private val fxCache = HashMap<Int, Int>()
    private fun fxNodeOf(i: Int): Int? = fxCache[i] ?: NativeBridge.nativeInstanceFxNode(i).takeIf { it >= 0 }
        ?.also { fxCache[i] = it }

    /** The effect on the whole mix: the Fx node that is no instance's. */
    private var masterFxNode: Int? = null
    private fun masterFx(): Int? = masterFxNode ?: run {
        val mine = (0 until NativeBridge.nativeInstanceCount()).mapNotNull { fxNodeOf(it) }.toSet()
        (0 until NativeBridge.nativeNodeCount()).firstOrNull { NativeBridge.nativeNodeType(it) == "Fx" && it !in mine }
    }?.also { masterFxNode = it }

    /** The effect node of [song]'s instance [id], or of the whole mix for null. */
    private fun fxTarget(song: RackSong?, id: String?): Int? =
        if (id == null) masterFx() else assignment(song)[id]?.let { fxNodeOf(it) }

    /**
     * Puts [fx] on an effect node: a fresh effect that starts at the song's
     * values (FxNode::configure), so nothing comes from the last song and no
     * delay glides in from its default time.
     */
    private fun applyFxAt(node: Int?, fx: RackFx?) {
        node ?: return
        val type = RackFx.TYPES.indexOf(fx?.type ?: "off").coerceAtLeast(0)
        val params = fx?.params.orEmpty()
        NativeBridge.nativeFxConfigure(node, type, params.keys.toTypedArray(),
            params.values.map { it.toFloat() }.toFloatArray())
        // Fully on until a live set says otherwise: an amount left at 0 by the
        // previous song would make a new effect silent.
        paramIndex(node, "Amount")?.let { NativeBridge.nativeSetNodeParam(node, it, 1f) }
    }

    /** [fx] on [song]'s instance [id], or on the whole mix for null (the effect editor). */
    fun applyFx(song: RackSong?, id: String?, fx: RackFx?) = applyFxAt(fxTarget(song, id), fx)

    /** The effect's parameters as they are now (the type excluded). */
    fun fxParams(song: RackSong?, id: String?): List<ParamDesc> =
        fxTarget(song, id)?.let { paramsOf(it, from = 1) } ?: emptyList()

    fun auditionFx(song: RackSong?, id: String?, name: String, v: Float) {
        val node = fxTarget(song, id) ?: return
        paramIndex(node, name)?.let { NativeBridge.nativeSetNodeParam(node, it, v) }
    }

    /** [v] of the effect's parameter [name] as the player reads it, or null. */
    fun fxDisplay(song: RackSong?, id: String?, name: String, v: Float): String? {
        val node = fxTarget(song, id) ?: return null
        return paramIndex(node, name)?.let { NativeBridge.nativeNodeParamDisplay(node, it, v) }
    }

    // ------------------------------------------------------------ the stage

    /** A stage fader moved: heard at once. */
    fun setLevel(id: String, v: Float) {
        onEngine[id]?.let { setParamAt(it, "Level", v) }
        levels[id] = v
    }

    fun panic() = NativeBridge.nativePanic()

    /** [song]'s instances on the engine's, with their patches; engine instances it does not use are silenced and parked. */
    fun setUp(song: RackSong?) {
        // Every song sets the whole reverb, so one song's room never stays
        // with the next.
        previewReverb(song?.reverb.orEmpty())
        applyFxAt(masterFx(), song?.masterFx)
        onEngine = assignment(song)
        val used = onEngine.values.toSet()
        levels.keys.retainAll(onEngine.keys)
        playing.keys.retainAll(onEngine.keys)
        for (i in 0 until NativeBridge.nativeInstanceCount()) {
            if (i in used) continue
            // Not in this song: no new notes, no effect, and level 0 lets the engine park.
            applyFxAt(fxNodeOf(i), null)
            setWireAt(i, midi = false)
            setParamAt(i, "Level", 0f)
        }
        for (inst in song?.instances.orEmpty()) {
            val i = onEngine[inst.id] ?: continue
            applyFxAt(fxNodeOf(i), inst.slot.fx)
            loadPresetAt(i, inst.engine, inst.slot.preset, inst.slot.program)
            // The patch's own parameters, over its preset (M8).
            for ((name, v) in inst.slot.params) setParamAt(i, name, v.toFloat())
        }
    }

    /** The patches' own values on again over their presets, once these have landed. */
    fun applyPatchValues(song: RackSong?) {
        for (inst in song?.instances.orEmpty()) {
            val i = onEngine[inst.id] ?: continue
            for ((name, v) in inst.slot.params) setParamAt(i, name, v.toFloat())
        }
    }

    private fun loadPresetAt(i: Int, e: RackEngine, wanted: String?, program: Int?) {
        // "As loaded" on an added instance means what the first of its engine
        // has: a new instance starts empty, and an empty sfizz is a silent one.
        val preset = wanted?.takeIf { it.isNotEmpty() } ?: if (i == e.ordinal) null else runCatching {
            when (e) {
                RackEngine.SFIZZ -> host.currentInstrument()
                RackEngine.SURGE -> NativeBridge.nativeSurgeCurrentPatchName()
                RackEngine.FLUID -> NativeBridge.nativeFluidCurrentFont()
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (preset.isNullOrEmpty()) return
        runCatching {
            // The first sfizz goes through EngineContent, which keeps the
            // choice for the next start and reads the samples' heads into the
            // page cache before the keys need them.
            if (i == e.ordinal && e == RackEngine.SFIZZ) {
                if (host.currentInstrument() != preset) host.selectInstrument(preset)
                return
            }
            // Every other instance, the first of its engine included: by name,
            // through one loader (AudioEngine::loadInstancePreset). It remembers
            // a preset asked for before its engine is up and loads it once the
            // engine is (REVIEW S2, W7: the first Surge XT, Dexed and FluidSynth
            // used to be skipped here while not ready, and played whatever the
            // engine came up with).
            if (NativeBridge.nativeInstancePreset(i) == preset && program == null) return
            val path = if (e == RackEngine.SFIZZ) host.instrumentPath(preset) else null
            NativeBridge.nativeInstanceLoadPreset(i, preset, program ?: -1, path)
        }.onFailure { Log.e("rigalong-ui", "preset $preset for instance $i (${e.key}): $it") }
    }

    /** A song key ("sfizz-2.zone.keyLow", "setbfree.Level") on the engine instance its song instance plays on. */
    fun applyParam(key: String, v: Float) {
        val first = key.indexOf('.')
        if (first < 0) return
        val id = key.substring(0, first)
        val i = onEngine[id] ?: return
        val rest = key.substring(first + 1)
        if (rest.startsWith("zone.")) {
            val z = ZONE_PARAMS.indexOf(rest.removePrefix("zone."))
            if (z >= 0) NativeBridge.nativeZoneParam(i, z, v)
        } else if (rest.startsWith("fx.")) {
            val node = fxNodeOf(i) ?: return
            paramIndex(node, rest.removePrefix("fx."))?.let { NativeBridge.nativeSetNodeParam(node, it, v) }
        } else {
            setParamAt(i, rest, v)
            if (rest == "Level") levels[id] = v
        }
    }

    /** A live set's wire ("<instance>.midi" / "<instance>.audio") on or off. */
    fun applyWire(c: Change.WireSet) {
        val dot = c.wire.lastIndexOf('.')
        val i = onEngine[c.wire.substring(0, dot)] ?: return
        when (c.wire.substring(dot + 1)) {
            "midi" -> setWireAt(i, midi = c.on)
            "audio" -> setWireAt(i, audio = c.on, g = c.gain.toFloat())
        }
    }

    /** [playing] as the wires are now. */
    fun showWires() {
        for ((id, i) in onEngine) playing[id] = midiOn[i]
    }

    /** No instance gets the keys: off the stage ([SongPlayer.onStage]). [playing] says so too. */
    fun silence() {
        for (i in 0 until runCatching { NativeBridge.nativeInstanceCount() }.getOrDefault(0)) setWireAt(i, midi = false)
        for ((id, i) in onEngine) playing[id] = midiOn[i]
    }

    private fun setWireAt(i: Int, midi: Boolean? = null, audio: Boolean? = null, g: Float? = null) {
        if (i !in 0 until MAX_INSTANCES) return
        midi?.let { midiOn[i] = it }
        audio?.let { audioOn[i] = it }
        g?.let { gain[i] = it }
        NativeBridge.nativeRackWire(i, midiOn[i], audioOn[i], gain[i])
    }

    /**
     * The controls of the sounds playing in live set [ls] onto the engine
     * (AudioEngine::setControlMaps): a knob moves its parameter on the render
     * thread, picking up first. A sound not playing here leaves its knob free
     * for another's. Returns each map's "<instance>.<parameter>", in the
     * engine's order (bit k of [takeMovedControls]), with its value now.
     */
    fun mapControls(song: RackSong?, ls: RackLiveSet?): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        val ccs = ArrayList<Int>(); val nodes = ArrayList<Int>(); val params = ArrayList<Int>()
        val mins = ArrayList<Float>(); val maxs = ArrayList<Float>(); val ints = ArrayList<Boolean>()
        if (song != null && ls != null) for (inst in song.instances) {
            if (ls.parts[inst.id]?.plays != true) continue
            val node = nodeOfInstance(inst.id) ?: continue
            for ((param, cc) in inst.controls) {
                val p = paramIndex(node, param) ?: continue
                val range = NativeBridge.nativeNodeParamRange(node, p)
                ccs += cc; nodes += node; params += p
                mins += range[0]; maxs += range[1]; ints += range[2] > 0.5f
                out += "${inst.id}.$param" to NativeBridge.nativeGetNodeParam(node, p).toDouble()
            }
        }
        runCatching {
            NativeBridge.nativeSetControlMaps(ccs.toIntArray(), nodes.toIntArray(), params.toIntArray(),
                mins.toFloatArray(), maxs.toFloatArray(), ints.toBooleanArray())
        }
        return out
    }

    /** Which controller maps moved since the last call: bit k for map k of [mapControls]. */
    fun takeMovedControls(): Long = runCatching { NativeBridge.nativeTakeMovedControls() }.getOrDefault(0L)

    companion object {
        /** AudioEngine::kMaxInstances. */
        const val MAX_INSTANCES = 24
        /** ReverbNode's own defaults (graph/Nodes.h), for a song that sets none. */
        val REVERB_DEFAULTS = mapOf("Room size" to 0.72, "Damping" to 0.35, "Width" to 1.0, "Mix" to 0.25)

        /** engine/include/rigalong/MidiZone.h, `Param`, in order. */
        val ZONE_PARAMS = listOf(
            "channelIn", "channelOut", "keyLow", "keyHigh", "velLow", "velHigh", "transpose",
            "passNotes", "passCC", "passBend", "passPressure", "passProgram",
            "ccFromA", "ccToA", "ccFromB", "ccToB")
    }
}
