package com.rigalong

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rigalong.model.Library
import com.rigalong.play.Diagnostics
import com.rigalong.play.EngineHost
import com.rigalong.play.RoutingSpec
import com.rigalong.play.SongPlayer

/**
 * Everything that lives as long as the instrument plays: one per process,
 * NOT one per activity. The engine is a process singleton that plays on
 * behind [EngineService] while no activity shows it, so what drives it has to
 * outlive activities too:
 *
 *  * [AudioRouter]: audio out and MIDI in, through Android or direct USB, and
 *    the watchdog that brings them back;
 *  * [EngineContent]: what the engines play from on this device;
 *  * [AudioStatus]: what the audio path says about itself;
 *  * [SongPlayer] and the [Library]: the setlist on the rack;
 *  * [TestHooks]: the scriptable broadcasts.
 *
 * An activity [get]s it and shows it. A second activity in the same process
 * -- Android may make one after the task was swiped away, or after it
 * destroyed the first to save memory -- finds the same rig running, instead
 * of bringing everything up again over an engine that never stopped (seen on
 * the tablet on 2026-11-11: the second bring-up could claim no interface the
 * first still held, MIDI in read "Device or resource busy" a hundred times a
 * second, and the sound fell back to the tablet's speaker).
 *
 * Main thread, like the activity.
 */
class Rig private constructor(private val app: Context) {

    val prefs = app.getSharedPreferences("rigalong", Context.MODE_PRIVATE)!!
    val router = AudioRouter(app, prefs)
    val content = EngineContent(app, prefs)
    val status = AudioStatus(app, router, content)
    val library = Library(java.io.File(app.filesDir, "library"), Build.MODEL)
    val host: EngineHost = Host()
    val player = SongPlayer(library, host, prefs)
    private val hooks = TestHooks(app, router, content, player, measure = ::measureLatency, setMasterDb = ::setMasterDb)
    private val handler = Handler(Looper.getMainLooper())

    /** The activity showing the rig, while there is one: for what only an activity can do (ask for a permission, finish). */
    var activity: MainActivity? = null

    /** [end] was called: this rig plays no more, and whoever still holds it stops watching it. */
    var ended = false
        private set

    // ------------------------------------------------------- master volume

    /**
     * The master volume in dB, 0 = unity: at the end of the chain, so it cuts
     * below the instrument's own mix and lifts above it. The direct-USB modes
     * bypass Android's stream volume entirely (measured 2026-09-22: about 28 dB
     * louder than the ALSA path at the same settings), so the instrument needs
     * a volume of its own. Half-decibel steps on the stage's fader.
     */
    private var masterDb = prefs.getFloat("masterDb", 0f).coerceIn(NativeBridge.MASTER_MIN_DB, NativeBridge.MASTER_MAX_DB)

    private fun setMasterDb(db: Float) {
        masterDb = db.coerceIn(NativeBridge.MASTER_MIN_DB, NativeBridge.MASTER_MAX_DB)
        NativeBridge.nativeSetMasterDb(masterDb)
        prefs.edit().putFloat("masterDb", masterDb).apply()
    }

    init {
        router.onDirectChosen = ::askRecordForUsbAlways
        router.onRecognised = { card -> player.devices.recognised(card) }
        val diagnostics = Diagnostics(app)
        player.diagnostics = diagnostics
        diagnostics.event("app started")
        // A crash is the one event a player cannot describe afterwards: its
        // stack goes into the diagnostic log before Android ends the process.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { diagnostics.event("CRASH on ${t.name}: ${android.util.Log.getStackTraceString(e)}") }
            previousHandler?.uncaughtException(t, e)
        }
        hooks.register()
    }

    /**
     * Brought up once: a resume is usually a return to something already
     * running, and re-running the bring-up would reload sfizz and re-scan
     * 641 Surge patches underneath audio that never stopped. Later calls only
     * check on MIDI.
     */
    fun bringUp() {
        if (router.started) {
            router.checkMidi()
            return
        }
        NativeBridge.nativeSetMasterDb(masterDb)
        router.start()
        content.start()
        // Once the engine has its nodes: the demo setlist on first run (S14.2),
        // then back to where the last session was (S13.2).
        handler.postDelayed({
            if (ended) return@postDelayed
            runCatching {
                library.seedIfEmpty(NativeBridge.nativeSetBfreePresetNames().toList(), firstKeys(), firstSurgePatch())
                player.resume()
            }.onFailure { android.util.Log.e("rigalong-ui", "start: $it") }
        }, 2500)
    }

    /**
     * What the first setlist's keyboard sound is on (Library.seedIfEmpty): sfizz
     * where an instrument is installed; where there is only the app's own test
     * tone, Dexed on an electric piano of its built-in banks, which needs
     * nothing installed.
     */
    private fun firstKeys(): Pair<com.rigalong.model.RackEngine, String?> {
        if (content.library.any { it.id != "test-saw" }) return com.rigalong.model.RackEngine.SFIZZ to null
        val voices = runCatching {
            (0 until NativeBridge.nativeDexedVoiceCount()).map { NativeBridge.nativeDexedVoiceName(it) }
        }.getOrDefault(emptyList())
        val voice = voices.firstOrNull { it.substringAfter('/').startsWith("E.PIANO") }
            ?: voices.firstOrNull { it.contains("PIANO", ignoreCase = true) } ?: voices.firstOrNull()
        return com.rigalong.model.RackEngine.DEXED to voice
    }

    /** A patch of Surge XT's factory set for the first setlist, if Surge has it by then; else what Surge has. */
    private fun firstSurgePatch(): String? = runCatching {
        (0 until NativeBridge.nativeSurgePatchCount()).map { NativeBridge.nativeSurgePatchName(it) }
            .firstOrNull { it == "Keys/EP 1" }
    }.getOrNull()

    /**
     * The instrument is closed -- the activity finished, or Stop on the
     * notification: both uses down, every direct-USB interface back to the
     * kernel, the service gone. The next start makes a new rig.
     */
    fun end() {
        if (ended) return
        ended = true
        if (current === this) current = null
        handler.removeCallbacksAndMessages(null)
        hooks.unregister()
        // While MIDI out is still there: the devices back on their own sounds,
        // so a CP-88 is a piano again when the instrument is closed.
        runCatching {
            player.devices.release()
            player.devices.settle()
        }
        router.detach()
        router.shutdown()
        EngineService.stop(app)
        activity?.let { if (!it.isFinishing) it.finishAndRemoveTask() }
        activity = null
    }

    // ------------------------------------- the microphone: MEASURE, and "Always"

    private fun granted(permission: String) =
        app.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * The notification is where the instrument's one Stop button is, and what
     * says in the shade that RigAlong is holding the keyboard. On Android 13
     * and later it only shows with a permission; asked once, the first time
     * the app is in front. (It used to be asked together with the microphone,
     * the first time Measure was used: until then there was no Stop.)
     */
    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (granted(android.Manifest.permission.POST_NOTIFICATIONS)) return
        if (prefs.getBoolean("notifications.asked", false)) return
        val a = activity ?: return
        prefs.edit().putBoolean("notifications.asked", true).apply()
        android.widget.Toast.makeText(app, R.string.notification_why, android.widget.Toast.LENGTH_LONG).show()
        a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), MainActivity.REQUEST_NOTIFICATIONS)
    }

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
        // POST_NOTIFICATIONS is asked for alongside, on API 33+: the foreground
        // service runs either way, but its notification carries the only Stop
        // button the instrument has.
        val wanted = mutableListOf<String>()
        if (!granted(android.Manifest.permission.RECORD_AUDIO)) wanted += android.Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(android.Manifest.permission.POST_NOTIFICATIONS)) {
            wanted += android.Manifest.permission.POST_NOTIFICATIONS
        }
        if (granted(android.Manifest.permission.RECORD_AUDIO)) openMicrophone()
        if (wanted.isNotEmpty()) activity?.requestPermissions(wanted.toTypedArray(), MainActivity.REQUEST_MEASURE)
    }

    /**
     * The probe's input on the tablet's own microphone -- the only input
     * usable while the CP-88 plays. Explicitly, because "unspecified" is not
     * good enough: with the CP-88 attached, Android makes the CP-88 the
     * default input, and its capture and playback are mutually exclusive on
     * Android's USB audio HAL (and it does not loop host playback back:
     * measured 2026-09-25).
     */
    fun openMicrophone() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mic = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        NativeBridge.nativeEnableInput(mic?.id ?: NativeBridge.DEVICE_UNSPECIFIED)
    }

    /**
     * Choosing a direct-USB mode asks once for the microphone. Android lets an
     * app become the one that opens a USB device ("Always") only if it may
     * record when the device has audio input, as the CP88/CP73 does; without it
     * "Always" is greyed out and every replug asks again. Asked once, when the
     * player opts in, never at start-up.
     */
    private fun askRecordForUsbAlways() {
        if (granted(android.Manifest.permission.RECORD_AUDIO)) return
        val a = activity ?: return
        if (prefs.getBoolean("usb.askedRecord", false)) return
        prefs.edit().putBoolean("usb.askedRecord", true).apply()
        android.widget.Toast.makeText(app, R.string.usb_record_why, android.widget.Toast.LENGTH_LONG).show()
        a.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MainActivity.REQUEST_USB_ALWAYS)
    }

    // --------------------------------------------------- Do Not Disturb (S17.2)

    private var dndBefore: Int? = null

    private fun notificationManager() = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

    /**
     * While the app is in front and the player asked for it: notifications and
     * calls silenced, alarms and media not (INTERRUPTION_FILTER_ALARMS). The
     * previous filter is put back when the app leaves the front.
     */
    fun applyStageDnd(front: Boolean) {
        val nm = notificationManager()
        if (!nm.isNotificationPolicyAccessGranted) return
        if (front && prefs.getBoolean("stage.dnd", false)) {
            if (dndBefore == null) dndBefore = nm.currentInterruptionFilter
            nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS)
        } else {
            dndBefore?.let { nm.setInterruptionFilter(it) }
            dndBefore = null
        }
    }

    // ------------------------------------------------------ what the screen gets

    private inner class Host : EngineHost {
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
        override fun measureLatency() = this@Rig.measureLatency()

        override fun routing() = router.routing()
        override fun routingSpec() = router.routingSpec()
        override fun applyRoutingSpec(spec: RoutingSpec) = router.applyRoutingSpec(spec)
        override fun availableDevices(): Set<String> = router.availableDevices()
        override fun cycleAudioDevice() = router.cycleOutput()
        override fun cycleMidiDevice() = router.cycleMidi()
        override fun directOffer(): String? =
            router.directOffer?.let { com.rigalong.model.devices.Devices.recognise(it)?.label ?: it }
        override fun answerDirectOffer(use: Boolean) = router.answerDirectOffer(use)
        override fun setAudioMode(mode: String) = router.setMode(Use.AUDIO, IoMode.parse(mode))
        override fun setMidiMode(mode: String) = router.setMode(Use.MIDI, IoMode.parse(mode))
        override fun setUsbBufferMs(ms: Int) = router.setUsbBufferMs(ms)

        override fun masterDb() = masterDb
        override fun setMasterDb(db: Float) = this@Rig.setMasterDb(db)
        override fun stageDnd() = prefs.getBoolean("stage.dnd", false) && notificationManager().isNotificationPolicyAccessGranted
        override fun setStageDnd(on: Boolean) {
            if (on && !notificationManager().isNotificationPolicyAccessGranted) {
                val settings = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                activity?.startActivity(settings)
                return
            }
            prefs.edit().putBoolean("stage.dnd", on).apply()
            applyStageDnd(on)
        }
    }

    companion object {
        private var current: Rig? = null

        /** The process's rig, made on first use. */
        fun get(context: Context): Rig = current ?: Rig(context.applicationContext).also { current = it }

        /** The rig if one is playing: for the notification's Stop, which must not make one. */
        fun running(): Rig? = current
    }
}
