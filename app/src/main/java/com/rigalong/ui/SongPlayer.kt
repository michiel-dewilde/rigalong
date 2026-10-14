package com.rigalong.ui

import com.rigalong.model.Library
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rigalong.NativeBridge
import com.rigalong.model.Digest
import com.rigalong.model.LiveSet
import com.rigalong.model.LiveSetSwitcher
import com.rigalong.model.LiveSetSwitcher.Change
import com.rigalong.model.RackEngine
import com.rigalong.model.RackSong
import com.rigalong.model.Song
import com.rigalong.model.Tempo
import com.rigalong.model.getTyped

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
    /**
     * An engine's own file ([com.rigalong.model.EngineFiles]: a SoundFont,
     * a DX7 bank, a Surge XT patch) installed where its engine looks, and the
     * engines told to look again. Returns the name it got, or null if exactly
     * that file was installed already. Call off the UI thread.
     */
    fun installEngineFile(kind: com.rigalong.model.EngineFiles.Kind, fileName: String, bytes: ByteArray): String?
    /**
     * [patch] as its engine's own file ([com.rigalong.model.PatchExport]),
     * called [name]: the file name and its bytes, or null where the engine's
     * sound is not one file. Reads the engine's content: call off the UI thread.
     */
    fun exportPatch(patch: com.rigalong.model.Patch, name: String): Pair<String, ByteArray>?
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
    /**
     * While the song editor is heard ([hear]): which song (its entry, null for
     * a new one) and which of its live sets the engine plays; null when the
     * engine plays the stage's song.
     */
    var heard by mutableStateOf<Pair<Digest?, Int>?>(null); private set
    /** The stage's song and live set, to come back to after [hear]. */
    private var stageSong = -1
    private var stageLiveSet = 0
    /** Instance levels as they are now, by instance id, for the stage faders. */
    val levels = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    val playing = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    /** The external devices and what the songs do to them (model/devices). */
    val devices = DeviceRig(lib, prefs) { note(it) }
    /** The live sets of the song on stage, as stored. */
    private var liveSetObjects: List<LiveSet> = emptyList()

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
        val (song, liveSet) = if (heard != null) stageSong to stageLiveSet else songIndex to liveSetIndex
        setlistId?.let { openSetlist(it, song.coerceAtLeast(0), liveSet.coerceAtLeast(0)) }
    }

    fun nextSong() { if (songIndex + 1 < songs.size) selectSong(songIndex + 1) }
    fun previousSong() { if (songIndex > 0) selectSong(songIndex - 1) }

    fun selectSong(index: Int, liveSet: Int = 0) {
        val s = songs.getOrNull(index) ?: return
        heard = null
        play(s, lib.rackSong(s), index, liveSet)
    }

    /**
     * The song open in the editor, as it is now (saved or not), on the engine
     * at its live set [liveSet], so that the keys -- the on-screen ones too --
     * play it (S20.4). [entry] is the song's library entry, null for a new
     * song. The stage's song comes back with [stopHearing].
     */
    fun hear(entry: Digest?, draft: RackSong, liveSet: Int) {
        if (liveSet !in draft.liveSets.indices) return
        if (heard == null) {
            stageSong = songIndex; stageLiveSet = liveSetIndex
            sessionBpm.keys.removeAll { it.startsWith("edit/") }
        }
        heard = entry to liveSet
        play(draft.toObjects(lib.store), draft, null, liveSet)
    }

    /** Back to the stage's song and live set after [hear]. */
    fun stopHearing() {
        if (heard == null) return
        if (songs.getOrNull(stageSong) != null) selectSong(stageSong, stageLiveSet) else heard = null
    }

    /** [s] on the engine and the devices, at live set [liveSet]: song [index] of the setlist, or (null) the editor's. */
    private fun play(s: Song, r: RackSong?, index: Int?, liveSet: Int) {
        // The last song's live set values off its engines first, so none stays
        // on into a song that leaves that parameter to its preset.
        restoreSongValues()
        touched.clear()
        // The same song again (a reload after saving): tapped tempos hold for
        // the session (I12.3) and a running click goes on. Another song stops it.
        val sameSong = index != null && index == songIndex
        if (index != songIndex && clickRunning) stopClick()
        if (index != null) songIndex = index
        song = s
        rack = r
        val liveSets = s.liveSets.map { lib.store.getTyped<LiveSet>(it.toRef().digest) }
        liveSetNames.clear()
        liveSetNames.addAll(liveSets.map { it.name })
        switcher = LiveSetSwitcher(s, liveSets)
        if (index != null) prefs.edit().putInt("stage.song", index).apply()
        note((if (index != null) "song ${index + 1} '${songNames.getOrNull(index)}'" else "the editor's song") +
                ": instances ${rack?.instances?.map { it.id }}, live sets ${liveSets.map { it.name }}")
        // The tempo before the engines: a synced effect is created at the song's
        // tempo, instead of gliding to it from the last song's over seconds.
        if (!sameSong) {
            if (index != null) sessionBpm.clear()
            if (!clickRunning) beatOrigin = System.nanoTime()
            taps.clear()
        }
        updateTempo(liveSet)
        setUpEngines()
        // A preset loading in the background resets its engine when it lands:
        // then the song's values go on again ([settle]).
        settling = onEngine.values.any { busy(it) }
        sheetPage = 0
        sheetPages = 0
        sheetFile = s.leadSheet?.toRef()?.digest?.let { java.io.File(lib.store.root, "blobs/sha256/${it.hex}") }
            ?.takeIf { it.exists() }
        liveSetObjects = liveSets
        devices.writeSong(liveSets)
        if (liveSets.isNotEmpty()) selectLiveSet(liveSet.coerceIn(0, liveSets.size - 1))
        // The next song's device sounds into the other reserved area, while this one plays.
        if (index != null) devices.preload(nextLiveSets())
    }

    /** The setlist's next song's live sets, for the devices to preload; null at the end. */
    private fun nextLiveSets(): List<LiveSet>? = songs.getOrNull(songIndex + 1)?.let { n ->
        runCatching { n.liveSets.map { lib.store.getTyped<LiveSet>(it.toRef().digest) } }.getOrNull()
    }

    fun selectLiveSet(index: Int) {
        val sw = switcher ?: return
        if (index !in liveSetNames.indices) return
        val changes = sw.select(index)
        pendingLiveSet = index
        apply(changes)
        // The external devices: each does what this live set says (DeviceRig).
        devices.liveSetSelected(index, liveSetObjects.getOrNull(index))
        liveSetIndex = index
        Log.i(TAG, "live set ${index + 1} '${liveSetNames[index]}': ${changes.size} changes: " +
                changes.joinToString("; ") { c -> when (c) {
                    is Change.Param -> "${c.key}=${"%.2f".format(c.value)}"
                    is Change.WireSet -> "${c.wire}=${if (c.on) "on" else "off"}"
                    else -> c.toString()
                } })
        if (heard == null) prefs.edit().putInt("stage.liveSet", index).apply()
        // Its own tempo and metre, or back to the song's.
        updateTempo(index)
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

    // ------------------------------------------------------------------ tempo

    /**
     * The tempo and metre in force (S12.1, S12.5): the current live set's own
     * if it has one, else the song's -- with what was tapped or typed on the
     * stage for that one this session (I12.3). Null: neither has one.
     */
    var tempo by mutableStateOf<Tempo?>(null); private set
    /** [tempo]'s BPM, in its beat unit; null is no tempo. */
    val bpm: Double? get() = tempo?.bpm
    /** When beat one of the visual beat fell, System.nanoTime(). */
    var beatOrigin by mutableStateOf(0L); private set
    /** The tempo in force was tapped or typed this session, not as stored: it can be saved, or let go. */
    var bpmTapped by mutableStateOf(false); private set
    private val taps = ArrayDeque<Long>()
    private var songEntries: List<Digest?> = emptyList()
    /**
     * Tempos tapped or typed this session, by where they belong ([tempoKey]):
     * the song's, or one live set's own. A key with null is "no tempo". The
     * stage song's hold until another song opens; the editor's song has its own.
     */
    private val sessionBpm = HashMap<String, Double?>()

    /** Where live set [ls]'s tempo comes from: its own, or the song's. */
    private fun tempoKey(ls: Int): String =
        (if (heard != null) "edit/" else "song/") + (if (rack?.liveSets?.getOrNull(ls)?.tempo != null) "ls$ls" else "song")

    /** The tempo as stored for live set [ls]: its own, or the song's. */
    private fun storedTempo(ls: Int): Tempo? = rack?.liveSets?.getOrNull(ls)?.tempo ?: rack?.tempo

    /** Works out [tempo] for live set [ls] and gives it to the click, the engines and MIDI clock. */
    private fun updateTempo(ls: Int = liveSetIndex) {
        val key = tempoKey(ls)
        val stored = storedTempo(ls)
        val b = if (key in sessionBpm) sessionBpm[key] else stored?.bpm
        val t = if (b == null && stored == null) null else (stored ?: Tempo()).copy(bpm = b)
        retempo(t)
        bpmTapped = key in sessionBpm
        applyMidiClock()
    }

    /** One tap; four or more taps less than 2 s apart set the tempo. */
    fun tap() {
        val now = System.nanoTime()
        if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
        taps.addLast(now)
        while (taps.size > 6) taps.removeFirst()
        if (taps.size >= 4) {
            val span = (taps.last() - taps.first()) / (taps.size - 1).toDouble()
            sessionBpm[tempoKey(liveSetIndex)] = Math.round(60e9 / span * 10) / 10.0
            updateTempo()
        }
        // The visual beat falls on the tap, unless the click is counting: then
        // it stays with what is heard.
        if (!clickRunning) beatOrigin = now
    }

    /** A tempo typed on the stage, or null for none: holds for the session like a tapped one. */
    fun typeTempo(newBpm: Double?) {
        sessionBpm[tempoKey(liveSetIndex)] = newBpm?.coerceIn(20.0, 400.0)
        if (newBpm == null && clickRunning) stopClick()
        updateTempo()
    }

    /** The tempo in force is the current live set's own, not the song's. */
    fun tempoIsLiveSets(): Boolean = rack?.liveSets?.getOrNull(liveSetIndex)?.tempo != null

    /** Back to the stored tempo, letting go of what was tapped or typed. */
    fun revertTempo() {
        sessionBpm.remove(tempoKey(liveSetIndex))
        updateTempo()
    }

    /** A new tempo; while the click runs, the visual beat bends with it instead of jumping. */
    private fun retempo(newTempo: Tempo?) {
        val old = bpm
        val newBpm = newTempo?.bpm
        if (clickRunning && old != null && newBpm != null) {
            val now = System.nanoTime()
            val beats = (now - beatOrigin) / (60e9 / old)
            beatOrigin = now - (beats * 60e9 / newBpm).toLong()
            NativeBridge.nativeClickTempo(newBpm)
        }
        tempo = newTempo
    }

    // ------------------------------------------------------------------ click

    /**
     * The metronome (S12.6). Running, it blinks on every beat; the **click** is
     * heard and the **bar count** (the beat's number, beat one accented) shown
     * always, during a count-in only, or never; after a count-in it keeps
     * going or stops. These are setup settings (the environment's); the stage
     * has Play, Count-in and Stop.
     */
    enum class Presence { ON, COUNT_IN, OFF }

    var clickSound by mutableStateOf(presence("click.sound") ?: legacyClick().first)
    var barCount by mutableStateOf(presence("click.barCount") ?: Presence.ON)
    var keepGoing by mutableStateOf(if (prefs.contains("click.keepGoing")) prefs.getBoolean("click.keepGoing", false)
        else legacyClick().second)
    /** The count-in's length in bars, where the song names none. */
    var countInBars by mutableIntStateOf(prefs.getInt("click.countInBars", 1))
    var clickLevel by mutableStateOf(prefs.getFloat("click.level", 0.5f))
    /** MIDI clock out to the keyboard (I12.2), for its tempo-synced effects. */
    var midiClock by mutableStateOf(prefs.getBoolean("click.midiClock", false))
    /** The metronome runs: the beat blinks. */
    var clickRunning by mutableStateOf(false); private set
    /** A count-in is under way (until [countInEnds]). */
    var countingIn by mutableStateOf(false); private set
    private var countInEnds = 0L

    private fun presence(key: String) = prefs.getString(key, null)?.let { runCatching { Presence.valueOf(it) }.getOrNull() }

    /** The click and keep-going as the settings before S12.6 had them (click.mode, click.continuous, click.audio). */
    private fun legacyClick(): Pair<Presence, Boolean> {
        val mode = prefs.getString("click.mode", null)
            ?: if (prefs.getBoolean("click.continuous", false)) "CONTINUOUS" else "COUNT_IN"
        val sound = when {
            !prefs.getBoolean("click.audio", true) -> Presence.OFF
            mode == "CONTINUOUS" -> Presence.ON
            else -> Presence.COUNT_IN
        }
        return sound to (mode != "COUNT_IN")
    }

    fun saveClickSettings() {
        prefs.edit().putString("click.sound", clickSound.name).putString("click.barCount", barCount.name)
            .putBoolean("click.keepGoing", keepGoing).putInt("click.countInBars", countInBars)
            .putFloat("click.level", clickLevel).putBoolean("click.midiClock", midiClock).apply()
        applyMidiClock()
    }

    /** The count-in's beats: the song's bars (or the setup's), of the metre in force. */
    private fun countInBeats(): Int {
        val t = tempo
        return t.barBeats() * (t?.countInBars ?: countInBars).coerceAtLeast(1)
    }

    private fun Tempo?.barBeats() = (this?.beatsPerBar ?: 4).coerceAtLeast(1)

    /** Play: the metronome from beat one, no count-in. Needs a tempo. */
    fun playClick() = startClick(countIn = false)

    /** Count-in: the song's bars counted in, then on or stopped as the setup says. Needs a tempo. */
    fun countIn() = startClick(countIn = true)

    /** Play or stop, for a footswitch. */
    fun toggleClick() = if (clickRunning) stopClick() else playClick()

    private fun startClick(countIn: Boolean) {
        val b = bpm ?: return
        val n = if (countIn) countInBeats() else 0
        fun beats(p: Presence) = when (p) { Presence.ON -> -1; Presence.COUNT_IN -> n; Presence.OFF -> 0 }
        val limit = if (countIn && !keepGoing) n else 0
        val delay = NativeBridge.nativeClickStart(b, tempo.barBeats(), limit, beats(clickSound), beats(barCount),
            clickLevel, midiClock)
        beatOrigin = System.nanoTime() + delay
        countInEnds = beatOrigin + (n * 60e9 / b).toLong()
        clickRunning = true
        countingIn = countIn
        Log.i(TAG, "click: ${if (countIn) "count-in, $n beats" else "play"} at $b BPM (${tempo?.metre}), " +
            "click ${clickSound.name.lowercase()}, bar count ${barCount.name.lowercase()}, " +
            "then ${if (keepGoing || !countIn) "on" else "stop"}; heard in ${delay / 1_000_000} ms")
    }

    fun stopClick() {
        NativeBridge.nativeClickStop(midiClock)
        clickRunning = false
        countingIn = false
        Log.i(TAG, "click stopped; MIDI clock ${NativeBridge.nativeMidiClockTicks()} ticks sent")
    }

    /** The beat's number in the bar to show now, 1-based, or 0: not running, or no bar count now. */
    fun clickBeat(now: Long): Int {
        val b = bpm ?: return 0
        if (!clickRunning || now < beatOrigin) return 0
        val counting = countingIn && now < countInEnds
        val shown = barCount == Presence.ON || (barCount == Presence.COUNT_IN && counting)
        return if (shown) ((now - beatOrigin) / (60e9 / b)).toLong().mod(tempo.barBeats()) + 1 else 0
    }

    /** The count-in's end, and a count-in that stops when it ends: the beat stops with the last click heard. */
    private fun followClick() {
        if (!clickRunning) return
        val now = System.nanoTime()
        if (countingIn && now >= countInEnds) {
            countingIn = false
            if (!keepGoing) { clickRunning = false; return }
        }
        if (!countingIn && !NativeBridge.nativeClickRunning()) clickRunning = false
    }

    /** The tempo goes to the engines (I12.1) and, when it is on, out as MIDI clock -- both in quarter notes. */
    private fun applyMidiClock() {
        val q = tempo?.quarterBpm
        runCatching { NativeBridge.nativeSetTempo(q ?: 0.0) }
        runCatching { NativeBridge.nativeMidiClock(if (midiClock) q ?: 0.0 else 0.0) }
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
            parts[inst.id] = (parts[inst.id] ?: com.rigalong.model.RackPart(plays = false)).copy(level = v.toDouble())
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

    /**
     * Writes the tempo tapped or typed (or "no tempo") where it belongs (I12.3):
     * into the current live set's own tempo if it has one, else the song's -- a
     * new version of the song's entry.
     */
    fun saveTempo() {
        if (heard != null) return
        val id = songEntries.getOrNull(songIndex) ?: return
        val r = rack ?: return
        val name = songNames.getOrNull(songIndex) ?: return
        val i = liveSetIndex
        val b = bpm
        fun with(t: Tempo?) = (t ?: Tempo()).copy(bpm = b).takeIf { it.bpm != null || it != Tempo() }
        val own = r.liveSets.getOrNull(i)?.tempo
        val saved = if (own != null) r.copy(liveSets = r.liveSets.toMutableList().also { it[i] = it[i].copy(tempo = with(own) ?: Tempo()) })
            else r.copy(tempo = with(r.tempo))
        lib.saveSong(id, name, saved)
        sessionBpm.remove(tempoKey(i))
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
     * TEST (PROVISIONAL.md): a device's own-sound path end to end, without the
     * screen, on the first device that can own sounds (a CP-88). Imports its
     * current sound, builds a song whose live set 1 owns it and whose live
     * set 2 selects the device's first stored sound, opens it on the stage
     * (which writes the sound into the reserved area) and selects live set
     * [liveSet]. Logs what it did under rigalong-stage; tools/cp88sysex reads a
     * CP-88 back.
     */
    fun deviceSelfTest(liveSet: Int) {
        Thread {
            runCatching {
                val d = devices.withSounds().firstOrNull() ?: error("no device that can own a sound")
                val drv = devices.driver(d)
                val (sound, name) = devices.importCurrent(d)
                Log.i(TAG, "selftest: imported '$name' ${sound.short} from ${d.name}")
                val first = drv.address.associate { it.key to it.range.first }.filterKeys { k -> drv.address.none { it.key == k && it.optional } }
                val draft = RackSong(
                    instances = listOf(com.rigalong.model.RackInstance("sfizz", RackEngine.SFIZZ,
                        com.rigalong.model.RackSlot(level = 0.0))),
                    liveSets = listOf(
                        com.rigalong.model.RackLiveSet("Own", devices = mapOf(d.name to
                            com.rigalong.model.RackDevicePart(d.kind, "own", sound = sound, soundName = name))),
                        com.rigalong.model.RackLiveSet("Select ${drv.describe(first)}", devices = mapOf(d.name to
                            com.rigalong.model.RackDevicePart(d.kind, "select", first))),
                    ))
                val song = draft.toObjects(lib.store)
                val liveSets = song.liveSets.map { lib.store.getTyped<LiveSet>(it.toRef().digest) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    this.song = song
                    rack = draft
                    switcher = LiveSetSwitcher(song, liveSets)
                    liveSetObjects = liveSets
                    liveSetNames.clear(); liveSetNames.addAll(liveSets.map { it.name })
                    devices.writeSong(liveSets)
                    selectLiveSet(liveSet - 1)
                    Thread { devices.settle(); Log.i(TAG, "selftest: live set $liveSet selected; ${devices.status}") }.start()
                }
            }.onFailure { Log.e(TAG, "selftest failed: $it") }
        }.start()
    }

    /**
     * DEBUG HOOK (PROVISIONAL.md): three songs of one live set each, owning
     * three different sounds of the first device that has them, opened in turn
     * through the rig as the stage would -- each preloading the next -- and
     * then the first again. Logs under rigalong-devices which area each plays from
     * and what was written; the stage's own song is left as it was.
     */
    fun devicePreloadTest() {
        Thread {
            runCatching {
                val d = devices.withSounds().firstOrNull() ?: error("no device that can own a sound")
                val type = devices.driver(d).sounds!!.mediaType
                val sounds = lib.deviceSounds(type).mapNotNull { it.version.target?.digest }.distinct().take(3).toMutableList()
                check(sounds.isNotEmpty()) { "no device sound in the library" }
                // Short of three: renamed copies of the first, through its text form (kept only in the store).
                val text = devices.driver(d).sounds!!.formats.firstOrNull { it.editable }
                while (sounds.size < 3) {
                    val f = text ?: error("need three different device sounds, have ${sounds.size}")
                    val renamed = f.toText(lib.store.get(sounds[0])!!).replaceFirst(Regex("(?m)^name: .*$"), "name: Preload ${sounds.size + 1}")
                    sounds += lib.store.put(f.fromText(renamed))
                }
                val songs = sounds.mapIndexed { i, digest ->
                    val draft = RackSong(instances = listOf(com.rigalong.model.RackInstance("sfizz", RackEngine.SFIZZ,
                        com.rigalong.model.RackSlot(level = 0.0))), liveSets = listOf(com.rigalong.model.RackLiveSet("Own ${i + 1}",
                        devices = mapOf(d.name to com.rigalong.model.RackDevicePart(d.kind, "own", sound = digest)))))
                    draft.toObjects(lib.store).liveSets.map { lib.store.getTyped<LiveSet>(it.toRef().digest) }
                }
                for (k in listOf(0, 1, 2, 0)) {
                    devices.writeSong(songs[k])
                    devices.liveSetSelected(0, songs[k][0])
                    devices.preload(songs.getOrNull(k + 1))
                    devices.settle()
                    Log.i("rigalong-devices", "preloadtest: song ${k + 1} done; ${devices.status}")
                }
            }.onFailure { Log.e("rigalong-devices", "preloadtest failed: $it") }
        }.start()
    }

    /** DEBUG HOOK (PROVISIONAL.md): [DeviceRig.writeSelectTest] with two renamed copies of a library sound. */
    fun deviceWriteSelectTest(delays: List<Int>) {
        Thread {
            runCatching {
                val d = devices.withSounds().firstOrNull() ?: error("no device that can own a sound")
                val f = devices.driver(d).sounds!!.formats.first { it.editable }
                val base = lib.deviceSounds(devices.driver(d).sounds!!.mediaType).firstNotNullOf { e ->
                    e.version.target?.let { lib.store.get(it.digest) } }
                fun named(n: String) = f.fromText(f.toText(base).replaceFirst(Regex("(?m)^name: .*$"), "name: $n"))
                for (line in devices.writeSelectTest(d, named("Write test A"), named("Write test B"), delays))
                    Log.i("rigalong-devices", "writeselect: $line")
            }.onFailure { Log.e("rigalong-devices", "writeselect failed: $it") }
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
     * live sets (or turn pages), "tap" taps the tempo, "countIn" counts in, "click" starts and stops
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
            "countIn" -> countIn()
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
            // It may be another unit than before (the stage's spare, a rental):
            // what this tablet wrote to the last one says nothing.
            devices.midiOutReady(liveSetObjects, liveSetIndex, nextLiveSets())
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
        followClick()
        val pcs = runCatching { NativeBridge.nativeDrainPrograms() }.getOrNull() ?: return
        for (v in pcs) {
            if (v ushr 31 == 1) {
                onController((v shr 8) and 0x7F, v and 0x7F)
                continue
            }
            val msb = (v shr 16) and 0x7F
            val lsb = (v shr 8) and 0x7F
            val pc = v and 0x7F
            // A device that follows the app reporting one of its own (its live set
            // buttons): that live set. -1 is a device's own with nothing to do, or our echo.
            val followed = devices.followed(msb, lsb, pc) { lastSelection = it }
            when {
                followed == null -> lastSelection = "PC ${pc + 1} (bank $msb/$lsb, ch ${(v ushr 24) + 1})"
                followed in liveSetNames.indices -> selectLiveSet(followed)
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
    fun previewSlot(song: RackSong, id: String, slot: com.rigalong.model.RackSlot) {
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
    private fun applyFxAt(node: Int?, fx: com.rigalong.model.RackFx?) {
        node ?: return
        val type = com.rigalong.model.RackFx.TYPES.indexOf(fx?.type ?: "off").coerceAtLeast(0)
        val params = fx?.params.orEmpty()
        NativeBridge.nativeFxConfigure(node, type, params.keys.toTypedArray(),
            params.values.map { it.toFloat() }.toFloatArray())
        // Fully on until a live set says otherwise: an amount left at 0 by the
        // previous song would make a new effect silent.
        paramIndex(node, "Amount")?.let { NativeBridge.nativeSetNodeParam(node, it, 1f) }
    }

    /** [fx] on [song]'s instance [id], or on the whole mix for null (the effect editor). */
    fun applyFx(song: RackSong?, id: String?, fx: com.rigalong.model.RackFx?) = applyFxAt(fxTarget(song, id), fx)

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
        }.onFailure { Log.e("rigalong-ui", "preset $preset for instance $i (${e.key}): $it") }
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
            // External devices: DeviceRig.liveSetSelected, after the changes.
            is Change.Device -> Unit
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
        private const val TAG = "rigalong-stage"
        /** What a switching controller can be taught (I10.4, I12.3). */
        val CONTROLLER_ACTIONS = listOf("next", "previous", "tap", "click", "countIn")
        /** AudioEngine::kMaxInstances. */
        const val MAX_INSTANCES = 24
        /** ReverbNode's own defaults (graph/Nodes.h), for a song that sets none. */
        val REVERB_DEFAULTS = mapOf("Room size" to 0.72, "Damping" to 0.35, "Width" to 1.0, "Mix" to 0.25)
        const val SETBFREE_LESLIE = 37
        const val SETBFREE_PERC = 27
        val SETBFREE_STAGE_PARAMS = (0..8).toList() + listOf(SETBFREE_PERC, SETBFREE_LESLIE)

        /** engine/include/rigalong/MidiZone.h, `Param`, in order. */
        val ZONE_PARAMS = listOf(
            "channelIn", "channelOut", "keyLow", "keyHigh", "velLow", "velHigh", "transpose",
            "passNotes", "passCC", "passBend", "passPressure", "passProgram",
            "ccFromA", "ccToA", "ccFromB", "ccToB")
    }
}
