package be.mdw.bagosynths.model

/**
 * The rack: the first slice of songs (PLAN.md M2/M3). The engine runs one
 * instance of each of six engines, each behind a MIDI zone and two switchable
 * wires. A rack song is a song whose graph is exactly that shape, so a simple
 * form can edit it: which engines it uses with which patch, and per live set
 * which engines play, at what level, over which keys, transposed how far.
 *
 * Names in the song graph, for engine `e` (its [RackEngine.key]):
 *   node  `e`        the engine; parameter `Level`
 *   node  `e.zone`   its zone; `keyLow`, `keyHigh`, `transpose`, `channelIn`, ...
 *   wire  `e.midi`   zone -> engine: off means the engine takes no new notes,
 *                    while held ones still end and tails still ring (Wire.h)
 *   wire  `e.audio`  engine -> mix
 * so a parameter key is `e.Level` or `e.zone.keyLow`.
 */
enum class RackEngine(val key: String, val nodeType: String, val label: String) {
    SFIZZ("sfizz", "sfizz", "Piano"),
    HAMMOND("hammond", "Hammond", "Organ"),
    SURGE("surge", "Surge XT", "Synth"),
    DEXED("dexed", "Dexed", "FM"),
    PLAITS("plaits", "Plaits", "Texture"),
    FLUID("fluid", "FluidSynth", "SF2");

    val rackIndex: Int get() = ordinal
    val levelKey: String get() = "$key.Level"
    fun zoneKey(param: String) = "$key.zone.$param"
    val midiWire: String get() = "$key.midi"
    val audioWire: String get() = "$key.audio"

    companion object {
        fun ofKey(k: String): RackEngine? = entries.firstOrNull { it.key == k }
    }
}

/** One engine as a rack song uses it. */
data class RackSlot(val preset: String? = null, val program: Int? = null, val level: Double = 0.8)

/** One engine in one live set. */
data class RackPart(
    val plays: Boolean = true,
    val level: Double? = null,           // null: the song's level
    val keyLow: Int = 0,
    val keyHigh: Int = 127,
    val transpose: Int = 0,
    val channelIn: Int = 0,              // 0: any
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

data class RackLiveSet(
    val name: String,
    val parts: Map<RackEngine, RackPart> = emptyMap(),
    val cp88: RackCp88 = RackCp88(),
)

/** A rack song as the form edits it. */
data class RackSong(
    val slots: Map<RackEngine, RackSlot>,
    val liveSets: List<RackLiveSet>,
    val tempo: Tempo? = null,
) {
    /** The song and its live sets as library objects. Live sets are put into [store]. */
    fun toObjects(store: Store): Song {
        val nodes = LinkedHashMap<String, Node>()
        val wires = LinkedHashMap<String, Wire>()
        nodes["in"] = Node(kind = "port.midiIn", port = "Keys")
        nodes["mix"] = Node(kind = "reverb")
        nodes["out"] = Node(kind = "port.audioOut", port = "Main Out")
        wires["mix.out"] = Wire("mix:out", "out:in")
        for ((e, slot) in slots.toSortedMap()) {
            val patch = store.putTyped(Patch(engine = e.key, preset = slot.preset, program = slot.program))
            nodes[e.key] = Node(kind = e.nodeType, patch = RefJ.of(patch, Patch.TYPE),
                params = mapOf("Level" to slot.level))
            nodes["${e.key}.zone"] = Node(kind = "midi.zone")
            wires["${e.key}.in"] = Wire("in:midi", "${e.key}.zone:midi")
            wires[e.midiWire] = Wire("${e.key}.zone:midi", "${e.key}:midi")
            wires[e.audioWire] = Wire("${e.key}:out", "mix:in")
        }
        val refs = liveSets.map { ls ->
            val overrides = LinkedHashMap<String, Double>()
            val wireStates = LinkedHashMap<String, WireState>()
            for (e in slots.keys.sorted()) {
                val part = ls.parts[e] ?: RackPart(plays = false)
                if (!part.plays) wireStates[e.midiWire] = WireState(on = false)
                part.level?.let { overrides[e.levelKey] = it }
                // Zones always written, so every live set recalls its own split.
                overrides[e.zoneKey("keyLow")] = part.keyLow.toDouble()
                overrides[e.zoneKey("keyHigh")] = part.keyHigh.toDouble()
                overrides[e.zoneKey("transpose")] = part.transpose.toDouble()
                overrides[e.zoneKey("channelIn")] = part.channelIn.toDouble()
            }
            val cp = when (ls.cp88.mode) {
                "select" -> Cp88Action("select", page = ls.cp88.page, set = ls.cp88.set)
                "own" -> ls.cp88.sound?.let { Cp88Action("own", sound = RefJ.of(it, Cp88.MEDIA_TYPE)) }
                else -> null
            }
            RefJ.of(store.putTyped(LiveSet(name = ls.name, overrides = overrides, wires = wireStates, cp88 = cp)),
                LiveSet.TYPE)
        }
        return Song(nodes = nodes, wires = wires, liveSets = refs, tempo = tempo)
    }

    companion object {
        /** Reads a rack song back; null if [song] is not rack-shaped. */
        fun fromObjects(store: Store, song: Song): RackSong? {
            val slots = LinkedHashMap<RackEngine, RackSlot>()
            for ((name, node) in song.nodes) {
                val e = RackEngine.ofKey(name) ?: continue
                if (node.kind != e.nodeType) return null
                val patch = node.patch?.let { store.getTyped<Patch>(it.toRef().digest) }
                slots[e] = RackSlot(patch?.preset, patch?.program, node.params["Level"] ?: 0.8)
            }
            val liveSets = song.liveSets.map { r ->
                val ls = store.getTyped<LiveSet>(r.toRef().digest)
                val parts = LinkedHashMap<RackEngine, RackPart>()
                for (e in slots.keys) {
                    parts[e] = RackPart(
                        plays = ls.wires[e.midiWire]?.on ?: true,
                        level = ls.overrides[e.levelKey],
                        keyLow = ls.overrides[e.zoneKey("keyLow")]?.toInt() ?: 0,
                        keyHigh = ls.overrides[e.zoneKey("keyHigh")]?.toInt() ?: 127,
                        transpose = ls.overrides[e.zoneKey("transpose")]?.toInt() ?: 0,
                        channelIn = ls.overrides[e.zoneKey("channelIn")]?.toInt() ?: 0,
                    )
                }
                val cp = ls.cp88?.let { a ->
                    val d = a.sound?.toRef()?.digest
                    RackCp88(a.mode, a.page ?: 1, a.set ?: 1, d, d?.let { store.get(it) }?.let(Cp88::name))
                } ?: RackCp88()
                RackLiveSet(ls.name, parts, cp)
            }
            return RackSong(slots, liveSets, song.tempo)
        }

        /** A new song: one engine, one live set. */
        fun starter(engine: RackEngine = RackEngine.SFIZZ, preset: String? = null) = RackSong(
            slots = mapOf(engine to RackSlot(preset = preset)),
            liveSets = listOf(RackLiveSet("Main", mapOf(engine to RackPart()))),
        )
    }
}
