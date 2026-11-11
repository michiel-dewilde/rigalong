package com.rigalong

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.rigalong.model.Library
import com.rigalong.play.SongPlayer

/**
 * The one activity. It puts the parts together and gives them what only an
 * activity has -- the screen, the lifecycle, permission requests, keys:
 *
 *  * [AudioRouter]: audio out and MIDI in, through Android or direct USB, and
 *    the watchdog that brings them back;
 *  * [EngineContent]: what the engines play from on this device;
 *  * [AudioStatus]: what the audio path says about itself;
 *  * [SongPlayer]: the setlist on the rack -- with the library, the model of
 *    everything the screen (ui/AppUi.kt) shows;
 *  * [TestHooks]: the scriptable broadcasts.
 *
 * The screen reaches the first three through [com.rigalong.play.EngineHost].
 */
class MainActivity : androidx.activity.ComponentActivity() {

    private lateinit var router: AudioRouter
    private lateinit var content: EngineContent
    private lateinit var status: AudioStatus
    private lateinit var hooks: TestHooks
    private lateinit var appLibrary: Library
    private lateinit var player: SongPlayer

    private val handler = Handler(Looper.getMainLooper())
    private var pollTick = 0

    /** In front: the watchdog, newly seen devices, and the status, four times a second. */
    private val poller = object : Runnable {
        override fun run() {
            // BEFORE the status, because it is what resolves the output's current
            // device id and the status reports against that. The other way
            // round, every reading would describe the previous one.
            router.watch()
            router.recogniseDevices()
            status.update()
            if (++pollTick % 4 == 0) router.watchUsbAltSetting()
            handler.postDelayed(this, 250)
        }
    }

    /**
     * What keeps watch while the activity is NOT in front.
     *
     * Since the instrument keeps playing when backgrounded (see
     * [EngineService]), it also has to keep RECOVERING when backgrounded. It
     * matters because of the division of labour in a recovery. The native
     * retry re-opens the device id it remembers, which catches a cable that was
     * merely nudged; only the Kotlin side can ask AudioManager for the CP-88
     * BY NAME, which is the only thing that works after a real re-enumeration,
     * because that hands out a new id and makes every remembered one useless.
     * Without this, a replug behind a dialog left a silent instrument that
     * believed it was playing.
     *
     * Once a second rather than four times, and no status work at all: nothing
     * is on screen to update. It also keeps [NativeBridge.stats] being called,
     * which is what emits the health heartbeat in the log.
     */
    private val backgroundPoller = object : Runnable {
        override fun run() {
            router.watch()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = prefs()
        masterDb = prefs.getFloat("masterDb", 0f).coerceIn(NativeBridge.MASTER_MIN_DB, NativeBridge.MASTER_MAX_DB)
        router = AudioRouter(this, prefs)
        router.onDirectChosen = ::askRecordForUsbAlways
        router.onRecognised = { card -> player.devices.recognised(card) }
        content = EngineContent(this, prefs)
        status = AudioStatus(this, router, content)
        appLibrary = Library(java.io.File(filesDir, "library"), Build.MODEL)
        player = SongPlayer(appLibrary, engineHost, prefs)
        val diagnostics = com.rigalong.play.Diagnostics(this)
        player.diagnostics = diagnostics
        diagnostics.event("app started")
        // A crash is the one event a player cannot describe afterwards: its
        // stack goes into the diagnostic log before Android ends the process.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { diagnostics.event("CRASH on ${t.name}: ${android.util.Log.getStackTraceString(e)}") }
            previousHandler?.uncaughtException(t, e)
        }
        setContentView(androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { com.rigalong.ui.AppUi(player, appLibrary, engineHost) }
        })
        // A STRAY BACK GESTURE MUST NOT END THE INSTRUMENT MID-SONG (found the
        // hard way on 2026-10-11): Back goes to the stage, and on the stage it
        // does nothing.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!player.onStage) player.backToStage?.invoke()
            }
        })
        // Once the engine has its nodes: the demo setlist on first run (S14.2),
        // then back to where the last session was (S13.2).
        handler.postDelayed({
            runCatching {
                appLibrary.seedIfEmpty(NativeBridge.nativeSetBfreePresetNames().toList())
                player.resume()
            }.onFailure { android.util.Log.e("rigalong-ui", "start: $it") }
        }, 2500)

        hooks = TestHooks(this, router, content, player, measure = ::measureLatency, setMasterDb = ::setMasterDb)
        hooks.register()
    }

    /**
     * Launched again by USB_DEVICE_ATTACHED (the manifest's filter, which is
     * what lets "always open with RigAlong" make a replug permission-free).
     * The instrument is singleTask, so this is the running one: re-route, never
     * restart.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) router.deviceAttached()
    }

    override fun onResume() {
        super.onResume()
        applyStageDnd(true)

        // Before anything else, and while this activity is genuinely in front:
        // starting a foreground service from the background is refused on
        // Android 12 and later, so this is the only moment it is legal.
        EngineService.start(this)
        handler.removeCallbacks(backgroundPoller)

        // Brought up once: since the instrument keeps playing while the
        // activity is away, a resume is usually a return to something already
        // running, and re-running the bring-up would reload sfizz and re-scan
        // 641 Surge patches underneath audio that never stopped.
        if (!router.started) {
            NativeBridge.nativeSetMasterDb(masterDb)
            router.start()
            content.start()
        } else {
            router.checkMidi()
        }
        handler.post(poller)
    }

    override fun onPause() {
        super.onPause()
        applyStageDnd(false)
        handler.removeCallbacks(poller)
        // Hand over to the cut-down watchdog. The instrument keeps playing, so
        // it has to keep recovering too.
        handler.post(backgroundPoller)

        // THE AUDIO AND MIDI DELIBERATELY STAY UP.
        //
        // Stopping them here is correct behaviour for an app and wrong
        // behaviour for an instrument: it meant anything that backgrounded the
        // activity killed the sound. Measured 2026-09-20 -- a press of the
        // power button stopped audio outright, and it stayed stopped until the
        // app was brought back to the front.
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
        hooks.unregister()
        router.detach()
        super.onDestroy()
        // Unconditionally, and BEFORE the isFinishing guard: both runnables hold
        // a reference to this activity, and one that outlived it would keep the
        // whole view hierarchy alive and go on poking a dead UI.
        handler.removeCallbacksAndMessages(null)

        // isFinishing distinguishes the user closing the instrument from a
        // recreation. The manifest absorbs every configuration change through
        // configChanges, so this should not fire otherwise -- but tearing the
        // stream down on one would be a very confusing bug to chase, and the
        // guard costs one line.
        if (!isFinishing) return
        router.shutdown()
        EngineService.stop(this)
    }

    private fun prefs() = getSharedPreferences("rigalong", Context.MODE_PRIVATE)

    // ------------------------------------------------------- master volume

    /**
     * The master volume in dB, 0 = unity: at the end of the chain, so it cuts
     * below the instrument's own mix and lifts above it. The direct-USB modes
     * bypass Android's stream volume entirely (measured 2026-09-22: about 28 dB
     * louder than the ALSA path at the same settings), so the instrument needs
     * a volume of its own. Half-decibel steps on the stage's fader.
     */
    private var masterDb = 0f

    private fun setMasterDb(db: Float) {
        masterDb = db.coerceIn(NativeBridge.MASTER_MIN_DB, NativeBridge.MASTER_MAX_DB)
        NativeBridge.nativeSetMasterDb(masterDb)
        prefs().edit().putFloat("masterDb", masterDb).apply()
    }

    // ------------------------------------- the microphone: MEASURE, and "Always"

    /** MEASURE (S20.3): the input first, armed once it has settled. */
    private fun measureLatency() {
        // A capture stream that has just been opened delivers zeros for the
        // best part of a second, and the probe gives up after half a second of
        // frames -- so arming in the same breath measures the warm-up, not the
        // room.
        enableInputIfPermitted()
        handler.postDelayed({ NativeBridge.nativeStartLatencyProbe() }, 1200)
    }

    /**
     * Opens the probe's input stream, only when a measurement needs it: held
     * for the whole session it was a second EXCLUSIVE MMAP stream -- on the
     * built-in mic, at 48 kHz, while the output runs at 44100 -- kept alive for
     * a measurement taken perhaps three times in a session.
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

        if (granted) NativeBridge.nativeEnableInput(findBuiltinMicDeviceId())
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_MEASURE)
    }

    /**
     * The tablet's own microphone -- the only input usable while the CP-88
     * plays. Explicitly, because "unspecified" is not good enough: with the
     * CP-88 attached, Android makes the CP-88 the default input, and its
     * capture and playback are mutually exclusive on Android's USB audio HAL
     * (and it does not loop host playback back: measured 2026-09-25).
     */
    private fun findBuiltinMicDeviceId(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mic = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return mic?.id ?: NativeBridge.DEVICE_UNSPECIFIED
    }

    /**
     * Choosing a direct-USB mode asks once for the microphone. Android lets an
     * app become the one that opens a USB device ("Always") only if it may
     * record when the device has audio input, as the CP88/CP73 does; without it
     * "Always" is greyed out and every replug asks again. Asked once, when the
     * player opts in, never at start-up.
     */
    private fun askRecordForUsbAlways() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val p = prefs()
        if (p.getBoolean("usb.askedRecord", false)) return
        p.edit().putBoolean("usb.askedRecord", true).apply()
        android.widget.Toast.makeText(this, R.string.usb_record_why, android.widget.Toast.LENGTH_LONG).show()
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_USB_ALWAYS)
    }

    @Deprecated("ComponentActivity's own callback; this app asks with the platform API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // By NAME rather than by position: the request carries either or both of
        // RECORD_AUDIO and POST_NOTIFICATIONS. Only the measurement's request
        // opens the input: the one for USB "Always" does not.
        permissions.forEachIndexed { i, permission ->
            val ok = grantResults.getOrNull(i) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok && permission == android.Manifest.permission.RECORD_AUDIO && requestCode == REQUEST_MEASURE) {
                NativeBridge.nativeEnableInput(findBuiltinMicDeviceId())
            }
        }
    }

    // ------------------------------------------------------------------ keys

    /**
     * Page turners and footswitches arrive as keys (I9.2). On the stage:
     * Page Down / right / down arrow / space the next live set, Page Up /
     * left / up arrow the previous one.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::player.isInitialized && player.onStage && event.action == android.view.KeyEvent.ACTION_DOWN) {
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

    // ------------------------------------------------------ what the screen gets

    private val engineHost = object : com.rigalong.play.EngineHost {
        override fun instruments() = content.library.map { it.id to "${it.name} (${it.sizeLabel})" }
        override fun selectInstrument(id: String) = content.select(id)
        override fun instrumentPath(id: String): String? = content.path(id)
        override fun currentInstrument() = content.currentInstrument?.id
        override fun importInstrumentZip(input: java.io.InputStream, fileName: String) = content.importInstrumentZip(input, fileName)
        override fun installEngineFile(kind: com.rigalong.model.EngineFiles.Kind, fileName: String, bytes: ByteArray) =
            content.installEngineFile(kind, fileName, bytes)
        override fun exportPatch(patch: com.rigalong.model.Patch, name: String) = content.exportPatch(patch, name)

        override fun healthLine(): String = status.stageHealth()
        override fun healthDetail(): String = status.healthDetail()
        override fun statusLines(): List<String> = status.lines
        override fun resetCounts() = status.resetCounts()
        override fun measureLatency() = this@MainActivity.measureLatency()

        override fun routing() = router.routing()
        override fun routingSpec() = router.routingSpec()
        override fun applyRoutingSpec(spec: com.rigalong.play.RoutingSpec) = router.applyRoutingSpec(spec)
        override fun availableDevices(): Set<String> = router.availableDevices()
        override fun cycleAudioDevice() = router.cycleOutput()
        override fun cycleMidiDevice() = router.cycleMidi()
        override fun setAudioMode(mode: String) = router.setMode(Use.AUDIO, IoMode.parse(mode))
        override fun setMidiMode(mode: String) = router.setMode(Use.MIDI, IoMode.parse(mode))
        override fun setUsbBufferMs(ms: Int) = router.setUsbBufferMs(ms)

        override fun masterDb() = masterDb
        override fun setMasterDb(db: Float) = this@MainActivity.setMasterDb(db)
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
    }

    private companion object {
        /** Permission requests: the measurement's opens the microphone when granted, the one for USB "Always" does not. */
        const val REQUEST_MEASURE = 1
        const val REQUEST_USB_ALWAYS = 2
    }
}
