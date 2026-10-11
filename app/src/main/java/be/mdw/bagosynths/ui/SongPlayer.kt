package be.mdw.bagosynths.ui

import be.mdw.bagosynths.model.Library
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import be.mdw.bagosynths.NativeBridge
import be.mdw.bagosynths.model.Cp88
import be.mdw.bagosynths.model.Digest
import be.mdw.bagosynths.model.LiveSet
import be.mdw.bagosynths.model.LiveSetSwitcher
import be.mdw.bagosynths.model.LiveSetSwitcher.Change
import be.mdw.bagosynths.model.RackEngine
import be.mdw.bagosynths.model.RackSong
import be.mdw.bagosynths.model.Song
import be.mdw.bagosynths.model.getTyped

/** Routing by the devices' own names; null name = automatic. Modes are IoMode names. */
data class RoutingSpec(val audioName: String?, val audioMode: String, val midiName: String?, val midiMode: String,
                       val bufferMs: Int)

/** What the new UI needs from the activity that owns the engine and the routing. */
interface EngineHost {
    /** Installed SFZ instruments: id to display name. */
    fun instruments(): List<Pair<String, String>>
    fun selectInstrument(id: String)
    /** The SFZ file of installed instrument [id], for an added sfizz instance; null if not installed. */
    fun instrumentPath(id: String): String?
    fun currentInstrument(): String?
    fun showPrototype()
    /** The stage's one line: the path and its latency when all is well, else what is wrong ("! ..."). */
    fun healthLine(): String
    /** Every figure on one line, the ones the screen leaves out included: for the diagnostics log. */
    fun healthDetail(): String
    /** Every status figure, for the setup view (S19.3), grouped and for the path in use. */
    fun statusLines(): List<String>
    /** Dropout counts and the engine's peaks start again from now. */
    fun resetCounts()
    fun routing(): Routing
    fun cycleAudioDevice()
    fun cycleMidiDevice()
    /** An IoMode name: ALSA, USB_INTERFACE, USB_DEVICE, NONE. */
    fun setAudioMode(mode: String)
    fun setMidiMode(mode: String)
    fun setUsbBufferMs(ms: Int)
    /**
     * Installs an SFZ instrument pack from a zip (I8.8: no adb), into the app's
     * instruments folder, and rescans. Blocking: call off the UI thread.
     * Returns the new instrument's id.
     */
    fun importInstrumentZip(input: java.io.InputStream, fileName: String): String
    /** The routing in force, by the devices' own names (for environments). */
    fun routingSpec(): RoutingSpec
    fun applyRoutingSpec(spec: RoutingSpec)
    /** The names of every audio output and MIDI port present now (S3.10). */
    fun availableDevices(): Set<String>
    /** MEASURE (S20.3): a click through the device, heard on the tablet's mic; the result is in [statusLines]. */
    fun measureLatency()
    /** The master volume in dB, a global control: never recalled by a live set (I6.8). */
    fun masterDb(): Float
    fun setMasterDb(db: Float)
    /** Do Not Disturb while the app is in front (S17.2); asking for access first if needed. */
    fun stageDnd(): Boolean
    fun setStageDnd(on: Boolean)
}

/**
 * Plays a setlist on the rack (model/Rack.kt): selecting a song sets up its
 * engines and patches, selecting a live set applies what [LiveSetSwitcher]
 * says, through the zones and the switchable wires (AudioEngine.h).
 *
 * Remembers setlist, song and live set in preferences on every move, so a
 * restart comes back where it was (S13.2).
 */
class SongPlayer(private val lib: Library, private val host: EngineHost, private val prefs: SharedPreferences) {

    var setlistId by mutableStateOf<Digest?>(null); private set
    var setlistName by mutableStateOf(""); private set
    val songNames = mutableStateListOf<String>()
    var songIndex by mutableIntStateOf(-1); private set
    var song: Song? by mutableStateOf(null); private set
    var rack: RackSong? by mutableStateOf(null); private set
    val liveSetNames = mutableStateListOf<String>()
    var liveSetIndex by mutableIntStateOf(-1); private set
    /** Instance levels as they are now, by instance id, for the stage faders. */
    val levels = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    val playing = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    /** The CP-88 page the app may write songs into (I3.4), and its MIDI channel. */
    var reservedPage: Int
        get() = prefs.getInt("cp88.reservedPage", 40)
        set(v) { prefs.edit().putInt("cp88.reservedPage", v.coerceIn(1, 40)).apply() }
    var cp88Channel: Int
        get() = prefs.getInt("cp88.channel", 1)
        set(v) { prefs.edit().putInt("cp88.channel", v.coerceIn(1, 16)).apply() }
    /** One line on the last CP-88 action, for the stage view. */
    var cp88Status by mutableStateOf(""); private set

    /** Live set index -> the reserved slot its owned sound was written to. */
    private val ownedSlots = HashMap<Int, Int>()
    private var lastSent: Triple<Int, Int, Long>? = null

    private var songs: List<Song> = emptyList()
    private var switcher: LiveSetSwitcher? = null
    // Per ENGINE instance (AudioEngine::kMaxInstances).
    private val midiOn = BooleanArray(MAX_INSTANCES) { true }
    private val audioOn = BooleanArray(MAX_INSTANCES) { true }
    private val gain = FloatArray(MAX_INSTANCES) { 1f }

    fun resume() {
        // The last session's setlist, if the library still has it; else the first one, from the top.
        val saved = prefs.getString("stage.setlist", null)?.let { runCatching { Digest(it) }.getOrNull() }
            ?.takeIf { lib.store.entry(it) != null }
        if (saved != null) openSetlist(saved, prefs.getInt("stage.song", 0), prefs.getInt("stage.liveSet", 0))
        else openSetlist(lib.setlists().firstOrNull()?.id ?: return)
    }

    fun openSetlist(id: Digest, song: Int = 0, liveSet: Int = 0) {
        val entry = lib.store.entry(id) ?: return
        setlistId = id
        setlistName = entry.version.name
        val pairs = lib.songsOf(id)
        songs = pairs.map { it.second }
        songNames.clear()
        songNames.addAll(pairs.map { it.first?.version?.name ?: "(removed song)" })
        songEntries = pairs.map { it.first?.id }
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
        // The last song's live set values off its engines first, so none stays
        // on into a song that leaves that parameter to its preset.
        restoreSongValues()
        touched.clear()
        // The same song again (a reload after saving): a tapped tempo holds for
        // the session (I12.3) and a running click goes on. Another song stops it.
        val sameSong = index == songIndex && bpmTapped
        if (index != songIndex && clickRunning) stopClick()
        songIndex = index
        song = s
        rack = lib.rackSong(s)
        val liveSets = s.liveSets.map { lib.store.getTyped<LiveSet>(it.toRef().digest) }
        liveSetNames.clear()
        liveSetNames.addAll(liveSets.map { it.name })
        switcher = LiveSetSwitcher(s, liveSets)
        prefs.edit().putInt("stage.song", index).apply()
        note("song ${index + 1} '${songNames.getOrNull(index)}': instances ${rack?.instances?.map { it.id }}, " +
                "live sets ${liveSets.map { it.name }}")
        // The tempo before the engines: a synced effect is created at the song's
        // tempo, instead of gliding to it from the last song's over seconds.
        if (!sameSong) {
            if (clickRunning) retempo(s.tempo?.bpm) else bpm = s.tempo?.bpm
            bpmTapped = false
            if (!clickRunning) beatOrigin = System.nanoTime()
            taps.clear()
        }
        applyMidiClock()
        setUpEngines()
        // A preset loading in the background resets its engine when it lands:
        // then the song's values go on again ([settle]).
        settling = onEngine.values.any { busy(it) }
        sheetPage = 0
        sheetPages = 0
        sheetFile = s.leadSheet?.toRef()?.digest?.let { java.io.File(lib.store.root, "blobs/sha256/${it.hex}") }
            ?.takeIf { it.exists() }
        writeSongPage(liveSets)
        if (liveSets.isNotEmpty()) selectLiveSet(liveSet.coerceIn(0, liveSets.size - 1))
    }

    fun selectLiveSet(index: Int) {
        val sw = switcher ?: return
        if (index !in liveSetNames.indices) return
        val changes = sw.select(index)
        pendingLiveSet = index
        apply(changes)
        // A live set that leaves the CP-88 alone, while its buttons select live
        // sets: put it on this live set's slot, which holds silence.
        if (cp88Follows && changes.none { it is Change.Cp88 }) ownedSlots[index]?.let { selectCp88(reservedPage, it) }
        liveSetIndex = index
        Log.i(TAG, "live set ${index + 1} '${liveSetNames[index]}': ${changes.size} changes: " +
                changes.joinToString("; ") { c -> when (c) {
                    is Change.Param -> "${c.key}=${"%.2f".format(c.value)}"
                    is Change.WireSet -> "${c.wire}=${if (c.on) "on" else "off"}"
                    else -> c.toString()
                } })
        prefs.edit().putInt("stage.liveSet", index).apply()
        touched.clear()
        // The stage's setBfree is the one playing here, its drawbars as this live set has them.
        setBfreeId = rack?.instances.orEmpty().filter { it.engine == RackEngine.SETBFREE }
            .let { all -> all.firstOrNull { playing[it.id] == true } ?: all.firstOrNull() }?.id
        refreshSetBfree()
        applyControls()
    }

    private var pendingLiveSet = -1

    // ------------------------------------------- a live set's own sound values

    private var settling = false

    /** Every "<instance>.<parameter>" a live set of the stage song gives a value of its own. */
    private fun liveSetKeys(): Set<String> = rack?.liveSets.orEmpty()
        .flatMap { ls -> ls.parts.flatMap { (id, p) -> p.params.keys.map { "$id.$it" } } }.toSet()

    /** The song's value of [key]: its patch's, or the one it keeps for its live sets (RackSlot.songValues). */
    private fun songValue(key: String): Double? {
        val dot = key.indexOf('.')
        return if (dot < 0) null else rack?.songValue(key.substring(0, dot), key.substring(dot + 1))
    }

    /** The song's values back where a live set had its own, so they do not stay on into the next song. */
    private fun restoreSongValues() {
        for (k in liveSetKeys()) songValue(k)?.let { applyParam(k, it.toFloat()) }
    }

    private fun busy(i: Int) = runCatching { NativeBridge.nativeInstanceBusy(i) }.getOrDefault(false)

    /**
     * Once the presets that loaded in the background have landed: the patches'
     * values on again, and the live set's values over them. Called from
     * [pollSelections].
     */
    private fun settle() {
        if (!settling || onEngine.values.any { busy(it) }) return
        settling = false
        for (inst in rack?.instances.orEmpty()) {
            val i = onEngine[inst.id] ?: continue
            for ((name, v) in inst.slot.params) setParamAt(i, name, v.toFloat())
        }
        val sw = switcher ?: return
        val ls = liveSetIndex.takeIf { it in liveSetNames.indices } ?: return
        for (k in liveSetKeys()) sw.storedValue(ls, k)?.let { applyParam(k, it.toFloat()); sw.noteValue(k, it) }
        refreshSetBfree()
        note("presets landed: the song's values and live set ${ls + 1}'s own on again")
    }

    // ------------------------------------------------------------ the CP-88

    /**
     * Whether the CP-88's own live set buttons select the app's live sets
     * (set n, live set n). Then its reserved page must hold, in every slot a
     * song does not give a sound of its own, a SILENT sound: pressing set n
     * switches the CP-88 to that slot too, and it must not mix in its own
     * instruments (Michiel, 2026-10-11).
     */
    var cp88Follows: Boolean
        get() = prefs.getBoolean("cp88.follow", true)
        set(v) { prefs.edit().putBoolean("cp88.follow", v).apply() }

    /**
     * The song's page on the CP-88, written when the song is selected (S5.3,
     * S4.11): live set n owns slot n of the reserved page. A live set that owns
     * a sound gets it; when the CP-88 follows, every other slot gets the silent
     * sound. 7 ms a sound (V3); a slot already holding the same sound is not
     * written again, which spares the CP-88's flash ([forgetCp88Page] resets that).
     */
    private fun writeSongPage(liveSets: List<LiveSet>) {
        ownedSlots.clear()
        var written = 0
        var kept = 0
        var failed = 0
        val follows = cp88Follows
        for (i in 0 until minOf(8, liveSets.size)) {
            val a = liveSets[i].cp88
            val own = if (a?.mode == "own") a.sound?.toRef()?.digest?.let { lib.store.get(it) } else null
            val bytes = own ?: if (follows && a?.mode != "select") Cp88.SILENT else continue
            val slot = i + 1
            val key = "cp88.slot.$reservedPage.$slot"
            val digest = Digest.of(bytes).hex
            if (prefs.getString(key, null) == digest) { ownedSlots[i] = slot; kept++; continue }
            val ok = Cp88.retarget(bytes, reservedPage, slot).all { NativeBridge.nativeUsbMidiSend(it) > 0 }
            if (ok) { ownedSlots[i] = slot; written++; prefs.edit().putString(key, digest).apply() } else failed++
        }
        if (liveSets.size > 8 && liveSets.drop(8).any { it.cp88?.mode == "own" }) {
            cp88Status = "only live sets 1-8 can own a CP-88 sound (one page)"
        } else if (written + failed > 0) {
            cp88Status = if (failed == 0) "CP-88: page $reservedPage, $written slot(s) written, $kept unchanged"
            else "CP-88: could not write $failed slot(s) -- is it in a direct USB mode?"
        }
        if (written + failed > 0) note(cp88Status)
    }

    /** Forget what the CP-88's reserved page holds, so the next song rewrites it all. */
    fun forgetCp88Page() {
        val e = prefs.edit()
        for (n in 1..8) e.remove("cp88.slot.$reservedPage.$n")
        e.apply()
    }

    private fun selectCp88(page: Int, set: Int) {
        val r = NativeBridge.nativeUsbMidiSend(Cp88.selectMessages(page, set, cp88Channel))
        lastSent = Triple(page, set, System.currentTimeMillis())
        cp88Status = if (r > 0) "CP-88 -> $page:$set" else "CP-88 not reachable (MIDI out needs a direct USB mode)"
    }

    /**
     * Reads the CP-88's current sound -- unsaved edits and panel state
     * included (V2) -- into the library. Blocking, about 0,2 s: call off the
     * UI thread. Returns the sound and its name, or throws with the reason.
     */
    fun importCurrentCp88Sound(): Pair<Digest, String> {
        while (NativeBridge.nativeUsbSysexDrain() != null) { /* stale replies */ }
        val r = NativeBridge.nativeUsbMidiSend(Cp88.dumpRequest())
        check(r > 0) { "MIDI out failed ($r): the CP-88 must be in a direct USB mode" }
        val got = ArrayList<ByteArray>()
        val end = System.currentTimeMillis() + 2000
        while (got.size < 19 && System.currentTimeMillis() < end) {
            val m = NativeBridge.nativeUsbSysexDrain()
            if (m == null) Thread.sleep(5) else if (Cp88.parse(m) != null) got += m
        }
        check(got.size == 19) { "the CP-88 answered with ${got.size} of 19 messages" }
        val sound = Cp88.normalize(got)
        return lib.store.put(sound) to Cp88.name(sound)
    }

    /** [sound] (stored form) into the CP-88's current sound, to hear it; no stored slot is touched. */
    fun auditionCp88(sound: ByteArray): Boolean =
        Cp88.split(sound).all { runCatching { NativeBridge.nativeUsbMidiSend(it) }.getOrDefault(-1) > 0 }

    /**
     * The song's CP-88 page as the app writes it into the reserved page (see
     * [writeSongPage]), as an .X9P file the keyboard loads from a USB stick:
     * set n holds live set n's own sound, the silent sound where the CP-88
     * follows the app and the live set selects none of its own, the Init
     * Sound otherwise.
     */
    fun cp88PageFile(song: RackSong): ByteArray {
        val sets = HashMap<Int, ByteArray>()
        for ((i, ls) in song.liveSets.take(8).withIndex()) {
            val own = if (ls.cp88.mode == "own") ls.cp88.sound?.let { lib.store.get(it) } else null
            (own ?: if (cp88Follows && ls.cp88.mode != "select") Cp88.SILENT else null)?.let { sets[i + 1] = it }
        }
        return be.mdw.bagosynths.model.Cp88X9.x9p(reservedPage, sets)
    }

    // ------------------------------------------------------------------ tempo

    /** The song's tempo (S12.1); a tapped one holds for the session (I12.3). */
    var bpm by mutableStateOf<Double?>(null); private set
    /** When beat one of the visual beat fell, System.nanoTime(). */
    var beatOrigin by mutableStateOf(0L); private set
    var bpmTapped by mutableStateOf(false); private set
    private val taps = ArrayDeque<Long>()
    private var songEntries: List<Digest?> = emptyList()

    /** One tap; four or more taps less than 2 s apart set the tempo. */
    fun tap() {
        val now = System.nanoTime()
        if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
        taps.addLast(now)
        while (taps.size > 6) taps.removeFirst()
        if (taps.size >= 4) {
            val span = (taps.last() - taps.first()) / (taps.size - 1).toDouble()
            retempo(Math.round(60e9 / span * 10) / 10.0)
            bpmTapped = true
            applyMidiClock()
        }
        // The visual beat falls on the tap, unless the click is counting: then
        // it stays with what is heard.
        if (!clickRunning) beatOrigin = now
    }

    /** A new tempo; while the click runs, the visual beat bends with it instead of jumping. */
    private fun retempo(newBpm: Double?) {
        val old = bpm
        if (clickRunning && old != null && newBpm != null) {
            val now = System.nanoTime()
            val beats = (now - beatOrigin) / (60e9 / old)
            beatOrigin = now - (beats * 60e9 / newBpm).toLong()
            NativeBridge.nativeClickTempo(newBpm)
        }
        bpm = newBpm
    }

    // ------------------------------------------------------------------ click

    /** Count-in only, or continuous (S12.2): an environment setting. */
    var clickContinuous by mutableStateOf(prefs.getBoolean("click.continuous", false))
    /** The click is heard in the main out (R12.1: on the CP-88 alone, by the audience too); else visual only. */
    var clickAudio by mutableStateOf(prefs.getBoolean("click.audio", true))
    var clickLevel by mutableStateOf(prefs.getFloat("click.level", 0.5f))
    /** MIDI clock out to the keyboard (I12.2), for its tempo-synced effects. */
    var midiClock by mutableStateOf(prefs.getBoolean("click.midiClock", false))
    var clickRunning by mutableStateOf(false); private set

    fun saveClickSettings() {
        prefs.edit().putBoolean("click.continuous", clickContinuous).putBoolean("click.audio", clickAudio)
            .putFloat("click.level", clickLevel).putBoolean("click.midiClock", midiClock).apply()
        applyMidiClock()
    }

    fun toggleClick() = if (clickRunning) stopClick() else startClick()

    /** A count-in of the song's bars (one if it names none), or the click until stopped. */
    fun startClick() {
        val b = bpm ?: return
        val t = song?.tempo
        val bar = (t?.beatsPerBar ?: 4).coerceAtLeast(1)
        val beats = if (clickContinuous) 0 else bar * (t?.countInBars ?: 1).coerceAtLeast(1)
        val delay = NativeBridge.nativeClickStart(b, bar, beats, clickLevel, clickAudio, midiClock)
        beatOrigin = System.nanoTime() + delay
        clickRunning = true
        Log.i(TAG, "click: ${if (beats > 0) "$beats beats" else "continuous"} at $b BPM, heard in ${delay / 1_000_000} ms")
    }

    fun stopClick() {
        NativeBridge.nativeClickStop(midiClock)
        clickRunning = false
        Log.i(TAG, "click stopped; MIDI clock ${NativeBridge.nativeMidiClockTicks()} ticks sent")
    }

    /** The beat being heard, 1-based within the bar, while the click runs; 0 otherwise. */
    fun clickBeat(now: Long): Int {
        val b = bpm ?: return 0
        if (!clickRunning || now < beatOrigin) return 0
        val bar = (song?.tempo?.beatsPerBar ?: 4).coerceAtLeast(1)
        return ((now - beatOrigin) / (60e9 / b)).toLong().mod(bar) + 1
    }

    /** The tempo goes to the engines (I12.1) and, when it is on, out as MIDI clock. */
    private fun applyMidiClock() {
        runCatching { NativeBridge.nativeSetTempo(bpm ?: 0.0) }
        runCatching { NativeBridge.nativeMidiClock(if (midiClock) bpm ?: 0.0 else 0.0) }
    }

    /**
     * What sounds now becomes the current live set's own (I6.9): its levels,
     * and every sound parameter moved on the stage or by a mapped control
     * since the live set was selected -- setBfree's drawbars, a filter on a
     * knob. A value moved back to the song's stops being the live set's own.
     * Saved as a new version of the song.
     */
    fun storeInLiveSet() {
        val id = songEntries.getOrNull(songIndex) ?: return
        var r = rack ?: return
        val i = liveSetIndex.takeIf { it in r.liveSets.indices } ?: return
        val ls = r.liveSets[i]
        val parts = ls.parts.toMutableMap()
        for (inst in r.instances) {
            val v = levels[inst.id] ?: continue
            parts[inst.id] = (parts[inst.id] ?: be.mdw.bagosynths.model.RackPart(plays = false)).copy(level = v.toDouble())
        }
        r = r.copy(liveSets = r.liveSets.toMutableList().also { it[i] = ls.copy(parts = parts) })
        val stored = ArrayList<String>()
        for ((key, before) in touched) {
            val dot = key.indexOf('.')
            val inst = key.substring(0, dot)
            val param = key.substring(dot + 1)
            val now = switcher?.currentValue(key) ?: continue
            val part = r.liveSets[i].parts[inst] ?: continue
            val songValue = r.songValue(inst, param) ?: before
            r = when {
                now != songValue -> r.withLiveSetValue(i, inst, param, now, songValue)
                param in part.params -> r.withPart(i, inst, part.copy(params = part.params - param))
                else -> r
            }
            stored += key
        }
        val name = songNames.getOrNull(songIndex) ?: return
        lib.saveSong(id, name, r)
        reload()
        note("stored into live set ${i + 1}: the levels" + if (stored.isEmpty()) "" else " and $stored")
    }

    /** Sound parameters moved on the stage since the live set was selected: "<instance>.<parameter>" -> the value before. */
    private val touched = LinkedHashMap<String, Double>()

    private fun touch(key: String, before: Double?) {
        if (key !in touched && before != null) touched[key] = before
    }

    /** The engine's value of "<instance>.<parameter>" now. */
    private fun engineValue(key: String): Double? {
        val dot = key.indexOf('.')
        val node = onEngine[key.substring(0, dot)]?.let { nodeOf(it) } ?: return null
        val p = paramIndex(node, key.substring(dot + 1)) ?: return null
        return NativeBridge.nativeGetNodeParam(node, p).toDouble()
    }

    // --------------------------------------------------------- mapped controls

    /** The keys of the controller maps in force, in the order the engine has them (bit k of the moved mask). */
    private var controlKeys: List<String> = emptyList()
    /** Their values when the maps were set: what a first move started from. */
    private val controlStart = HashMap<String, Double>()

    /**
     * The controls of the sounds playing in this live set onto the engine
     * (AudioEngine::setControlMaps): a knob moves its parameter on the render
     * thread, picking up first. A sound not playing here leaves its knob free
     * for another's.
     */
    private fun applyControls() {
        val r = rack
        val ls = r?.liveSets?.getOrNull(liveSetIndex)
        val keys = ArrayList<String>()
        val ccs = ArrayList<Int>(); val nodes = ArrayList<Int>(); val params = ArrayList<Int>()
        val mins = ArrayList<Float>(); val maxs = ArrayList<Float>(); val ints = ArrayList<Boolean>()
        controlStart.clear()
        if (r != null && ls != null) for (inst in r.instances) {
            if (ls.parts[inst.id]?.plays != true) continue
            val node = onEngine[inst.id]?.let { nodeOf(it) } ?: continue
            for ((param, cc) in inst.controls) {
                val p = paramIndex(node, param) ?: continue
                val range = NativeBridge.nativeNodeParamRange(node, p)
                val key = "${inst.id}.$param"
                keys += key; ccs += cc; nodes += node; params += p
                mins += range[0]; maxs += range[1]; ints += range[2] > 0.5f
                controlStart[key] = NativeBridge.nativeGetNodeParam(node, p).toDouble()
            }
        }
        controlKeys = keys
        runCatching {
            NativeBridge.nativeSetControlMaps(ccs.toIntArray(), nodes.toIntArray(), params.toIntArray(),
                mins.toFloatArray(), maxs.toFloatArray(), ints.toBooleanArray())
        }
    }

    /** What the mapped controls moved since the last poll: the switcher and the stage learn the new values. */
    private fun pollControls() {
        val moved = runCatching { NativeBridge.nativeTakeMovedControls() }.getOrDefault(0L)
        if (moved == 0L) return
        var setBfreeMoved = false
        for ((k, key) in controlKeys.withIndex()) {
            if (moved and (1L shl k) == 0L) continue
            val v = engineValue(key) ?: continue
            touch(key, switcher?.currentValue(key) ?: controlStart[key])
            switcher?.noteValue(key, v)
            if (key.startsWith("$setBfreeId.")) setBfreeMoved = true
            Log.i(TAG, "control $key -> ${"%.2f".format(v)}")
        }
        if (setBfreeMoved) refreshSetBfree()
    }

    /** The "<instance>.<parameter>" waiting for a controller to be moved (MIDI learn), or null. */
    var learningControl by mutableStateOf<String?>(null); private set
    private var onLearned: ((Int) -> Unit)? = null

    /** The next controller moved goes to [key]: [done] gets its number. */
    fun learnControl(key: String, done: (Int) -> Unit) {
        learningControl = key
        onLearned = done
        runCatching { NativeBridge.nativeControlLearn(true) }
    }

    fun cancelLearn() {
        if (learningControl == null) return
        learningControl = null
        onLearned = null
        runCatching { NativeBridge.nativeControlLearn(false) }
    }

    private fun pollLearn() {
        if (learningControl == null) return
        val cc = runCatching { NativeBridge.nativeTakeLearnedControl() }.getOrDefault(-1)
        if (cc < 0) return
        val done = onLearned
        note("control learned: CC $cc -> $learningControl")
        cancelLearn()
        done?.invoke(cc)
    }

    /** Writes a tapped tempo into the song (I12.3): a new version of its entry. */
    fun saveTempo() {
        val id = songEntries.getOrNull(songIndex) ?: return
        val r = rack ?: return
        val b = bpm ?: return
        val name = songNames.getOrNull(songIndex) ?: return
        lib.saveSong(id, name, r.copy(tempo = be.mdw.bagosynths.model.Tempo(bpm = b)))
        bpmTapped = false
        reload()
    }

    // ------------------------------------------------------------ lead sheet

    /** The current song's lead sheet as a file in the store, or null (S9.1). */
    var sheetFile by mutableStateOf<java.io.File?>(null); private set
    var sheetPage by mutableIntStateOf(0); private set
    var sheetPages by mutableIntStateOf(0)
    /** Settings of the environment (S9.2): 2-up, half turns, and what the page turner turns. */
    var sheetTwoUp by mutableStateOf(prefs.getBoolean("sheet.twoUp", true))
    var sheetHalfTurn by mutableStateOf(prefs.getBoolean("sheet.halfTurn", true))
    var keysTurnPages by mutableStateOf(prefs.getBoolean("sheet.keysTurnPages", true))

    /** TEST (PROVISIONAL.md): attach [file] as the current song's lead sheet, as the editor does. */
    fun attachSheetForTest(file: java.io.File) {
        val id = songEntries.getOrNull(songIndex) ?: return
        val r = rack ?: return
        val name = songNames.getOrNull(songIndex) ?: return
        lib.saveSong(id, name, r.copy(leadSheet = storePdf(file.readBytes())))
        reload()
        Log.i(TAG, "lead sheet attached to '$name': ${sheetFile?.name}, ${file.length()} B")
    }

    /** A PDF into the library; returns its object. */
    fun storePdf(bytes: ByteArray): Digest {
        require(bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "%PDF") { "not a PDF" }
        return lib.store.put(bytes)
    }

    fun saveSheetSettings() {
        prefs.edit().putBoolean("sheet.twoUp", sheetTwoUp).putBoolean("sheet.halfTurn", sheetHalfTurn)
            .putBoolean("sheet.keysTurnPages", keysTurnPages).apply()
    }

    /** In 2-up a half turn moves one page, so the right page becomes the left (I9.3). */
    private fun turnStep() = if (sheetTwoUp && !sheetHalfTurn) 2 else 1

    fun pageForward() {
        val last = (sheetPages - (if (sheetTwoUp) 2 else 1)).coerceAtLeast(0)
        sheetPage = (sheetPage + turnStep()).coerceAtMost(last)
    }

    fun pageBack() { sheetPage = (sheetPage - turnStep()).coerceAtLeast(0) }

    /** A page-turner key: pages when the song has a lead sheet and the setup says so, live sets otherwise. */
    fun forwardKey() = if (keysTurnPages && sheetFile != null) pageForward() else nextLiveSet()
    fun backKey() = if (keysTurnPages && sheetFile != null) pageBack() else previousLiveSet()

    /** Set by the UI: a Back gesture goes to the stage instead of leaving the app. */
    var backToStage: (() -> Unit)? = null

    /** Whether the stage view is what is showing: page-turner keys act only there. */
    @Volatile var onStage = true

    /**
     * TEST (PROVISIONAL.md): the CP-88 path end to end, without the screen.
     * Imports the current sound, builds a song whose live set 1 owns it and
     * whose live set 2 selects 1:1, opens it on the stage (which writes the
     * sound into the reserved page) and selects live set [liveSet]. Logs what
     * it did under bos-stage; tools/cp88sysex reads the CP-88 back.
     */
    fun cp88SelfTest(liveSet: Int) {
        Thread {
            runCatching {
                val (sound, name) = importCurrentCp88Sound()
                Log.i(TAG, "selftest: imported '$name' ${sound.short}")
                val draft = RackSong(
                    instances = listOf(be.mdw.bagosynths.model.RackInstance("sfizz", RackEngine.SFIZZ,
                        be.mdw.bagosynths.model.RackSlot(level = 0.0))),
                    liveSets = listOf(
                        be.mdw.bagosynths.model.RackLiveSet("Own", cp88 = be.mdw.bagosynths.model.RackCp88("own", sound = sound, soundName = name)),
                        be.mdw.bagosynths.model.RackLiveSet("Select 1:1", cp88 = be.mdw.bagosynths.model.RackCp88("select", 1, 1)),
                    ))
                val song = draft.toObjects(lib.store)
                val liveSets = song.liveSets.map { lib.store.getTyped<LiveSet>(it.toRef().digest) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    this.song = song
                    rack = draft
                    switcher = LiveSetSwitcher(song, liveSets)
                    liveSetNames.clear(); liveSetNames.addAll(liveSets.map { it.name })
                    writeSongPage(liveSets)
                    selectLiveSet(liveSet - 1)
                    Log.i(TAG, "selftest: live set $liveSet selected; $cp88Status")
                }
            }.onFailure { Log.e(TAG, "selftest failed: $it") }
        }.start()
    }

    // ------------------------------------------------------------ quick edit

    /** What the stage asked to edit: a song, and in it a live set or an instance's patch. */
    data class EditRequest(val song: Digest, val liveSet: Int? = null, val instance: String? = null)
    var editRequest by mutableStateOf<EditRequest?>(null)
    /** Set by the UI: shows the Songs screen. */
    var showSongs: (() -> Unit)? = null

    /**
     * From the stage, one long press: the current song in the editor, at
     * [liveSet], or with [instance]'s sound open -- for the live set playing,
     * where a change is that live set's own unless the player says "whole song".
     */
    fun requestEdit(liveSet: Int? = null, instance: String? = null) {
        val id = songEntries.getOrNull(songIndex) ?: return
        editRequest = EditRequest(id, liveSet ?: liveSetIndex.takeIf { instance != null && it >= 0 }, instance)
        showSongs?.invoke()
    }

    /** The diagnostic log (S19.2), set by the activity. */
    var diagnostics: Diagnostics? = null

    /** Logged, and kept in the diagnostic log: what is worth finding again after a gig. */
    private fun note(msg: String) {
        Log.i(TAG, msg)
        diagnostics?.event(msg)
    }

    /** What the last external selection was, for the stage view. */
    var lastSelection by mutableStateOf(""); private set

    // ------------------------------------------------- switching controllers

    /**
     * Controller -> action (I10.4, I12.3): "next" and "previous" step through
     * live sets (or turn pages), "tap" taps the tempo, "click" starts and stops
     * the click. -1 = none.
     */
    val controllers = androidx.compose.runtime.mutableStateMapOf<String, Int>().apply {
        for (a in CONTROLLER_ACTIONS) put(a, prefs.getInt(controllerKey(a), -1))
    }
    val ccNext get() = controllers["next"] ?: -1
    val ccPrevious get() = controllers["previous"] ?: -1
    /** Which action the next controller press is learned into, or null. */
    var learning by mutableStateOf<String?>(null); private set
    private val ccDown = BooleanArray(128)

    private fun controllerKey(action: String) = "switch.cc" + action.replaceFirstChar { it.uppercase() }

    private fun saveControllers() {
        val e = prefs.edit()
        for (a in CONTROLLER_ACTIONS) e.putInt(controllerKey(a), controllers[a] ?: -1)
        e.apply()
    }

    fun watchControllers() {
        var low = 0L
        var high = 0L
        for (cc in controllers.values) {
            if (cc in 0..63) low = low or (1L shl cc) else if (cc in 64..127) high = high or (1L shl (cc - 64))
        }
        runCatching { NativeBridge.nativeWatchControllers(low, high, learning != null) }
    }

    fun learn(which: String?) { learning = which; watchControllers() }

    /** All of them at once, as an environment brings them; an action left out has none. */
    fun setControllers(map: Map<String, Int>) {
        for (a in CONTROLLER_ACTIONS) controllers[a] = map[a] ?: -1
        saveControllers()
        watchControllers()
    }

    fun clearController(which: String) {
        controllers[which] = -1
        saveControllers()
        watchControllers()
    }

    /** Where controller events from the MIDI path land; public for the STAGE test hook. */
    fun onController(cc: Int, value: Int) {
        val down = value >= 64
        val pressed = down && !ccDown[cc]          // act on the press, once
        ccDown[cc] = down
        if (!pressed) return
        val l = learning
        if (l != null) {
            // One controller, one action: learning it here takes it from any other.
            for (a in CONTROLLER_ACTIONS) if (controllers[a] == cc) controllers[a] = -1
            controllers[l] = cc
            saveControllers()
            learning = null
            watchControllers()
            return
        }
        when (CONTROLLER_ACTIONS.firstOrNull { controllers[it] == cc }) {
            "next" -> forwardKey()
            "previous" -> backKey()
            "tap" -> tap()
            "click" -> toggleClick()
            else -> return
        }
        lastSelection = "CC $cc"
    }

    private var midiOutWasReady = false

    /**
     * Brings the CP-88 back in line whenever MIDI out to it BECOMES available
     * (I13.3): after start-up, when the route took longer than the stage, and
     * after a re-plug or a power cycle -- its page rewritten where it changed,
     * the current live set selected again.
     */
    private fun watchMidiOut() {
        val ready = runCatching { NativeBridge.nativeUsbMidiOutReady() }.getOrDefault(false)
        if (ready && !midiOutWasReady && songIndex >= 0) {
            // It may be another keyboard than before (the stage's spare, a
            // rental): what this tablet wrote to the last one says nothing.
            forgetCp88Page()
            val liveSets = song?.liveSets?.map { lib.store.getTyped<LiveSet>(it.toRef().digest) }.orEmpty()
            writeSongPage(liveSets)
            val i = liveSetIndex
            val action = liveSets.getOrNull(i)?.cp88
            when {
                action?.mode == "select" -> selectCp88(action.page ?: 1, action.set ?: 1)
                ownedSlots[i] != null -> selectCp88(reservedPage, ownedSlots[i]!!)
            }
            note("MIDI out ready: the CP-88 brought in line ($cp88Status)")
        }
        midiOutWasReady = ready
    }

    /**
     * Follows the CP-88's own live set buttons (V1, LOG.md 2026-10-11): a
     * selection arrives as Bank Select MSB 63, LSB page - 1, Program Change
     * set - 1. Set n selects live set n of the current song, whatever the
     * page. Called by the UI every 50 ms.
     */
    fun pollSelections() {
        watchMidiOut()
        settle()
        pollControls()
        pollLearn()
        // A count-in ends on its own; the visual beat stops with the last click heard.
        if (clickRunning && !NativeBridge.nativeClickRunning()) {
            val b = bpm
            val end = beatOrigin + if (b != null) ((song?.tempo?.let { (it.beatsPerBar.coerceAtLeast(1)) *
                (it.countInBars ?: 1).coerceAtLeast(1) } ?: 4) * 60e9 / b).toLong() else 0L
            if (clickContinuous || System.nanoTime() >= end) clickRunning = false
        }
        val pcs = runCatching { NativeBridge.nativeDrainPrograms() }.getOrNull() ?: return
        for (v in pcs) {
            if (v ushr 31 == 1) {
                onController((v shr 8) and 0x7F, v and 0x7F)
                continue
            }
            val msb = (v shr 16) and 0x7F
            val lsb = (v shr 8) and 0x7F
            val pc = v and 0x7F
            if (msb == 63 && cp88Follows) {
                // Our own selection coming back is not the player choosing.
                val sent = lastSent
                if (sent != null && sent.first == lsb + 1 && sent.second == pc + 1 &&
                    System.currentTimeMillis() - sent.third < 2000) continue
                lastSelection = "CP-88 ${lsb + 1}:${pc + 1}"
                if (pc in liveSetNames.indices) selectLiveSet(pc)
            } else {
                lastSelection = "PC ${pc + 1} (bank $msb/$lsb, ch ${(v ushr 24) + 1})"
            }
        }
    }

    fun nextLiveSet() { if (liveSetIndex + 1 < liveSetNames.size) selectLiveSet(liveSetIndex + 1) }
    fun previousLiveSet() { if (liveSetIndex > 0) selectLiveSet(liveSetIndex - 1) }

    /** setBfree's stage controls (SetBfreeNode.h `Param`): drawbars 0-8, Leslie 37, percussion 27. */
    val setBfree = androidx.compose.runtime.mutableStateMapOf<Int, Float>()
    /** Which of the song's setBfree instances the stage shows: the first playing in the live set, else the first. */
    var setBfreeId by mutableStateOf<String?>(null); private set

    private fun setBfreeNode(): Int? = setBfreeId?.let { onEngine[it] }?.let { nodeOf(it) }

    fun refreshSetBfree() {
        val node = setBfreeNode() ?: return
        for (i in SETBFREE_STAGE_PARAMS) setBfree[i] = runCatching { NativeBridge.nativeGetNodeParam(node, i) }.getOrDefault(0f)
    }

    /** A stage setBfree control moved: heard at once, and kept in mind for "Store in live set". */
    fun setSetBfree(index: Int, v: Float) {
        val node = setBfreeNode() ?: return
        val key = "$setBfreeId.${NativeBridge.nativeNodeParamName(node, index)}"
        touch(key, switcher?.currentValue(key) ?: NativeBridge.nativeGetNodeParam(node, index).toDouble())
        NativeBridge.nativeSetNodeParam(node, index, v)
        switcher?.noteValue(key, v.toDouble())
        setBfree[index] = v
    }

    // ------------------------------------------------------------- instances

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

    /** The stage song's instances on the engine's. */
    private var onEngine: Map<String, Int> = emptyMap()

    private val nodeCache = HashMap<Int, Int>()
    private fun nodeOf(i: Int): Int? = nodeCache[i] ?: NativeBridge.nativeInstanceNode(i).takeIf { it >= 0 }
        ?.also { nodeCache[i] = it }

    private fun paramIndex(node: Int, name: String): Int? = (0 until NativeBridge.nativeNodeParamCount(node))
        .firstOrNull { NativeBridge.nativeNodeParamName(node, it) == name }

    private fun setParamAt(i: Int, param: String, v: Float) {
        val node = nodeOf(i) ?: return
        paramIndex(node, param)?.let { NativeBridge.nativeSetNodeParam(node, it, v) }
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

    /** Every parameter of [song]'s instance [id] but its level, with its range and current value. */
    fun engineParams(song: RackSong, id: String): List<ParamDesc> {
        val node = assignment(song)[id]?.let { nodeOf(it) } ?: return emptyList()
        return paramsOf(node, skip = setOf("Level"))
    }

    /**
     * Puts [song]'s instance [id] with [slot] on its engine instance, so the
     * patch editor shows -- and the keys play -- this song's patch, not
     * whatever was on stage. [reload] puts the stage's song back.
     */
    fun previewSlot(song: RackSong, id: String, slot: be.mdw.bagosynths.model.RackSlot) {
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
    private fun applyFxAt(node: Int?, fx: be.mdw.bagosynths.model.RackFx?) {
        node ?: return
        val type = be.mdw.bagosynths.model.RackFx.TYPES.indexOf(fx?.type ?: "off").coerceAtLeast(0)
        val params = fx?.params.orEmpty()
        NativeBridge.nativeFxConfigure(node, type, params.keys.toTypedArray(),
            params.values.map { it.toFloat() }.toFloatArray())
        // Fully on until a live set says otherwise: an amount left at 0 by the
        // previous song would make a new effect silent.
        paramIndex(node, "Amount")?.let { NativeBridge.nativeSetNodeParam(node, it, 1f) }
    }

    /** [fx] on [song]'s instance [id], or on the whole mix for null (the effect editor). */
    fun applyFx(song: RackSong?, id: String?, fx: be.mdw.bagosynths.model.RackFx?) = applyFxAt(fxTarget(song, id), fx)

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

    /** A stage fader moved. */
    fun setLevel(id: String, v: Float) {
        onEngine[id]?.let { setParamAt(it, "Level", v) }
        levels[id] = v
        rack?.instance(id)?.let { switcher?.noteValue(it.levelKey, v.toDouble()) }
    }

    fun panic() = NativeBridge.nativePanic()

    /**
     * Hands the rack back neutral -- every wire on, every zone open -- so the
     * prototype's part tabs, which know nothing of zones and wires, work as
     * they always did. [reload] takes it back.
     */
    fun releaseRack() {
        for (i in 0 until NativeBridge.nativeInstanceCount()) {
            midiOn[i] = true; audioOn[i] = true; gain[i] = 1f
            NativeBridge.nativeRackWire(i, true, true, 1f)
            NativeBridge.nativeZoneParam(i, ZONE_PARAMS.indexOf("keyLow"), 0f)
            NativeBridge.nativeZoneParam(i, ZONE_PARAMS.indexOf("keyHigh"), 127f)
            NativeBridge.nativeZoneParam(i, ZONE_PARAMS.indexOf("transpose"), 0f)
            NativeBridge.nativeZoneParam(i, ZONE_PARAMS.indexOf("channelIn"), 0f)
        }
    }

    // ------------------------------------------------------------- applying

    /** The song's instances on the engine's, with their patches; engine instances it does not use are silenced and parked. */
    private fun setUpEngines() {
        val r = rack
        // Every song sets the whole reverb, so one song's room never stays
        // with the next.
        previewReverb(r?.reverb.orEmpty())
        applyFxAt(masterFx(), r?.masterFx)
        onEngine = assignment(r)
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
        for (inst in r?.instances.orEmpty()) {
            val i = onEngine[inst.id] ?: continue
            applyFxAt(fxNodeOf(i), inst.slot.fx)
            loadPresetAt(i, inst.engine, inst.slot.preset, inst.slot.program)
            // The patch's own parameters, over its preset (M8).
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
            if (i != e.ordinal) {
                // An added instance: by name, its own loader (AudioEngine::loadInstancePreset).
                if (NativeBridge.nativeInstancePreset(i) == preset && program == null) return
                val path = if (e == RackEngine.SFIZZ) host.instrumentPath(preset) else null
                NativeBridge.nativeInstanceLoadPreset(i, preset, program ?: -1, path)
                return
            }
            when (e) {
                RackEngine.SFIZZ -> if (host.currentInstrument() != preset) host.selectInstrument(preset)
                RackEngine.SETBFREE -> {
                    val k = NativeBridge.nativeSetBfreePresetNames().indexOf(preset)
                    if (k >= 0) NativeBridge.nativeSetBfreePreset(k, 0)
                }
                RackEngine.SURGE ->
                    if (NativeBridge.nativeSurgeReady() && NativeBridge.nativeSurgeCurrentPatchName() != preset) {
                        NativeBridge.nativeSurgeLoadPatchNamed(preset)
                    }
                RackEngine.DEXED -> if (NativeBridge.nativeDexedReady()) NativeBridge.nativeDexedSelectVoiceNamed(preset)
                RackEngine.FLUID -> if (NativeBridge.nativeFluidReady()) {
                    if (NativeBridge.nativeFluidCurrentFont() != preset) NativeBridge.nativeFluidLoadFontNamed(preset)
                    if (program != null) Thread {
                        for (k in 0 until 200) {
                            if (!NativeBridge.nativeFluidBusy()) break
                            Thread.sleep(50)
                        }
                        NativeBridge.nativeFluidSelectPreset(program)
                    }.start()
                }
                RackEngine.PLAITS -> {
                    val k = NativeBridge.nativePlaitsEngineNames().indexOf(preset)
                    if (k >= 0) NativeBridge.nativeSetPlaitsParam(1, k.toFloat())
                }
            }
        }.onFailure { Log.e("bos-ui", "preset $preset for instance $i (${e.key}): $it") }
    }

    private fun apply(changes: List<Change>) {
        for (c in changes) when (c) {
            is Change.Param -> applyParam(c.key, c.value.toFloat())
            is Change.WireSet -> {
                val dot = c.wire.lastIndexOf('.')
                val i = onEngine[c.wire.substring(0, dot)] ?: continue
                when (c.wire.substring(dot + 1)) {
                    "midi" -> setWireAt(i, midi = c.on)
                    "audio" -> setWireAt(i, audio = c.on, g = c.gain.toFloat())
                }
            }
            is Change.Cp88 -> when (c.action.mode) {
                "select" -> selectCp88(c.action.page ?: 1, c.action.set ?: 1)
                "own" -> ownedSlots[pendingLiveSet]?.let { selectCp88(reservedPage, it) }
            }
            // On-select MIDI (I6.7): to the keyboard's MIDI out, in the direct modes.
            is Change.Send -> {
                val bytes = runCatching { ByteArray(c.hex.length / 2) { c.hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
                    .getOrNull()
                val r = bytes?.let { runCatching { NativeBridge.nativeUsbMidiSend(it) }.getOrDefault(-1) } ?: -1
                if (r < 0) note("on-select MIDI ${c.hex} not sent ($r): no MIDI out in this mode")
            }
            is Change.Enable -> Unit   // not in the rack slice yet
        }
        for ((id, i) in onEngine) playing[id] = midiOn[i]
    }

    /** A song key ("sfizz-2.zone.keyLow", "setbfree.Level") on the engine instance its song instance plays on. */
    private fun applyParam(key: String, v: Float) {
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

    private fun setWireAt(i: Int, midi: Boolean? = null, audio: Boolean? = null, g: Float? = null) {
        if (i !in 0 until MAX_INSTANCES) return
        midi?.let { midiOn[i] = it }
        audio?.let { audioOn[i] = it }
        g?.let { gain[i] = it }
        NativeBridge.nativeRackWire(i, midiOn[i], audioOn[i], gain[i])
    }

    companion object {
        private const val TAG = "bos-stage"
        /** What a switching controller can be taught (I10.4, I12.3). */
        val CONTROLLER_ACTIONS = listOf("next", "previous", "tap", "click")
        /** AudioEngine::kMaxInstances. */
        const val MAX_INSTANCES = 24
        /** ReverbNode's own defaults (graph/Nodes.h), for a song that sets none. */
        val REVERB_DEFAULTS = mapOf("Room size" to 0.72, "Damping" to 0.35, "Width" to 1.0, "Mix" to 0.25)
        const val SETBFREE_LESLIE = 37
        const val SETBFREE_PERC = 27
        val SETBFREE_STAGE_PARAMS = (0..8).toList() + listOf(SETBFREE_PERC, SETBFREE_LESLIE)

        /** engine/include/bagosynths/MidiZone.h, `Param`, in order. */
        val ZONE_PARAMS = listOf(
            "channelIn", "channelOut", "keyLow", "keyHigh", "velLow", "velHigh", "transpose",
            "passNotes", "passCC", "passBend", "passPressure", "passProgram",
            "ccFromA", "ccToA", "ccFromB", "ccToB")
    }
}
