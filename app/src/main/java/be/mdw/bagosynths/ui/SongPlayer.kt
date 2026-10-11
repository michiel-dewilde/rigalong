package be.mdw.bagosynths.ui

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

/** What the new UI needs from the activity that owns the engine and the routing. */
interface EngineHost {
    /** Installed SFZ instruments: id to display name. */
    fun instruments(): List<Pair<String, String>>
    fun selectInstrument(id: String)
    fun currentInstrument(): String?
    fun showPrototype()
    /** One line on the state of the audio and MIDI path. */
    fun healthLine(): String
    /** Every status figure, for the setup view (S19.3). */
    fun statusLines(): List<String>
    fun routing(): Routing
    fun cycleAudioDevice()
    fun cycleMidiDevice()
    /** An IoMode name: ALSA, USB_INTERFACE, USB_DEVICE, NONE. */
    fun setAudioMode(mode: String)
    fun setMidiMode(mode: String)
    fun setUsbBufferMs(ms: Int)
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
        if (rack?.slots?.containsKey(RackEngine.HAMMOND) == true) refreshOrgan()
        sheetPage = 0
        sheetPages = 0
        bpm = s.tempo?.bpm
        bpmTapped = false
        beatOrigin = System.nanoTime()
        taps.clear()
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
    }

    private var pendingLiveSet = -1

    // ------------------------------------------------------------ the CP-88

    /**
     * A song's owned sounds go into the reserved page when the song is
     * selected (S5.3, S4.11), one set each in live set order, so a live set
     * switch is only a Program Change. 7 ms a sound (V3), so done here.
     */
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
        if (written + failed > 0) Log.i(TAG, cp88Status)
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
            bpm = Math.round(60e9 / span * 10) / 10.0
            bpmTapped = true
        }
        beatOrigin = now
    }

    /**
     * The levels as they sound now become the current live set's own levels
     * (I6.9), saved as a new version of the song.
     */
    fun storeLevels() {
        val id = songEntries.getOrNull(songIndex) ?: return
        val r = rack ?: return
        val i = liveSetIndex.takeIf { it in r.liveSets.indices } ?: return
        val ls = r.liveSets[i]
        val parts = ls.parts.toMutableMap()
        for (e in r.slots.keys) {
            val v = levels[e] ?: continue
            parts[e] = (parts[e] ?: be.mdw.bagosynths.model.RackPart(plays = false)).copy(level = v.toDouble())
        }
        val name = songNames.getOrNull(songIndex) ?: return
        lib.saveSong(id, name, r.copy(liveSets = r.liveSets.toMutableList().also { it[i] = ls.copy(parts = parts) }))
        reload()
        Log.i(TAG, "stored the levels into live set ${i + 1}")
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
                    slots = mapOf(RackEngine.SFIZZ to be.mdw.bagosynths.model.RackSlot(level = 0.0)),
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

    /** The organ's stage controls (HammondNode.h `Param`): drawbars 0-8, Leslie 37, percussion 27. */
    val organ = androidx.compose.runtime.mutableStateMapOf<Int, Float>()

    fun refreshOrgan() {
        for (i in ORGAN_STAGE_PARAMS) organ[i] = runCatching { NativeBridge.nativeGetOrganParam(i) }.getOrDefault(0f)
    }

    fun setOrgan(index: Int, v: Float) {
        NativeBridge.nativeSetOrganParam(index, v)
        organ[index] = v
    }

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
            is Change.Cp88 -> when (c.action.mode) {
                "select" -> selectCp88(c.action.page ?: 1, c.action.set ?: 1)
                "own" -> ownedSlots[pendingLiveSet]?.let { selectCp88(reservedPage, it) }
            }
            is Change.Enable, is Change.Send -> Unit   // not in the rack slice yet
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
        const val ORGAN_LESLIE = 37
        const val ORGAN_PERC = 27
        val ORGAN_STAGE_PARAMS = (0..8).toList() + listOf(ORGAN_PERC, ORGAN_LESLIE)

        /** engine/include/bagosynths/MidiZone.h, `Param`, in order. */
        val ZONE_PARAMS = listOf(
            "channelIn", "channelOut", "keyLow", "keyHigh", "velLow", "velHigh", "transpose",
            "passNotes", "passCC", "passBend", "passPressure", "passProgram",
            "ccFromA", "ccToA", "ccFromB", "ccToB")
    }
}
