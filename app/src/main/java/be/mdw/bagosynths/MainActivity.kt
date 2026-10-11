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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
class MainActivity : androidx.activity.ComponentActivity() {

    private companion object {
        /** The USB permission answer. Explicit to this package; see requestUsbPermission. */
        const val ACTION_USB_PERMISSION = "be.mdw.bagosynths.USB_PERMISSION"
    }

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

    /**
     * How long the CP-88 must be missing before the instrument falls back to
     * the tablet's own speaker. Long enough that a jogged cable re-enumerating
     * is recovered onto the piano instead, short enough that a demo with no
     * piano in the room is playable without anyone waiting.
     */
    private val SPEAKER_FALLBACK_AFTER_MS = 6000L
    private var lastRestartAttempt = 0L

    // Consecutive failed attempts to bring a DIRECT USB sink up, and whether we
    // have given up and fallen back to ALSA so the instrument still sounds.
    // Both are cleared by any routing the player asks for. See watchAudioDevice.
    private var directRetries = 0
    private var alsaFallbackOn = false
    private val kDirectRetries = 3
    private var lastMidiAttempt = 0L

    /**
     * The piano's device id as of the last watchdog pass.
     *
     * Cached so the status line can say whether the stream is on it without
     * asking AudioManager a second time every frame — and because a replug
     * changes the id, so a value read once at startup would be a lie.
     */
    private var lastChosenOutputId = NativeBridge.DEVICE_UNSPECIFIED

    private var midiDevice: MidiDevice? = null
    // 2 bursts. Once the render thread exists the callback is a memcpy that
    // cannot overrun, so this buffer no longer has to cover synthesis time at
    // all, only the OS waking the callback late. The earlier 6 was sized for a
    // deadline that no longer applies, and 3 was AAudio's nominal floor rather
    // than anything measured.
    //
    // MEASURED, on the tablet's own speaker, under load rather than idle -- an
    // empty graph survives any buffer, so a sweep of a silent engine measures
    // nothing. A note a second plus a chord every fourth, 30 s per setting:
    //
    //     3 bursts -> 12 ms, 0 xruns
    //     2 bursts ->  8 ms, 0 xruns     <- here
    //     1 burst  ->  4 ms, 1 xrun
    //
    // So 2 is the last setting that holds. This does NOT relax the per-callback
    // deadline -- the callback still runs on the same block -- it buys slack
    // before a late callback becomes an audible dropout, and one burst of slack
    // turned out to be one too few.
    //
    // Deliberately NOT restored from prefs. The Buffer button and the `bursts`
    // extra on ROUTE both write the pref, which is useful for sweeping, but a
    // sweep that ended at 1 should not quietly become the setting the app boots
    // into tomorrow night. Every launch starts from the value that was measured.
    private var bufferBursts = 2

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
            if (++altTick % 4 == 0) watchUsbAltSetting()
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
        // Before buildUi, so the buttons are built with the right faces on them.
        loadPrefs()
        // Two screens in one frame while M3 is under way: the new UI (Compose,
        // ui/AppUi.kt) and the prototype's, which still owns routing and the
        // engine panels. The new one is the default; each has a way to the other.
        prototypeView = buildUi()
        appLibrary = be.mdw.bagosynths.ui.AppLibrary(java.io.File(filesDir, "library"), Build.MODEL)
        player = be.mdw.bagosynths.ui.SongPlayer(appLibrary, engineHost, prefs())
        composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { be.mdw.bagosynths.ui.AppUi(player, appLibrary, engineHost) }
        }
        setContentView(android.widget.FrameLayout(this).apply {
            addView(prototypeView)
            addView(composeView)
        })
        showNewUi(prefs().getBoolean("ui.new", true))
        // Once the engine has its nodes: the demo setlist on first run (S14.2),
        // then back to where the last session was (S13.2).
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                appLibrary.seedIfEmpty(NativeBridge.nativeOrganPresetNames().toList())
                player.resume()
            }.onFailure { android.util.Log.e("bos-ui", "new UI start: $it") }
        }, 2500)

        usbThread.start()
        usbHandler = Handler(usbThread.looper)

        val filter = android.content.IntentFilter("be.mdw.bagosynths.NOTE")
        filter.addAction("be.mdw.bagosynths.LEVEL")
        filter.addAction("be.mdw.bagosynths.DUMP")
        filter.addAction("be.mdw.bagosynths.MASTER")
        filter.addAction("be.mdw.bagosynths.SHARING")
        filter.addAction("be.mdw.bagosynths.RATE")
        filter.addAction("be.mdw.bagosynths.FILL")
        filter.addAction("be.mdw.bagosynths.PARAM")
        filter.addAction("be.mdw.bagosynths.PANIC")
        filter.addAction("be.mdw.bagosynths.STRESS")
        filter.addAction("be.mdw.bagosynths.CONCEAL")
        filter.addAction("be.mdw.bagosynths.REINIT")
        filter.addAction("be.mdw.bagosynths.RESETPEAKS")
        filter.addAction("be.mdw.bagosynths.PROFILE")
        filter.addAction("be.mdw.bagosynths.INSTRUMENT")
        filter.addAction("be.mdw.bagosynths.OUTPUT")
        filter.addAction("be.mdw.bagosynths.RESET")
        filter.addAction("be.mdw.bagosynths.PART")
        filter.addAction("be.mdw.bagosynths.MEASURE")
        filter.addAction("be.mdw.bagosynths.DEVHEALTH")
        filter.addAction("be.mdw.bagosynths.PANIC")
        filter.addAction("be.mdw.bagosynths.ROUTE")
        filter.addAction("be.mdw.bagosynths.SYSEX")
        filter.addAction("be.mdw.bagosynths.USBSTALL")
        filter.addAction("be.mdw.bagosynths.STAGE")
        filter.addAction("be.mdw.bagosynths.CP88SELFTEST")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(debugReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(debugReceiver, filter)
        }

        // USB permission answers, and devices coming and going. Not exported:
        // the permission broadcast is aimed at this package explicitly.
        val usbFilter = android.content.IntentFilter(ACTION_USB_PERMISSION)
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, usbFilter)
        }
    }

    /**
     * Launched again by USB_DEVICE_ATTACHED (the manifest's filter, which is
     * what lets "always open with Bag-O-Synths" make a replug permission-free).
     * The instrument is singleTask, so this is the running one: re-route, never
     * restart.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && engineBroughtUp) {
            applyRouting("device attached")
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

    // ASK FOR SOMETHING THE DEVICE DOES NOT DECLARE, deliberately and only for
    // a measurement. Zero means "use what the device declares", which is the
    // shipped behaviour and the only behaviour a user ever gets.
    //
    // This exists because of what Android's policy dump says on the phone:
    // the MMAP mix port that EXCLUSIVE comes from -- "mmap_no_irq_out" -- has
    // exactly ONE profile, 48000 Hz and PCM_16_BIT, while the CP-88 port has
    // exactly one profile, 44100 Hz and PCM_24_BIT_PACKED. So exclusive on
    // this device is not merely "available at 48 kHz"; it is available ONLY
    // through a port that cannot carry the piano's rate OR its word length,
    // and the HAL therefore converts both. The question worth measuring is
    // what that conversion costs in latency and quality -- not whether it is
    // happening, which the dump already settles.
    //
    //   adb shell am broadcast -a be.mdw.bagosynths.RATE --ei hz 48000 --ei fmt 1
    //   adb shell am broadcast -a be.mdw.bagosynths.RATE --ei hz 0 --ei fmt 0
    private var rateOverride = 0
    private var formatOverride = 0

    override fun onResume() {
        super.onResume()
        applyStageDnd(true)

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
            //
            // The ALSA path starts synchronously, as it always has. A direct-USB
            // start is asynchronous -- it may have to wait for the kernel to let
            // go of the interface -- so the graph is built up front for the
            // instrument loaders below, and routing finishes on its own thread.
            NativeBridge.nativeSetMasterDb(masterDb)
            if (effectiveMode(Use.AUDIO) == IoMode.ALSA &&
                effectiveMode(Use.MIDI) == IoMode.ALSA) {
                startEngine()
                openMidi()
            } else {
                NativeBridge.nativePrepareGraph()
                applyRouting("start")
            }
            // NOT enableInputIfPermitted() here any more -- see that function.
            loadFirstInstrument()
            startSurge()
            startDexed()
            startFluid()
            // Restore AFTER loadFirstInstrument(), which picks a default: a patch
            // that names an instrument should win over the default, not race it.
            restorePatch()
            engineBroughtUp = true
        } else if (!NativeBridge.nativeMidiOpen() && !routingBusy) {
            // It kept playing while we were away. The one thing worth checking is
            // MIDI, because the port can be lost to a cable event without the
            // audio stream noticing.
            if (effectiveMode(Use.MIDI).direct) applyRouting("midi lost")
            else openMidi()
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
    // MEASURED AND DEAD, 2026-09-25. The idea was to make the latency probe
    // listen on the piano's capture endpoint instead of a microphone: a click
    // read digitally, at full scale, with no room and no volume control in
    // between, would fix the oracle that has now been wrong four times.
    //
    // The CP-88 does not loop host playback back. With the output running on
    // the CP-88 and the input pinned to device 6692 -- verified by the device
    // check in openInput, not assumed -- three probes in a row read
    // maxPeak=0,0000 exactly. The stream opens and delivers nothing, which
    // agrees with what this file already said above: the two directions are
    // mutually exclusive on that card.
    //
    // It cost two builds because the first attempts were read as a quiet
    // loopback. They were the microphone: MEASURE calls enableInputIfPermitted
    // on its way in, which put the mic back before every probe, and the give
    // away was that sending the output to the phone's own speaker took the
    // peak from 0,015 to 0,69.
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

    @Deprecated("ComponentActivity's own callback; this app asks with the platform API")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
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
        applyStageDnd(false)
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
        runCatching { unregisterReceiver(debugReceiver) }
        runCatching { unregisterReceiver(usbReceiver) }
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
        // Give every direct-USB interface back to the kernel on the way out, or
        // the device would stay invisible to Android until it was replugged.
        closeAllSessions()
        usbThread.quitSafely()
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
            on.none { it } -> Part.None
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
            // NONE lights when nothing is sounding, which is the one case where
            // a lit tab means the absence of sound rather than its presence.
            Part.None to on.none { it },
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
                // "Add NONE to the mix" is not a thing, so a long-press does
                // what a tap does rather than quietly nothing.
                Part.None -> {
                    organPanel.setPart(Part.None)
                    surgePanel.setPart(Part.None)
                    dexedPanel.setPart(Part.None)
                    plaitsPanel.setPart(Part.None)
                    fluidPanel.setPart(Part.None)
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

        // The mix faders belong to ALL and to nothing else. Soloed, a part's
        // level is not a setting: it is 1 because it is the part that is
        // playing. Leaving the fader there let a drag put the soloed part at
        // half, or at zero, with the tab still saying it was the one sounding.
        //
        // And it was worse than merely redundant: the piano and organ faders
        // live on a SHARED panel, so PIANO and ORGAN showed two mix faders
        // while every other solo showed one, which is the arbitrariness
        // Michiel reported rather than any deliberate rule.
        val mixing = target == Part.All
        organPanel.setMixVisible(mixing)
        surgePanel.setMixVisible(mixing)
        dexedPanel.setMixVisible(mixing)
        plaitsPanel.setMixVisible(mixing)
        fluidPanel.setMixVisible(mixing)
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
        // A routing change deliberately leaves the output closed for seconds --
        // it waits for the kernel to release the interface. Without this guard
        // the watchdog would see "not running" and reopen the ALSA stream
        // underneath it, which is precisely the thing the wait exists to avoid.
        if (routingBusy) return

        val stats = NativeBridge.stats()

        // --- direct USB -----------------------------------------------------
        if (effectiveMode(Use.AUDIO).direct) {
            lastChosenOutputId = NativeBridge.DEVICE_USB_DIRECT
            val now = android.os.SystemClock.uptimeMillis()
            val midiOk = !effectiveMode(Use.MIDI).direct || NativeBridge.nativeMidiOpen()
            if (stats.running && stats.usbActive && midiOk) {
                deviceLostSince = 0L
                directRetries = 0
                alsaFallbackOn = false
                return
            }
            // The sink stopped on its own -- the device went away, or something
            // reconfigured the interface under it. Rebuild it, rate-limited like
            // the ALSA path, so a device that is really gone is not hammered.
            if (deviceLostSince == 0L) { deviceLostSince = now; return }
            if (now - deviceLostSince < 2000L || now - lastRestartAttempt < 2000L) return

            // A retry is for something TRANSIENT -- a jogged cable, an
            // interface the kernel has not let go of yet. It is not a way to
            // keep asking a question that has already been answered.
            //
            // Michiel's report of a "connect/disconnect loop" was exactly this:
            // the clock request failed with EBUSY, which it was always going to
            // do, and the watchdog re-routed every two seconds for as long as
            // the app was open. Each pass tore the MIDI down and built it back,
            // which is why notes went missing while it spun.
            //
            // So the retries are counted. After kDirectRetries the mode is left
            // exactly as the player chose it -- their setting is not ours to
            // change behind their back -- but the ALSA engine is started, so
            // the instrument MAKES SOUND while the reason sits on the screen.
            // Silence with a correct setting is worse than sound with a
            // complaint, on a stage most of all.
            if (directRetries >= kDirectRetries) {
                if (!alsaFallbackOn) {
                    alsaFallbackOn = true
                    android.util.Log.e("bos-ui",
                        "direct USB failed $directRetries times -- falling back to ALSA. " +
                        "Last reason: $routingStatus")
                    status("direct USB failed: $routingStatus -- playing through ALSA")
                    usbHandler.post { runCatching { startEngine() } }
                }
                return
            }
            lastRestartAttempt = now
            directRetries += 1
            android.util.Log.i("bos-ui",
                "direct USB not running -- re-routing (attempt $directRetries)")
            applyRouting("usb sink stopped", fresh = false)
            return
        }
        // Audio is ALSA but MIDI is direct: the MIDI reader can stop on its own
        // (the device went away), and only re-routing brings it back.
        if (effectiveMode(Use.MIDI).direct && !NativeBridge.nativeMidiOpen()) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMidiAttempt >= 2000L) {
                lastMidiAttempt = now
                applyRouting("usb midi lost", fresh = false)
            }
            return
        }

        val output = findOutputDeviceId()
        lastChosenOutputId = output
        val haveOutput = output != NativeBridge.DEVICE_UNSPECIFIED

        val healthy = stats.running && haveOutput && stats.deviceId == output
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

        // No piano. Two bad answers, and the least bad one depends on where you
        // are standing.
        //
        // The original rule was to refuse the tablet's own speaker outright,
        // because on a stage a speaker nobody can hear is worse than silence:
        // the meters move, the player believes the rig works, and the failure
        // goes unnoticed until someone asks why the keyboard is quiet.
        //
        // But measured 2026-09-21: pulling the cable left the app silent and
        // STUCK -- no stream, and playing did nothing until it was restarted.
        // That is not a rig that "works without the CP-88" in any sense, and it
        // is a trap anywhere the piano is simply not part of the setup.
        //
        // So: fall back to the built-in speaker, but only after the piano has
        // been missing for a good while, and SAY SO loudly -- findCp88OutputDeviceId
        // already labels it "built-in (NO USB DEVICE ...)" and the status line
        // shows that label. The piano is still preferred and is taken back the
        // moment it reappears, which is the branch above.
        if (!haveOutput) {
            if (now - deviceLostSince < SPEAKER_FALLBACK_AFTER_MS) return
            if (stats.running && stats.deviceId != NativeBridge.DEVICE_UNSPECIFIED &&
                stats.deviceId != 0) {
                // Already playing somewhere that is not the piano, which after
                // this fallback means the speaker. Leave it alone.
                return
            }
            NativeBridge.nativeStop()
            if (NativeBridge.nativeStart(NativeBridge.DEVICE_UNSPECIFIED, bufferBursts)) {
                android.util.Log.i("bos-ui",
                    "no CP-88 for ${SPEAKER_FALLBACK_AFTER_MS} ms -- fell back to the " +
                        "built-in speaker so the instrument still plays")
            }
            return
        }

        // Stop first, unconditionally. `start()` returns true immediately when it
        // thinks it is already running, so a stream sitting on the wrong device
        // would otherwise never be replaced.
        NativeBridge.nativeStop()
        if (NativeBridge.nativeStart(output, bufferBursts)) {
            android.util.Log.i("bos-ui", "audio restarted on $audioDeviceLabel (device $output)")
            deviceLostSince = 0L
            // Only if MIDI is actually down. It used to be reopened
            // unconditionally, which was harmless while both directions were the
            // same cable and is wrong now that they need not be: restarting
            // audio on a Scarlett would drop the notes coming from the piano.
            if (!NativeBridge.nativeMidiOpen()) reopenMidi()
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

    // ---------------------------------------------------------------- routing
    //
    // THE RECYCLE STRATEGY IS GONE (2026-09-22). It closed the stream every ten
    // minutes and held four seconds of silence, because that was the only thing
    // that kept the CP-88's USB audio from halting on Android's path. The
    // direct-USB modes below reach the device without Android's audio stack,
    // and Michiel's call was to remove the workaround rather than keep two
    // answers to one problem. The history is in HANDOFF and LOG.md.
    //
    // For audio out and for MIDI in, separately, one of three modes (IoMode):
    // ALSA, USB interface, USB device. The mode is a property of the use; the
    // consistency rule (ModeRule) is applied across the uses of one physical
    // device, so the two can never disagree about who owns it.
    //
    // Every name shown is the ALSA name -- the card name and "card MIDI n" --
    // whatever the mode, computed by the rule snd-usb-audio uses (engine/,
    // tested against the CP-88's real names).

    private var audioMode = IoMode.ALSA
    private var midiMode = IoMode.ALSA
    private var usbBufferMs = UsbBuffers.DEFAULT_MS

    /** Master volume in dB, 0 = unity. Half-decibel steps on the fader. */
    private var masterDb = 0f
    private lateinit var masterLabel: TextView
    private lateinit var masterBar: android.widget.SeekBar
    private val MASTER_STEPS =
        ((NativeBridge.MASTER_MAX_DB - NativeBridge.MASTER_MIN_DB) * 2).toInt()
    private fun dbToProgress(db: Float) = ((db - NativeBridge.MASTER_MIN_DB) * 2).toInt()
    private fun progressToDb(p: Int) = NativeBridge.MASTER_MIN_DB + p / 2f

    /**
     * Routing runs here, never on the main thread: entering a direct mode can
     * mean waiting seconds for the kernel -- and the DSP behind it -- to let go
     * of an interface, and that wait must not freeze the UI.
     */
    private val usbThread = android.os.HandlerThread("usb-routing")
    private lateinit var usbHandler: Handler
    @Volatile private var routingBusy = false
    @Volatile private var routingPending = false
    @Volatile private var routingStatus = ""

    /** Open USB sessions by device path. Touched on the usb thread only. */
    private val sessions = HashMap<String, UsbSession>()
    /** Port names of open sessions, for the main thread's MIDI list. */
    @Volatile private var sessionPortNames: Map<String, List<String>> = emptyMap()
    /** The device a direct audio stream is running on, if any. */
    @Volatile private var directAudioOn: String? = null
    /**
     * The USB device an ALSA stream was last opened on. If a direct mode is
     * entered on that device, the DSP session there may still be winding down,
     * and the release has to be waited for rather than assumed.
     */
    @Volatile private var alsaAudioOn: String? = null
    /** Devices whose USB permission was refused: ALSA until the user chooses again. */
    private val deniedDevices = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var permissionPendingFor: String? = null

    private fun usbManager() = getSystemService(Context.USB_SERVICE) as UsbManager

    private fun loadPrefs() {
        val p = prefs()
        chosenOutputName = p.getString("chosenOutputName", null)
        chosenMidiName = p.getString("chosenMidiName", null)
        audioMode = IoMode.parse(p.getString("audioMode", null))
        midiMode = IoMode.parse(p.getString("midiMode", null))
        usbBufferMs = p.getInt("usbBufferMs", UsbBuffers.DEFAULT_MS)
            .takeIf { it in UsbBuffers.choicesMs } ?: UsbBuffers.DEFAULT_MS
        masterDb = p.getFloat("masterDb", 0f)
            .coerceIn(NativeBridge.MASTER_MIN_DB, NativeBridge.MASTER_MAX_DB)
    }

    private fun saveModePrefs() {
        prefs().edit()
            .putString("audioMode", audioMode.name)
            .putString("midiMode", midiMode.name)
            .putInt("usbBufferMs", usbBufferMs)
            .apply()
    }

    // --------------------------------------------------------------- choices

    /** An audio output, by its ALSA card name. `usb` set when it is a USB device. */
    class OutputChoice(val name: String, val alsa: AudioDeviceInfo?, val usb: UsbDevice?)

    /** A MIDI input port, by its ALSA port name ("CP88/CP73 MIDI 1"). */
    class MidiChoice(val name: String, val card: String, val port: Int,
                     val info: MidiDeviceInfo?, val usb: UsbDevice?)

    private fun isUsbType(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /** The UsbDevice behind an AAudio device, matched by product name. */
    private fun usbFor(d: AudioDeviceInfo, usbDevices: Collection<UsbDevice>): UsbDevice? {
        val pn = d.productName?.toString().orEmpty()
        return usbDevices.firstOrNull { u ->
            UsbSession.hasAudioOut(u) && u.productName?.trim()?.let { it.isNotEmpty() && pn.contains(it) } == true
        }
    }

    /**
     * Every output, in the order the button cycles: what AudioManager lists
     * (USB devices and the tablet's own speaker), plus USB devices this process
     * owns whole -- they have no ALSA card while it does, so AudioManager no
     * longer lists them, but they are still the instrument's output.
     */
    private fun outputChoices(): List<OutputChoice> {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usbDevices = usbManager().deviceList.values
        val out = LinkedHashMap<String, OutputChoice>()
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val usbType = isUsbType(d)
            if (!usbType && d.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) continue
            val usb = if (usbType) usbFor(d, usbDevices) else null
            val name = usb?.let { UsbSession.alsaCardName(it) } ?: d.productName?.toString().orEmpty()
            if (name !in out) out[name] = OutputChoice(name, d, usb)
        }
        for (u in usbDevices) {
            if (!UsbSession.hasAudioOut(u)) continue
            val name = UsbSession.alsaCardName(u)
            if (name !in out) out[name] = OutputChoice(name, null, u)
        }
        return out.values.toList()
    }

    /**
     * Every MIDI input port, by ALSA name. MidiManager's USB devices are named
     * from their UsbDevice by the snd-usb-audio rule; ports of devices this
     * process owns come from the open session, which read the jack strings.
     */
    private fun midiChoices(): List<MidiChoice> {
        val out = LinkedHashMap<String, MidiChoice>()
        val manager = getSystemService(Context.MIDI_SERVICE) as? MidiManager
        val devices: Array<MidiDeviceInfo> = when {
            manager == null -> emptyArray()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toTypedArray()
            else -> @Suppress("DEPRECATION") manager.devices
        }
        for (info in devices) {
            if (info.outputPortCount <= 0) continue
            @Suppress("DEPRECATION")
            val usb = info.properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)
            val card = usb?.let { UsbSession.alsaCardName(it) } ?: info.midiName()
            for (p in 0 until info.outputPortCount) {
                val name = if (usb != null) {
                    NativeBridge.nativeAlsaMidiPortName(card, JackNames.lookup(usb, p), p)
                } else {
                    info.ports.firstOrNull {
                        it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT && it.portNumber == p
                    }?.name?.takeIf { it.isNotBlank() } ?: "$card ${p + 1}"
                }
                if (name !in out) out[name] = MidiChoice(name, card, p, info, usb)
            }
        }
        for (u in usbManager().deviceList.values) {
            if (!UsbSession.hasMidi(u)) continue
            val card = UsbSession.alsaCardName(u)
            val names = sessionPortNames[u.deviceName]
                ?: listOf(NativeBridge.nativeAlsaMidiPortName(card, JackNames.lookup(u, 0), 0))
            names.forEachIndexed { p, n -> if (n !in out) out[n] = MidiChoice(n, card, p, null, u) }
        }
        return out.values.toList()
    }

    /**
     * The output in use. The user's choice wins; otherwise the interim order,
     * hardcoded until the graph editor makes the output a property of the
     * patch: an audio interface (Scarlett) -- where one is present it is the
     * monitoring path and the piano is just a controller -- then the CP-88,
     * then any other USB device. Null: the caller decides about the speaker.
     */
    private fun resolveAudio(): OutputChoice? {
        val c = outputChoices()
        chosenOutputName?.let { want -> c.firstOrNull { it.name == want }?.let { return it } }
        fun named(vararg keys: String) = c.firstOrNull { ch ->
            ch.usb != null && keys.any { ch.name.uppercase().contains(it) }
        }
        return named("SCARLETT", "FOCUSRITE") ?: named("CP88", "CP73", "YAMAHA")
            ?: c.firstOrNull { it.usb != null }
    }

    private fun resolveMidi(): MidiChoice? {
        val c = midiChoices()
        chosenMidiName?.let { want -> c.firstOrNull { it.name == want }?.let { return it } }
        return c.firstOrNull { ch ->
            ch.port == 0 && listOf("CP88", "CP73", "YAMAHA").any { ch.card.uppercase().contains(it) }
        } ?: c.firstOrNull()
    }

    private fun sameDevice(a: OutputChoice?, m: MidiChoice?): Boolean =
        a?.usb != null && m?.usb != null && a.usb.deviceName == m.usb.deviceName

    /**
     * The modes in force: a use on something that is not a USB device is ALSA
     * whatever is stored, a refused permission falls back to ALSA, and the rule
     * is applied across uses of one device.
     */
    private fun effectiveModes(a: OutputChoice?, m: MidiChoice?): Pair<IoMode, IoMode> {
        // NONE survives everything: it is a decision not to open the use at all,
        // so it does not fall back to ALSA when the device is not USB.
        val am = when {
            audioMode.off -> IoMode.NONE
            a?.usb != null && a.usb.deviceName !in deniedDevices -> audioMode
            else -> IoMode.ALSA
        }
        val mm = when {
            midiMode.off -> IoMode.NONE
            m?.usb != null && m.usb.deviceName !in deniedDevices -> midiMode
            else -> IoMode.ALSA
        }
        return ModeRule.normalize(am, mm, sameDevice(a, m))
    }

    private fun effectiveMode(use: Use): IoMode {
        val modes = effectiveModes(resolveAudio(), resolveMidi())
        return if (use == Use.AUDIO) modes.first else modes.second
    }

    // --------------------------------------------------------------- routing

    /**
     * Re-routes both uses to what the settings now say. Serialised; never on main.
     *
     * `fresh` says this is a routing the PLAYER asked for -- a mode change, a
     * device change, a replug -- rather than the watchdog retrying. A fresh
     * routing clears the give-up state, so a setting that failed three times is
     * tried again as soon as the player touches it.
     */
    private fun applyRouting(reason: String, fresh: Boolean = true) {
        if (!::usbHandler.isInitialized) return
        if (fresh) { directRetries = 0; alsaFallbackOn = false }
        if (routingBusy) { routingPending = true; return }
        routingBusy = true
        usbHandler.post {
            try {
                route(reason)
            } catch (e: Exception) {
                android.util.Log.e("bos-ui", "routing failed", e)
                status("routing failed: ${e.message}")
            } finally {
                routingBusy = false
                if (routingPending) {
                    routingPending = false
                    // Not fresh: this is the tail of a routing already asked
                    // for, and whoever asked has already cleared the state.
                    handler.post { applyRouting("pending change", fresh = false) }
                }
            }
        }
    }

    // --- NO LATCH WATCHDOG. Deliberately. -------------------------------------
    //
    // There was one. It watched `late` and reset the device when a hole
    // appeared, taking the reset at the next musical gap. It is gone, and it
    // should not come back in that form.
    //
    // The reason is a negative result that is now thoroughly established:
    // **a latched CP-88 is indistinguishable from a healthy one, from here.**
    // Measured live during a latch against a healthy baseline -- frames
    // advancing at exactly 44100/s, `deficit`, `late`, `starved` and `errors`
    // all reading as healthy, altsetting 1=1 2=1 with GET_INTERFACE still
    // answering, IN packet sizes 44..45, URB flight time ~7,6 ms, and the
    // submitted packet-size sequence a flawless 44/45 cadence straight through
    // the transition. AudioControl has eps=0, so there is no status interrupt
    // endpoint and no asynchronous error channel; isochronous OUT has no
    // handshake by design. There is no question left to ask the device.
    //
    // So the old watchdog triggered on the DISTURBANCE rather than the state,
    // which was defensible only while a hole nearly always meant a latch. The
    // implicit-feedback fix inverted that. Holes are now routine and survivable
    // -- trials run to `late` 103, 277, 370, 670 with the instrument perfectly
    // audible -- so the trigger no longer discriminates at all, and under hard
    // playing it would fire every 30 s and spend 2,7 s in silence each time,
    // for nothing. A recovery that cannot tell the fault from the noise is a
    // dropout generator.
    //
    // Michiel's rule, and it is the right one: no watchdog that cannot
    // conclusively detect the latch OVER THE WIRE. An acoustic oracle is not
    // acceptable for this -- it needs the microphone, it is defeated by a loud
    // room, and it makes a noise of its own.
    //
    // Recovery is therefore MANUAL and certain: the RESET USB button, and the
    // RESET broadcast for scripts. Both call resetDirectUsb(), which is proved.
    //
    // To reinstate one, something must first be found that says FOR SURE that a
    // latch is present or must have occurred. HANDOFF section 21 lists what has
    // already been ruled out, so that work does not start from zero.

    private var altTick = 0
    private var lastAltReport: String? = null
    private var altReads = 0

    /**
     * Asks the DEVICE, once a second, what altsetting its streaming interfaces
     * are on.
     *
     * This is a hypothesis under test, not a settled diagnostic. THE LATCH has
     * no host-visible signature: during one, the transport's own counters say
     * everything is fine -- `errors=0`, `starved=0`, frames advancing at
     * exactly 44100/s, the controller reporting every packet as put on the
     * wire. Isochronous OUT has no handshake, so a device that has stopped
     * listening looks identical to one that is playing.
     *
     * GET_INTERFACE is the one question that goes to the device and comes back
     * with an answer. Two things could show up in it:
     *
     *   * the streaming interface sitting at alt 0, the zero-bandwidth
     *     setting, which would mean the device stopped streaming and said so
     *     in the only place we never looked;
     *   * the control transfer itself failing (-1), which would mean the
     *     device's function is wedged rather than merely quiet.
     *
     * If neither ever moves during a latch, that is worth knowing too: it
     * would rule out the endpoint configuration and point at the device's
     * internal audio path, which nothing on this end can see.
     *
     * Cheap enough to leave running: one control transfer a second is a
     * rounding error on the bus, and it runs on the usb thread rather than the
     * caller's, because `sessions` belongs to that thread.
     */
    private fun watchUsbAltSetting() {
        if (!::usbHandler.isInitialized || routingBusy) return
        usbHandler.post {
            val s = directAudioOn?.let { sessions[it] } ?: return@post
            if (!s.isOpen) return@post
            val plan = s.plan ?: return@post
            val report = plan.streamingInterfaces.joinToString(" ") { "$it=${s.altSetting(it)}" }
            // Every 30th read as well as on change: "no log" has to mean
            // "still being asked and still the same", not "the probe died".
            if (report != lastAltReport || altReads % 30 == 0) {
                android.util.Log.i("bos-usb", "altsetting: $report")
                lastAltReport = report
            }
            ++altReads
        }
    }

    private fun status(s: String) {
        routingStatus = s
        if (s.isNotEmpty()) android.util.Log.i("bos-ui", "routing: $s")
    }

    /**
     * The switch itself. On the usb thread.
     *
     * The ORDER is the point, and the step that costs time is deliberate:
     *
     *   1. permission first, so nothing is torn down for a mode that cannot be
     *      entered;
     *   2. stop both sinks;
     *   3. give back interfaces no longer wanted -- to the kernel, explicitly --
     *      and wait until Android can see the device again;
     *   4. before claiming a streaming interface, WAIT until the kernel and the
     *      DSP behind it have really let go of it. Closing the AAudio stream
     *      does not do that: AudioFlinger holds the HAL stream through its
     *      standby delay, and the offloaded session owns the transfer ring
     *      until it is torn down. Claiming earlier would be tearing the
     *      interface out from under a live DSP session;
     *   5. start audio, then MIDI.
     */
    private fun route(reason: String) {
        val um = usbManager()
        val audio = resolveAudio()
        val midi = resolveMidi()
        val (aMode, mMode) = effectiveModes(audio, midi)
        android.util.Log.i("bos-ui", "route ($reason): audio=${audio?.name ?: "speaker"} " +
            "${aMode.label}, midi=${midi?.name ?: "none"} ${mMode.label}, buffer=$usbBufferMs ms")

        // 1. Permission.
        val direct = listOfNotNull(
            audio?.usb?.takeIf { aMode.direct }, midi?.usb?.takeIf { mMode.direct }
        ).distinctBy { it.deviceName }
        for (d in direct) {
            if (!um.hasPermission(d)) {
                requestUsbPermission(d)
                status("waiting for USB permission for ${UsbSession.alsaCardName(d)}")
                return
            }
        }

        // 2. Stop both sinks.
        val alsaWasOn = alsaAudioOn
        stopAudioSinks()
        NativeBridge.nativeCloseMidi()
        runOnUiThread { midiDevice?.close(); midiDevice = null }

        // Sessions for what will be direct, and the interfaces each needs.
        for (d in direct) {
            val s = sessions.getOrPut(d.deviceName) { UsbSession(um, d) }
            s.open()?.let { status("USB: $it"); return }
        }
        val want = HashMap<String, MutableSet<Int>>()
        fun need(d: UsbDevice, mode: IoMode, own: (NativeBridge.UsbPlan) -> List<Int>) {
            val p = sessions[d.deviceName]?.plan ?: return
            want.getOrPut(d.deviceName) { mutableSetOf() } +=
                if (mode == IoMode.USB_DEVICE) p.allInterfaces else own(p)
        }
        if (aMode.direct) audio?.usb?.let { need(it, aMode) { p -> p.audioInterfaces } }
        if (mMode.direct) midi?.usb?.let { need(it, mMode) { p -> p.midiInterfaces } }

        // 3. Give back what is no longer wanted.
        for (key in sessions.keys.toList()) {
            val s = sessions.getValue(key)
            val keep = want[key].orEmpty()
            val drop = s.claimedInterfaces() - keep
            if (drop.isNotEmpty()) s.release(drop)
            if (keep.isEmpty()) {
                handBackToAndroid(s)
                s.close()
                sessions.remove(key)
            }
        }

        // 4. Claim -- after the release has really happened.
        for ((key, ids) in want) {
            val s = sessions[key] ?: continue
            val plan = s.plan ?: continue
            // Only the interfaces that CARRY SAMPLES are waited on. AudioControl
            // is claimed too on UAC2 (the clock lives there), but it never
            // streams, so waiting for it to go idle would wait for nothing.
            val streaming = (ids - s.claimedInterfaces()).intersect(plan.streamingInterfaces.toSet())
            if (streaming.isNotEmpty()) {
                InterfaceRelease.awaitIdle(s.device, plan.configValue, streaming,
                    assumeBusy = alsaWasOn == s.device.deviceName) { secs, what ->
                    status("%s (%.1f s)".format(what, secs))
                }
            }
            s.claim(ids)?.let { status("USB: $it"); return }
            for (i in streaming) {
                android.util.Log.i("bos-ui", "claimed; device reports interface $i at alt ${s.altSetting(i)}")
            }
        }
        sessionPortNames = sessions.mapValues { it.value.midiPortNames }

        // 5. Audio, then MIDI.
        if (aMode.direct && audio?.usb != null) {
            val s = sessions.getValue(audio.usb.deviceName)
            applyUsbHost()
            val err = NativeBridge.nativeStartUsbAudio(s.fd, s.raw, usbBufferMs, 0)
            if (err == null) {
                directAudioOn = audio.usb.deviceName
                alsaAudioOn = null
                audioDeviceLabel = "${audio.name}  [${aMode.label}, $usbBufferMs ms]"
            } else {
                status("direct audio failed: $err")
            }
        } else if (aMode.off) {
            // Nothing to start. stopAudioSinks() has already run.
            audioDeviceLabel = "none"
        } else {
            startEngine()
        }

        if (mMode.direct && midi?.usb != null) {
            val s = sessions.getValue(midi.usb.deviceName)
            val err = NativeBridge.nativeStartUsbMidi(s.fd, s.raw, true, midi.port)
            runOnUiThread {
                midiView.text = if (err == null) "MIDI: ${midi.name}  [${mMode.label}]"
                                else "MIDI: $err"
            }
        } else if (mMode.off) {
            // nativeCloseMidi() and the device close have already run.
            runOnUiThread { midiView.text = "MIDI: none" }
        } else {
            runOnUiThread { lastMidiAttempt = 0L; openMidi() }
        }
        if (routingStatus.startsWith("waiting") || routingStatus.startsWith("routing")) status("")
    }

    /**
     * Whether the direct-USB reap loop borrows an AAudio callback thread -- the
     * only way an app gets SCHED_FIFO here -- and on which device: the built-in
     * speaker, never the instrument the loop is driving. It plays silence.
     */
    private var usbHostOnAAudio = true

    private fun applyUsbHost() {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val speaker = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        NativeBridge.nativeSetUsbHostDevice(if (usbHostOnAAudio) speaker?.id ?: 0 else 0)
    }

    /** Stops whichever audio sink is running, returning streaming interfaces to alt 0. */
    private fun stopAudioSinks() {
        val on = directAudioOn
        val s = on?.let { sessions[it] }
        if (s != null && s.isOpen) NativeBridge.nativeStopUsbAudio(s.fd, s.raw)
        else NativeBridge.nativeStop()
        directAudioOn = null
    }

    /**
     * After every interface has been given back: wait for Android to see the
     * device again, and reset it if it does not. Reconnecting interface by
     * interface normally suffices; a USB reset is the fallback, because it
     * unbinds and rebinds every driver on the device.
     */
    private fun handBackToAndroid(s: UsbSession) {
        for (attempt in 0 until 2) {
            if (awaitAndroidSees(s)) return
            if (attempt == 0) {
                android.util.Log.i("bos-ui", "\"${s.cardName}\" did not reappear -- USB reset")
                s.reset()
            }
        }
        android.util.Log.e("bos-ui", "\"${s.cardName}\" still not visible to Android after a reset")
    }

    /**
     * Blocks until Android can see [s]'s card again, or the timeout. Returns
     * whether it came back.
     *
     * This is the OBSERVABLE for "the kernel has re-owned and re-initialised the
     * device". snd-usb-audio creates the ALSA card when it probes interface 0,
     * so the card appearing in AudioManager is proof that the probe ran and
     * completed -- which a fixed sleep after USBDEVFS_CONNECT would only assume.
     * A fixed sleep after USBDEVFS_CONNECT would only assume it. See
     * resetDirectUsb, which waits on this before taking the device back.
     *
     * Deliberately does NOT reset the device on failure; the caller decides
     * whether to escalate.
     */
    private fun awaitAndroidSees(s: UsbSession, timeoutMs: Long = 4000L): Boolean {
        fun seen(): Boolean {
            val d = s.device
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audio = !UsbSession.hasAudioOut(d) || am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { isUsbType(it) && usbFor(it, listOf(d)) != null }
            val midi = !UsbSession.hasMidi(d) || midiChoices().any { it.info != null && it.usb?.deviceName == d.deviceName }
            return audio && midi
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - t0 < timeoutMs) {
            if (seen()) {
                android.util.Log.i("bos-ui", "\"${s.cardName}\" is back with Android after " +
                    "${android.os.SystemClock.uptimeMillis() - t0} ms")
                return true
            }
            status("handing \"${s.cardName}\" back to Android")
            android.os.SystemClock.sleep(200)
        }
        return false
    }

    /**
     * Recovery from THE LATCH.
     *
     * The fault: in a direct USB mode the CP-88 goes silent while the transport
     * keeps running -- URBs are acknowledged, the feedback endpoint still
     * reports its rate, frames keep counting -- and nothing in the app can tell
     * "presenting" from "acknowledging and discarding". There is no detector
     * yet, so there is no automatic recovery either. This is the button to
     * press when it happens.
     *
     * WHAT IT DOES, and why exactly this:
     *
     *   1. stop both sinks and give every interface back to the kernel;
     *   2. **USB reset** -- USBDEVFS_RESET, every driver on the device unbound
     *      and rebound;
     *   3. wait until Android can see the card again;
     *   4. route the device to ALSA and hold, so a stream really opens on it;
     *   5. take it back into the direct mode it was in.
     *
     * THIS WAS NOT GUESSED. It shipped first as a ladder -- three rungs,
     * cheapest first, each adding one thing, so that whichever rung cleared a
     * real latch would also say which part was load-bearing. Michiel then met a
     * latch and reported: "only rung 3 worked."
     *
     * That is a genuine result and it kills the cheap theories:
     *
     *   * Disowning and re-owning the device is NOT enough. Releasing every
     *     interface with USBDEVFS_CONNECT, and waiting until snd-usb-audio has
     *     rebuilt the card, still left it latched.
     *   * Nor is letting ALSA actually STREAM on the card. That was rung 2, and
     *     it is the closest automation of the manual fix, and it did not clear
     *     it either.
     *   * Only unbinding and rebinding the drivers -- a port-level reset the
     *     device itself sees -- brings the sound back. So the stuck state lives
     *     in the DEVICE or in its endpoint configuration, not in which process
     *     happens to own the interfaces.
     *
     * The ladder is gone because it has done its job; keeping it would only
     * make the player try two things that are now known not to work, at the one
     * moment there is no time for it.
     *
     * The ALSA dwell in step 4 is KEPT even though the reset is what does the
     * work. It has never been tested apart from the reset -- rung 3 included
     * it -- so dropping it now would be trading a procedure known to work for
     * an untested shorter one. It costs about two seconds.
     */
    /**
     * How long the ALSA recycle stays silent. Three to four seconds, and the
     * reason is in the device's own behaviour rather than taste.
     *
     * AudioFlinger holds the output open for a two second standby delay
     * (`Standby delay ns=2000000000`), so an immediate reopen never tears the
     * USB substream down and the budget is not reset. Four seconds clears that
     * with margin. Measured in cp88-usb-audio-halt: recycling every eight
     * minutes WITH this gap survived 105 683 968 frames across two 40-minute
     * runs agreeing to 0,04 %, where the same recycle WITHOUT the gap failed
     * normally at 39 365 632 frames. The silence is the entire mechanism.
     */
    private val kAlsaRecycleGapMs = 4000L

    /**
     * The ALSA path halts at a FRAME BUDGET, not a fault, and this resets it.
     *
     * Michiel, after living with both paths: "still the alsa flow is much more
     * stable if it would just not have latched deterministically at 13-14
     * minutes." That halt is not the latch this app has been chasing -- it is
     * reproducible to 0,04 % and it is cleared by a teardown plus silence,
     * where the latch is stochastic and needs USBDEVFS_RESET.
     *
     * So the RESET USB button does the right thing for whichever path is live:
     * a USB reset in a direct mode, and this recycle on ALSA. Taken BEFORE the
     * budget runs out it prevents the halt rather than recovering from it,
     * which is the whole point -- roughly every eight minutes, between pieces.
     *
     * The gap is audible and unavoidable here. Covering it by bridging the
     * audio through this app's own usbfs transport while ALSA is torn down is
     * the obvious next step, and it is not this function.
     */
    private fun recycleAlsaOutput() {
        if (!::usbHandler.isInitialized) return
        usbHandler.post {
            android.util.Log.i("bos-ui", "alsa recycle: releasing the output stream")
            status("recycle: silent for ${kAlsaRecycleGapMs / 1000} s -- resetting the ADSP budget")
            NativeBridge.nativeStop()
            android.os.SystemClock.sleep(kAlsaRecycleGapMs)
            runOnUiThread {
                NativeBridge.nativeStart(findOutputDeviceId(), bufferBursts)
                NativeBridge.nativeResetPeaks()
                status("recycle: done")
                android.util.Log.i("bos-ui", "alsa recycle: output restarted")
            }
        }
    }

    /** RESET USB, dispatched to whatever the audio path actually is. */
    private fun resetAudioPath() {
        val mode = effectiveMode(Use.AUDIO)
        when {
            mode.direct -> resetDirectUsb()
            mode.off -> status("audio is None: nothing to reset")
            else -> recycleAlsaOutput()
        }
    }

    private fun resetDirectUsb() {
        if (!::usbHandler.isInitialized) return
        usbHandler.post {
            val key = directAudioOn
            val s = key?.let { sessions[it] }
            if (key == null || s == null) {
                status("reset: not in a direct USB mode -- nothing to reset")
                return@post
            }
            val card = s.cardName
            android.util.Log.i("bos-ui", "RESET on \"$card\" (claimed ${s.claimedInterfaces()})")
            status("reset: \"$card\"")

            // Stop everything this app is doing to the device, then give every
            // interface back. release() reconnects each one to its kernel
            // driver explicitly, descending so AudioControl goes last -- that
            // is what makes snd-usb-audio build the card again.
            stopAudioSinks()
            NativeBridge.nativeCloseMidi()
            runOnUiThread { midiDevice?.close(); midiDevice = null }
            s.release(s.claimedInterfaces())

            // The step that actually clears it.
            android.util.Log.i("bos-ui", "reset: USB reset of \"$card\"")
            if (!s.reset()) android.util.Log.e("bos-ui", "reset: USB reset of \"$card\" FAILED")

            val back = awaitAndroidSees(s)
            android.util.Log.i("bos-ui", "reset: \"$card\" " +
                (if (back) "re-owned by the kernel" else "did NOT come back within 4 s"))
            s.close()
            sessions.remove(key)

            // Let ALSA really open the card before taking it back.
            //
            // BOTH modes, not just the audio one. Setting audio alone did
            // nothing and testing caught it: UsbRouting.normalize pulls audio
            // straight back to USB device while MIDI is still there, because
            // one device cannot be half owned by this app and half by the
            // kernel. The log said it plainly -- "route (out to ALSA):
            // audio=CP88/CP73 USB device". Michiel's manual fix moves both for
            // the same reason.
            val savedAudio = audioMode
            val savedMidi = midiMode
            audioMode = IoMode.ALSA
            midiMode = IoMode.ALSA
            route("reset: out to ALSA")
            status("reset: streaming on ALSA for $kResetAlsaDwellMs ms")
            android.os.SystemClock.sleep(kResetAlsaDwellMs)
            audioMode = savedAudio
            midiMode = savedMidi

            route("reset: back to ${audioMode.label}")
            status("reset done -- play something")

        }
    }

    /**
     * How long the reset leaves the card streaming on ALSA. 2 s: long enough
     * that AudioFlinger has certainly opened the stream and pushed frames
     * rather than being interrupted mid-open, short enough to do between two
     * songs.
     */
    private val kResetAlsaDwellMs = 2000L

    private fun requestUsbPermission(d: UsbDevice) {
        if (permissionPendingFor == d.deviceName) return
        permissionPendingFor = d.deviceName
        // FLAG_MUTABLE is required: the system writes EXTRA_DEVICE and
        // EXTRA_PERMISSION_GRANTED into this intent before broadcasting it, and
        // an immutable PendingIntent silently never arrives.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.app.PendingIntent.FLAG_MUTABLE else 0
        val pi = android.app.PendingIntent.getBroadcast(
            this, 0, android.content.Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        usbManager().requestPermission(d, pi)
    }

    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            @Suppress("DEPRECATION")
            val d = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    permissionPendingFor = null
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        applyRouting("permission granted")
                    } else {
                        // Refused: that device plays through ALSA until the user
                        // chooses a mode again, rather than asking in a loop.
                        d?.let { deniedDevices += it.deviceName }
                        status("USB permission refused -- ALSA in use")
                        applyRouting("permission refused")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // The connection is dead with the device. Drop the session;
                    // the watchdog sees the sink stop and re-routes.
                    if (d != null && ::usbHandler.isInitialized) {
                        usbHandler.post {
                            sessions.remove(d.deviceName)?.let { runCatching { it.close() } }
                            if (directAudioOn == d.deviceName) directAudioOn = null
                            sessionPortNames = sessions.mapValues { it.value.midiPortNames }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (engineBroughtUp) applyRouting("device attached")
            }
        }
    }

    /** Every direct interface back to the kernel. On the way out of the app. */
    private fun closeAllSessions() {
        for (s in sessions.values) runCatching { s.close() }
        sessions.clear()
    }

    /**
     * Mode and buffer, for one use. Long-press on the Audio or MIDI button.
     * A list of choices rather than a form: it has to be usable in a hurry.
     */
    private fun showModeDialog(use: Use) {
        val audio = resolveAudio()
        val midi = resolveMidi()
        val isUsb = if (use == Use.AUDIO) audio?.usb != null else midi?.usb != null
        val current = if (use == Use.AUDIO) audioMode else midiMode
        val target = if (use == Use.AUDIO) audio?.name ?: "speaker" else midi?.name ?: "none"
        val same = sameDevice(audio, midi)

        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for (m in IoMode.values()) {
            val note = when {
                m == IoMode.USB_INTERFACE && use == Use.AUDIO ->
                    "  -- fragile: Android can still route system sounds here"
                m == IoMode.USB_DEVICE && same -> "  -- owns the whole device: MIDI too"
                m == IoMode.USB_DEVICE -> "  -- owns the whole device"
                m == IoMode.NONE && use == Use.MIDI -> "  -- not opened by anyone"
                m == IoMode.NONE -> "  -- silent: nothing is opened"
                else -> ""
            }
            items += (if (m == current) "(o)  " else "( )  ") + m.label + note
            actions += { setMode(use, m) }
        }
        if (use == Use.AUDIO) {
            for (ms in UsbBuffers.choicesMs) {
                items += (if (ms == usbBufferMs) "(o)  " else "( )  ") + "Direct USB buffer $ms ms" +
                    if (ms == UsbBuffers.DEFAULT_MS) "  (default)" else ""
                actions += {
                    usbBufferMs = ms
                    saveModePrefs()
                    if (effectiveMode(Use.AUDIO).direct) applyRouting("buffer change")
                }
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isUsb) "${if (use == Use.AUDIO) "Audio" else "MIDI"}: $target"
                      else "$target is not a USB device -- always ALSA")
            .setItems(items.toTypedArray()) { _, which ->
                actions[which]()
                refreshDeviceButtons()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setMode(use: Use, to: IoMode) {
        val same = sameDevice(resolveAudio(), resolveMidi())
        val (a, m) = ModeRule.change(audioMode, midiMode, use, to, same)
        audioMode = a
        midiMode = m
        deniedDevices.clear()
        saveModePrefs()
        android.util.Log.i("bos-ui", "mode: audio=${a.label} midi=${m.label} (same device: $same)")
        applyRouting("mode change")
    }

    /**
     * Sends MIDI over direct USB and logs every SysEx that comes back, on its
     * own thread: a bulk dump answer takes some 0,4 s and must not hold up the
     * UI. Replies are logged under `bos-sysex` with their time since the first
     * byte went out.
     */
    private fun sysexExchange(hex: String, gapMs: Int, waitMs: Int) {
        Thread({
            while (NativeBridge.nativeUsbSysexDrain() != null) { /* discard stale replies */ }
            val messages = hex.split('|').map { part ->
                part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    .map { it.toInt(16).toByte() }.toByteArray()
            }.filter { it.isNotEmpty() }
            val t0 = System.nanoTime()
            var bytes = 0
            for ((i, m) in messages.withIndex()) {
                val r = NativeBridge.nativeUsbMidiSend(m)
                if (r < 0) {
                    android.util.Log.e("bos-sysex", "send ${i + 1}/${messages.size} failed: $r")
                    return@Thread
                }
                bytes += r
                if (gapMs > 0 && i < messages.size - 1) Thread.sleep(gapMs.toLong())
            }
            val sentMs = (System.nanoTime() - t0) / 1e6
            android.util.Log.i("bos-sysex", "sent ${messages.size} msg(s), $bytes B in ${"%.1f".format(sentMs)} ms")
            val end = t0 + (sentMs * 1e6).toLong() + waitMs * 1_000_000L
            var count = 0
            while (System.nanoTime() < end) {
                val rx = NativeBridge.nativeUsbSysexDrain()
                if (rx == null) { Thread.sleep(2); continue }
                count++
                val at = (System.nanoTime() - t0) / 1e6
                android.util.Log.i("bos-sysex", "rx ${"%.1f".format(at)} ms ${rx.size} B " +
                        rx.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
            }
            android.util.Log.i("bos-sysex", "done: $count repl${if (count == 1) "y" else "ies"}")
        }, "bos-sysex").start()
    }

    /**
     * Debug triggers, scriptable so an unattended run needs no taps:
     *   adb shell am broadcast -a be.mdw.bagosynths.NOTE --ei note 48 --ei vel 90
     *   adb shell am broadcast -a be.mdw.bagosynths.ROUTE \
     *       --es audio USB_DEVICE --es midi USB_DEVICE --ei buffer 8
     * Registered for the life of the process, because the engine outlives the
     * activity.
     */
    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                // A note, so that an unattended soak can make the rig do WORK
                // rather than push silence.
                // MIDI out over direct USB, and the SysEx that comes back:
                //   adb shell am broadcast -a be.mdw.bagosynths.SYSEX                 //       --es hex "F0 43 20 7F 1C 08 0E 7F 00 F7" --ei wait 1500
                // Several messages separated by '|' go out `gap` ms apart.
                // TEST: one stall of the USB reap loop (a starved IN queue):
                //   adb shell am broadcast -a be.mdw.bagosynths.USBSTALL --ei ms 600
                "be.mdw.bagosynths.USBSTALL" -> NativeBridge.nativeUsbTestStall(intent.getIntExtra("ms", 600))
                // The stage, without the screen (1-based):
                //   adb shell am broadcast -a be.mdw.bagosynths.STAGE --ei song 2 --ei liveset 3
                "be.mdw.bagosynths.STAGE" -> {
                    val song = intent.getIntExtra("song", 0)
                    val ls = intent.getIntExtra("liveset", 0)
                    if (song > 0) player.selectSong(song - 1)
                    if (ls > 0) player.selectLiveSet(ls - 1)
                    // A file in the app's own folder as the song's lead sheet:
                    //   --es sheet leadsheet-test.pdf   --ei page +1 / -1 (a page-turner key)
                    intent.getStringExtra("sheet")?.let { name ->
                        runCatching { player.attachSheetForTest(java.io.File(getExternalFilesDir(null), name)) }
                            .onFailure { android.util.Log.e("bos-stage", "sheet: $it") }
                    }
                    when (intent.getIntExtra("page", 0)) { 1 -> player.forwardKey(); -1 -> player.backKey() }
                }
                // TEST: the CP-88 path end to end (SongPlayer.cp88SelfTest):
                //   adb shell am broadcast -a be.mdw.bagosynths.CP88SELFTEST --ei liveset 1
                "be.mdw.bagosynths.CP88SELFTEST" -> player.cp88SelfTest(intent.getIntExtra("liveset", 1))
                "be.mdw.bagosynths.SYSEX" -> sysexExchange(
                    intent.getStringExtra("hex").orEmpty(),
                    intent.getIntExtra("gap", 20),
                    intent.getIntExtra("wait", 1500))
                "be.mdw.bagosynths.NOTE" -> NativeBridge.nativeTestNote(
                    intent.getIntExtra("note", 48),
                    intent.getIntExtra("vel", 90))
                // Driven by name rather than by tapping a coordinate: fixed
                // screen positions went stale silently three times.
                "be.mdw.bagosynths.PANIC" -> NativeBridge.nativePanic()
                // Select a part from a script. Added while hunting THE LATCH:
                // every stress test until then had been silently landing on
                // whatever part was last tapped, and the tell was voices=0/0 in
                // the health line -- the cluster was playing, but not through
                // sfizz, which is the engine the fault needs.
                //   adb shell am broadcast -a be.mdw.bagosynths.PART --es name piano
                "be.mdw.bagosynths.PART" -> {
                    val want = intent.getStringExtra("name").orEmpty()
                    val hit = Part.values().firstOrNull { it.name.equals(want, true) }
                    if (hit == null) {
                        android.util.Log.i("bos-ui",
                            "parts: " + Part.values().joinToString { it.name })
                    } else {
                        runOnUiThread { showPart(hit, solo = true) }
                    }
                }
                // The acoustic oracle. THE LATCH has no host-visible signature
                // -- measured -- so the only way to answer "is sound actually
                // reaching the piano" is to make a noise and listen for it with
                // the tablet's microphone. Scriptable, so a latch hunt can run
                // as a loop instead of as a person tapping and listening.
                //   adb shell am broadcast -a be.mdw.bagosynths.MEASURE
                "be.mdw.bagosynths.MEASURE" -> runOnUiThread {
                    // Open the input FIRST and let it run before arming. A
                    // capture stream that has just been opened delivers zeros
                    // for the best part of a second, and the probe gives up
                    // after half a second of frames -- so arming in the same
                    // breath measures the warm-up, not the room.
                    enableInputIfPermitted()
                    handler.postDelayed({ NativeBridge.nativeStartLatencyProbe() }, 1200)
                }
                // Ask the device itself, over the control pipe, in the hope
                // that something in the CLASS protocol can see a latch that
                // the data path cannot.
                //   adb shell am broadcast -a be.mdw.bagosynths.DEVHEALTH
                "be.mdw.bagosynths.DEVHEALTH" -> usbHandler.post {
                    val s2 = directAudioOn?.let { sessions[it] }
                    if (s2 == null || !s2.isOpen) {
                        android.util.Log.i("bos-usb", "device health: no direct session")
                    } else {
                        NativeBridge.nativeUsbHealthProbe(s2.fd, s2.raw)
                    }
                }
                "be.mdw.bagosynths.STRESS" -> NativeBridge.nativeStressChord(
                    intent.getIntExtra("count", 30))
                // Because a stress test that ends leaves 64 voices ringing, and
                // whoever is in the room did not ask for that.
                //   adb shell am broadcast -a be.mdw.bagosynths.PANIC
                "be.mdw.bagosynths.PANIC" -> NativeBridge.nativePanic()
                // REINIT: the cheap recovery candidate.
                //
                // Michiel: "if routing to alsa and back fixes it, surely you
                // can somehow send some sequence to reinit the stream without
                // closing?" Routing to ALSA releases the interfaces, lets
                // snd-usb-audio rebind and re-SET_INTERFACE them, then takes
                // them back -- about 2,7 s and a full teardown. The USB-level
                // part of that is just the UAC teardown: drop the streaming
                // interfaces to alt 0, the zero-bandwidth setting, and put them
                // back. usbfs can do that WITHOUT releasing anything.
                //
                // nativeStopUsbAudio already sets both interfaces to alt 0, and
                // nativeStartUsbAudio sets alt 0, re-establishes the sample
                // rate and sets the altsettings again -- so this needs no new
                // native code, only a path that skips the release, the reclaim,
                // the ALSA dwell and the MIDI teardown.
                //   adb shell am broadcast -a be.mdw.bagosynths.REINIT
                "be.mdw.bagosynths.REINIT" -> usbHandler.post {
                    val key = directAudioOn
                    val s2 = key?.let { sessions[it] }
                    if (s2 == null || !s2.isOpen) {
                        android.util.Log.i("bos-ui", "reinit: no direct session")
                    } else {
                        // The recovery ladder, measured against a deterministic
                        // latch (ZLP). Level 1 is the UAC teardown and is known
                        // NOT to be enough; the higher rungs are what a route
                        // to ALSA and back does on the way past.
                        val level = intent.getIntExtra("level", 1)
                        val t0 = android.os.SystemClock.uptimeMillis()
                        NativeBridge.nativeStopUsbAudio(s2.fd, s2.raw)
                        if (level >= 2) NativeBridge.nativeUsbClearHalt(s2.fd, s2.raw)
                        if (level >= 3) {
                            // Hand the interfaces back so the kernel driver
                            // rebinds, then take them again -- the ALSA
                            // round-trip minus ALSA actually streaming.
                            val claimed = s2.claimedInterfaces()
                            s2.release(claimed)
                            android.os.SystemClock.sleep(300)
                            s2.claim(claimed)
                        }
                        val err = NativeBridge.nativeStartUsbAudio(s2.fd, s2.raw, usbBufferMs, 0)
                        val ms = android.os.SystemClock.uptimeMillis() - t0
                        android.util.Log.i("bos-ui",
                            "reinit level $level in $ms ms" + (err?.let { " FAILED: $it" } ?: ""))
                    }
                }
                //   adb shell am broadcast -a be.mdw.bagosynths.CONCEAL --ei on 0
                "be.mdw.bagosynths.CONCEAL" -> {
                    val on = intent.getIntExtra("on", 1) != 0
                    NativeBridge.nativeSetConcealment(on)
                    android.util.Log.i("bos-ui", "underrun concealment: " + if (on) "ON" else "OFF")
                }
                "be.mdw.bagosynths.RESETPEAKS" -> NativeBridge.nativeResetPeaks()
                // The sampler's level, so a test can make a measurable sound
                // without tapping a fader: a restored patch may have left the
                // piano quiet, and a microphone in a room with a PC fan needs
                // signal it can actually hear.
                //   adb shell am broadcast -a be.mdw.bagosynths.LEVEL --ef value 0.8
                "be.mdw.bagosynths.LEVEL" ->
                    NativeBridge.nativeSetSamplerLevel(intent.getFloatExtra("value", 0.8f))
                // The whole graph, to the log: which node holds a level down is
                // otherwise a question only a human with the screen can answer.
                // EXCLUSIVE/MMAP or SHARED, scriptable. The long-press on the
                // Buffer button does the same, but a tap coordinate read from a
                // screenshot goes stale silently, and this has to be testable.
                //   adb shell am broadcast -a be.mdw.bagosynths.SHARING --ez exclusive false
                "be.mdw.bagosynths.SHARING" -> {
                    wantExclusive = intent.getBooleanExtra("exclusive", true)
                    NativeBridge.nativeSetExclusive(wantExclusive)
                    if (!effectiveMode(Use.AUDIO).direct) {
                        NativeBridge.nativeStop()
                        NativeBridge.nativeStart(findOutputDeviceId(), bufferBursts)
                        NativeBridge.nativeResetPeaks()
                    }
                    android.util.Log.i("bos-ui", "sharing: ${if (wantExclusive) "EXCLUSIVE" else "SHARED"}")
                }
                //   ... .RATE --ei hz 48000 --ei fmt 1    ask for what the MMAP
                //                                          port wants, not what
                //                                          the piano declares
                "be.mdw.bagosynths.RATE" -> {
                    rateOverride = intent.getIntExtra("hz", 0)
                    formatOverride = intent.getIntExtra("fmt", 0)
                    if (!effectiveMode(Use.AUDIO).direct) {
                        NativeBridge.nativeStop()
                        NativeBridge.nativeStart(findOutputDeviceId(), bufferBursts)
                        NativeBridge.nativeResetPeaks()
                    }
                    android.util.Log.i("bos-ui", "rate override: " +
                        (if (rateOverride > 0) "$rateOverride Hz" else "device's own") +
                        ", format " +
                        (if (formatOverride > 0) "$formatOverride" else "device's own"))
                }
                //   ... .FILL --ei ms 12     how much slack the render thread gets
                //   ... .FILL --ei ms 0      back to the burst-derived default
                //
                // Separate from RATE on purpose: the ring depth is derived from
                // the BURST, which means choosing a stream geometry silently
                // chooses a render deadline as well. On the phone's two ALSA
                // arms that hidden coupling is a factor of 7,5 -- 30 ms of
                // slack at burst 882, 4 ms at burst 96 -- and it shows up as a
                // factor of 45 in ring underruns under intensive piano. Worth
                // sweeping rather than inheriting.
                "be.mdw.bagosynths.FILL" -> {
                    val ms = intent.getIntExtra("ms", 0)
                    NativeBridge.nativeSetFillMs(ms)
                    android.util.Log.i("bos-ui", "ring fill: " +
                        if (ms > 0) "$ms ms" else "burst-derived")
                }
                //   adb shell am broadcast -a be.mdw.bagosynths.MASTER --ef db -12
                // The profile, including the per-engine held peaks. Scriptable
                // because gain staging is measured by playing the SAME note on
                // each engine in turn and comparing -- which is a loop, not a
                // thing to do by hand.
                //   adb shell am broadcast -a be.mdw.bagosynths.PROFILE
                "be.mdw.bagosynths.PROFILE" -> NativeBridge.nativeLogProfile()
                // The latch recovery, scriptable so a soak test can drive it.
                //   adb shell am broadcast -a be.mdw.bagosynths.RESET
                "be.mdw.bagosynths.RESET" -> resetAudioPath()
                // Pick the output device by name, for the same reason the rest
                // of these exist: a tap coordinate read from a screenshot goes
                // stale silently. Empty name means "automatic".
                //   adb shell am broadcast -a be.mdw.bagosynths.OUTPUT --es name Speaker
                "be.mdw.bagosynths.OUTPUT" -> {
                    val want = intent.getStringExtra("name").orEmpty()
                    val hit = outputChoices().firstOrNull { it.name.contains(want, true) }
                    if (want.isEmpty()) {
                        chosenOutputName = null
                    } else if (hit == null) {
                        android.util.Log.i("bos-ui",
                            "outputs: " + outputChoices().joinToString { it.name })
                        return
                    } else {
                        chosenOutputName = hit.name
                    }
                    prefs().edit().putString("chosenOutputName", chosenOutputName).apply()
                    applyRouting("output broadcast")
                }
                // Pick a sampled instrument by name, so gain staging can be
                // compared across SFZ packs without tapping INSTRUMENT.
                //   adb shell am broadcast -a be.mdw.bagosynths.INSTRUMENT --es name tight
                "be.mdw.bagosynths.INSTRUMENT" -> {
                    val want = intent.getStringExtra("name").orEmpty()
                    val hit = library.firstOrNull {
                        it.id.contains(want, true) || it.name.contains(want, true)
                    }
                    if (hit == null) {
                        android.util.Log.i("bos-ui", "instruments: " +
                            library.joinToString { "${it.id} (${it.name})" })
                    } else {
                        runOnUiThread { selectInstrument(hit) }
                    }
                }
                "be.mdw.bagosynths.MASTER" ->
                    runOnUiThread { setMasterDb(intent.getFloatExtra("db", 0f)) }
                "be.mdw.bagosynths.DUMP" -> {
                    for (n in 0 until NativeBridge.nativeNodeCount()) {
                        val params = (0 until NativeBridge.nativeNodeParamCount(n)).joinToString(" ") { pi ->
                            "${NativeBridge.nativeNodeParamName(n, pi)}=" +
                                "%.3f".format(NativeBridge.nativeGetNodeParam(n, pi))
                        }
                        android.util.Log.i("bos-ui", "node $n ${NativeBridge.nativeNodeType(n)}: $params")
                    }
                }
                // One parameter by name, for the same reason.
                //   adb shell am broadcast -a be.mdw.bagosynths.PARAM                 //       --es node SfizzNode --es param level --ef value 0.9
                "be.mdw.bagosynths.PARAM" -> {
                    val wantNode = intent.getStringExtra("node").orEmpty()
                    val wantParam = intent.getStringExtra("param").orEmpty()
                    val value = intent.getFloatExtra("value", 1.0f)
                    for (n in 0 until NativeBridge.nativeNodeCount()) {
                        if (!NativeBridge.nativeNodeType(n).contains(wantNode, ignoreCase = true)) continue
                        for (pi in 0 until NativeBridge.nativeNodeParamCount(n)) {
                            if (!NativeBridge.nativeNodeParamName(n, pi).contains(wantParam, ignoreCase = true)) continue
                            NativeBridge.nativeSetNodeParam(n, pi, value)
                            android.util.Log.i("bos-ui",
                                "set ${NativeBridge.nativeNodeType(n)}.${NativeBridge.nativeNodeParamName(n, pi)} = $value")
                        }
                    }
                }
                "be.mdw.bagosynths.ROUTE" -> {
                    intent.getStringExtra("audio")?.let { setModeQuiet(Use.AUDIO, IoMode.parse(it)) }
                    intent.getStringExtra("midi")?.let { setModeQuiet(Use.MIDI, IoMode.parse(it)) }
                    //   ... --ei aaudio 0|1   reap loop on an AAudio callback thread
                    if (intent.hasExtra("aaudio")) {
                        usbHostOnAAudio = intent.getIntExtra("aaudio", 1) != 0
                        applyUsbHost()
                        android.util.Log.i("bos-ui", "usb reap loop on AAudio: $usbHostOnAAudio")
                    }
                    //   ... --ei buffer 6   the direct-USB depth, ms
                    if (intent.hasExtra("buffer")) {
                        // ANY value here, not just the ones the UI offers: this
                        // is the debug path, for values the UI does not. Below
                        // 4 the transport raises it to 4.
                        val ms = intent.getIntExtra("buffer", UsbBuffers.DEFAULT_MS)
                        if (ms in 2..64) {
                            usbBufferMs = ms
                            android.util.Log.i("bos-ui", "usb buffer: $ms ms")
                        } else {
                            android.util.Log.i("bos-ui", "usb buffer: $ms ms REFUSED (2..64)")
                        }
                    }
                    // AAudio bursts, which is what the ALSA path's latency is
                    // made of. Scriptable so the trade against xruns can be
                    // SWEPT rather than judged by tapping a button.
                    //   ... --ei bursts 2
                    if (intent.hasExtra("bursts")) {
                        bufferBursts = intent.getIntExtra("bursts", 2).coerceIn(1, 4)
                        prefs().edit().putInt("bufferBursts", bufferBursts).apply()
                    }
                    deniedDevices.clear()
                    saveModePrefs()
                    applyRouting("broadcast")
                }
            }
        }
    }

    private fun setModeQuiet(use: Use, to: IoMode) {
        val (a, m) = ModeRule.change(audioMode, midiMode, use, to, sameDevice(resolveAudio(), resolveMidi()))
        audioMode = a
        midiMode = m
    }

    /** The ALSA path. Records which USB device it is on, for the release wait. */
    private fun startEngine() {
        val deviceId = findOutputDeviceId()
        val ok = NativeBridge.nativeStart(deviceId, bufferBursts)
        alsaAudioOn = if (ok) resolveAudio()?.usb?.deviceName else null
        if (!ok) {
            runOnUiThread {
                statusView.text = "Failed to open audio stream.\n" +
                    "Is the CP-88 connected and powered on?"
            }
        }
    }

    /**
     * The output the user picked with the Audio button, by its ALSA NAME rather
     * than by id: ids are handed out per connection, so anything remembered by
     * id is wrong the moment a cable is pulled. Null means automatic.
     */
    private var chosenOutputName: String? = null

    /** Same, for MIDI in: an ALSA port name. Deliberately independent of the audio choice. */
    private var chosenMidiName: String? = null

    /**
     * Cycles: automatic, then each output present, then back to automatic. The
     * new device is opened at ITS OWN native rate, and since the graph is
     * compiled against whatever rate the sink reports, the whole instrument
     * reconfigures itself.
     */
    private fun cycleOutput() {
        val names = listOf<String?>(null) + outputChoices().map { it.name }
        val i = names.indexOf(chosenOutputName)
        chosenOutputName = names[(if (i < 0) 0 else i + 1) % names.size]
        prefs().edit().putString("chosenOutputName", chosenOutputName).apply()
        // Reopen right away rather than waiting for the watchdog, so the button
        // does something audible when it is pressed.
        applyRouting("output change")
    }

    private fun shortName(n: String?): String {
        val s = n.orEmpty()
        return when {
            s.isEmpty() -> "auto"
            s.contains("CP88") || s.contains("CP73") ->
                if (s.contains("MIDI")) "CP-88 " + s.substringAfterLast(' ') else "CP-88"
            s.uppercase().contains("SCARLETT") -> "Scarlett"
            s.length > 14 -> s.takeLast(14)
            else -> s
        }
    }

    /**
     * The AAudio device id for the output in use, or DEVICE_UNSPECIFIED. Also
     * sets the label and asks the engine for the device's own rate and format.
     */
    private fun findOutputDeviceId(): Int {
        val choice = resolveAudio()
        val d = choice?.alsa
        audioDeviceLabel = when {
            choice == null -> "built-in (NO USB DEVICE — piano not connected?)"
            d == null -> "${choice.name}  [owned by this app -- no ALSA device]"
            chosenOutputName != null -> "${choice.name}  [chosen, ALSA]"
            choice.name.uppercase().let { it.contains("SCARLETT") || it.contains("FOCUSRITE") } ->
                "${choice.name}  [interface, ALSA]"
            choice.name.uppercase().let { it.contains("CP88") || it.contains("CP73") } ->
                "${choice.name}  [CP-88, ALSA]"
            else -> "${choice.name}  [USB, ALSA]"
        }
        NativeBridge.nativeSetPreferredRate(
            if (rateOverride > 0) rateOverride else nativeRateOf(d))
        NativeBridge.nativeSetPreferredFormat(
            if (formatOverride > 0) formatOverride else nativeFormatOf(d))
        return d?.id ?: NativeBridge.DEVICE_UNSPECIFIED
    }

    /**
     * The rate the device itself declares, or 0 for "let AAudio pick".
     *
     * This has to be asked of the DEVICE. Leaving the rate unspecified and
     * trusting what comes back was wrong: AAudio returned 48000 for the CP-88,
     * which declares 44100 and nothing else, and the stream opened happily with
     * a resampler in the HAL. Android's own policy dump shows the single
     * profile -- 44100, 24-bit packed, stereo -- and it agrees with Yamaha's
     * documentation.
     *
     * Where a device offers several rates, the highest is taken.
     */
    private fun nativeRateOf(d: AudioDeviceInfo?): Int {
        val rates = d?.sampleRates ?: return 0
        return rates.maxOrNull() ?: 0
    }

    /**
     * The widest format the device declares, as an Oboe AudioFormat value.
     *
     * Widest, not most convenient: the CP-88 declares 24-bit packed and asking
     * it for I16 had the HAL pad eight bits away. The render ring is float, so
     * handing it any of these costs one conversion in the callback and loses
     * nothing on our side. Dither is applied only for a 16-bit target, where
     * truncation would otherwise be audible as a modulating noise floor.
     *
     * If the wide format turns out not to be granted -- EXCLUSIVE/MMAP may only
     * be available at one format -- Oboe's own fallback attempt covers it, and
     * the granted format is logged on every open.
     */
    private fun nativeFormatOf(d: AudioDeviceInfo?): Int {
        // TRIED AND REJECTED, 2026-09-22: asking the built-in speaker for I16
        // in the hope that a narrower format would unlock MMAP/EXCLUSIVE. It
        // does not. I16 is granted and the stream is still SHARED, with the
        // same 192-frame burst, even when EXCLUSIVE is asked for explicitly.
        // The same code gets EXCLUSIVE on the CP-88 (sharing=0, burst 89), so
        // the request is right and this tablet's speaker simply has no
        // exclusive path. There is no permission for it either: AAudio gates
        // EXCLUSIVE on the HAL, and aaudio.mmap_exclusive_policy here is
        // already 2 (AUTO). Do not spend another evening on it.
        val enc = d?.encodings ?: return 0
        val has24 = enc.contains(android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED)
        val has32 = enc.contains(android.media.AudioFormat.ENCODING_PCM_32BIT)
        val hasFloat = enc.contains(android.media.AudioFormat.ENCODING_PCM_FLOAT)
        val has16 = enc.contains(android.media.AudioFormat.ENCODING_PCM_16BIT)
        return when {
            has24 -> 3      // oboe I24
            has32 -> 4      // oboe I32
            hasFloat -> 2   // oboe Float
            has16 -> 1      // oboe I16
            else -> 0
        }
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

    /**
     * The ALSA path for MIDI in: MidiManager and AMidi, on the port resolveMidi()
     * picked. The direct modes never come here -- route() opens those itself.
     *
     * MIDI IN IS CHOSEN INDEPENDENTLY OF THE AUDIO OUT. With a Scarlett carrying
     * the sound and the CP-88 carrying the notes, the two are different devices
     * on different cables, and tying them together -- as this did when both were
     * the piano -- means changing one silently changes the other.
     */
    private fun openMidi() {
        val manager = getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (manager == null) {
            midiView.text = "MIDI: no MidiManager"
            return
        }
        val choice = resolveMidi()
        val info = choice?.info
        if (choice == null) {
            midiView.text = "MIDI: no input device found"
            return
        }
        if (info == null) {
            // Visible over USB but not to Android: this process owns the
            // device, so there is no ALSA MIDI port to open.
            midiView.text = "MIDI: ${choice.name} -- not an ALSA device right now"
            return
        }

        manager.openDevice(info, { device ->
            if (device == null) {
                runOnUiThread { midiView.text = "MIDI: openDevice returned null" }
                return@openDevice
            }
            midiDevice = device
            val opened = NativeBridge.nativeOpenMidi(device, choice.port)
            runOnUiThread {
                midiView.text = if (opened) "MIDI: ${choice.name}  [ALSA]" else "MIDI: failed to open port"
            }
        }, handler)
    }

    private fun MidiDeviceInfo.midiName(): String =
        properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "device"

    /** Cycles MIDI in: automatic, then each port present, then back. */
    private fun cycleMidi() {
        val names = listOf<String?>(null) + midiChoices().map { it.name }
        val i = names.indexOf(chosenMidiName)
        chosenMidiName = names[(if (i < 0) 0 else i + 1) % names.size]
        prefs().edit().putString("chosenMidiName", chosenMidiName).apply()
        // Through routing whatever the mode: the new port may be on a device
        // whose mode differs, or share a device with the audio, and only the
        // router knows how to hand interfaces over between the two.
        lastMidiAttempt = 0L
        applyRouting("midi change")
    }

    // --------------------------------------------------------------------- UI

    // ------------------------------------------------------------ the new UI

    private lateinit var prototypeView: View
    private lateinit var composeView: androidx.compose.ui.platform.ComposeView
    private lateinit var appLibrary: be.mdw.bagosynths.ui.AppLibrary
    private lateinit var player: be.mdw.bagosynths.ui.SongPlayer

    /**
     * Page turners and footswitches arrive as keys (I9.2). On the new UI's
     * stage: Page Down / right / down arrow / space the next live set, Page Up /
     * left / up arrow the previous one. Configurable in the environment once
     * that view exists (S9.2).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::composeView.isInitialized && composeView.visibility == View.VISIBLE && player.onStage &&
            event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_SPACE -> {
                    player.forwardKey(); return true
                }
                android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    player.backKey(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // --------------------------------------------------- Do Not Disturb (S17.2)

    private var dndBefore: Int? = null

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

    /**
     * While the app is in front and the player asked for it: notifications and
     * calls silenced, alarms and media not (INTERRUPTION_FILTER_ALARMS). The
     * previous filter is put back when the app leaves the front.
     */
    private fun applyStageDnd(front: Boolean) {
        val nm = notificationManager()
        if (!nm.isNotificationPolicyAccessGranted) return
        if (front && prefs().getBoolean("stage.dnd", false)) {
            if (dndBefore == null) dndBefore = nm.currentInterruptionFilter
            nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS)
        } else {
            dndBefore?.let { nm.setInterruptionFilter(it) }
            dndBefore = null
        }
    }

    /**
     * The tablet's heat (REQUIREMENTS S16.2): Android's thermal status, and from
     * API 30 its headroom forecast (1.0 = throttling begins). Hot from
     * "moderate" on, which is where sustained performance starts to give.
     */
    private fun thermal(): Pair<String, Boolean> {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val status = pm.currentThermalStatus
        val name = when (status) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "cool"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "warm"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "HOT"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "VERY HOT"
            else -> "THROTTLING"
        }
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10).takeIf { !it.isNaN() } else null
        val text = "heat $name" + (headroom?.let { " (%.0f %% of the limit in 10 s)".format(it * 100) } ?: "")
        return text to (status >= android.os.PowerManager.THERMAL_STATUS_MODERATE)
    }

    private fun showNewUi(on: Boolean) {
        val was = composeView.visibility == View.VISIBLE
        composeView.visibility = if (on) View.VISIBLE else View.GONE
        prototypeView.visibility = if (on) View.GONE else View.VISIBLE
        prefs().edit().putBoolean("ui.new", on).apply()
        // The prototype knows nothing of zones and wires: hand it the rack
        // neutral, and take the song back on the way in.
        if (was && !on) runCatching { player.releaseRack() }
        if (!was && on) runCatching { player.reload() }
    }

    private val engineHost = object : be.mdw.bagosynths.ui.EngineHost {
        override fun instruments() = library.map { it.id to "${it.name} (${it.sizeLabel})" }
        override fun selectInstrument(id: String) {
            library.firstOrNull { it.id == id }?.let { selectInstrument(it) }
        }
        override fun currentInstrument() = currentInstrument?.id
        override fun showPrototype() = showNewUi(false)
        override fun healthLine(): String {
            val lines = statusView.text.toString().lines()
            val pick = lines.filter { l -> listOf("state", "path", "usb", "xruns").any { l.trimStart().startsWith(it) } }
                .joinToString("   ") { it.trim().replace(Regex("\\s+"), " ") }
            val (heat, hot) = thermal()
            val bad = hot || lines.any { it.contains("WRONG") || it.contains("failed") || it.contains("STALLED") }
            return (if (bad) "! " else "") + heat + "   " + pick
        }
        override fun statusLines() = statusView.text.toString().lines() + listOf("", thermal().first)
        override fun routing(): be.mdw.bagosynths.ui.Routing {
            val (a, m) = effectiveModes(resolveAudio(), resolveMidi())
            return be.mdw.bagosynths.ui.Routing(
                audioDevice = shortName(chosenOutputName), audioMode = a.name,
                midiDevice = shortName(chosenMidiName), midiMode = m.name,
                usbBufferMs = usbBufferMs, usbBufferChoices = UsbBuffers.choicesMs.toList())
        }
        override fun stageDnd() = prefs().getBoolean("stage.dnd", false) &&
                notificationManager().isNotificationPolicyAccessGranted
        override fun setStageDnd(on: Boolean) {
            if (on && !notificationManager().isNotificationPolicyAccessGranted) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return
            }
            prefs().edit().putBoolean("stage.dnd", on).apply()
            applyStageDnd(on)
        }
        override fun cycleAudioDevice() = cycleOutput()
        override fun cycleMidiDevice() = cycleMidi()
        override fun setAudioMode(mode: String) = setMode(Use.AUDIO, IoMode.parse(mode))
        override fun setMidiMode(mode: String) = setMode(Use.MIDI, IoMode.parse(mode))
        override fun setUsbBufferMs(ms: Int) {
            if (ms !in UsbBuffers.choicesMs || ms == usbBufferMs) return
            usbBufferMs = ms
            saveModePrefs()
            if (effectiveMode(Use.AUDIO).direct) applyRouting("buffer change")
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14161A"))
            setPadding(48, 48, 48, 48)
        }
        root.addView(Button(this).apply {
            text = "← New UI"
            setOnClickListener { showNewUi(true) }
        })

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
        root.addView(buildMasterVolume())

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }

        // Audio out and MIDI in, cycled independently. Two cables, two choices:
        // a Scarlett can carry the sound while the CP-88 carries the notes.
        //
        // TAP cycles the device; LONG-PRESS chooses the mode -- ALSA, USB
        // interface, USB device -- and, for audio, the direct-USB buffer. Hung
        // off the existing buttons so nothing in the row moves.
        audioButton = makeButton("Audio: auto") { cycleOutput(); refreshDeviceButtons() }
        audioButton.setOnLongClickListener { showModeDialog(Use.AUDIO); true }
        buttonRow.addView(audioButton)
        midiButton = makeButton("MIDI: auto") { cycleMidi(); refreshDeviceButtons() }
        midiButton.setOnLongClickListener { showModeDialog(Use.MIDI); true }
        buttonRow.addView(midiButton)

        buttonRow.addView(makeButton("Buffer: $bufferBursts") { button ->
            // AAudio bursts. The direct-USB buffer is a different thing -- the
            // URBs in flight -- and lives in the Audio button's long-press.
            if (effectiveMode(Use.AUDIO).direct) {
                android.widget.Toast.makeText(this@MainActivity,
                    "Direct USB: buffer is in the Audio long-press menu",
                    android.widget.Toast.LENGTH_SHORT).show()
                return@makeButton
            }
            bufferBursts = if (bufferBursts >= 4) 1 else bufferBursts + 1
            button.text = "Buffer: $bufferBursts"
            NativeBridge.nativeStop()
            NativeBridge.nativeStart(findOutputDeviceId(), bufferBursts)
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
                if (effectiveMode(Use.AUDIO).direct) return@setOnLongClickListener true
                wantExclusive = !wantExclusive
                NativeBridge.nativeSetExclusive(wantExclusive)
                NativeBridge.nativeStop()
                NativeBridge.nativeStart(findOutputDeviceId(), bufferBursts)
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

        // Next to Panic on purpose: both are things reached for mid-song, with
        // no time to read, and both do one thing with no options to weigh.
        buttonRow.addView(makeButton("RESET USB") {
            resetAudioPath()
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

        // Scrollable sideways, because the row is now wider than the tablet and
        // a button that has fallen off the edge is a button that does not exist.
        // Audio and MIDI sit FIRST so they are reachable without scrolling: they
        // are what gets reached for when something goes wrong mid-set.
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttonRow)
        })

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

        // The panel makes the screen taller than the tablet in landscape, and a
        // control that cannot be reached is worse than one that is not there.
        //
        // The keyboard is PINNED BELOW the scroller rather than placed inside
        // it. Inside, it sat under the drawbars and needed a scroll past every
        // panel to reach -- fine as a test fixture, useless as an instrument.
        // Pinned, it is playable whatever the rest of the screen is showing,
        // which is what makes the app demonstrable with no CP-88 attached.
        val scroller = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#14161A"))
            addView(root)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14161A"))
            // Inset for the navigation bar and the taskbar. Without this the
            // system bar sits over the BOTTOM of the keys -- which is where the
            // loudest velocities are, so the keyboard lost its forte and the
            // octave labels at the same time.
            fitsSystemWindows = true
            addView(scroller)
            addView(makeTestKeys())
        }
    }

    /**
     * The master volume: a centred fader in dB at the end of the chain.
     *
     * Centred, not a 0-to-max slider, because the instrument's own mix is the
     * reference: the control exists to cut BELOW it and to lift ABOVE it, and
     * a fader whose useful position is 80 % of the way up tells you nothing at
     * a glance. The scale is dB and therefore logarithmic in amplitude, which
     * is the only scale on which "half as loud" is a fixed distance.
     *
     * It matters more since the direct-USB modes arrived: those bypass
     * Android's stream volume entirely -- measured 2026-09-22, about 28 dB
     * louder than the ALSA path at the same settings -- so the instrument
     * needs a volume of its own rather than borrowing the system's.
     *
     * Tap the label to return to 0 dB.
     */
    private fun buildMasterVolume(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        masterLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#E6E9EF"))
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 24, 0)
            setOnClickListener { setMasterDb(0f) }
        }
        row.addView(masterLabel)

        masterBar = android.widget.SeekBar(this).apply {
            max = MASTER_STEPS
            progress = dbToProgress(masterDb)
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) setMasterDb(progressToDb(value), moveBar = false)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
            })
        }
        row.addView(masterBar)
        refreshMasterLabel()
        return row
    }

    private fun refreshMasterLabel() {
        if (!::masterLabel.isInitialized) return
        masterLabel.text = "MASTER %+5.1f dB".format(masterDb)
    }

    private fun setMasterDb(db: Float, moveBar: Boolean = true) {
        masterDb = db.coerceIn(NativeBridge.MASTER_MIN_DB, NativeBridge.MASTER_MAX_DB)
        NativeBridge.nativeSetMasterDb(masterDb)
        prefs().edit().putFloat("masterDb", masterDb).apply()
        if (moveBar && ::masterBar.isInitialized) masterBar.progress = dbToProgress(masterDb)
        refreshMasterLabel()
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

    /**
     * A playable touch keyboard, so the instrument can be demonstrated with no
     * CP-88 attached — through the tablet's own speaker if need be.
     *
     * Two octaves from C3, which is what a piano demo actually needs: one
     * octave forces everything into the right hand. Black keys are drawn as
     * narrower, darker keys in the same row rather than overlaid, because an
     * overlay needs a custom ViewGroup and hit-testing, and this has to be
     * right today rather than pretty.
     *
     * Velocity comes from WHERE the key is touched — near the top is soft, near
     * the bottom is loud, as on a real keybed where the pivot gives you less
     * leverage at the back. Without it every note is mezzo-forte and a sampled
     * piano with sixteen velocity layers sounds like a toy.
     */
    private lateinit var audioButton: Button
    private lateinit var midiButton: Button

    private fun refreshDeviceButtons() {
        // The mode in force, not merely the one stored: a use on a non-USB
        // device is ALSA whatever the setting, and the device rule applies.
        val (a, m) = effectiveModes(resolveAudio(), resolveMidi())
        if (::audioButton.isInitialized) {
            audioButton.text = "Audio: ${shortName(chosenOutputName)} · ${a.short}"
        }
        if (::midiButton.isInitialized) {
            midiButton.text = "MIDI: ${shortName(chosenMidiName)} · ${m.short}"
        }
    }


    private fun makeTestKeys(): View {
        return PianoKeyboardView(this) { note, velocity ->
            NativeBridge.nativeTestNote(note, velocity)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 360)
        }
    }

    private fun updateStats() {
        refreshDeviceButtons()
        val s = NativeBridge.stats()

        val xrunsNow = s.xRuns - xrunBaseline

        // Whether we are on the PIANO, not merely whether audio is running.
        // AAudio falls back to the built-in speaker without saying so, and that
        // failure is otherwise invisible: the meters move and it sounds fine in
        // a quiet room, while a stage hears nothing at all.
        val output = lastChosenOutputId
        val onChosen = output != NativeBridge.DEVICE_UNSPECIFIED && s.deviceId == output

        val warn = when {
            routingBusy -> "SWITCHING — ${routingStatus.ifEmpty { "routing" }}"
            s.usbActive && !s.usbRunning -> "DIRECT USB DOWN — re-routing"
            !s.running -> "STREAM DOWN"
            output == NativeBridge.DEVICE_UNSPECIFIED -> "NO USB OUTPUT — built-in speaker"
            !onChosen -> "WRONG DEVICE (${s.deviceId}) — recovering"
            // Above xruns deliberately: this one means NO SOUND AT ALL while
            // everything else on screen still looks alive, which is precisely
            // why it went undiagnosed for a whole session.
            s.audioStalled -> "AUDIO STALLED ${"%.0f".format(s.stalledSeconds)}s — replug"
            // Direct USB has no sharing mode; "not exclusive" would be a lie.
            s.usbActive -> "direct USB"
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
            val (aMode, mMode) = effectiveModes(resolveAudio(), resolveMidi())
            appendLine("midi       ${if (NativeBridge.nativeMidiOpen()) "connected" else "NOT CONNECTED"}" +
                "   [${mMode.label}]")
            appendLine("state      ${if (s.running) "running" else "stopped"}   [$warn]")
            if (s.usbActive) {
                appendLine("path       USB direct (${aMode.label})  buffer $usbBufferMs ms  " +
                    "device clock ${"%.1f".format(s.usbFeedbackRate)} Hz")
                // A hold is a short silence by design (the transport emptying
                // the device's FIFO after something it could not prove); alt
                // errors are holds that could not, and must stay 0.
                appendLine("usb        holds ${s.usbHolds}   alt errors ${s.usbAltErrors}   " +
                    "errors ${s.usbErrors}   deficit ${"%.1f".format(s.usbDeficitMs)} ms   " +
                    "max gap ${"%.1f".format(s.usbMaxGapMs)} ms")
            } else {
                appendLine("path       ${if (s.usesAAudio) "AAudio" else "OpenSLES"}  " +
                    "${s.sharingModeText}  ${s.performanceModeText}   [${aMode.label}]")
            }
            if (routingStatus.isNotEmpty()) appendLine("routing    $routingStatus")
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
