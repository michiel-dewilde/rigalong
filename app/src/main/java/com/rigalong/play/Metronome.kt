package com.rigalong.play

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rigalong.NativeBridge
import com.rigalong.model.ClickPattern
import com.rigalong.model.Tempo

/**
 * The tempo in force and the metronome (S12): what was tapped or typed this
 * session, the click and its count-in, the visual beat, and the tempo to the
 * engines and out as MIDI clock. [SongPlayer] says which tempo is stored where
 * ([follow]); this keeps the rest (REVIEW S3).
 */
class Metronome(private val prefs: SharedPreferences, private val host: EngineHost) {

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
    private var tapper = TapTempo()
    /**
     * Tempos tapped or typed this session, by where they belong (the key
     * [SongPlayer] gives [follow]): the song's, or one live set's own. A key
     * with null is "no tempo". The stage song's hold until another song opens;
     * the editor's song has its own.
     */
    private val sessionBpm = HashMap<String, Double?>()
    /** Where the tempo in force belongs, and what is stored there: the last [follow]. */
    private var key = ""
    private var stored: Tempo? = null

    /** Another song: what was tapped for the last one goes if [forgetTapped], the tapping starts again. */
    fun newSong(forgetTapped: Boolean) {
        if (forgetTapped) sessionBpm.clear()
        if (!clickRunning) beatOrigin = System.nanoTime()
        tapper = TapTempo()
    }

    /** The editor's song is heard afresh: what was tapped for the last one it heard goes. */
    fun forgetEditorTempos(prefix: String) {
        sessionBpm.keys.removeAll { it.startsWith(prefix) }
    }

    /**
     * The tempo in force belongs at [key], where [stored] is stored: works out
     * [tempo] and gives it to the click, the engines and MIDI clock.
     */
    fun follow(key: String, stored: Tempo?) {
        this.key = key
        this.stored = stored
        val b = if (key in sessionBpm) sessionBpm[key] else stored?.bpm
        val t = if (b == null && stored == null) null else (stored ?: Tempo()).copy(bpm = b)
        retempo(t)
        bpmTapped = key in sessionBpm
        applyMidiClock()
    }

    private fun refollow() = follow(key, stored)

    /** One tap; four or more taps less than 2 s apart set the tempo ([TapTempo]). */
    fun tap() {
        val now = System.nanoTime()
        tapper.tap()?.let {
            sessionBpm[key] = it
            refollow()
        }
        // The visual beat falls on the tap, unless the click is counting: then
        // it stays with what is heard.
        if (!clickRunning) beatOrigin = now
    }

    /** A tempo typed on the stage, or null for none: holds for the session like a tapped one. */
    fun typeTempo(newBpm: Double?) {
        sessionBpm[key] = newBpm?.coerceIn(20.0, 400.0)
        if (newBpm == null && clickRunning) stopClick()
        refollow()
    }

    /** Back to the stored tempo, letting go of what was tapped or typed. */
    fun revertTempo() {
        sessionBpm.remove(key)
        refollow()
    }

    /** What was tapped or typed is now stored (SongPlayer.saveTempo): let go of it. */
    fun tappedSaved() {
        sessionBpm.remove(key)
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
    /**
     * Where the click goes besides the main mix (S12.4): a key of
     * [EngineHost.clickOutputs], or null for the main mix (the default).
     */
    var clickOutput by mutableStateOf(prefs.getString("click.output", null))
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
            .putFloat("click.level", clickLevel).putBoolean("click.midiClock", midiClock)
            .putString("click.output", clickOutput).apply()
        applyMidiClock()
        host.setClickOutput(clickOutput)
    }

    /** The count-in's beats: its lead bar, if any, and the song's bars (or the setup's), of the metre in force. */
    private fun countInBeats(): Int {
        val t = tempo
        return leadBeats + t.barBeats() * (t?.countInBars ?: countInBars).coerceAtLeast(1)
    }

    /** Beats of the lead bar of the run going on ("1 . 2 ." before a 4/4 count-in: S12.9), 0 for none. */
    private var leadBeats = 0

    private fun Tempo?.barBeats() = (this?.beatsPerBar ?: 4).coerceAtLeast(1)

    /** Play: the metronome from beat one, no count-in. Needs a tempo. */
    fun playClick() = startClick(countIn = false)

    /** Count-in: the song's bars counted in, then on or stopped as the setup says. Needs a tempo. */
    fun countIn() = startClick(countIn = true)

    /** Play or stop, for a footswitch. */
    fun toggleClick() = if (clickRunning) stopClick() else playClick()

    private fun startClick(countIn: Boolean) {
        val b = bpm ?: return
        val t = tempo ?: Tempo()
        // The bar's pattern (grouped metres: S12.10), and the count-in's lead bar (S12.9).
        val bar = ClickPattern.bar(t)
        val lead = if (countIn) ClickPattern.lead(t) else IntArray(0)
        leadBeats = lead.size
        val n = if (countIn) countInBeats() else 0
        fun beats(p: Presence) = when (p) { Presence.ON -> -1; Presence.COUNT_IN -> n; Presence.OFF -> 0 }
        val limit = if (countIn && !keepGoing) n else 0
        val delay = NativeBridge.nativeClickStartPattern(b, ByteArray(bar.size) { bar[it].toByte() },
            ByteArray(lead.size) { lead[it].toByte() }, limit, beats(clickSound), beats(barCount), clickLevel, midiClock)
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
        if (!shown) return 0
        val beat = ((now - beatOrigin) / (60e9 / b)).toLong()
        // The lead bar is counted as it is heard: "1 . 2 .".
        if (beat < leadBeats) return ClickPattern.leadCount(tempo ?: Tempo(), beat.toInt())
        return (beat - leadBeats).mod(tempo.barBeats()) + 1
    }

    /** The count-in's end, and a count-in that stops when it ends: the beat stops with the last click heard. Polled. */
    fun poll() {
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

    private companion object {
        const val TAG = "rigalong-stage"
    }
}
