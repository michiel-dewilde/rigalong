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
    // Deliberately NOT restored from prefs. The `bursts` extra on the ROUTE
    // test hook writes the pref, which is useful for sweeping, but a
    // sweep that ended at 1 should not quietly become the setting the app boots
    // into tomorrow night. Every launch starts from the value that was measured.
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

    // ASK FOR SOMETHING THE DEVICE DOES NOT DECLARE, deliberately and only for
    // a measurement. Zero means "use what the device declares", which is the
    // shipped behaviour and the only behaviour a user ever gets.
    //
    // This exists because of what Android's policy dump says on the phone:
    // the MMAP mix port that EXCLUSIVE comes from -- "mmap_no_irq_out" -- has
    // exactly ONE profile, 48000 Hz and PCM_16_BIT, while the CP-88 port has
    // exactly one profile, 44100 Hz and PCM_24_BIT_PACKED. So exclusive on
    // this device is not merely "available at 48 kHz"; it is available ONLY
    // through a port that cannot carry the CP-88's rate OR its word length,
    // and the HAL therefore converts both. The question worth measuring is
    // what that conversion costs in latency and quality -- not whether it is
    // happening, which the dump already settles.
    //
    //   adb shell am broadcast -a com.rigalong.RATE --ei hz 48000 --ei fmt 1
    //   adb shell am broadcast -a com.rigalong.RATE --ei hz 0 --ei fmt 0
    private var rateOverride = 0
    private var formatOverride = 0

    /**
     * Brings the rig back when the CP-88 reappears, and gets it OFF the tablet
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
     *   * not running at all, and the CP-88 is available again;
     *   * running, but on something that is not the CP-88.
     *
     * The second is the dangerous one. It is silent: audio "works", the meters
     * move, and the sound is coming out of a speaker nobody on a stage can hear.
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
                    android.util.Log.e("rigalong-ui",
                        "direct USB failed $directRetries times -- falling back to ALSA. " +
                        "Last reason: $routingStatus")
                    status("direct USB failed: $routingStatus -- playing through ALSA")
                    usbHandler.post { runCatching { startEngine() } }
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

        // No CP-88. Two bad answers, and the least bad one depends on where you
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
        // is a trap anywhere the CP-88 is simply not part of the setup.
        //
        // So: fall back to the built-in speaker, but only after the CP-88 has
        // been missing for a good while, and SAY SO loudly -- findOutputDeviceId
        // already labels it "built-in (NO USB DEVICE ...)" and the status line
        // shows that label. The CP-88 is still preferred and is taken back the
        // moment it reappears, which is the branch above.
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
     * Every output, in the order Setup's "next device" cycles: what AudioManager lists
     * (USB devices and the tablet's own speaker), plus USB devices this process
     * owns whole -- they have no ALSA card while it does, so AudioManager no
     * longer lists them, but they are still the instrument's output.
     */
    private fun outputChoices(): List<OutputChoice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
        val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
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
     * monitoring path and a keyboard is just a controller -- then a device a
     * driver knows (model/devices: a CP-88), then any other USB device. Null:
     * the caller decides about the speaker.
     */
    private fun resolveAudio(): OutputChoice? {
        val c = outputChoices()
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
        val seen = prefs.getStringSet("devices.seen", emptySet())!!
        val now = midiChoices().map { it.card }.filter { Devices.recognise(it) != null && it !in seen }.toSet()
        if (now.isEmpty()) return
        for (card in now) onRecognised(card)
        prefs.edit().putStringSet("devices.seen", seen + now).apply()
    }

    private fun resolveMidi(): MidiChoice? {
        val c = midiChoices()
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
    // Recovery is therefore MANUAL and certain: the RESET test hook, which
    // calls resetDirectUsb(), which is proved. (Since the fbx transport holds
    // rather than latches, nothing on the screen offers it any more.)
    //
    // To reinstate one, something must first be found that says FOR SURE that a
    // latch is present or must have occurred. HANDOFF section 21 lists what has
    // already been ruled out, so that work does not start from zero.

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
        fun seen(): Boolean {
            val d = s.device
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audio = !UsbSession.hasAudioOut(d) || am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { isUsbType(it) && usbFor(it, listOf(d)) != null }
            val midi = !UsbSession.hasMidi(d) || midiChoices().any { it.info != null && it.usb?.deviceName == d.deviceName }
            return audio && midi
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - t0 < timeoutMs) {
            if (seen()) {
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
     * Recovery from THE LATCH.
     *
     * The fault: in a direct USB mode the CP-88 goes silent while the transport
     * keeps running -- URBs are acknowledged, the feedback endpoint still
     * reports its rate, frames keep counting -- and nothing in the app can tell
     * "presenting" from "acknowledging and discarding". There is no detector
     * yet, so there is no automatic recovery either. This is what the RESET
     * test hook does when it happens.
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
     * So RESET does the right thing for whichever path is live:
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
        val names = listOf<String?>(null) + outputChoices().map { it.name }
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

    private fun MidiDeviceInfo.midiName(): String =
        properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "device"

    /** Cycles MIDI in: automatic, then each port present, then back. */
    fun cycleMidi() {
        val names = listOf<String?>(null) + midiChoices().map { it.name }
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
        (outputChoices().map { it.name } + midiChoices().map { it.name }).toSet()

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
     * REINIT: the cheap recovery candidate.
     *
     * Michiel: "if routing to alsa and back fixes it, surely you can somehow
     * send some sequence to reinit the stream without closing?" Routing to
     * ALSA releases the interfaces, lets snd-usb-audio rebind and
     * re-SET_INTERFACE them, then takes them back -- about 2,7 s and a full
     * teardown. The USB-level part of that is just the UAC teardown: drop the
     * streaming interfaces to alt 0, the zero-bandwidth setting, and put them
     * back. usbfs can do that WITHOUT releasing anything.
     *
     * The recovery ladder, measured against a deterministic latch (ZLP).
     * Level 1 is the UAC teardown and is known NOT to be enough; the higher
     * rungs are what a route to ALSA and back does on the way past: level 2
     * clears the endpoints' halt, level 3 hands the interfaces back so the
     * kernel driver rebinds, then takes them again -- the ALSA round-trip
     * minus ALSA actually streaming.
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
        val hit = outputChoices().firstOrNull { it.name.contains(want, true) }
        if (want.isEmpty()) {
            chosenOutputName = null
        } else if (hit == null) {
            android.util.Log.i("rigalong-ui", "outputs: " + outputChoices().joinToString { it.name })
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
