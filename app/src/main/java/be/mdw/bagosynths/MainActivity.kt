package be.mdw.bagosynths

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * M2: prove the pipe end to end. Opens an exclusive/MMAP Oboe stream to the
 * CP-88, takes MIDI in over the same cable, and plays a real synth voice --
 * with the numbers that matter visible while you play.
 *
 * The UI is deliberately plain Views. Compose would be nicer to write and is a
 * worse fit here: this screen exists to report on the audio thread, and every
 * frame the UI renders is a frame competing with it.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var midiView: TextView
    private lateinit var organPanel: OrganPanelBuilder
    private lateinit var surgePanel: SurgePanelBuilder
    private lateinit var dexedPanel: DexedPanelBuilder
    private lateinit var plaitsPanel: PlaitsPanelBuilder
    private lateinit var fluidPanel: FluidPanelBuilder

    /** Piano on its own is where the app opens, matching the node's own default. */
    private var part = Part.Piano

    /**
     * Which panel is on screen. NOT the same thing as which part is audible, and
     * keeping them separate is the point: a long-press adds a part to the mix
     * without hiding the panel you were working on, so a layer can be built
     * without losing your place.
     */
    private var visiblePart = Part.Piano
    private val partTabs = LinkedHashMap<Part, Button>()
    private val partPanels = LinkedHashMap<Part, View>()
    private var patchIndex = -1

    /** All 0 when the engine is healthy. See watchAudioDevice(). */
    private var deviceLostSince = 0L
    private var lastRestartAttempt = 0L
    private var lastMidiAttempt = 0L

    /**
     * The piano's device id as of the last watchdog pass.
     *
     * Cached so the status line can say whether the stream is on it without
     * asking AudioManager a second time every frame — and because a replug
     * changes the id, so a value read once at startup would be a lie.
     */
    private var lastKnownPianoId = NativeBridge.DEVICE_UNSPECIFIED

    private var midiDevice: MidiDevice? = null
    // 3 bursts -- AAudio's floor. Once the render thread exists the callback is a
    // memcpy that cannot overrun, so this buffer no longer has to cover synthesis
    // time at all, only the OS waking the callback late. The earlier 6 was sized
    // for a deadline that no longer applies.
    // The callback still runs every 2 ms with the same 89-frame
    // block, so this does NOT relax the per-callback deadline -- it buys slack
    // before a late callback becomes an audible dropout. Costs a few ms of
    // latency and removes clicks under a dense cluster, which is the right trade.
    private var bufferBursts = 3

    /**
     * Which audio device we actually opened. Worth displaying prominently: the
     * failure mode that wastes the most time is silently landing on the built-in
     * speaker and wondering why the latency is terrible.
     */
    private var audioDeviceLabel = "—"
    private var xrunBaseline = 0
    private val handler = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            // BEFORE updateStats, because it is what resolves the piano's current
            // device id and the status line reports against that. The other way
            // round, every frame would describe the previous one.
            watchAudioDevice()
            updateStats()
            // Surge comes up asynchronously and its patch loads are asynchronous
            // too, so the panel is re-read rather than being told. Cheap: it is a
            // handful of reads of values the node already holds, and it means the
            // screen can never disagree with the engine.
            if (::surgePanel.isInitialized) surgePanel.refresh()
            if (::dexedPanel.isInitialized) dexedPanel.refresh()
            if (::plaitsPanel.isInitialized) plaitsPanel.refresh()
            if (::fluidPanel.isInitialized) fluidPanel.refresh()
            handler.postDelayed(this, 250)
        }
    }

    /**
     * What keeps watch while the activity is NOT in front.
     *
     * Since the instrument now keeps playing when backgrounded (see
     * [EngineService]), it also has to keep RECOVERING when backgrounded, and
     * that did not follow for free. The disconnect watchdog lives in the poller
     * above, and the poller used to stop in onPause -- which was harmless only
     * because the audio stopped there too.
     *
     * It matters because of the division of labour in a recovery. The native
     * retry re-opens the device id it remembers, which catches a cable that was
     * merely nudged; only this Kotlin side can ask AudioManager for the piano
     * BY NAME, which is the only thing that works after a real re-enumeration,
     * because that hands out a new id and makes every remembered one useless.
     * Without this, a replug behind a dialog left a silent instrument that
     * believed it was playing.
     *
     * Once a second rather than four times, and no UI work at all: nothing is
     * on screen to update, and this runs while the user is doing something else
     * with the tablet.
     *
     * It also keeps [NativeBridge.stats] being called, which is what emits the
     * health heartbeat -- so the dropout hunt in HANDOFF section 13 is no longer
     * blind whenever the app is not in front.
     */
    private val backgroundPoller = object : Runnable {
        override fun run() {
            watchAudioDevice()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        val filter = android.content.IntentFilter("be.mdw.bagosynths.RECYCLE")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recycleReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(recycleReceiver, filter)
        }
    }

    /**
     * Whether the engine has been brought up in this process. Not the same
     * question as whether the activity is resumed: since the instrument now
     * keeps playing while the activity is away (see [EngineService]), a resume
     * is usually a return to something already running, and re-running the whole
     * bring-up would reload sfizz and re-scan 641 Surge patches underneath audio
     * that never stopped.
     */
    private var engineBroughtUp = false

    /** EXCLUSIVE by default; long-press Buffer to switch. See that handler. */
    private var wantExclusive = true

    override fun onResume() {
        super.onResume()

        // Before anything else, and while this activity is genuinely in front:
        // starting a foreground service from the background is refused on
        // Android 12 and later, so this is the only moment it is legal.
        EngineService.start(this)
        handler.removeCallbacks(backgroundPoller)

        if (!engineBroughtUp) {
            // Output FIRST and always. Established by experiment 2026-09-19: the
            // CP-88's capture and playback are mutually exclusive on Android's USB
            // audio HAL. Whichever direction opens first wins, and the other fails
            // with ErrorDisconnected. Opening capture first silently costs you the
            // instrument, which is the worst possible failure.
            startEngine()
            openMidi()
            // NOT enableInputIfPermitted() here any more -- see that function.
            loadFirstInstrument()
            startSurge()
            startDexed()
            startFluid()
            // Restore AFTER loadFirstInstrument(), which picks a default: a patch
            // that names an instrument should win over the default, not race it.
            restorePatch()
            engineBroughtUp = true
        } else if (!NativeBridge.nativeMidiOpen()) {
            // It kept playing while we were away. The one thing worth checking is
            // MIDI, because the port can be lost to a cable event without the
            // audio stream noticing.
            openMidi()
        }

        // The nodes hold the live values either way, so the panels can show what
        // they actually are.
        organPanel.refresh()
        surgePanel.refresh()
        syncPartTabs()
        handler.post(poller)
    }

    /**
     * Opens the probe's input stream. Call it from the thing that needs it.
     *
     * It used to be opened at startup and held for the whole session, which is a
     * second EXCLUSIVE MMAP stream -- on the built-in mic, at 48 kHz, while the
     * output runs at 44100 -- kept alive continuously for a measurement taken
     * perhaps three times in a session. That is a cost paid always for a benefit
     * taken rarely, and asking a HAL to hold two exclusive MMAP streams at
     * different rates is an unusual thing to do besides.
     *
     * It became worth fixing on 2026-09-20, while hunting a USB audio path that
     * dies after about fifteen minutes: the fewer odd things the rig asks of the
     * audio stack, the shorter the list of suspects. Whether it had anything to
     * do with that fault is a separate question, and this change is defensible
     * either way.
     *
     * Purely optional: the instrument is fully functional without it.
     */
    private fun enableInputIfPermitted() {
        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        // POST_NOTIFICATIONS is asked for alongside, on API 33+: the foreground
        // service runs either way, but its notification carries the only Stop
        // button the instrument has.
        val wanted = mutableListOf<String>()
        if (!granted) wanted += android.Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            wanted += android.Manifest.permission.POST_NOTIFICATIONS
        }

        if (granted) {
            // Explicitly the BUILT-IN MIC. "Unspecified" is not good enough:
            // with the CP-88 attached, Android makes the piano the default input,
            // so an unspecified input stream resolves to the CP-88's capture and
            // fails -- the two directions are mutually exclusive on that card.
            NativeBridge.nativeEnableInput(findBuiltinMicDeviceId())
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    /** The tablet's own microphone — the only input usable while the CP-88 plays. */
    private fun findBuiltinMicDeviceId(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mic = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return mic?.id ?: NativeBridge.DEVICE_UNSPECIFIED
    }

    /**
     * The CP-88's capture side. Input and output device ids are different objects
     * even for the same physical interface, so this cannot reuse the output id.
     */
    @Suppress("unused")  // kept: useful if a future Android release allows full duplex
    private fun findCp88InputDeviceId(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        val named = inputs.firstOrNull { d ->
            val name = d.productName?.toString()?.uppercase().orEmpty()
            name.contains("CP88") || name.contains("CP73") || name.contains("YAMAHA")
        }
        return (named ?: inputs.firstOrNull())?.id ?: NativeBridge.DEVICE_UNSPECIFIED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Match by NAME rather than by position: the request now carries either
        // or both of RECORD_AUDIO and POST_NOTIFICATIONS, so grantResults[0] is
        // no longer reliably the microphone. It also asks for the BUILT-IN mic
        // explicitly, which the old unspecified call got wrong -- with the CP-88
        // attached, an unspecified input resolves to the piano and fails.
        permissions.forEachIndexed { i, permission ->
            val ok = grantResults.getOrNull(i) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok && permission == android.Manifest.permission.RECORD_AUDIO) {
                NativeBridge.nativeEnableInput(findBuiltinMicDeviceId())
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Save on the way out, every time. A patch you have to remember to save is
        // a patch you lose, and capturing the graph is a few dozen plain reads.
        savePatch(named = false)
        handler.removeCallbacks(poller)
        // Hand over to the cut-down watchdog. The instrument keeps playing, so
        // it has to keep recovering too.
        handler.post(backgroundPoller)

        // THE AUDIO AND MIDI DELIBERATELY STAY UP.
        //
        // This used to call nativeStop() and nativeCloseMidi(), which is correct
        // behaviour for an app and wrong behaviour for an instrument: it meant
        // anything that backgrounded the activity killed the sound. Measured
        // 2026-09-20 -- a press of the power button stopped audio outright, and
        // it stayed stopped until the app was brought back to the front.
        //
        // FLAG_KEEP_SCREEN_ON already stops the screen timing out on its own. It
        // does not stop a power button caught with a sleeve, a system dialog, an
        // alarm, or an incoming call on a tablet that has a SIM. On a stage every
        // one of those was a dead instrument in the middle of a song.
        //
        // EngineService is what makes keeping it running legitimate. onDestroy
        // below is what shuts it down, along with the notification Stop button.
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(recycleReceiver) }
        super.onDestroy()
        // Unconditionally, and BEFORE the isFinishing guard: both runnables hold
        // a reference to this activity, and one that outlived it would keep the
        // whole view hierarchy alive and go on poking a dead UI.
        handler.removeCallbacks(poller)
        handler.removeCallbacks(backgroundPoller)

        // isFinishing distinguishes the user closing the instrument from a
        // recreation. The manifest already absorbs rotation and uiMode through
        // configChanges, so this should not fire otherwise -- but tearing the
        // stream down on a configuration change would be a very confusing bug to
        // chase, and the guard costs one line.
        if (!isFinishing) return
        NativeBridge.nativeCloseMidi()
        midiDevice?.close()
        midiDevice = null
        NativeBridge.nativeStop()
        EngineService.stop(this)
        engineBroughtUp = false
    }

    // ---------------------------------------------------------------- patches

    private var patchStatus = "—"

    private fun savePatch(named: Boolean) {
        val instrument = currentInstrument?.id.orEmpty()
        patchStatus = if (named) {
            "saved ${PatchStore.saveNamed(this, instrument)}"
        } else {
            PatchStore.saveLast(this, instrument)
            patchStatus
        }
    }

    /**
     * Restores the auto-saved patch, if there is one.
     *
     * The instrument is applied through the normal asynchronous load rather than
     * being forced: parsing an SFZ takes seconds and must not happen on the way
     * through onResume.
     */
    private fun restorePatch() {
        val patch = PatchStore.readLast(this) ?: return
        applyPatch(patch, "restored")
    }

    private fun applyPatch(patch: org.json.JSONObject, verb: String) {
        val wanted = PatchStore.apply(patch)
        if (!wanted.isNullOrEmpty() && wanted != currentInstrument?.id) {
            library.firstOrNull { it.id == wanted }?.let { selectInstrument(it) }
        }
        // The Surge patch is addressed by NAME, not by index, for the same reason
        // the instrument is addressed by id: an index means something different
        // the moment the library changes underneath it. The load is asynchronous
        // and harmless if Surge is still starting -- it is simply refused, and
        // the poller will show what actually happened.
        patch.optString("surgePatch").takeIf { it.isNotEmpty() }?.let {
            NativeBridge.nativeSurgeLoadPatchNamed(it)
        }
        patch.optString("dexedVoice").takeIf { it.isNotEmpty() }?.let {
            NativeBridge.nativeDexedSelectVoiceNamed(it)
        }
        // The preset is asked for BEFORE the font, deliberately. Loading a
        // font takes hundreds of milliseconds and lands on its first preset
        // when it finishes; recording the wanted preset first means the load
        // honours it instead of overwriting it.
        patch.optString("fluidPreset").takeIf { it.isNotEmpty() }?.let {
            NativeBridge.nativeFluidSelectPresetNamed(it)
        }
        patch.optString("fluidFont").takeIf { it.isNotEmpty() }?.let {
            NativeBridge.nativeFluidLoadFontNamed(it)
        }
        patchStatus = "$verb ${patch.optString("name", "patch")}"
        organPanel.refresh()
        surgePanel.refresh()
        dexedPanel.refresh()
        plaitsPanel.refresh()
        fluidPanel.refresh()
        syncPartTabs()
    }

    /**
     * Repaints the tabs from the LEVELS, which are the truth.
     *
     * The tabs are a view of what is audible, never a separate setting of their
     * own — so after a patch load, or a fader move, they are re-derived rather
     * than left where they were. Any combination the faders can express is legal
     * and the tabs will show it, which is what makes layering visible.
     */
    private fun syncPartTabs() {
        val organ = NativeBridge.nativeGetOrganParam(Organ.LEVEL) > 0.01f
        val piano = NativeBridge.nativeGetSamplerLevel() > 0.01f
        val synth = NativeBridge.nativeGetSurgeParam(Surge.LEVEL) > 0.01f
        val fm = NativeBridge.nativeGetDexedParam(Dexed.LEVEL) > 0.01f
        val texture = NativeBridge.nativeGetPlaitsParam(Plaits.LEVEL) > 0.01f
        val soundfont = NativeBridge.nativeGetFluidParam(Fluid.LEVEL) > 0.01f
        val on = listOf(piano, organ, synth, fm, texture, soundfont)
        part = when {
            on.count { it } != 1 -> Part.All
            piano -> Part.Piano
            organ -> Part.Organ
            synth -> Part.Synth
            fm -> Part.FM
            texture -> Part.Texture
            else -> Part.SoundFont
        }

        val audible = mapOf(
            Part.Piano to piano, Part.Organ to organ, Part.Synth to synth,
            Part.FM to fm, Part.Texture to texture, Part.SoundFont to soundfont,
            Part.All to (on.count { it } > 1),
        )
        partTabs.forEach { (p, button) ->
            val lit = audible[p] == true
            val shown = p == visiblePart
            // Two independent states on one control, so they must not use the
            // same channel: the BACKGROUND says which panel you are looking at,
            // the TEXT says whether that part is making sound.
            button.setBackgroundColor(
                if (shown) Color.parseColor("#3A4657") else Color.parseColor("#20242C")
            )
            button.setTextColor(
                if (lit) Color.parseColor("#7FE0C0") else Color.parseColor("#8A93A3")
            )
            button.setTypeface(null, if (lit) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * Shows a part's panel, and optionally makes it the only one sounding.
     *
     * `solo = true` is the ordinary tap and does what the old PART cycle did:
     * that part up, the rest to zero. `solo = false` is the long-press — it
     * brings the part IN without silencing anything, which is how a layer gets
     * built, and it is the one thing the cycle could never do.
     */
    private fun showPart(target: Part, solo: Boolean) {
        visiblePart = target
        if (solo) {
            organPanel.setPart(target)
            surgePanel.setPart(target)
            dexedPanel.setPart(target)
            plaitsPanel.setPart(target)
            fluidPanel.setPart(target)
        } else {
            when (target) {
                Part.Piano -> NativeBridge.nativeSetSamplerLevel(1f)
                Part.Organ -> NativeBridge.nativeSetOrganParam(Organ.LEVEL, 1f)
                Part.Synth -> NativeBridge.nativeSetSurgeParam(Surge.LEVEL, Surge.DEFAULT_LEVEL)
                Part.FM -> NativeBridge.nativeSetDexedParam(Dexed.LEVEL, Dexed.DEFAULT_LEVEL)
                Part.Texture -> NativeBridge.nativeSetPlaitsParam(Plaits.LEVEL, Plaits.DEFAULT_LEVEL)
                Part.SoundFont -> NativeBridge.nativeSetFluidParam(Fluid.LEVEL, Fluid.DEFAULT_LEVEL)
                Part.All -> {
                    organPanel.setPart(Part.All)
                    surgePanel.setPart(Part.All)
                    dexedPanel.setPart(Part.All)
                    plaitsPanel.setPart(Part.All)
                    fluidPanel.setPart(Part.All)
                }
            }
        }

        applyPanelVisibility(target)

        organPanel.refresh()
        surgePanel.refresh()
        dexedPanel.refresh()
        plaitsPanel.refresh()
        fluidPanel.refresh()
        syncPartTabs()
    }

    /**
     * Hides every panel, then shows the ones this part wants.
     *
     * Two passes, not one, because PIANO and ORGAN map to the SAME view — a
     * single pass would set it visible for one key and immediately hide it
     * again for the other, and which won would depend on map order.
     *
     * ALL shows everything, scrolled, which is the old behaviour and still the
     * right thing when balancing a layer against itself.
     */
    private fun applyPanelVisibility(target: Part) {
        partPanels.values.forEach { it.visibility = View.GONE }
        partPanels.forEach { (p, view) ->
            if (target == Part.All || p == target) view.visibility = View.VISIBLE
        }
    }

    /** One tab. Tap to show and solo; long-press to add to the mix. */
    private fun makePartTab(target: Part): Button {
        val label = when (target) {
            Part.SoundFont -> "SF2"
            else -> target.name.uppercase()
        }
        return Button(this).apply {
            text = label
            textSize = 17f
            setPadding(28, 22, 28, 22)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 10 }
            setOnClickListener { showPart(target, solo = true) }
            setOnLongClickListener { showPart(target, solo = false); true }
        }
    }

    /**
     * Brings Surge up. Asynchronous on purpose: constructing it scans 641
     * patches and 188 wavetables, 41 ms of file work that has no business on the
     * way through onResume. Until it finishes, the node renders silence and
     * everything else plays normally.
     *
     * The content lives beside the instrument library rather than in the APK.
     * Surge takes a filesystem path, so it cannot read out of an APK at all, and
     * keeping it outside means the full 483 MB library can be swapped in without
     * a rebuild. See tools/install_surge_data.sh.
     */
    /**
     * Brings the DX7 up. The 33 cartridges that ship in the APK are seeded into
     * internal storage once; anything the user drops into the external `dx7/`
     * folder is read as well, so a personal collection sits alongside rather
     * than replacing the built-in one.
     */
    private fun startDexed() {
        val builtin = java.io.File(filesDir, "dx7").apply { mkdirs() }
        if (builtin.listFiles { f -> f.name.endsWith(".syx") }.isNullOrEmpty()) {
            runCatching {
                assets.list("dx7")?.forEach { name ->
                    assets.open("dx7/$name").use { input ->
                        java.io.File(builtin, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }.onFailure { android.util.Log.e("bos-ui", "could not seed dx7 banks: $it") }
        }
        val external = java.io.File(getExternalFilesDir(null), "dx7").apply { mkdirs() }
        NativeBridge.nativeLoadDexedBanks(
            arrayOf(builtin.absolutePath, external.absolutePath)
        )
    }

    /**
     * Brings FluidSynth up and lists the SoundFonts it can see.
     *
     * SoundFonts are CONTENT, like the SFZ instruments: they have their own
     * licences, a General MIDI font is 30 MB or more, and none of that belongs
     * in the APK. So nothing is seeded here -- the folder is created, and
     * `tools/install_soundfont.sh` is how a font gets into it.
     */
    private fun startFluid() {
        val builtin = java.io.File(filesDir, "soundfonts").apply { mkdirs() }
        val external = java.io.File(getExternalFilesDir(null), "soundfonts").apply { mkdirs() }
        NativeBridge.nativeInitFluid(
            arrayOf(builtin.absolutePath, external.absolutePath)
        )
    }

    private fun startSurge() {
        val data = java.io.File(getExternalFilesDir(null), "surge-xt")
        // Writable and app-private: sst-plugininfra's homePath() THROWS if HOME
        // is unset, and Android makes no promise about it.
        val home = java.io.File(filesDir, "surge-home").apply { mkdirs() }
        NativeBridge.nativeInitSurge(data.absolutePath, home.absolutePath)
    }

    /**
     * Brings the rig back when the piano reappears, and gets it OFF the tablet
     * speaker if it ever lands there.
     *
     * The first version of this watched only for `running == false`, and a real
     * unplug never produced that. Here is what a real unplug actually did:
     *
     *   stream error after close: ErrorDisconnected
     *   ...got Legacy, devIds = [2], perf = NO, burst = 882
     *   stream recovered after disconnect, on device 10051 (attempt 1)
     *
     * Asking for a device id that no longer exists does not fail. AAudio falls
     * back to the built-in speaker and returns OK, so the engine "recovered",
     * `running` stayed true, and this function never ran. The native side now
     * refuses a stream that came back on the wrong device — but the check that
     * matters is here, because only this side can ask AudioManager which device
     * the CP-88 is TODAY, and a replug gives it a new id.
     *
     * So there are two failures to notice, not one:
     *   * not running at all, and the piano is available again;
     *   * running, but on something that is not the piano.
     *
     * The second is the dangerous one. It is silent: audio "works", the meters
     * move, and the sound is coming out of a speaker nobody on a stage can hear.
     */
    private fun watchAudioDevice() {
        val stats = NativeBridge.stats()
        val piano = findCp88OutputDeviceId()
        lastKnownPianoId = piano
        val havePiano = piano != NativeBridge.DEVICE_UNSPECIFIED

        val healthy = stats.running && havePiano && stats.deviceId == piano
        if (healthy) {
            deviceLostSince = 0L
            // MIDI dies with the same cable and does NOT come back by itself.
            if (!NativeBridge.nativeMidiOpen()) reopenMidi()
            return
        }

        val now = android.os.SystemClock.uptimeMillis()
        if (deviceLostSince == 0L) {
            deviceLostSince = now
            return
        }
        // Let the native retries have their 1.5 s before reaching for a new id.
        if (now - deviceLostSince < 2000L) return
        if (now - lastRestartAttempt < 2000L) return
        lastRestartAttempt = now

        // Never start on a non-USB device. Playing a set out of the tablet's own
        // speaker is worse than silence: the player cannot hear it over a stage
        // and the audience never hears it at all, so the failure goes unnoticed
        // until someone asks why the keyboard is quiet.
        if (!havePiano) return

        // Stop first, unconditionally. `start()` returns true immediately when it
        // thinks it is already running, so a stream sitting on the wrong device
        // would otherwise never be replaced.
        NativeBridge.nativeStop()
        if (NativeBridge.nativeStart(piano, bufferBursts)) {
            android.util.Log.i("bos-ui", "audio restarted on the CP-88 (device $piano)")
            deviceLostSince = 0L
            reopenMidi()
        }
    }

    /**
     * MIDI shares the cable, so it dies with the audio and must be reopened.
     *
     * Rate-limited because `openMidi()` is asynchronous — it hands a callback to
     * `MidiManager.openDevice` — so calling it from a 4 Hz poller without a
     * guard would stack up eight opens before the first one answered.
     */
    private fun reopenMidi() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMidiAttempt < 2000L) return
        lastMidiAttempt = now

        NativeBridge.nativeCloseMidi()
        midiDevice?.close()
        midiDevice = null
        openMidi()
    }

    // ------------------------------------------------------------------ audio

    // ---------------------------------------------------------------- recycle
    //
    // The USB output halts after 34-42 M frames (see USB-AUDIO-HALT.md). The
    // only thing that brings it back short of a replug is closing the stream and
    // leaving the device ALONE for long enough that it reaches standby, then
    // reopening. A close followed immediately by an open does not do it, because
    // AudioFlinger keeps the HAL stream open through its standby delay and the
    // USB substream is therefore never torn down.
    //
    // `holdMs` is the silence between close and open. Proven at 4000 ms on the
    // AudioTrack path; this is the hook that establishes the minimum on the path
    // the instrument actually uses (Oboe, EXCLUSIVE, MMAP).
    //
    // Triggered for testing with:
    //     adb shell am broadcast -a be.mdw.bagosynths.RECYCLE --el hold 1500
    private var recycling = false

    private fun recycleAudio(holdMs: Long) {
        if (recycling) {
            android.util.Log.i("bos-ui", "recycle: already in flight, ignoring")
            return
        }
        recycling = true
        val device = findCp88OutputDeviceId()
        val bursts = bufferBursts
        android.util.Log.i("bos-ui", "recycle: closing, holding ${holdMs} ms, device $device")
        Thread {
            NativeBridge.nativeStop()
            if (holdMs > 0) {
                try { Thread.sleep(holdMs) } catch (_: InterruptedException) {}
            }
            val ok = NativeBridge.nativeStart(device, bursts)
            android.util.Log.i("bos-ui",
                "recycle: reopened after ${holdMs} ms -> $ok")
            handler.post {
                recycling = false
                NativeBridge.nativeResetPeaks()
            }
        }.start()
    }

    /**
     * Debug trigger for [recycleAudio], so the hold can be bracketed from a
     * script rather than by tapping a button and guessing at the timing. It is
     * registered for the life of the process, not just while resumed, because
     * the engine outlives the activity.
     */
    private val recycleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val hold = intent?.getLongExtra("hold", 4000L) ?: 4000L
            recycleAudio(hold)
        }
    }

    private fun startEngine() {
        val deviceId = findCp88OutputDeviceId()
        val ok = NativeBridge.nativeStart(deviceId, bufferBursts)
        if (!ok) {
            statusView.text = "Failed to open audio stream.\n" +
                "Is the CP-88 connected and powered on?"
        }
    }

    /**
     * Find the CP-88 explicitly rather than letting AAudio auto-select. Auto
     * select can land on the built-in speaker, which reports excellent latency
     * and tells you nothing about the rig you actually play through.
     */
    private fun findCp88OutputDeviceId(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val usb = outputs.filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

        // Prefer a device that names itself as the piano.
        val named = usb.firstOrNull { d ->
            val name = d.productName?.toString()?.uppercase().orEmpty()
            name.contains("CP88") || name.contains("CP73") || name.contains("YAMAHA")
        }

        val chosen = named ?: usb.firstOrNull()
        audioDeviceLabel = when {
            named != null -> "${named.productName}  [CP-88]"
            chosen != null -> "${chosen.productName}  [USB, NOT the piano]"
            else -> "built-in (NO USB DEVICE — piano not connected?)"
        }

        return chosen?.id ?: NativeBridge.DEVICE_UNSPECIFIED
    }

    /**
     * Loads the first .sfz found in the app's external instruments folder:
     *   /sdcard/Android/data/be.mdw.bagosynths/files/instruments/
     * Push libraries there with adb. A proper instrument browser is M5; this
     * exists so the thing makes a sound.
     */
    private var library: List<Instrument> = emptyList()
    private var currentInstrument: Instrument? = null

    private fun prefs() = getSharedPreferences("bagosynths", Context.MODE_PRIVATE)

    /**
     * Rescans the library and loads the remembered instrument by **id**. The id
     * is what a patch stores; the path is resolved here and never persisted.
     */
    private fun loadFirstInstrument() {
        // Two places are searched, in order:
        //   1. the app's private files dir, seeded from assets on first run, so
        //      the app always makes a sound out of the box;
        //   2. the external instruments dir, for libraries pushed by the user.
        //
        // Note the external dir is NOT reliable for adb push: a folder created by
        // `adb shell mkdir` is owned by the shell user and the app cannot read it.
        // Create it from here first, then push into it.
        val privateDir = java.io.File(filesDir, "instruments").apply { mkdirs() }
        val externalDir = java.io.File(getExternalFilesDir(null), "instruments").apply { mkdirs() }
        instrumentDir = externalDir.absolutePath

        copyAssetInstruments(privateDir)

        // External first: a real library beats the seeded fallback of the same id.
        library = InstrumentLibrary.scan(listOf(externalDir, privateDir))

        val wanted = prefs().getString("instrumentId", null)
        val chosen = library.firstOrNull { it.id == wanted } ?: library.firstOrNull()
        if (chosen != null) selectInstrument(chosen)
    }

    private fun selectInstrument(instrument: Instrument) {
        currentInstrument = instrument
        prefs().edit().putString("instrumentId", instrument.id).apply()
        NativeBridge.nativeLoadInstrument(instrument.id, instrument.sfz.absolutePath)
        // Page-cache warming is deliberately OFF. It was needed when a 2.5 ms
        // blocking read landed on the audio callback's 2 ms deadline. With the
        // render thread and its ~10 ms ring reserve, a cold read simply makes one
        // render block slower and is inaudible -- so the minute-long warm-up and
        // 1.2 GB of page cache buy nothing. Kept for the case of a library on
        // genuinely slow storage.
        warmInstrument(instrument)
    }

    @Volatile private var warming = false
    @Volatile private var warmedMb = 0

    /**
     * Reads every sample file of the library so the OS page cache holds them.
     *
     * Measured cause of the remaining dropouts: a single note-on on a COLD sample
     * cost 2.5 ms on the audio thread — a blocking disk read, not computation.
     * Note-on budgeting cannot help with that, because one expensive call is
     * already too late by the time it is noticed. The only real fix is that the
     * data is already in RAM when the key goes down.
     *
     * Ordinary priority, and deliberately NOT pinned to the fast cores: it must
     * never compete with the audio thread. Finishing in 4 s instead of 2 s costs
     * nothing; stealing a big core costs a glitch.
     */
    private fun warmInstrument(instrument: Instrument) {
        // Only the HEAD of each sample is read, not the whole library.
        //
        // What blocks the render thread is the note-START path: sfizz reaching for
        // a sample's preloaded head that is not resident yet. Measured cost of one
        // such read: up to 12 ms, which overruns even a 12 ms ring reserve.
        // Sustained streaming further into a sample is handled by the ring and
        // does not need warming.
        //
        // sfizz preloads 65536 frames per sample, which is 256 kB at 16-bit
        // stereo, so 512 kB per file covers it with margin. For Salamander that is
        // ~330 MB read in about a second, instead of 1.2 GB over a minute.
        if (warming) return
        warming = true
        warmedMb = 0
        Thread {
            val root = instrument.sfz.parentFile?.parentFile ?: instrument.sfz.parentFile
            val buf = ByteArray(1 shl 18)
            val headBytesPerFile = 512L * 1024L
            var total = 0L
            try {
                root?.walkTopDown()
                    ?.filter { it.isFile && it.extension.lowercase() in setOf("wav", "flac", "ogg") }
                    ?.forEach { f ->
                        f.inputStream().use { input ->
                            var fromThisFile = 0L
                            while (fromThisFile < headBytesPerFile) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                fromThisFile += n
                                total += n
                                warmedMb = (total / (1L shl 20)).toInt()
                            }
                        }
                    }
            } catch (_: Exception) {
                // Best effort: a failure here costs performance, never correctness.
            }
            warming = false
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** Cycles through the library. Loading happens on the background thread. */
    private fun nextInstrument() {
        if (library.isEmpty()) {
            loadFirstInstrument()
            return
        }
        val i = library.indexOfFirst { it.id == currentInstrument?.id }
        selectInstrument(library[(i + 1) % library.size])
    }

    /** Seeds the private instruments folder from assets, once. */
    private fun copyAssetInstruments(target: java.io.File) {
        val names = runCatching { assets.list("instruments") }.getOrNull() ?: return
        for (name in names) {
            val dest = java.io.File(target, name)
            if (dest.exists()) continue
            runCatching {
                assets.open("instruments/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private var instrumentDir: String = ""

    // ------------------------------------------------------------------- MIDI

    private fun openMidi() {
        val manager = getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (manager == null) {
            midiView.text = "MIDI: no MidiManager"
            return
        }

        val devices: Array<MidiDeviceInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
                    .toTypedArray()
            } else {
                @Suppress("DEPRECATION")
                manager.devices
            }

        val info = devices.firstOrNull { it.outputPortCount > 0 && it.isLikelyCp88() }
            ?: devices.firstOrNull { it.outputPortCount > 0 }

        if (info == null) {
            midiView.text = "MIDI: no input device found"
            return
        }

        manager.openDevice(info, { device ->
            if (device == null) {
                runOnUiThread { midiView.text = "MIDI: openDevice returned null" }
                return@openDevice
            }
            midiDevice = device
            val opened = NativeBridge.nativeOpenMidi(device, 0)
            runOnUiThread {
                val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "device"
                midiView.text = if (opened) "MIDI: $name (port 0)" else "MIDI: failed to open port"
            }
        }, handler)
    }

    private fun MidiDeviceInfo.isLikelyCp88(): Boolean {
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty()
        val product = properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty()
        val haystack = "$name $product".uppercase()
        return haystack.contains("CP88") || haystack.contains("CP73") ||
            haystack.contains("YAMAHA")
    }

    // --------------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14161A"))
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Bag-O-Synths"
            setTextColor(Color.parseColor("#E6E9EF"))
            textSize = 28f
            setTypeface(Typeface.DEFAULT_BOLD)
        })

        midiView = TextView(this).apply {
            text = "MIDI: —"
            setTextColor(Color.parseColor("#7FD1B9"))
            textSize = 18f
            setPadding(0, 16, 0, 16)
        }
        root.addView(midiView)

        statusView = TextView(this).apply {
            text = "starting…"
            setTextColor(Color.parseColor("#E6E9EF"))
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
        }
        root.addView(statusView)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }

        buttonRow.addView(makeButton("Buffer: $bufferBursts") { button ->
            bufferBursts = if (bufferBursts >= 4) 1 else bufferBursts + 1
            button.text = "Buffer: $bufferBursts"
            NativeBridge.nativeStop()
            NativeBridge.nativeStart(findCp88OutputDeviceId(), bufferBursts)
            NativeBridge.nativeResetPeaks()
        }.apply {
            // LONG-PRESS: EXCLUSIVE <-> SHARED.
            //
            // Deliberately hung off an existing button rather than given one of
            // its own. Every control added to this row moves the ones after it,
            // and the adb tap coordinates in tools/ are read from a screenshot
            // and go stale silently when they move -- which has now cost time
            // twice, once from a button and once from a single status LINE.
            //
            // The mode already shows on the `path` line, so nothing new has to
            // be displayed either.
            setOnLongClickListener {
                wantExclusive = !wantExclusive
                NativeBridge.nativeSetExclusive(wantExclusive)
                NativeBridge.nativeStop()
                NativeBridge.nativeStart(findCp88OutputDeviceId(), bufferBursts)
                NativeBridge.nativeResetPeaks()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (wantExclusive) "EXCLUSIVE" else "SHARED",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                true
            }
        })

        // The part switch lives in the TOP row on purpose: it is the control most
        // reached for while testing, and the faders that actually do the work sit
        // below the drawbars, which swallow a drag. One tap, always on screen.
        buttonRow.addView(makeButton("SAVE PATCH") {
            savePatch(named = true)
        })

        buttonRow.addView(makeButton("PATCH ▶") {
            val patches = PatchStore.list(this)
            if (patches.isEmpty()) {
                patchStatus = "no saved patches"
                return@makeButton
            }
            patchIndex = (patchIndex + 1) % patches.size
            PatchStore.read(patches[patchIndex])?.let { applyPatch(it, "loaded") }
        })

        buttonRow.addView(makeButton("Reset peaks") {
            NativeBridge.nativeResetPeaks()
            // AAudio's xrun counter only ever climbs, so baseline it here:
            // during a test what matters is "since I started watching".
            xrunBaseline = NativeBridge.stats().xRuns
        })

        buttonRow.addView(makeButton("Panic") {
            NativeBridge.nativePanic()
        })

        buttonRow.addView(makeButton("MEASURE") {
            enableInputIfPermitted()
            NativeBridge.nativeStartLatencyProbe()
        })

        buttonRow.addView(makeButton("STRESS 30") {
            NativeBridge.nativeStressChord(30)
            handler.postDelayed({ NativeBridge.nativeLogProfile() }, 1500)
        })

        buttonRow.addView(makeButton("REC 30s") { b ->
            // One capture, analysed offline as often as needed -- rather than
            // asking a human to replay a passage per threshold tweak.
            val dir = java.io.File(getExternalFilesDir(null), "captures").apply { mkdirs() }
            if (NativeBridge.nativeIsRecording()) return@makeButton
            enableInputIfPermitted()
            NativeBridge.nativeStartRecording(30)
            b.text = "RECORDING…"
            handler.postDelayed({
                val stamp = System.currentTimeMillis()
                NativeBridge.nativeWriteRecording(
                    java.io.File(dir, "capture-$stamp.wav").absolutePath,
                    java.io.File(dir, "capture-$stamp.marks.txt").absolutePath)
                b.text = "REC 30s"
            }, 31_000)
        })

        buttonRow.addView(makeButton("KEY→TONE") {
            enableInputIfPermitted()
            NativeBridge.nativeStartNoteProbe()
        })

        buttonRow.addView(makeButton("INSTRUMENT ▶") {
            nextInstrument()
        })

        buttonRow.addView(makeButton("RESCAN") {
            loadFirstInstrument()
        })

        root.addView(buttonRow)

        // The instrument selector, in place of the old PART cycle.
        //
        // A cycle was fine for three parts and absurd for seven: getting from
        // PIANO to SOUNDFONT was five taps with no way back. And with one panel
        // per instrument stacked, reaching the last of them took eight swipes.
        //
        // Tabs fix both. Tap shows that panel and solos that part; LONG-PRESS
        // brings a part into the mix without silencing the others, which is how
        // a layer is built and is the one thing the cycle could never do. The
        // tabs themselves are a view of the levels, never a setting of their own.
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 8)
        }
        for (target in Part.values()) {
            val tab = makePartTab(target)
            partTabs[target] = tab
            tabRow.addView(tab)
        }
        root.addView(tabRow)

        // The organ's front panel, ABOVE the test keys: the drawbars are played
        // during a set and the test keys only ever exist for bringing the audio
        // path up without a piano attached, so the organ gets the screen.
        //
        // PIANO and ORGAN share it, because it carries both faders -- it is
        // really the keyboards panel. Worth splitting one day; not worth
        // splitting while the piano's only other control is a library picker
        // that already lives in the button row.
        organPanel = OrganPanelBuilder(this)
        val organView = organPanel.build()
        root.addView(organView)
        partPanels[Part.Piano] = organView
        partPanels[Part.Organ] = organView

        surgePanel = SurgePanelBuilder(this)
        partPanels[Part.Synth] = surgePanel.build().also { root.addView(it) }

        dexedPanel = DexedPanelBuilder(this)
        partPanels[Part.FM] = dexedPanel.build().also { root.addView(it) }

        plaitsPanel = PlaitsPanelBuilder(this)
        partPanels[Part.Texture] = plaitsPanel.build().also { root.addView(it) }

        fluidPanel = FluidPanelBuilder(this)
        partPanels[Part.SoundFont] = fluidPanel.build().also { root.addView(it) }

        // Open on the piano's panel. Levels are deliberately NOT touched here:
        // the nodes carry their own defaults and a saved patch may be restored a
        // moment from now, and forcing a solo would overwrite it.
        applyPanelVisibility(visiblePart)

        // A touch keyboard for testing the audio path with no piano attached.
        root.addView(makeTestKeys())

        // The panel makes the screen taller than the tablet in landscape, and a
        // control that cannot be reached is worse than one that is not there.
        return android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#14161A"))
            addView(root)
        }
    }

    private fun makeButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 18f
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 24 }
            setOnClickListener { onClick(this) }
        }
    }

    private fun makeTestKeys(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 48, 0, 0)
        }

        // One octave from middle C.
        for (semitone in 0..12) {
            val note = 60 + semitone
            val key = TextView(this).apply {
                text = note.toString()
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(Color.parseColor("#14161A"))
                setBackgroundColor(Color.parseColor("#C9D1E0"))
                layoutParams = LinearLayout.LayoutParams(0, 220, 1f).apply {
                    rightMargin = 8
                }
                setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            NativeBridge.nativeTestNote(note, 100)
                            view.setBackgroundColor(Color.parseColor("#7FD1B9"))
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            NativeBridge.nativeTestNote(note, 0)
                            view.setBackgroundColor(Color.parseColor("#C9D1E0"))
                            true
                        }
                        else -> false
                    }
                }
            }
            row.addView(key)
        }
        return row
    }

    private fun updateStats() {
        val s = NativeBridge.stats()

        val xrunsNow = s.xRuns - xrunBaseline

        // Whether we are on the PIANO, not merely whether audio is running.
        // AAudio falls back to the built-in speaker without saying so, and that
        // failure is otherwise invisible: the meters move and it sounds fine in
        // a quiet room, while a stage hears nothing at all.
        val piano = lastKnownPianoId
        val onPiano = piano != NativeBridge.DEVICE_UNSPECIFIED && s.deviceId == piano

        val warn = when {
            !s.running -> "STREAM DOWN"
            piano == NativeBridge.DEVICE_UNSPECIFIED -> "NO PIANO — waiting"
            !onPiano -> "WRONG DEVICE (${s.deviceId}) — recovering"
            // Above xruns deliberately: this one means NO SOUND AT ALL while
            // everything else on screen still looks alive, which is precisely
            // why it went undiagnosed for a whole session.
            s.audioStalled -> "AUDIO STALLED ${"%.0f".format(s.stalledSeconds)}s — replug"
            s.sharingMode != 0 -> "not exclusive"
            xrunsNow > 0 -> "$xrunsNow xruns"
            else -> "clean"
        }

        statusView.text = buildString {
            appendLine("device     $audioDeviceLabel  [id ${s.deviceId}]")
            // The presentation position: the only reading here that does not
            // come from this process.
            //
            // It says the STREAM IS ALIVE. It does NOT say anything is audible,
            // and the wording matters because it was read as a guarantee once
            // already. On an MMAP stream this position can come from a hardware
            // counter that keeps ticking whether or not the data path reaches
            // the DAC -- which is exactly what happened on 2026-09-20, when the
            // dock starved the CP-88 of isochronous bandwidth and every reading
            // in this app stayed healthy through hours of silence.
            appendLine("presented  " + when {
                !s.timestampOk -> "no timestamp from the HAL"
                s.stalledSeconds > 1.0f -> "FROZEN for ${"%.1f".format(s.stalledSeconds)} s"
                else -> "advancing  (stream alive — not proof of sound)"
            })
            appendLine("midi       ${if (NativeBridge.nativeMidiOpen()) "connected" else "NOT CONNECTED"}")
            appendLine("state      ${if (s.running) "running" else "stopped"}   [$warn]")
            appendLine("path       ${if (s.usesAAudio) "AAudio" else "OpenSLES"}  " +
                "${s.sharingModeText}  ${s.performanceModeText}")
            appendLine("rate       ${s.sampleRate} Hz")
            appendLine("burst      ${s.burstFrames} fr  (" +
                "${"%.2f".format(s.burstFrames * 1000f / maxOf(1, s.sampleRate))} ms)")
            appendLine("buffer     ${s.bufferFrames} fr  (" +
                "${"%.2f".format(s.bufferFrames * 1000f / maxOf(1, s.sampleRate))} ms)")
            appendLine("latency    ${"%.1f".format(s.latencyMs)} ms")
            appendLine("xruns      $xrunsNow   (total ${s.xRuns})")
            appendLine("ring       ${"%.1f".format(s.ringFillMs)} ms reserve, " +
                "${s.ringUnderruns} underruns, target ${"%.1f".format(s.ringTargetMs)} ms, " +
                "render peak ${"%.2f".format(s.renderMaxMs)} ms")
            appendLine("cpu        ${"%.1f".format(s.cpuLoadPercent)} %  " +
                "(peak callback ${"%.2f".format(s.callbackMaxMs)} ms)")
            val current = currentInstrument
            val loaded = NativeBridge.nativeLoadedInstrument()
            val label = when {
                s.loading -> "loading ${current?.name ?: ""}…"
                current == null -> "none — put an instrument folder in $instrumentDir"
                loaded.isEmpty() -> "FAILED to load ${current.id}"
                else -> "${current.name}  [id: ${current.id}, ${current.sizeLabel}]"
            }
            appendLine("instrument $label")
            if (warming) appendLine("warming    ${warmedMb} MB read into page cache…")
            appendLine("library    ${library.size} available: " +
                library.joinToString(", ") { it.id }.ifEmpty { "—" })
            appendLine("voices     ${s.activeVoices} / ${s.voiceLimit}")
            // A bar, not a number: the useful question is "is it moving", which
            // the eye answers from a bar at a glance and from four decimals not
            // at all. The number stays for when a figure is actually wanted.
            val bars = (s.outputPeak.coerceIn(0f, 1f) * 24f).toInt()
            appendLine("out        ${"#".repeat(bars).padEnd(24, '.')} " +
                "${"%.3f".format(s.outputPeak)}" +
                if (s.outputPeak >= 0.999f) "  CLIPPING" else "")
            appendLine("midi msgs  ${s.midiMessages}")
            appendLine("patch      $patchStatus   " +
                "(${PatchStore.list(this@MainActivity).size} saved)")
            appendLine("")
            appendLine("in level   ${"%.4f".format(s.inputPeak)}   " +
                "(in latency ${"%.1f".format(s.inputLatencyMs)} ms)")
            append("probe      ${s.probeText}")
        }
    }
}
