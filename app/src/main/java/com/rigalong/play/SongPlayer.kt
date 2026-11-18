package com.rigalong.play

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

/**
 * Plays a setlist on the rack (model/Rack.kt): selecting a song sets up its
 * engines and patches, selecting a live set applies what [LiveSetSwitcher]
 * says, through the zones and the switchable wires (AudioEngine.h).
 *
 * Remembers setlist, song and live set in preferences on every move, so a
 * restart comes back where it was (S13.2).
 *
 * Its parts (REVIEW S3): the calls into the engine ([engines]), the tempo
 * and click ([metronome]), the lead sheet ([sheet]), the switching
 * controllers ([switches]), MIDI learn ([midiLearn]) and the external
 * devices ([devices]). What is left here is which song and live set are on,
 * and what follows from that.
 */
class SongPlayer(private val lib: Library, host: EngineHost, private val prefs: SharedPreferences) {

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

    /** The calls into the engine: instances, presets, parameters, effects, wires. */
    val engines = EngineRack(host) { note(it) }
    /** The tempo in force and the metronome. */
    val metronome = Metronome(prefs, host)
    /** The song's lead sheet on the stage. */
    val sheet = LeadSheet(prefs, lib.store)
    /** The footswitches' controllers. */
    val switches = SwitchControllers(prefs)
    /** MIDI learn for the editors. */
    val midiLearn = MidiLearn { note(it) }
    /** The external devices and what the songs do to them (model/devices). */
    val devices = DeviceRig(lib, prefs) { note(it) }

    /** The live sets of the song on stage, as stored. */
    private var liveSetObjects: List<LiveSet> = emptyList()

    private var songs: List<Song> = emptyList()
    private var songEntries: List<Digest?> = emptyList()
    private var switcher: LiveSetSwitcher? = null

    /** The library entry of the stage's song, or null (a removed one). */
    val songEntry: Digest? get() = songEntries.getOrNull(songIndex)

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
            metronome.forgetEditorTempos(EDITOR_TEMPOS)
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
        if (index != songIndex && metronome.clickRunning) metronome.stopClick()
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
        if (!sameSong) metronome.newSong(forgetTapped = index != null)
        updateTempo(liveSet)
        engines.setUp(rack)
        // A preset loading in the background resets its engine when it lands:
        // then the song's values go on again ([settle]).
        settling = engines.anyBusy()
        loading = settling
        sheet.open(s.leadSheet?.toRef()?.digest)
        liveSetObjects = liveSets
        // The first song on a MIDI out that was up before it: this is where
        // the devices are brought in line, in the one pass that writes the
        // song. (Left to watchMidiOut, a moment later, the page went out
        // twice.)
        if (!midiOutWasReady && midiOutReady()) {
            midiOutWasReady = true
            devices.forget()
        }
        // Taken before the song's slots are written, so that what is read is the player's own sound.
        if (sounding() && liveSets.isNotEmpty()) devices.take()
        devices.writeSong(liveSets)
        if (liveSets.isNotEmpty()) selectLiveSet(liveSet.coerceIn(0, liveSets.size - 1))
        parkIfSilent()
    }

    fun selectLiveSet(index: Int) {
        val sw = switcher ?: return
        if (index !in liveSetNames.indices) return
        val changes = sw.select(index)
        apply(changes)
        // The external devices: each does what this live set says (DeviceRig) --
        // while the rig sounds; off the stage they are left on their own sounds.
        if (sounding()) devices.liveSetSelected(index, liveSetObjects.getOrNull(index))
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
            .let { all -> all.firstOrNull { engines.playing[it.id] == true } ?: all.firstOrNull() }?.id
        refreshSetBfree()
        applyControls()
        parkIfSilent()
    }

    fun nextLiveSet() { if (liveSetIndex + 1 < liveSetNames.size) selectLiveSet(liveSetIndex + 1) }
    fun previousLiveSet() { if (liveSetIndex > 0) selectLiveSet(liveSetIndex - 1) }

    /** A live set's changes onto the engine; the devices follow in [selectLiveSet]. */
    private fun apply(changes: List<Change>) {
        for (c in changes) when (c) {
            is Change.Param -> engines.applyParam(c.key, c.value.toFloat())
            is Change.WireSet -> engines.applyWire(c)
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
        engines.showWires()
    }

    // ------------------------------------------- a live set's own sound values

    private var settling = false
    /** The song's presets are still coming in: the keys may play nothing yet, or the last sound. For the stage to say. */
    var loading by mutableStateOf(false); private set

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
        for (k in liveSetKeys()) songValue(k)?.let { engines.applyParam(k, it.toFloat()) }
    }

    /**
     * Once the presets that loaded in the background have landed: the patches'
     * values on again, and the live set's values over them. Polled.
     */
    private fun settle() {
        if (!settling || engines.anyBusy()) return
        settling = false
        loading = false
        engines.applyPatchValues(rack)
        val sw = switcher ?: return
        val ls = liveSetIndex.takeIf { it in liveSetNames.indices } ?: return
        for (k in liveSetKeys()) sw.storedValue(ls, k)?.let { engines.applyParam(k, it.toFloat()); sw.noteValue(k, it) }
        refreshSetBfree()
        // The knobs again: their parameters are the landed presets' now.
        applyControls()
        note("presets landed: the song's values and live set ${ls + 1}'s own on again")
    }

    // ------------------------------------------------------------------ tempo

    /** Where live set [ls]'s tempo comes from: its own, or the song's; the editor's song apart from the stage's. */
    private fun tempoKey(ls: Int): String =
        (if (heard != null) EDITOR_TEMPOS else "song/") + (if (rack?.liveSets?.getOrNull(ls)?.tempo != null) "ls$ls" else "song")

    /** The tempo as stored for live set [ls]: its own, or the song's. */
    private fun storedTempo(ls: Int): Tempo? = rack?.liveSets?.getOrNull(ls)?.tempo ?: rack?.tempo

    private fun updateTempo(ls: Int) = metronome.follow(tempoKey(ls), storedTempo(ls))

    /** The tempo in force is the current live set's own, not the song's. */
    fun tempoIsLiveSets(): Boolean = rack?.liveSets?.getOrNull(liveSetIndex)?.tempo != null

    /**
     * Writes the tempo tapped or typed (or "no tempo") where it belongs (I12.3):
     * into the current live set's own tempo if it has one, else the song's -- a
     * new version of the song's entry.
     */
    fun saveTempo() {
        if (heard != null) return
        val id = songEntry ?: return
        val r = rack ?: return
        val name = songNames.getOrNull(songIndex) ?: return
        val i = liveSetIndex
        val b = metronome.bpm
        fun with(t: Tempo?) = (t ?: Tempo()).copy(bpm = b).takeIf { it.bpm != null || it != Tempo() }
        val own = r.liveSets.getOrNull(i)?.tempo
        val saved = if (own != null) r.copy(liveSets = r.liveSets.toMutableList().also { it[i] = it[i].copy(tempo = with(own) ?: Tempo()) })
            else r.copy(tempo = with(r.tempo))
        lib.saveSong(id, name, engines.frozen(saved))
        metronome.tappedSaved()
        reload()
    }

    // ------------------------------------------------- store in this live set

    /**
     * What sounds now becomes the current live set's own (I6.9): its levels,
     * and every sound parameter moved on the stage or by a mapped control
     * since the live set was selected -- setBfree's drawbars, a filter on a
     * knob. A value moved back to the song's stops being the live set's own.
     * Saved as a new version of the song.
     */
    fun storeInLiveSet() {
        val id = songEntry ?: return
        var r = rack ?: return
        val i = liveSetIndex.takeIf { it in r.liveSets.indices } ?: return
        val ls = r.liveSets[i]
        val parts = ls.parts.toMutableMap()
        for (inst in r.instances) {
            val v = engines.levels[inst.id] ?: continue
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
        lib.saveSong(id, name, engines.frozen(r))
        reload()
        note("stored into live set ${i + 1}: the levels" + if (stored.isEmpty()) "" else " and $stored")
        // Said on the stage for a while, with a way back.
        storedChange = lib.lastChange()
        this.stored = ls.name
        main.removeCallbacks(forgetStored)
        main.postDelayed(forgetStored, 10000)
    }

    /** The live set "Store in this live set" last stored into, while the stage still says so and offers to take it back. */
    var stored by mutableStateOf<String?>(null); private set
    private var storedChange: Library.ChangeSet? = null
    private val forgetStored = Runnable { stored = null }

    /** Takes the last store back, if it is still the library's last change: the song as it was before it. */
    fun undoStore() {
        if (stored == null) return
        stored = null
        main.removeCallbacks(forgetStored)
        if (storedChange == null || lib.lastChange() != storedChange) return
        lib.undo()
        reload()
        note("the store into the live set taken back")
    }

    /** The stage shows every sound of the song, not only those the live set plays (P6: one tap away). */
    var stageAllSounds by mutableStateOf(false)

    /** Sound parameters moved on the stage since the live set was selected: "<instance>.<parameter>" -> the value before. */
    private val touched = LinkedHashMap<String, Double>()

    private fun touch(key: String, before: Double?) {
        if (key !in touched && before != null) touched[key] = before
    }

    /** A stage fader moved. */
    fun setLevel(id: String, v: Float) {
        engines.setLevel(id, v)
        rack?.instance(id)?.let { switcher?.noteValue(it.levelKey, v.toDouble()) }
    }

    // --------------------------------------------------------- mapped controls

    /** The keys of the controller maps in force, in the order the engine has them (bit k of the moved mask). */
    private var controlKeys: List<String> = emptyList()
    /** Their values when the maps were set: what a first move started from. */
    private val controlStart = HashMap<String, Double>()

    /** The controls of the sounds playing in this live set onto the engine ([EngineRack.mapControls]). */
    private fun applyControls() {
        val maps = engines.mapControls(rack, rack?.liveSets?.getOrNull(liveSetIndex))
        controlKeys = maps.map { it.first }
        controlStart.clear()
        controlStart.putAll(maps)
    }

    /** What the mapped controls moved since the last poll: the switcher and the stage learn the new values. */
    private fun pollControls() {
        val moved = engines.takeMovedControls()
        if (moved == 0L) return
        var setBfreeMoved = false
        for ((k, key) in controlKeys.withIndex()) {
            if (moved and (1L shl k) == 0L) continue
            val v = engines.engineValue(key) ?: continue
            touch(key, switcher?.currentValue(key) ?: controlStart[key])
            switcher?.noteValue(key, v)
            if (key.startsWith("$setBfreeId.")) setBfreeMoved = true
            Log.i(TAG, "control $key -> ${"%.2f".format(v)}")
        }
        if (setBfreeMoved) refreshSetBfree()
    }

    // ---------------------------------------------------------- the stage's setBfree

    /** setBfree's stage controls, by the engine's names for them ([SETBFREE_STAGE_PARAMS]). */
    val setBfree = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    /** Which of the song's setBfree instances the stage shows: the first playing in the live set, else the first. */
    var setBfreeId by mutableStateOf<String?>(null); private set

    private fun setBfreeNode(): Int? = setBfreeId?.let { engines.nodeOfInstance(it) }

    /** Where setBfree has each parameter, by its name: the same in every setBfree, so read once. */
    private var setBfreeParams: Map<String, Int>? = null
    private fun setBfreeParam(node: Int, name: String): Int? = (setBfreeParams
        ?: (0 until NativeBridge.nativeNodeParamCount(node)).associateBy { NativeBridge.nativeNodeParamName(node, it) }
            .also { setBfreeParams = it })[name]

    fun refreshSetBfree() {
        val node = setBfreeNode() ?: return
        for (name in SETBFREE_STAGE_PARAMS) {
            val p = setBfreeParam(node, name) ?: continue
            setBfree[name] = runCatching { NativeBridge.nativeGetNodeParam(node, p) }.getOrDefault(0f)
        }
    }

    /**
     * The Leslie or the percussion changed by the keyboard -- a pedal or a
     * slider through setBfree's own controller map: the stage shows it,
     * "Store in this live set" keeps it, and the song change puts it back, as
     * if moved on the stage. Polled.
     */
    private fun followLeslie() {
        val id = setBfreeId ?: return
        if (settling) return
        val node = setBfreeNode() ?: return
        for (name in listOf(SETBFREE_LESLIE, SETBFREE_PERC)) {
            val p = setBfreeParam(node, name) ?: continue
            val shown = setBfree[name] ?: continue
            val now = runCatching { NativeBridge.nativeGetNodeParam(node, p) }.getOrNull() ?: continue
            if (now == shown) continue
            val key = "$id.$name"
            touch(key, switcher?.currentValue(key) ?: shown.toDouble())
            engines.noteChanged(id, name, shown)
            switcher?.noteValue(key, now.toDouble())
            setBfree[name] = now
        }
    }

    /** A stage setBfree control moved: heard at once, and kept in mind for "Store in live set". */
    fun setSetBfree(name: String, v: Float) {
        val node = setBfreeNode() ?: return
        val p = setBfreeParam(node, name) ?: return
        val key = "$setBfreeId.$name"
        touch(key, switcher?.currentValue(key) ?: NativeBridge.nativeGetNodeParam(node, p).toDouble())
        setBfreeId?.let { engines.setParam(it, name, v) }
        switcher?.noteValue(key, v.toDouble())
        setBfree[name] = v
    }

    // ------------------------------------------------------------ stage or not

    /** A page-turner key: pages when the song has a lead sheet and the setup says so, live sets otherwise. */
    fun forwardKey() = if (sheet.turnsPages) sheet.forward() else nextLiveSet()
    fun backKey() = if (sheet.turnsPages) sheet.back() else previousLiveSet()

    /** Set by the UI: a Back gesture goes to the stage instead of leaving the app. */
    var backToStage: (() -> Unit)? = null

    /**
     * Whether the stage view is what is showing: page-turner keys act only
     * there, and it is where the rig sounds ([sounding]). Set by the UI when
     * the screen changes -- not by the app going to the background, which
     * leaves the instrument playing as it was.
     */
    @Volatile var onStage = true
        set(v) {
            if (field == v) return
            field = v
            if (!sounding()) park()
            // Back on the stage: its song on the keys again (Hear, if it is on, is stopped by the UI next).
            else if (parked && heard == null && songs.getOrNull(songIndex) != null) selectSong(songIndex, liveSetIndex.coerceAtLeast(0))
        }

    /**
     * The rig sounds on the stage, and in Songs while a live set of the song
     * being edited is heard. Everywhere else the app is silent and the
     * devices it gives sounds to are on their own (Michiel, 2026-11-13): a
     * CP-88 is a piano again in Library and Setup, and after the app is
     * closed.
     */
    private fun sounding() = onStage || heard != null

    /** Silent, the devices let go of: see [sounding]. The song stays on the engine, ready. */
    private var parked = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val releaser = Runnable { if (parked) devices.release() }

    private fun park() {
        engines.silence()
        if (parked) return
        parked = true
        // A moment later, and not at all if a song is heard meanwhile: saving
        // a song that is being heard passes through here on its way back.
        main.removeCallbacks(releaser)
        main.postDelayed(releaser, 250)
        note("off the stage: silent, the devices back on their own sounds")
    }

    /** After anything that puts a song or a live set on the engine: silent again if the rig is not to sound. */
    private fun parkIfSilent() {
        if (sounding()) {
            parked = false
            main.removeCallbacks(releaser)
        } else {
            park()
        }
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
        val id = songEntry ?: return
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

    // ------------------------------------------------------- what comes in

    /** Where controller events from the MIDI path land; public for the STAGE test hook. */
    fun onController(cc: Int, value: Int) {
        when (switches.press(cc, value)) {
            "next" -> forwardKey()
            "previous" -> backKey()
            "tap" -> metronome.tap()
            "click" -> metronome.toggleClick()
            "countIn" -> metronome.countIn()
            else -> return
        }
        lastSelection = "CC $cc"
    }

    private var midiOutWasReady = false

    /**
     * Brings the CP-88 back in line whenever MIDI out to it BECOMES available
     * (I13.3): after start-up, and after a re-plug or a power cycle -- its
     * page rewritten, the current live set selected again.
     *
     * MIDI out that is ready before a song is open stays "not yet seen" until
     * one is ([play] then does the bringing in line). It used to be taken as
     * seen, so a start whose route was quicker than the stage (the usual one,
     * now that plugging in no longer starts the app) wrote only what its own
     * record said had changed, and selected a slot it had never looked at: on
     * 2026-11-12 the record said "Silent", the CP-88's pages held Init Sound,
     * and the piano played along.
     */
    private fun midiOutReady() = runCatching { NativeBridge.nativeUsbMidiOutReady() }.getOrDefault(false)

    private fun watchMidiOut() {
        val ready = midiOutReady()
        if (ready && !midiOutWasReady) {
            if (songIndex < 0) return
            // It may be another unit than before (the stage's spare, a rental),
            // or the same one having lost what was written: what this tablet
            // wrote last says nothing.
            devices.midiOutReady(liveSetObjects, liveSetIndex, select = sounding())
        }
        midiOutWasReady = ready
    }

    /**
     * Called by the UI every 50 ms: each part takes what came in since --
     * MIDI out coming up, presets landing, mapped controls moved, MIDI learn,
     * the count-in's end -- and then the program changes and switching
     * controllers from the keyboard ([takeSelections]).
     */
    fun poll() {
        watchMidiOut()
        settle()
        pollControls()
        followLeslie()
        midiLearn.poll()
        metronome.poll()
        takeSelections()
    }

    /**
     * Follows the CP-88's own live set buttons (V1, LOG.md 2026-10-11): a
     * selection arrives as Bank Select MSB 63, LSB page - 1, Program Change
     * set - 1. Set n selects live set n of the current song, whatever the
     * page. A switching controller comes the same way.
     */
    private fun takeSelections() {
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
                // Off the stage the device is its own again: its buttons choose its sounds, not the app's live sets.
                followed in liveSetNames.indices -> if (sounding()) selectLiveSet(followed)
            }
        }
    }

    companion object {
        private const val TAG = "rigalong-stage"
        /** The editor's song's tapped tempos, apart from the stage's ([Metronome.follow]'s keys). */
        private const val EDITOR_TEMPOS = "edit/"
        /** setBfree's parameters on the stage, by the engine's names: the upper manual's nine drawbars, percussion, the Leslie. */
        val SETBFREE_DRAWBARS = listOf("Upper 16", "Upper 5 1/3", "Upper 8", "Upper 4", "Upper 2 2/3",
            "Upper 2", "Upper 1 3/5", "Upper 1 1/3", "Upper 1")
        const val SETBFREE_LESLIE = "Leslie"
        const val SETBFREE_PERC = "Percussion"
        val SETBFREE_STAGE_PARAMS = SETBFREE_DRAWBARS + listOf(SETBFREE_PERC, SETBFREE_LESLIE)
    }
}
