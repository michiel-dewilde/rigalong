package be.mdw.rigalong.model

import be.mdw.rigalong.model.devices.Devices

/**
 * The rack: songs as a simple form can edit them (PLAN.md M2/M3). A rack song
 * has INSTANCES -- its sounds, each an instance of one of six engines, several
 * of one engine if it wants (two sfizz, three Surge XT) -- each behind a MIDI
 * zone and two switchable wires; and live sets that say which instances play,
 * at what level, over which keys, transposed how far.
 *
 * Names in the song graph, for instance `i` (its [RackInstance.id]: the
 * engine's key for the first of an engine, "sfizz", then "sfizz-2", ...):
 *   node  `i`        the engine; parameter `Level`; `label` if the player named it
 *   node  `i.zone`   its zone; `keyLow`, `keyHigh`, `transpose`, `channelIn`, ...
 *   (a live set may give the engine's own parameters values of its own too,
 *   `i.<parameter>`, over the song's patch: setBfree's drawbars in one part;
 *   node `i` then holds the song's value of it next to `Level` -- the patch's,
 *   or the one heard from the preset -- so leaving the live set restores it)
 *   wire  `i.midi`   zone -> engine: off means the engine takes no new notes,
 *                    while held ones still end and tails still ring (Wire.h)
 *   wire  `i.audio`  engine -> mix, or its insert effect -> mix
 *   node  `i.fx`     its insert effect, kind `fx.<type>`, when it has one;
 *                    then wire `i.fx.in` runs engine -> effect
 * so a parameter key is `i.Level` or `i.zone.keyLow`. A song with one
 * instance of each engine is exactly the song of before instances were first
 * class: same names, same hash. The instances meet in node `mix`, the reverb,
 * whose parameters ("Room size", "Damping", "Width", "Mix") the song may set;
 * those it leaves out keep the reverb's defaults. A song may put an effect on
 * the whole mix: node `mix.fx`, kind `fx.<type>`, between `mix` and `out`.
 */
enum class RackEngine(val key: String, val nodeType: String, val label: String) {
    SFIZZ("sfizz", "sfizz", "sfizz"),
    SETBFREE("setbfree", "setBfree", "setBfree"),
    SURGE("surge", "Surge XT", "Surge XT"),
    DEXED("dexed", "Dexed", "Dexed"),
    PLAITS("plaits", "Plaits", "Plaits"),
    FLUID("fluid", "FluidSynth", "FluidSynth");

    val rackIndex: Int get() = ordinal

    companion object {
        fun ofKey(k: String): RackEngine? = entries.firstOrNull { it.key == k }
        fun ofNodeType(t: String): RackEngine? = entries.firstOrNull { it.nodeType == t }
    }
}

/**
 * A sound in a rack song: an instance of an engine, first class -- its own
 * patch, level and effect, its own part in every live set, and a name.
 */
data class RackInstance(
    /** Its name in the song graph: the engine's key for the first of an engine, then "<key>-2", ... */
    val id: String,
    val engine: RackEngine,
    val slot: RackSlot = RackSlot(),
    /** What the player calls it ("Pad", "Bass"), or null for the engine's own label. */
    val label: String? = null,
    /**
     * Controllers that move its parameters (I6.1, I6.6): parameter -> MIDI CC,
     * any channel, over the parameter's whole range, picking up. A knob, wheel
     * or pedal; it acts while this sound plays in the live set, so one knob
     * can move setBfree in one live set and Surge XT in another. Stored as
     * the song's `controls`.
     */
    val controls: Map<String, Int> = emptyMap(),
) {
    val levelKey: String get() = "$id.Level"
    fun zoneKey(param: String) = "$id.zone.$param"
    val midiWire: String get() = "$id.midi"
    val audioWire: String get() = "$id.audio"
    val fxAmountKey: String get() = "$id.fx.Amount"
}

/** One engine as a rack song uses it. */
data class RackSlot(
    val preset: String? = null,
    val program: Int? = null,
    val level: Double = 0.8,
    /** The engine's own parameters, by their stable names, over the preset (M8). */
    val params: Map<String, Double> = emptyMap(),
    /** Its insert effect, or null. */
    val fx: RackFx? = null,
    /** The library patch this slot uses (picked or saved by the player), or null. */
    val libraryPatch: Digest? = null,
    /**
     * The song's values of parameters a live set gives values of its own and
     * the patch leaves to its preset, as heard when the live set first
     * differed: what leaving that live set goes back to. Kept with the song,
     * not the patch, so a library patch stays as it is.
     */
    val songValues: Map<String, Double> = emptyMap(),
) {
    /** The patch object this slot is stored as. */
    fun patch(e: RackEngine) = Patch(engine = e.key, preset = preset, program = program, params = params)

    /** This slot taking [p]'s sound -- its preset, program and values -- and its link to library patch [entry]. */
    fun withPatch(p: Patch, entry: Digest? = null) =
        copy(preset = p.preset, program = p.program, params = p.params, libraryPatch = entry)
}

/**
 * An engine's insert effect (M6): one of [TYPES], and its parameters by the
 * effect's own names; a tempo-synced one has "<name> sync" = 1.
 */
data class RackFx(val type: String, val params: Map<String, Double> = emptyMap()) {
    companion object {
        /** In the engine's order (FxNode::Type); "off" is no effect. */
        val TYPES = listOf("off", "delay", "phaser", "flanger", "rotary", "reverb")
    }
}

/** One engine in one live set. */
data class RackPart(
    val plays: Boolean = true,
    val level: Double? = null,           // null: the song's level
    val keyLow: Int = 0,
    val keyHigh: Int = 127,
    val transpose: Int = 0,
    val channelIn: Int = 0,              // 0: any
    /** How much of the engine goes through its insert effect here, 0-1 (a send: tails ring on at 0). */
    val fxAmount: Double = 1.0,
    /**
     * The sound's own parameters as this live set has them, by their stable
     * names, over the song's patch: only what differs here. Plain values, so
     * selecting the live set is as quick as ever -- a preset of its own would
     * have to load; that is another instance ([RackSong.layerFor]).
     */
    val params: Map<String, Double> = emptyMap(),
)

/**
 * What a live set does to one external device (I4.7, I6.7), keyed by the
 * device's name in the environment: leave it alone, select one of its stored
 * sounds at [address] (its driver's fields: a CP-88's page and set, a module's
 * program and bank), or give it a sound of its own -- kept in the library in
 * the driver's form, written into the device's reserved area when the song is
 * selected. See model/devices.
 */
data class RackDevicePart(
    /** The driver: "midi", "yamaha.cp88". */
    val kind: String,
    val mode: String = "leave",          // leave | select | own
    val address: Map<String, Int> = emptyMap(),
    val sound: Digest? = null,           // mode own: the sound object
    val soundName: String? = null,
)

data class RackLiveSet(
    val name: String,
    /** Per instance id, what it does in this live set; an instance left out does not play. */
    val parts: Map<String, RackPart> = emptyMap(),
    /** Per external device, by its name in the environment: what selecting this live set does to it. */
    val devices: Map<String, RackDevicePart> = emptyMap(),
)

/** A rack song as the form edits it. */
data class RackSong(
    val instances: List<RackInstance>,
    val liveSets: List<RackLiveSet>,
    val tempo: Tempo? = null,
    /** The song's lead sheet, a PDF object (S9.3). */
    val leadSheet: Digest? = null,
    /** The reverb the instances meet in, by parameter name; empty = its defaults. */
    val reverb: Map<String, Double> = emptyMap(),
    /** An effect on the whole mix, after the reverb, or null. */
    val masterFx: RackFx? = null,
) {
    fun instance(id: String): RackInstance? = instances.firstOrNull { it.id == id }

    /** [id] with [slot]. */
    fun withSlot(id: String, slot: RackSlot) =
        copy(instances = instances.map { if (it.id == id) it.copy(slot = slot) else it })

    /** An id for another instance of [e]: its key if the song has none yet, else "<key>-n". */
    fun newInstanceId(e: RackEngine): String {
        if (instances.none { it.id == e.key }) return e.key
        var n = 2
        while (instances.any { it.id == "${e.key}-$n" }) n++
        return "${e.key}-$n"
    }

    /** A new instance of [e], playing in every live set. */
    fun plus(e: RackEngine, slot: RackSlot = RackSlot()): RackSong {
        val id = newInstanceId(e)
        return copy(instances = instances + RackInstance(id, e, slot),
            liveSets = liveSets.map { it.copy(parts = it.parts + (id to RackPart())) })
    }

    /** Without instance [id], everywhere. */
    fun minus(id: String) = copy(instances = instances.filter { it.id != id },
        liveSets = liveSets.map { it.copy(parts = it.parts - id) })

    /** Controller [cc] moving [param] of instance [id]; null takes the control off. */
    fun withControl(id: String, param: String, cc: Int?) = copy(instances = instances.map {
        if (it.id != id) it else it.copy(controls = if (cc == null) it.controls - param else it.controls + (param to cc))
    })

    /** Live set [liveSet]'s part for instance [id] replaced by [part]. */
    fun withPart(liveSet: Int, id: String, part: RackPart) = copy(liveSets = liveSets.mapIndexed { k, ls ->
        if (k == liveSet) ls.copy(parts = ls.parts + (id to part)) else ls
    }).tidy()

    /**
     * Live set [liveSet]'s own [value] of [param] on instance [id]. Where the
     * patch leaves [param] to its preset, [songValue] -- the value heard with
     * the song's patch on -- is kept as the song's, the first time.
     */
    fun withLiveSetValue(liveSet: Int, id: String, param: String, value: Double, songValue: Double): RackSong {
        val inst = instance(id) ?: return this
        val part = liveSets.getOrNull(liveSet)?.parts?.get(id) ?: RackPart(plays = false)
        val slot = inst.slot
        val keep = if (param in slot.params || param in slot.songValues) slot
        else slot.copy(songValues = slot.songValues + (param to songValue))
        return withSlot(id, keep).withPart(liveSet, id, part.copy(params = part.params + (param to value)))
    }

    /** The song's value of [param] on instance [id] where it has one: the patch's, or kept for a live set. */
    fun songValue(id: String, param: String): Double? =
        instance(id)?.slot?.let { it.params[param] ?: it.songValues[param] }

    /** Song values no live set needs any more dropped: the patch sets them, or no live set differs. */
    private fun tidy() = copy(instances = instances.map { inst ->
        val used = liveSets.flatMap { it.parts[inst.id]?.params?.keys.orEmpty() }.toSet()
        val keep = inst.slot.songValues.filterKeys { it in used && it !in inst.slot.params }
        if (keep == inst.slot.songValues) inst else inst.copy(slot = inst.slot.copy(songValues = keep))
    })

    /**
     * Live set [liveSet]'s own value of [param] on instance [id] made the
     * song's: the patch takes it, and this live set no longer differs. Other
     * live sets keep values of their own.
     */
    fun useInSong(liveSet: Int, id: String, param: String): RackSong {
        val inst = instance(id) ?: return this
        val part = liveSets.getOrNull(liveSet)?.parts?.get(id) ?: return this
        val v = part.params[param] ?: return this
        // Other live sets with values of their own now differ from the patch's value.
        return withSlot(id, inst.slot.copy(params = inst.slot.params + (param to v)))
            .withPart(liveSet, id, part.copy(params = part.params - param))
    }

    /**
     * A new instance for live set [liveSet] alone, starting as instance [id]
     * sounds there -- its patch with the live set's own values, its part --
     * while [id] stops playing in it. The way to a different preset in one
     * live set, still switched instantly: both are loaded with the song.
     * Returns the song and the new instance's id.
     */
    fun layerFor(liveSet: Int, id: String): Pair<RackSong, String> {
        val inst = instance(id) ?: return this to id
        val ls = liveSets.getOrNull(liveSet) ?: return this to id
        val part = ls.parts[id] ?: RackPart(plays = false)
        val newId = newInstanceId(inst.engine)
        // The same sound keeps its library link; changed here, it is a sound of its own.
        val slot = if (part.params.isEmpty()) inst.slot
        else inst.slot.copy(params = inst.slot.params + part.params, libraryPatch = null)
        val song = copy(instances = instances + RackInstance(newId, inst.engine, slot),
            liveSets = liveSets.mapIndexed { k, l ->
                if (k == liveSet) l.copy(parts = l.parts + (id to part.copy(plays = false)) +
                    (newId to part.copy(plays = true, params = emptyMap())))
                else l.copy(parts = l.parts + (newId to RackPart(plays = false)))
            })
        return song.tidy() to newId
    }

    /** What the player sees it called: its own name, or the engine's label, numbered if the song has several. */
    fun displayName(inst: RackInstance): String {
        inst.label?.let { return it }
        val same = instances.filter { it.engine == inst.engine }
        return if (same.size < 2) inst.engine.label else "${inst.engine.label} ${same.indexOf(inst) + 1}"
    }

    /** The song and its live sets as library objects. Live sets are put into [store]. */
    fun toObjects(store: Store): Song {
        val nodes = LinkedHashMap<String, Node>()
        val wires = LinkedHashMap<String, Wire>()
        nodes["in"] = Node(kind = "port.midiIn", port = "Keys")
        nodes["mix"] = Node(kind = "reverb", params = reverb)
        nodes["out"] = Node(kind = "port.audioOut", port = "Main Out")
        val master = masterFx?.takeIf { it.type != "off" }
        if (master == null) {
            wires["mix.out"] = Wire("mix:out", "out:in")
        } else {
            nodes["mix.fx"] = Node(kind = "fx.${master.type}", params = master.params)
            wires["mix.fx.in"] = Wire("mix:out", "mix.fx:in")
            wires["mix.out"] = Wire("mix.fx:out", "out:in")
        }
        for (inst in instances) {
            val id = inst.id
            val slot = inst.slot
            val patch = store.putTyped(slot.patch(inst.engine))
            // Next to the level, the song's values its live sets go back to (none in most songs).
            val songValues = liveSets.flatMap { it.parts[id]?.params?.keys.orEmpty() }.toSortedSet()
                .filter { isEngineParam(it) }.mapNotNull { k -> songValue(id, k)?.let { k to it } }.toMap()
            nodes[id] = Node(kind = inst.engine.nodeType, patch = RefJ.of(patch, Patch.TYPE),
                patchEntry = slot.libraryPatch?.toString(), params = mapOf("Level" to slot.level) + songValues,
                label = inst.label)
            nodes["$id.zone"] = Node(kind = "midi.zone")
            wires["$id.in"] = Wire("in:midi", "$id.zone:midi")
            wires[inst.midiWire] = Wire("$id.zone:midi", "$id:midi")
            val fx = slot.fx?.takeIf { it.type != "off" }
            if (fx == null) {
                wires[inst.audioWire] = Wire("$id:out", "mix:in")
            } else {
                // Only a song with an effect has the node, so no other song changes hash.
                nodes["$id.fx"] = Node(kind = "fx.${fx.type}", params = fx.params)
                wires["$id.fx.in"] = Wire("$id:out", "$id.fx:in")
                wires[inst.audioWire] = Wire("$id.fx:out", "mix:in")
            }
        }
        val refs = liveSets.map { ls ->
            val overrides = LinkedHashMap<String, Double>()
            val wireStates = LinkedHashMap<String, WireState>()
            for (inst in instances) {
                val part = ls.parts[inst.id] ?: RackPart(plays = false)
                if (!part.plays) wireStates[inst.midiWire] = WireState(on = false)
                part.level?.let { overrides[inst.levelKey] = it }
                // Zones always written, so every live set recalls its own split.
                overrides[inst.zoneKey("keyLow")] = part.keyLow.toDouble()
                overrides[inst.zoneKey("keyHigh")] = part.keyHigh.toDouble()
                overrides[inst.zoneKey("transpose")] = part.transpose.toDouble()
                overrides[inst.zoneKey("channelIn")] = part.channelIn.toDouble()
                // Written in every live set while the instance has an effect, like the zones.
                if (inst.slot.fx?.takeIf { it.type != "off" } != null) overrides[inst.fxAmountKey] = part.fxAmount
                for ((name, v) in part.params) if (isEngineParam(name)) overrides["${inst.id}.$name"] = v
            }
            val devices = ls.devices.mapNotNull { (name, d) ->
                when (d.mode) {
                    "select" -> name to DeviceAction(d.kind, "select", address = d.address)
                    "own" -> d.sound?.let { name to DeviceAction(d.kind, "own", sound = RefJ.of(it, soundType(d.kind))) }
                    else -> null
                }
            }.toMap()
            RefJ.of(store.putTyped(LiveSet(name = ls.name, overrides = overrides, wires = wireStates, devices = devices)),
                LiveSet.TYPE)
        }
        // Controls (I6.1): min and max as fractions of the parameter's range; channel 0 is any.
        val controls = instances.flatMap { inst ->
            inst.controls.entries.sortedBy { it.key }.map { (param, cc) ->
                Control(id = "${inst.id}.$param", label = param, target = "${inst.id}.$param", widget = "knob",
                    midi = MidiBinding(port = "Keys", channel = 0, cc = cc))
            }
        }
        return Song(nodes = nodes, wires = wires, liveSets = refs, tempo = tempo, controls = controls,
            leadSheet = leadSheet?.let { RefJ.of(it, LEAD_SHEET_TYPE) })
    }

    companion object {
        const val LEAD_SHEET_TYPE = "application/pdf"

        /** Reads a rack song back; null if [song] is not rack-shaped. */
        fun fromObjects(store: Store, song: Song): RackSong? {
            val instances = ArrayList<RackInstance>()
            for ((name, node) in song.nodes) {
                if ('.' in name || name in PORTS) continue
                // Any other top-level node must be an engine, or the song is not rack-shaped.
                val e = RackEngine.ofNodeType(node.kind) ?: return null
                val patch = node.patch?.let { store.getTyped<Patch>(it.toRef().digest) }
                val fx = song.nodes["$name.fx"]?.let { RackFx(it.kind.removePrefix("fx."), it.params) }
                instances += RackInstance(name, e, RackSlot(patch?.preset, patch?.program, node.params["Level"] ?: 0.8,
                    patch?.params ?: emptyMap(), fx, node.patchEntry?.let { Digest.parse(it) },
                    node.params.filterKeys { isEngineParam(it) && it !in patch?.params.orEmpty() }), node.label)
            }
            // Their controls: "<instance>.<parameter>" moved by a controller.
            for (c in song.controls) {
                val cc = c.midi?.cc ?: continue
                val k = instances.indexOfFirst { c.target.startsWith("${it.id}.") }.takeIf { it >= 0 } ?: continue
                val inst = instances[k]
                instances[k] = inst.copy(controls = inst.controls + (c.target.removePrefix("${inst.id}.") to cc))
            }
            // Engine order, then the order they were added ("sfizz", "sfizz-2", "sfizz-10").
            instances.sortWith(compareBy({ it.engine.ordinal }, { it.id.substringAfter('-', "1").toIntOrNull() ?: 0 }))
            val liveSets = song.liveSets.map { r ->
                val ls = store.getTyped<LiveSet>(r.toRef().digest)
                val parts = LinkedHashMap<String, RackPart>()
                for (inst in instances) {
                    parts[inst.id] = RackPart(
                        plays = ls.wires[inst.midiWire]?.on ?: true,
                        level = ls.overrides[inst.levelKey],
                        keyLow = ls.overrides[inst.zoneKey("keyLow")]?.toInt() ?: 0,
                        keyHigh = ls.overrides[inst.zoneKey("keyHigh")]?.toInt() ?: 127,
                        transpose = ls.overrides[inst.zoneKey("transpose")]?.toInt() ?: 0,
                        channelIn = ls.overrides[inst.zoneKey("channelIn")]?.toInt() ?: 0,
                        fxAmount = ls.overrides[inst.fxAmountKey] ?: 1.0,
                        params = ls.overrides.mapNotNull { (k, v) ->
                            k.removePrefix("${inst.id}.").takeIf { it != k && isEngineParam(it) }?.let { it to v }
                        }.toMap(),
                    )
                }
                val devices = ls.devices.mapValues { (_, a) ->
                    val d = a.sound?.toRef()?.digest
                    RackDevicePart(a.kind, a.mode, a.address, d,
                        d?.let { store.get(it) }?.let { Devices.driver(a.kind)?.sounds?.name(it) })
                }
                RackLiveSet(ls.name, parts, devices)
            }
            return RackSong(instances, liveSets, song.tempo, song.leadSheet?.toRef()?.digest,
                song.nodes["mix"]?.params ?: emptyMap(),
                song.nodes["mix.fx"]?.let { RackFx(it.kind.removePrefix("fx."), it.params) })
        }

        /** The media type of a [kind] device's own sounds. */
        private fun soundType(kind: String) = Devices.driver(kind)?.sounds?.mediaType ?: MediaTypes.OCTET

        /** A key's part after "<instance>.": the engine's own parameter, not its level, zone or effect. */
        private fun isEngineParam(rest: String) = rest != "Level" && !rest.startsWith("zone.") && !rest.startsWith("fx.")

        /** The song graph's own nodes, which are not instances. */
        private val PORTS = setOf("in", "mix", "out")

        /** A new song: one instance, one live set. */
        fun starter(engine: RackEngine = RackEngine.SFIZZ, preset: String? = null) = RackSong(
            instances = listOf(RackInstance(engine.key, engine, RackSlot(preset = preset))),
            liveSets = listOf(RackLiveSet("Main", mapOf(engine.key to RackPart()))),
        )
    }
}
