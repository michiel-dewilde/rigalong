package be.mdw.bagosynths.model

/**
 * The rack: songs as a simple form can edit them (PLAN.md M2/M3). A rack song
 * has INSTANCES -- its sounds, each an instance of one of six engines, several
 * of one engine if it wants (two pianos, three synths) -- each behind a MIDI
 * zone and two switchable wires; and live sets that say which instances play,
 * at what level, over which keys, transposed how far.
 *
 * Names in the song graph, for instance `i` (its [RackInstance.id]: the
 * engine's key for the first of an engine, "sfizz", then "sfizz-2", ...):
 *   node  `i`        the engine; parameter `Level`; `label` if the player named it
 *   node  `i.zone`   its zone; `keyLow`, `keyHigh`, `transpose`, `channelIn`, ...
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
    SFIZZ("sfizz", "sfizz", "Piano"),
    HAMMOND("hammond", "Hammond", "Organ"),
    SURGE("surge", "Surge XT", "Synth"),
    DEXED("dexed", "Dexed", "FM"),
    PLAITS("plaits", "Plaits", "Texture"),
    FLUID("fluid", "FluidSynth", "SF2");

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
)

/**
 * What a live set does to the CP-88 (I4.7): leave it alone, select one of its
 * stored sounds, or own a sound (stored in the library, written into the
 * environment's reserved page when the song is selected).
 */
data class RackCp88(
    val mode: String = "leave",          // leave | select | own
    val page: Int = 1,
    val set: Int = 1,
    val sound: Digest? = null,           // mode own: the sound object
    val soundName: String? = null,
)

/**
 * A program change sent when the live set is selected (I6.7), for any
 * keyboard or sound module on the MIDI out: channel 1-16, program 1-128, and a
 * bank select before it when given (MSB, then LSB, 0-127).
 */
data class RackProgram(val channel: Int = 1, val program: Int = 1, val bankMsb: Int? = null, val bankLsb: Int? = null) {
    fun bytes(): ByteArray {
        val ch = (channel - 1).coerceIn(0, 15)
        val out = ArrayList<Int>()
        bankMsb?.let { out += listOf(0xB0 or ch, 0x00, it.coerceIn(0, 127)) }
        bankLsb?.let { out += listOf(0xB0 or ch, 0x20, it.coerceIn(0, 127)) }
        out += listOf(0xC0 or ch, (program - 1).coerceIn(0, 127))
        return ByteArray(out.size) { out[it].toByte() }
    }

    fun hex(): String = bytes().joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        /** Reads back what [hex] wrote; null for anything else. */
        fun parse(hex: String): RackProgram? {
            if (hex.length % 2 != 0) return null
            val b = runCatching { (hex.indices step 2).map { hex.substring(it, it + 2).toInt(16) } }.getOrNull() ?: return null
            var i = 0
            var msb: Int? = null
            var lsb: Int? = null
            var ch: Int? = null
            while (i + 2 < b.size && (b[i] and 0xF0) == 0xB0) {
                if (ch != null && (b[i] and 0x0F) != ch) return null
                ch = b[i] and 0x0F
                when (b[i + 1]) {
                    0x00 -> msb = b[i + 2]
                    0x20 -> lsb = b[i + 2]
                    else -> return null
                }
                i += 3
            }
            if (i + 2 != b.size || (b[i] and 0xF0) != 0xC0 || (ch != null && (b[i] and 0x0F) != ch)) return null
            return RackProgram((b[i] and 0x0F) + 1, b[i + 1] + 1, msb, lsb)
        }
    }
}

data class RackLiveSet(
    val name: String,
    /** Per instance id, what it does in this live set; an instance left out does not play. */
    val parts: Map<String, RackPart> = emptyMap(),
    val cp88: RackCp88 = RackCp88(),
    /** Sent to the MIDI out when this live set is selected, or null. */
    val program: RackProgram? = null,
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
            nodes[id] = Node(kind = inst.engine.nodeType, patch = RefJ.of(patch, Patch.TYPE),
                patchEntry = slot.libraryPatch?.toString(), params = mapOf("Level" to slot.level), label = inst.label)
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
            }
            val cp = when (ls.cp88.mode) {
                "select" -> Cp88Action("select", page = ls.cp88.page, set = ls.cp88.set)
                "own" -> ls.cp88.sound?.let { Cp88Action("own", sound = RefJ.of(it, Cp88.MEDIA_TYPE)) }
                else -> null
            }
            val sends = listOfNotNull(ls.program?.let { MidiSend(port = "Keys", hex = it.hex()) })
            RefJ.of(store.putTyped(LiveSet(name = ls.name, overrides = overrides, wires = wireStates, cp88 = cp,
                onSelect = sends)), LiveSet.TYPE)
        }
        return Song(nodes = nodes, wires = wires, liveSets = refs, tempo = tempo,
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
                    patch?.params ?: emptyMap(), fx, node.patchEntry?.let { Digest.parse(it) }), node.label)
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
                    )
                }
                val cp = ls.cp88?.let { a ->
                    val d = a.sound?.toRef()?.digest
                    RackCp88(a.mode, a.page ?: 1, a.set ?: 1, d, d?.let { store.get(it) }?.let(Cp88::name))
                } ?: RackCp88()
                RackLiveSet(ls.name, parts, cp, ls.onSelect.firstNotNullOfOrNull { RackProgram.parse(it.hex) })
            }
            return RackSong(instances, liveSets, song.tempo, song.leadSheet?.toRef()?.digest,
                song.nodes["mix"]?.params ?: emptyMap(),
                song.nodes["mix.fx"]?.let { RackFx(it.kind.removePrefix("fx."), it.params) })
        }

        /** The song graph's own nodes, which are not instances. */
        private val PORTS = setOf("in", "mix", "out")

        /** A new song: one instance, one live set. */
        fun starter(engine: RackEngine = RackEngine.SFIZZ, preset: String? = null) = RackSong(
            instances = listOf(RackInstance(engine.key, engine, RackSlot(preset = preset))),
            liveSets = listOf(RackLiveSet("Main", mapOf(engine.key to RackPart()))),
        )
    }
}
