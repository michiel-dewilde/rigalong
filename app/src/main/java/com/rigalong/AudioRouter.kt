package com.rigalong

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rigalong.DeviceLists.MidiChoice
import com.rigalong.DeviceLists.OutputChoice
import com.rigalong.model.devices.Devices

/**
 * Where the sound goes and the notes come from: which device carries audio
 * out and MIDI in, each through Android (ALSA) or driven directly over USB
 * ([IoMode]), the switch between them ([route]), and the watchdog that brings
 * either back after a cable was pulled ([watch]).
 *
 * Touched from the main thread, except what says "usb thread".
 */
class AudioRouter(private val context: Context, private val prefs: SharedPreferences) {

    private companion object {
        /** The USB permission answer. Explicit to this package; see requestUsbPermission. */
        const val ACTION_USB_PERMISSION = "com.rigalong.USB_PERMISSION"
    }

    /** A direct-USB mode was chosen: the moment to ask for what "Always" needs (the activity's to ask). */
    var onDirectChosen: () -> Unit = {}

    /** A device one of the drivers knows was seen for the first time ever: its ALSA card name. */
    var onRecognised: (String) -> Unit = {}

    private val handler = Handler(Looper.getMainLooper())

    private fun runOnUiThread(body: () -> Unit) { handler.post(body) }

    /** What became of MIDI in the last time it was opened: the port, or why not. */
    @Volatile var midiStatus = ""
        private set

    /** All 0 when the engine is healthy. See watchAudioDevice(). */
    private var deviceLostSince = 0L

    /**
     * How long the CP-88 must be missing before the instrument falls back to
     * the tablet's own speaker. Long enough that a jogged cable re-enumerating
     * is recovered onto the CP-88 instead, short enough that a demo with no
     * CP-88 in the room is playable without anyone waiting.
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
     * The CP-88's device id as of the last watchdog pass.
     *
     * Cached so the status line can say whether the stream is on it without
     * asking AudioManager a second time every frame — and because a replug
     * changes the id, so a value read once at startup would be a lie.
     */
    var lastChosenOutputId = NativeBridge.DEVICE_UNSPECIFIED
        private set

    private var midiDevice: MidiDevice? = null
    // 2 bursts: the last setting that held under load (3 -> 12 ms and 2 -> 8 ms
    // without an xrun, 1 -> 4 ms with one; FINDINGS 9c). NOT restored from
    // prefs: a sweep of the ROUTE hook that ended at 1 must not become what
    // the app starts with tomorrow night.
    private var bufferBursts = 2

    /**
     * Which audio device we actually opened. Worth displaying prominently: the
     * failure mode that wastes the most time is silently landing on the built-in
     * speaker and wondering why the latency is terrible.
     */
    var audioDeviceLabel = "—"
        private set

    /**
     * Whether the engine has been brought up in this process. Not the same
     * question as whether the activity is resumed: since the instrument now
     * keeps playing while the activity is away (see [EngineService]), a resume
     * is usually a return to something already running, and re-running the whole
     * bring-up would reload sfizz and re-scan 641 Surge patches underneath audio
     * that never stopped.
     */
    var started = false
        private set

    /** EXCLUSIVE by default; the SHARING test hook switches. */
    private var wantExclusive = true

    // A rate and format the device does NOT declare, for a measurement only
    // (the RATE hook; FINDINGS 9c). Zero: what the device declares, which is
    // all a player ever gets.
    private var rateOverride = 0
    private var formatOverride = 0

    /**
     * Brings the rig back when the CP-88 reappears, and gets it OFF the tablet
     * speaker if it ever lands there. Two failures to notice, not one:
     *   * not running at all, and the CP-88 is available again;
     *   * running, but on something that is not the CP-88 -- the dangerous one:
     *     AAudio answers a device id that is gone with the built-in speaker
     *     and OK, the meters move, and nobody on a stage hears it.
     * Only this side can ask which device the CP-88 is today; a replug gives
     * it a new id (FINDINGS 9c).
     */
    fun watch() {
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

            // A retry is for something transient. After kDirectRetries the
            // mode stays as the player chose it, but the ALSA engine is
            // started: sound with a complaint on the screen beats silence with
            // a correct setting (the "connect/disconnect loop", FINDINGS 9c).
            if (directRetries >= kDirectRetries) {
                if (!alsaFallbackOn) {
                    alsaFallbackOn = true
                    android.util.Log.e("rigalong-ui",
                        "direct USB failed $directRetries times -- falling back to ALSA. " +
                        "Last reason: $routingStatus")
                    status("direct USB failed: $routingStatus -- playing through ALSA")
                    usbHandler.post {
                        // Said where the player looks: the line above claims ALSA plays.
                        runCatching { startEngine() }.onFailure {
                            android.util.Log.e("rigalong-ui", "ALSA fallback did not start: $it")
                            status("direct USB failed, and ALSA did not start either: ${it.message ?: it}")
                        }
                    }
                }
                return
            }
            lastRestartAttempt = now
            directRetries += 1
            android.util.Log.i("rigalong-ui",
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

        // No CP-88: the built-in speaker, but only after it has been missing
        // a good while, and labelled so. Refusing the speaker left the app
        // silent and stuck after a pulled cable (FINDINGS 9c). The CP-88 is
        // taken back the moment it reappears, above.
        if (!haveOutput) {
            if (now - deviceLostSince < SPEAKER_FALLBACK_AFTER_MS) return
            if (stats.running && stats.deviceId != NativeBridge.DEVICE_UNSPECIFIED &&
                stats.deviceId != 0) {
                // Already playing somewhere that is not the CP-88, which after
                // this fallback means the speaker. Leave it alone.
                return
            }
            NativeBridge.nativeStop()
            if (NativeBridge.nativeStart(NativeBridge.DEVICE_UNSPECIFIED, bufferBursts)) {
                android.util.Log.i("rigalong-ui",
                    "no USB output for ${SPEAKER_FALLBACK_AFTER_MS} ms -- fell back to the " +
                        "built-in speaker so the instrument still plays")
            }
            return
        }

        // Stop first, unconditionally. `start()` returns true immediately when it
        // thinks it is already running, so a stream sitting on the wrong device
        // would otherwise never be replaced.
        NativeBridge.nativeStop()
        if (NativeBridge.nativeStart(output, bufferBursts)) {
            android.util.Log.i("rigalong-ui", "audio restarted on $audioDeviceLabel (device $output)")
            deviceLostSince = 0L
            // Only if MIDI is actually down. It used to be reopened
            // unconditionally, which was harmless while both directions were the
            // same cable and is wrong now that they need not be: restarting
            // audio on a Scarlett would drop the notes coming from the CP-88.
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
    var usbBufferMs = UsbBuffers.DEFAULT_MS
        private set

    /**
     * Routing runs here, never on the main thread: entering a direct mode can
     * mean waiting seconds for the kernel -- and the DSP behind it -- to let go
     * of an interface, and that wait must not freeze the UI.
     */
    private val usbThread = android.os.HandlerThread("usb-routing")
    private lateinit var usbHandler: Handler
    @Volatile var routingBusy = false
        private set
    @Volatile private var routingPending = false
    @Volatile var routingStatus = ""
        private set

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

    /**
     * The USB devices, by ALSA card name, that the player chose a direct route
     * FOR. The direct modes are one setting, but they apply to a device only
     * if RigAlong knows it (a driver in model/devices: the CP88/CP73) or the
     * player chose them with that device in front of them. Anything else
     * plays through Android, and so is never asked a USB permission for
     * (Michiel, 2026-11-12: with the CP-88 unplugged, the setting had moved on
     * to the dock's own sound card and raised a permission dialog for it).
     */
    private val directFor = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun directAllowed(card: String) = Devices.recognise(card) != null || card in directFor

    /** The devices in use now are ones the player chose a direct route for: for each use whose mode is direct. */
    private fun chooseDirectForDevicesInUse() {
        if (audioMode.direct) resolveAudio()?.takeIf { it.usb != null }?.let { directFor += it.name }
        if (midiMode.direct) resolveMidi()?.takeIf { it.usb != null }?.let { directFor += it.card }
        prefs.edit().putStringSet("directFor", HashSet(directFor)).apply()
    }
    @Volatile private var permissionPendingFor: String? = null

    private fun usbManager() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun loadPrefs() {
        val p = prefs
        chosenOutputName = p.getString("chosenOutputName", null)
        chosenMidiName = p.getString("chosenMidiName", null)
        audioMode = IoMode.parse(p.getString("audioMode", null))
        midiMode = IoMode.parse(p.getString("midiMode", null))
        usbBufferMs = p.getInt("usbBufferMs", UsbBuffers.DEFAULT_MS)
            .takeIf { it in UsbBuffers.choicesMs } ?: UsbBuffers.DEFAULT_MS
        directFor.clear()
        directFor += p.getStringSet("directFor", emptySet())!!
    }

    private fun saveModePrefs() {
        prefs.edit()
            .putString("audioMode", audioMode.name)
            .putString("midiMode", midiMode.name)
            .putInt("usbBufferMs", usbBufferMs)
            .apply()
        syncUsbAttach()
    }

    /**
     * The USB attach filter (the manifest's [UsbAttach]) on only while a
     * direct-USB mode is chosen. Off, plugging in a CP88/CP73 raises no
     * "Open RigAlong?" dialog, and the app never asks for USB permission:
     * ALSA needs none. Choosing a direct mode is the opt-in to both. On, it
     * still starts nothing: see [UsbAttach].
     */
    fun syncUsbAttach() {
        val on = audioMode.direct || midiMode.direct
        val state = if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val alias = android.content.ComponentName(context, "${context.packageName}.UsbAttach")
        runCatching {
            if (context.packageManager.getComponentEnabledSetting(alias) != state)
                context.packageManager.setComponentEnabledSetting(alias, state, android.content.pm.PackageManager.DONT_KILL_APP)
        }.onFailure { android.util.Log.w("rigalong-ui", "usb attach filter: ${it.message}") }
    }

    // --------------------------------------------------------------- choices

    /** What is plugged in (DeviceLists.kt): the outputs and MIDI inputs to choose from. */
    private val devices = DeviceLists(context) { sessionPortNames }

    /**
     * The output in use. The user's choice wins; otherwise the interim order,
     * hardcoded until the graph editor makes the output a property of the
     * patch: an audio interface (Scarlett) -- where one is present it is the
     * monitoring path and a keyboard is just a controller -- then a device a
     * driver knows (model/devices: a CP-88), then any other USB device. Null:
     * the caller decides about the speaker.
     */
    private fun resolveAudio(): OutputChoice? {
        val c = devices.outputs()
        chosenOutputName?.let { want -> c.firstOrNull { it.name == want }?.let { return it } }
        fun named(vararg keys: String) = c.firstOrNull { ch ->
            ch.usb != null && keys.any { ch.name.uppercase().contains(it) }
        }
        return named("SCARLETT", "FOCUSRITE") ?: c.firstOrNull { it.usb != null && Devices.recognise(it.name) != null }
            ?: c.firstOrNull { it.usb != null }
    }

    /**
     * A USB device one of the drivers knows (model/devices), plugged in for the
     * first time ever: added to the devices with its driver's defaults. Once
     * only, so a device the player removed stays removed.
     */
    fun recogniseDevices() {
        val cards = devices.midiInputs().map { it.card }.filter { Devices.recognise(it) != null }.toSet()
        if (cards.isEmpty()) return
        val seen = prefs.getStringSet("devices.seen", emptySet())!!
        val now = cards - seen
        if (now.isNotEmpty()) {
            for (card in now) onRecognised(card)
            prefs.edit().putStringSet("devices.seen", seen + now).apply()
        }
        offerDirect(cards)
    }

    /**
     * A keyboard RigAlong has a USB audio profile for (the CP88/CP73), there
     * while nothing is driven directly, and not asked about yet: its card
     * name, for the app to offer the direct route -- once per keyboard. Out of
     * the box the sound goes through Android, and for the CP-88 that path
     * stopped after a quarter of an hour when it was measured; nothing told
     * the player there was a better one. The offer is the app's own question:
     * Android's permission dialogs come only after a yes.
     */
    @Volatile var directOffer: String? = null
        private set

    private fun offerDirect(cards: Set<String>) {
        if (directOffer != null) return
        val offered = prefs.getStringSet("direct.offered", emptySet())!!
        val fresh = cards.filter { it !in offered && Devices.recognise(it)?.usbAudio != null }
        if (fresh.isEmpty()) return
        if (audioMode.direct || midiMode.direct) {
            // Driven directly already (chosen in Setup): nothing to ask.
            prefs.edit().putStringSet("direct.offered", offered + fresh).apply()
            return
        }
        directOffer = fresh.first()
    }

    /** The player's answer to [directOffer]: the direct route for that keyboard's audio and MIDI, or as it is. Not asked again. */
    fun answerDirectOffer(use: Boolean) {
        val card = directOffer ?: return
        directOffer = null
        prefs.edit().putStringSet("direct.offered", prefs.getStringSet("direct.offered", emptySet())!! + card).apply()
        android.util.Log.i("rigalong-ui", "direct USB offered for $card: ${if (use) "taken" else "not now"}")
        if (!use) return
        if (resolveAudio()?.name == card) setMode(Use.AUDIO, IoMode.USB_DEVICE)
        if (resolveMidi()?.card == card && !midiMode.direct) setMode(Use.MIDI, IoMode.USB_DEVICE)
    }

    private fun resolveMidi(): MidiChoice? {
        val c = devices.midiInputs()
        chosenMidiName?.let { want -> c.firstOrNull { it.name == want }?.let { return it } }
        return c.firstOrNull { ch -> ch.port == 0 && Devices.recognise(ch.card) != null } ?: c.firstOrNull()
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
            a?.usb != null && a.usb.deviceName !in deniedDevices && (!audioMode.direct || directAllowed(a.name)) -> audioMode
            else -> IoMode.ALSA
        }
        val mm = when {
            midiMode.off -> IoMode.NONE
            m?.usb != null && m.usb.deviceName !in deniedDevices && (!midiMode.direct || directAllowed(m.card)) -> midiMode
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
    fun applyRouting(reason: String, fresh: Boolean = true) {
        if (!::usbHandler.isInitialized || closed) return
        if (fresh) { directRetries = 0; alsaFallbackOn = false }
        if (routingBusy) { routingPending = true; return }
        routingBusy = true
        usbHandler.post {
            try {
                route(reason)
            } catch (e: Exception) {
                android.util.Log.e("rigalong-ui", "routing failed", e)
                status("routing failed: ${e.message}")
            } finally {
                routingBusy = false
                if (routingPending && !closed) {
                    routingPending = false
                    // Not fresh: this is the tail of a routing already asked
                    // for, and whoever asked has already cleared the state.
                    handler.post { applyRouting("pending change", fresh = false) }
                }
            }
        }
    }

    // --- NO LATCH WATCHDOG. Deliberately. -------------------------------------
    // A latched CP-88 cannot be told from a healthy one over the wire, and a
    // recovery that fires on holes instead is a dropout generator. Michiel's
    // rule: no watchdog that cannot conclusively detect the latch over the
    // wire. Recovery is manual and certain: RESET, resetDirectUsb(). What was
    // measured and ruled out: FINDINGS 9c, HANDOFF section 21.

    private var lastAltReport: String? = null
    private var altReads = 0

    /**
     * Asks the DEVICE, once a second, what altsetting its streaming interfaces
     * are on: GET_INTERFACE is the one question that goes to the device and
     * comes back with an answer, and a change is logged. A hypothesis under
     * test, not a settled diagnostic (FINDINGS 9c). On the usb thread.
     */
    fun watchUsbAltSetting() {
        if (!::usbHandler.isInitialized || routingBusy) return
        usbHandler.post {
            val s = directAudioOn?.let { sessions[it] } ?: return@post
            if (!s.isOpen) return@post
            val plan = s.plan ?: return@post
            val report = plan.streamingInterfaces.joinToString(" ") { "$it=${s.altSetting(it)}" }
            // Every 30th read as well as on change: "no log" has to mean
            // "still being asked and still the same", not "the probe died".
            if (report != lastAltReport || altReads % 30 == 0) {
                android.util.Log.i("rigalong-usb", "altsetting: $report")
                lastAltReport = report
            }
            ++altReads
        }
    }

    private fun status(s: String) {
        routingStatus = s
        if (s.isNotEmpty()) android.util.Log.i("rigalong-ui", "routing: $s")
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
        android.util.Log.i("rigalong-ui", "route ($reason): audio=${audio?.name ?: "speaker"}" +
            "${if (chosenOutputName == null) " (auto)" else ""} " +
            "${aMode.label}, midi=${midi?.name ?: "none"}${if (chosenMidiName == null) " (auto)" else ""} " +
            "${mMode.label}, buffer=$usbBufferMs ms")
        if (closed) return

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
        if (closed) return
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
            if (closed) return
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
                android.util.Log.i("rigalong-ui", "claimed; device reports interface $i at alt ${s.altSetting(i)}")
            }
        }
        sessionPortNames = sessions.mapValues { it.value.midiPortNames }

        // 5. Audio, then MIDI.
        if (closed) return
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
            midiStatus = if (err == null) "${midi.name}  [${mMode.label}]" else err
        } else if (mMode.off) {
            // nativeCloseMidi() and the device close have already run.
            midiStatus = "none"
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
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
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
                android.util.Log.i("rigalong-ui", "\"${s.cardName}\" did not reappear -- USB reset")
                s.reset()
            }
        }
        android.util.Log.e("rigalong-ui", "\"${s.cardName}\" still not visible to Android after a reset")
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
        val t0 = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - t0 < timeoutMs) {
            if (devices.androidSees(s.device)) {
                android.util.Log.i("rigalong-ui", "\"${s.cardName}\" is back with Android after " +
                    "${android.os.SystemClock.uptimeMillis() - t0} ms")
                return true
            }
            status("handing \"${s.cardName}\" back to Android")
            android.os.SystemClock.sleep(200)
        }
        return false
    }

    /**
     * How long the ALSA recycle stays silent: past AudioFlinger's two second
     * standby delay, or the USB substream is never torn down and the frame
     * budget is not reset. The silence is the whole mechanism (FINDINGS 9c).
     */
    private val kAlsaRecycleGapMs = 4000L

    /**
     * The ALSA path halts at a frame budget (13-14 minutes, reproducible to
     * 0,04 %), not a fault; a teardown plus silence resets it. Taken before
     * the budget runs out it prevents the halt. The gap is audible
     * (FINDINGS 9c).
     */
    private fun recycleAlsaOutput() {
        if (!::usbHandler.isInitialized) return
        usbHandler.post {
            android.util.Log.i("rigalong-ui", "alsa recycle: releasing the output stream")
            status("recycle: silent for ${kAlsaRecycleGapMs / 1000} s -- resetting the ADSP budget")
            NativeBridge.nativeStop()
            android.os.SystemClock.sleep(kAlsaRecycleGapMs)
            runOnUiThread {
                NativeBridge.nativeStart(findOutputDeviceId(), bufferBursts)
                NativeBridge.nativeResetPeaks()
                status("recycle: done")
                android.util.Log.i("rigalong-ui", "alsa recycle: output restarted")
            }
        }
    }

    /** RESET USB, dispatched to whatever the audio path actually is. */
    fun resetAudioPath() {
        val mode = effectiveMode(Use.AUDIO)
        when {
            mode.direct -> resetDirectUsb()
            mode.off -> status("audio is None: nothing to reset")
            else -> recycleAlsaOutput()
        }
    }

    /**
     * Recovery from THE LATCH (the RESET hook), in a direct USB mode:
     *   1. stop both sinks and give every interface back to the kernel;
     *   2. USB reset -- every driver on the device unbound and rebound: the
     *      one step that clears it ("only rung 3 worked");
     *   3. wait until Android can see the card again;
     *   4. route the device to ALSA and hold, so a stream really opens on it
     *      (kept: never tested apart from the reset; about two seconds);
     *   5. take it back into the direct mode it was in.
     * The ladder this came from, and what it ruled out: FINDINGS 9c.
     */
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
            android.util.Log.i("rigalong-ui", "RESET on \"$card\" (claimed ${s.claimedInterfaces()})")
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
            android.util.Log.i("rigalong-ui", "reset: USB reset of \"$card\"")
            if (!s.reset()) android.util.Log.e("rigalong-ui", "reset: USB reset of \"$card\" FAILED")

            val back = awaitAndroidSees(s)
            android.util.Log.i("rigalong-ui", "reset: \"$card\" " +
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
            context, 0, android.content.Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (started) applyRouting("device attached")
            }
        }
    }

    /** Every direct interface back to the kernel. On the way out of the app. */
    private fun closeAllSessions() {
        for (s in sessions.values) runCatching { s.close() }
        sessions.clear()
    }

    fun setMode(use: Use, to: IoMode) {
        val same = sameDevice(resolveAudio(), resolveMidi())
        val (a, m) = ModeRule.change(audioMode, midiMode, use, to, same)
        audioMode = a
        midiMode = m
        deniedDevices.clear()
        // Chosen with these devices in front of the player: theirs to be driven
        // directly. Sent back to Android's path, a device is no longer one.
        if (to == IoMode.ALSA) {
            if (use == Use.AUDIO) resolveAudio()?.let { directFor -= it.name } else resolveMidi()?.let { directFor -= it.card }
        }
        chooseDirectForDevicesInUse()
        saveModePrefs()
        android.util.Log.i("rigalong-ui", "mode: audio=${a.label} midi=${m.label} (same device: $same)")
        if (a.direct || m.direct) onDirectChosen()
        applyRouting("mode change")
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
        if (!ok) status("could not open the audio stream: is the USB audio device connected and powered on?")
    }

    /**
     * The output the player picked in Setup, by its ALSA NAME rather
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
    fun cycleOutput() {
        val names = listOf<String?>(null) + devices.outputs().map { it.name }
        val i = names.indexOf(chosenOutputName)
        chosenOutputName = names[(if (i < 0) 0 else i + 1) % names.size]
        prefs.edit().putString("chosenOutputName", chosenOutputName).apply()
        // Reopen right away rather than waiting for the watchdog, so the choice
        // is heard at once.
        applyRouting("output change")
    }

    private fun shortName(n: String?): String {
        val s = n.orEmpty()
        return when {
            s.isEmpty() -> "auto"
            // A device a driver knows goes by the driver's name for it ("CP-88", "CP-88 1" for its MIDI port).
            Devices.recognise(s) != null -> Devices.recognise(s)!!.defaultName.let { n ->
                if (s.contains("MIDI")) n + " " + s.substringAfterLast(' ') else n
            }
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
            choice == null -> "built-in (NO USB DEVICE — keyboard not connected?)"
            d == null -> "${choice.name}  [owned by this app -- no ALSA device]"
            chosenOutputName != null -> "${choice.name}  [chosen, ALSA]"
            choice.name.uppercase().let { it.contains("SCARLETT") || it.contains("FOCUSRITE") } ->
                "${choice.name}  [interface, ALSA]"
            Devices.recognise(choice.name) != null ->
                "${choice.name}  [${Devices.recognise(choice.name)!!.defaultName}, ALSA]"
            else -> "${choice.name}  [USB, ALSA]"
        }
        NativeBridge.nativeSetPreferredRate(
            if (rateOverride > 0) rateOverride else devices.rateOf(d))
        NativeBridge.nativeSetPreferredFormat(
            if (formatOverride > 0) formatOverride else devices.formatOf(d))
        return d?.id ?: NativeBridge.DEVICE_UNSPECIFIED
    }

    // ------------------------------------------------------------------- MIDI

    /**
     * The ALSA path for MIDI in: MidiManager and AMidi, on the port resolveMidi()
     * picked. The direct modes never come here -- route() opens those itself.
     *
     * MIDI IN IS CHOSEN INDEPENDENTLY OF THE AUDIO OUT. With a Scarlett carrying
     * the sound and the CP-88 carrying the notes, the two are different devices
     * on different cables, and tying them together -- as this did when both were
     * the CP-88 -- means changing one silently changes the other.
     */
    private fun openMidi() {
        val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (manager == null) {
            midiStatus = "no MidiManager"
            return
        }
        val choice = resolveMidi()
        val info = choice?.info
        if (choice == null) {
            midiStatus = "no input device found"
            return
        }
        if (info == null) {
            // Visible over USB but not to Android: this process owns the
            // device, so there is no ALSA MIDI port to open.
            midiStatus = "${choice.name} -- not an ALSA device right now"
            return
        }

        manager.openDevice(info, { device ->
            if (device == null) {
                midiStatus = "openDevice returned null"
                return@openDevice
            }
            midiDevice = device
            val opened = NativeBridge.nativeOpenMidi(device, choice.port)
            midiStatus = if (opened) "${choice.name}  [ALSA]" else "failed to open port"
        }, handler)
    }

    /** Cycles MIDI in: automatic, then each port present, then back. */
    fun cycleMidi() {
        val names = listOf<String?>(null) + devices.midiInputs().map { it.name }
        val i = names.indexOf(chosenMidiName)
        chosenMidiName = names[(if (i < 0) 0 else i + 1) % names.size]
        prefs.edit().putString("chosenMidiName", chosenMidiName).apply()
        // Through routing whatever the mode: the new port may be on a device
        // whose mode differs, or share a device with the audio, and only the
        // router knows how to hand interfaces over between the two.
        lastMidiAttempt = 0L
        applyRouting("midi change")
    }

    // ------------------------------------------------------ bring-up and end

    init {
        loadPrefs()
        syncUsbAttach()
        usbThread.start()
        usbHandler = Handler(usbThread.looper)
        // USB permission answers, and devices coming and going. Not exported:
        // the permission broadcast is aimed at this package explicitly.
        val usbFilter = android.content.IntentFilter(ACTION_USB_PERMISSION)
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, usbFilter)
        }
    }

    /**
     * Brings audio out and MIDI in up, once per process.
     *
     * Output FIRST and always. Established by experiment 2026-09-19: the
     * CP-88's capture and playback are mutually exclusive on Android's USB
     * audio HAL. Whichever direction opens first wins, and the other fails
     * with ErrorDisconnected. Opening capture first silently costs you the
     * instrument, which is the worst possible failure.
     *
     * The ALSA path starts synchronously, as it always has. A direct-USB
     * start is asynchronous -- it may have to wait for the kernel to let go of
     * the interface -- so the graph is built up front for the instrument
     * loaders that follow, and routing finishes on its own thread.
     */
    fun start() {
        if (effectiveMode(Use.AUDIO) == IoMode.ALSA && effectiveMode(Use.MIDI) == IoMode.ALSA) {
            startEngine()
            openMidi()
        } else {
            NativeBridge.nativePrepareGraph()
            applyRouting("start")
        }
        started = true
    }

    /**
     * Back in front, the engine having played on meanwhile. The one thing
     * worth checking is MIDI, because the port can be lost to a cable event
     * without the audio stream noticing.
     */
    fun checkMidi() {
        if (NativeBridge.nativeMidiOpen() || routingBusy) return
        if (effectiveMode(Use.MIDI).direct) applyRouting("midi lost") else openMidi()
    }

    /** A CP88/CP73 was attached while the rig runs ([UsbAttach], after Android's grant): re-route. */
    fun deviceAttached() { if (started) applyRouting("device attached") }

    /** The owner is going away: no more USB broadcasts. */
    fun detach() { runCatching { context.unregisterReceiver(usbReceiver) } }

    /** [shutdown] was asked for: a route in flight stops at its next step, and none starts. */
    @Volatile private var closed = false

    /**
     * The instrument is closed: both uses down, and every direct-USB interface
     * back to the kernel, or the device would stay invisible to Android until
     * it was replugged.
     *
     * The sessions belong to the routing thread, so they are closed THERE,
     * behind whatever route is in flight (which stops at its next step). They
     * used to be closed from here under it: on 2026-11-12 a route died on a
     * session that had just been taken away, and one that had been past its
     * claims would have started audio on a rig that had ended. This waits for
     * the routing thread, but not for ever: a route can be seconds into its
     * wait for the kernel to let go of an interface, and the thread still does
     * the closing when that returns.
     */
    fun shutdown() {
        closed = true
        started = false
        // The sound off at once, whatever the routing thread is doing.
        NativeBridge.nativeCloseMidi()
        midiDevice?.close()
        midiDevice = null
        NativeBridge.nativeStop()
        val done = java.util.concurrent.CountDownLatch(1)
        usbHandler.post {
            // Again: a route that was in its last step has started them since.
            NativeBridge.nativeCloseMidi()
            NativeBridge.nativeStop()
            closeAllSessions()
            done.countDown()
        }
        usbThread.quitSafely()
        if (!done.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
            android.util.Log.w("rigalong-ui", "shutdown: the routing thread is still busy; it closes the USB sessions when it returns")
        }
    }

    // ------------------------------------------------------------- for Setup

    /** The modes in force for the devices in use: (audio, MIDI). */
    fun modesInForce(): Pair<IoMode, IoMode> = effectiveModes(resolveAudio(), resolveMidi())

    /** The audio output as the player chose it, short ("CP-88", "auto"). */
    fun audioName(): String = shortName(chosenOutputName)

    fun routing(): com.rigalong.play.Routing {
        val midi = resolveMidi()
        val (a, m) = effectiveModes(resolveAudio(), midi)
        return com.rigalong.play.Routing(
            audioDevice = shortName(chosenOutputName), audioMode = a.name,
            midiDevice = shortName(chosenMidiName), midiMode = m.name,
            usbBufferMs = usbBufferMs, usbBufferChoices = UsbBuffers.choicesMs.toList(),
            midiIn = midi != null && !m.off)
    }

    fun routingSpec() = com.rigalong.play.RoutingSpec(
        chosenOutputName, audioMode.name, chosenMidiName, midiMode.name, usbBufferMs)

    fun applyRoutingSpec(spec: com.rigalong.play.RoutingSpec) {
        chosenOutputName = spec.audioName
        chosenMidiName = spec.midiName
        audioMode = IoMode.parse(spec.audioMode)
        midiMode = IoMode.parse(spec.midiMode)
        if (spec.bufferMs in UsbBuffers.choicesMs) usbBufferMs = spec.bufferMs
        prefs.edit().putString("chosenOutputName", chosenOutputName)
            .putString("chosenMidiName", chosenMidiName).apply()
        // An environment is the player's own rig, saved: its devices are chosen.
        chooseDirectForDevicesInUse()
        saveModePrefs()
        deniedDevices.clear()
        if (audioMode.direct || midiMode.direct) onDirectChosen()
        applyRouting("environment")
    }

    /** The names of every audio output and MIDI port present now (S3.10). */
    fun availableDevices(): Set<String> =
        (devices.outputs().map { it.name } + devices.midiInputs().map { it.name }).toSet()

    fun setUsbBufferMs(ms: Int) {
        if (ms !in UsbBuffers.choicesMs || ms == usbBufferMs) return
        usbBufferMs = ms
        saveModePrefs()
        if (effectiveMode(Use.AUDIO).direct) applyRouting("buffer change")
    }

    // ------------------------------------------- for the test hooks (TestHooks)

    /**
     * DEVHEALTH: asks the device itself, over the control pipe, in the hope
     * that something in the CLASS protocol can see a latch that the data path
     * cannot.
     */
    fun debugDeviceHealth() {
        usbHandler.post {
            val s = directAudioOn?.let { sessions[it] }
            if (s == null || !s.isOpen) {
                android.util.Log.i("rigalong-usb", "device health: no direct session")
            } else {
                NativeBridge.nativeUsbHealthProbe(s.fd, s.raw)
            }
        }
    }

    /**
     * REINIT: the cheap recovery candidates, without releasing the device.
     * Level 1 the UAC teardown (alt 0 and back; known NOT to be enough), 2
     * also clears the endpoints' halt, 3 hands the interfaces back so the
     * kernel driver rebinds, then takes them again (FINDINGS 9c).
     */
    fun debugReinit(level: Int) {
        usbHandler.post {
            val s = directAudioOn?.let { sessions[it] }
            if (s == null || !s.isOpen) {
                android.util.Log.i("rigalong-ui", "reinit: no direct session")
                return@post
            }
            val t0 = android.os.SystemClock.uptimeMillis()
            NativeBridge.nativeStopUsbAudio(s.fd, s.raw)
            if (level >= 2) NativeBridge.nativeUsbClearHalt(s.fd, s.raw)
            if (level >= 3) {
                val claimed = s.claimedInterfaces()
                s.release(claimed)
                android.os.SystemClock.sleep(300)
                s.claim(claimed)
            }
            val err = NativeBridge.nativeStartUsbAudio(s.fd, s.raw, usbBufferMs, 0)
            val ms = android.os.SystemClock.uptimeMillis() - t0
            android.util.Log.i("rigalong-ui",
                "reinit level $level in $ms ms" + (err?.let { " FAILED: $it" } ?: ""))
        }
    }

    /** The ALSA stream again, as the settings now are (a test changed one). */
    private fun reopenAlsa() {
        if (effectiveMode(Use.AUDIO).direct) return
        NativeBridge.nativeStop()
        NativeBridge.nativeStart(findOutputDeviceId(), bufferBursts)
        NativeBridge.nativeResetPeaks()
    }

    /** SHARING: EXCLUSIVE/MMAP or SHARED for the ALSA stream. */
    fun debugSharing(exclusive: Boolean) {
        wantExclusive = exclusive
        NativeBridge.nativeSetExclusive(wantExclusive)
        reopenAlsa()
        android.util.Log.i("rigalong-ui", "sharing: ${if (wantExclusive) "EXCLUSIVE" else "SHARED"}")
    }

    /** RATE: ask for what the MMAP port wants, not what the device declares (0: the device's own). */
    fun debugRate(hz: Int, format: Int) {
        rateOverride = hz
        formatOverride = format
        reopenAlsa()
        android.util.Log.i("rigalong-ui", "rate override: " +
            (if (rateOverride > 0) "$rateOverride Hz" else "device's own") +
            ", format " +
            (if (formatOverride > 0) "$formatOverride" else "device's own"))
    }

    /** OUTPUT: the output device by (part of) its name; empty is automatic. */
    fun debugOutput(want: String) {
        val hit = devices.outputs().firstOrNull { it.name.contains(want, true) }
        if (want.isEmpty()) {
            chosenOutputName = null
        } else if (hit == null) {
            android.util.Log.i("rigalong-ui", "outputs: " + devices.outputs().joinToString { it.name })
            return
        } else {
            chosenOutputName = hit.name
        }
        prefs.edit().putString("chosenOutputName", chosenOutputName).apply()
        applyRouting("output broadcast")
    }

    /**
     * ROUTE: the modes ([audio], [midi]: IoMode names), whether the reap loop
     * borrows an AAudio callback thread ([aaudio]), the direct-USB depth in ms
     * ([buffer]: ANY value from 2 to 64, not just the ones Setup offers; below
     * 4 the transport raises it to 4) and the ALSA path's AAudio bursts.
     */
    fun debugRoute(audio: String?, midi: String?, aaudio: Boolean?, buffer: Int?, bursts: Int?) {
        audio?.let { setModeQuiet(Use.AUDIO, IoMode.parse(it)) }
        midi?.let { setModeQuiet(Use.MIDI, IoMode.parse(it)) }
        if (aaudio != null) {
            usbHostOnAAudio = aaudio
            applyUsbHost()
            android.util.Log.i("rigalong-ui", "usb reap loop on AAudio: $usbHostOnAAudio")
        }
        if (buffer != null) {
            if (buffer in 2..64) {
                usbBufferMs = buffer
                android.util.Log.i("rigalong-ui", "usb buffer: $buffer ms")
            } else {
                android.util.Log.i("rigalong-ui", "usb buffer: $buffer ms REFUSED (2..64)")
            }
        }
        if (bursts != null) {
            bufferBursts = bursts.coerceIn(1, 4)
            prefs.edit().putInt("bufferBursts", bufferBursts).apply()
        }
        deniedDevices.clear()
        chooseDirectForDevicesInUse()
        saveModePrefs()
        applyRouting("broadcast")
    }
}
