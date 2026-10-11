package be.mdw.bagosynths.model

/**
 * What selecting a live set does (REQUIREMENTS I4.1-I4.4, I6.3-I6.7).
 *
 * A song owns one graph; a live set only changes values. This turns "select
 * live set n" into the list of changes to apply, given what is sounding now:
 *
 *  * every parameter the song or the live set gives a value has a stored value:
 *    the live set's override if it has one, the song value otherwise (I6.3);
 *  * its recall mode decides what happens (I6.4): RECALL sets the stored value
 *    every time, KEEP leaves the current value alone, RESUME restores the value
 *    it had when this live set was last left in this session;
 *  * nodes are enabled unless the live set disables them, wires are on at gain 1
 *    unless it says otherwise;
 *  * on-select MIDI and the CP-88 action come last.
 *
 * Pure logic: the app applies the changes to the engine, and reports values
 * the player changes by hand through [noteValue], so that KEEP and RESUME know
 * what "current" is.
 */
class LiveSetSwitcher(private val song: Song, private val liveSets: List<LiveSet>) {

    sealed class Change {
        data class Param(val key: String, val value: Double, val rampMs: Double?) : Change()
        data class Enable(val node: String, val on: Boolean) : Change()
        data class WireSet(val wire: String, val on: Boolean, val gain: Double) : Change()
        data class Send(val port: String, val hex: String) : Change()
        data class Cp88(val action: Cp88Action) : Change()
    }

    /** The value each parameter has now, as far as this switcher knows. */
    private val current = HashMap<String, Double>()
    private val enabled = HashMap<String, Boolean>()
    private val wires = HashMap<String, WireState>()
    /** Per live set: values of its RESUME parameters when it was last left. */
    private val resume = HashMap<Int, Map<String, Double>>()

    var selected: Int = -1
        private set

    /** The player (or MIDI) moved [key] to [value]. */
    fun noteValue(key: String, value: Double) {
        current[key] = value
    }

    fun currentValue(key: String): Double? = current[key]

    /** Every parameter key the song or any live set gives a value. */
    val keys: Set<String> by lazy {
        val out = LinkedHashSet<String>()
        for ((name, node) in song.nodes) for (p in node.params.keys) out += "$name.$p"
        for (ls in liveSets) out += ls.overrides.keys
        out
    }

    fun storedValue(index: Int, key: String): Double? =
        liveSets[index].overrides[key] ?: songValue(key)

    private fun songValue(key: String): Double? {
        val dot = key.indexOf('.')
        if (dot < 0) return null
        return song.nodes[key.substring(0, dot)]?.params?.get(key.substring(dot + 1))
    }

    fun recallMode(index: Int, key: String): Recall =
        liveSets[index].recall[key] ?: song.recall[key] ?: Recall.RECALL

    /** The changes that select live set [index]; the switcher's state moves with them. */
    fun select(index: Int): List<Change> {
        require(index in liveSets.indices) { "no live set $index" }
        val ls = liveSets[index]
        val out = ArrayList<Change>()

        // Leaving: remember the RESUME parameters of the live set being left.
        if (selected >= 0 && selected != index) {
            val keep = HashMap<String, Double>()
            for (k in keys) if (recallMode(selected, k) == Recall.RESUME) current[k]?.let { keep[k] = it }
            resume[selected] = keep
        }

        for (k in keys) {
            val stored = storedValue(index, k) ?: continue
            val target = when (recallMode(index, k)) {
                Recall.RECALL -> stored
                Recall.KEEP -> if (current.containsKey(k)) null else stored   // first time: the stored value
                Recall.RESUME -> resume[index]?.get(k) ?: stored
            } ?: continue
            if (current[k] != target) {
                out += Change.Param(k, target, ls.transitionMs[k])
                current[k] = target
            }
        }

        for (name in song.nodes.keys) {
            val on = ls.enabled[name] ?: true
            if (enabled[name] != on) {
                out += Change.Enable(name, on)
                enabled[name] = on
            }
        }
        for (name in song.wires.keys) {
            val st = ls.wires[name] ?: WireState()
            if (wires[name] != st) {
                out += Change.WireSet(name, st.on, st.gain)
                wires[name] = st
            }
        }
        for (m in ls.onSelect) out += Change.Send(m.port, m.hex)
        ls.cp88?.let { out += Change.Cp88(it) }

        selected = index
        return out
    }

    /** Store what is sounding now as live set [index]'s overrides (I6.9). */
    fun snapshot(index: Int, only: Collection<String> = keys): LiveSet {
        val ls = liveSets[index]
        val overrides = LinkedHashMap(ls.overrides)
        for (k in only) {
            val v = current[k] ?: continue
            if (v == songValue(k)) overrides.remove(k) else overrides[k] = v
        }
        return ls.copy(overrides = overrides)
    }
}
