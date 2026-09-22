package be.mdw.bagosynths

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * How one use of a device -- audio out, or MIDI in -- reaches it.
 *
 * ALSA: through Android's own stack. snd-usb-audio owns the device; audio goes
 * through AAudio and, on this tablet, Qualcomm's DSP offload; MIDI through
 * MidiManager. What the instrument always did.
 *
 * USB INTERFACE: this process drives the interfaces that use needs -- audio's
 * streaming interfaces, or MIDI's streaming interface -- and leaves the rest
 * of the device to the kernel. Measured caveat for audio: the ALSA card
 * survives (snd-usb-audio keeps AudioControl), so Android still lists the
 * device as an output, and any sound it routes there -- the lock sound, a
 * notification -- reconfigures the interface under the app and kills the
 * stream. The dialogs say so.
 *
 * USB DEVICE: this process owns every interface. The ALSA card disappears, so
 * nothing else can route anything to the device. Measured immune to the
 * system-sound interference. Because it owns everything, it applies to every
 * use of that device at once: see [ModeRule].
 */
enum class IoMode(val label: String, val short: String) {
    ALSA("ALSA", "ALSA"),
    USB_INTERFACE("USB interface", "USB if"),
    USB_DEVICE("USB device", "USB dev");

    val direct get() = this != ALSA

    companion object {
        fun parse(s: String?): IoMode = values().firstOrNull { it.name == s } ?: ALSA
    }
}

enum class Use { AUDIO, MIDI }

/**
 * The consistency rule between the uses of ONE physical device.
 *
 *   * Moving any use of a device to USB device moves every use of it there:
 *     the app then owns every interface, so no use can still be ALSA or be
 *     "just its own interface".
 *   * Moving a use AWAY from USB device moves every other use of that device
 *     to USB interface: the device is no longer owned whole, but the others
 *     were direct and stay direct, on their own interfaces.
 *
 * Uses on different devices are independent. Pure, so it can be read -- and
 * reasoned about -- in one place.
 */
object ModeRule {
    fun change(audio: IoMode, midi: IoMode, use: Use, to: IoMode,
               sameDevice: Boolean): Pair<IoMode, IoMode> {
        val from = if (use == Use.AUDIO) audio else midi
        var a = if (use == Use.AUDIO) to else audio
        var m = if (use == Use.MIDI) to else midi
        if (sameDevice) {
            when {
                to == IoMode.USB_DEVICE -> { a = IoMode.USB_DEVICE; m = IoMode.USB_DEVICE }
                from == IoMode.USB_DEVICE -> {
                    if (use == Use.AUDIO) m = IoMode.USB_INTERFACE else a = IoMode.USB_INTERFACE
                }
            }
        }
        return a to m
    }

    /**
     * For when the SELECTION changes rather than the mode: picking the CP-88
     * for MIDI while its audio is in USB device mode has to put the MIDI there
     * too, or the choice cannot be honoured -- the kernel's MIDI device is gone.
     */
    fun normalize(audio: IoMode, midi: IoMode, sameDevice: Boolean): Pair<IoMode, IoMode> {
        if (!sameDevice) return audio to midi
        return if (audio == IoMode.USB_DEVICE || midi == IoMode.USB_DEVICE)
            IoMode.USB_DEVICE to IoMode.USB_DEVICE
        else audio to midi
    }
}

/**
 * The direct-mode buffer choices, in milliseconds of transport slack.
 *
 * 4 ms since 2026-09-23, down from 8. This is the latency the direct-USB path
 * is actually made of -- unlike the AAudio buffer it is ours end to end, so it
 * is the one knob here worth the smallest value that survives.
 *
 * SAY THE UNCOMFORTABLE PART: 4 ms is also one of the two standing suspects for
 * THE LATCH -- the fault where the CP-88 keeps acknowledging URBs while going
 * silent, and only an ALSA round trip brings it back. The run that latched in
 * ~25 s was at 4 ms; the clean 37-minute run the same day was at 8 ms. That was
 * never a controlled comparison (the latched run was being PLAYED, the clean one
 * was silent), and the other suspect -- the bare multiply that made muting a
 * sounding instrument a step discontinuity -- has since been fixed by
 * SmoothedGain's 15 ms ramp.
 *
 * So this default is deliberately the experiment: if part switches at 4 ms stop
 * latching, the click was the cause and the buffer was innocent. If they latch
 * again, the buffer is implicated and 8 is one tap away. Do not quietly restore
 * 8 to make the symptom go away without writing down which of the two it was.
 */
object UsbBuffers {
    val choicesMs = intArrayOf(4, 8, 16)
    const val DEFAULT_MS = 4
}

/**
 * One physical USB device, opened once, shared by every direct use of it.
 *
 * Holds the connection and the set of interfaces this process has claimed.
 * Claims are made with `force = true`, which is what detaches snd-usb-audio.
 * Releases hand each interface back to the kernel explicitly -- measured on this
 * tablet, snd-usb-audio does NOT rebind by itself after a release; the driver
 * link just goes empty, and the device would stay invisible to Android.
 */
class UsbSession(private val um: UsbManager, val device: UsbDevice) {

    var connection: UsbDeviceConnection? = null
        private set
    var raw: ByteArray = ByteArray(0)
        private set
    var plan: NativeBridge.UsbPlan? = null
        private set
    private val claimed = sortedSetOf<Int>()

    val fd get() = connection?.fileDescriptor ?: -1
    val isOpen get() = connection != null
    fun claimedInterfaces(): Set<Int> = claimed.toSet()

    /** The ALSA card name, whatever mode is in use. */
    val cardName: String by lazy { alsaCardName(device) }

    /** MIDI port names, ALSA-style, fetching jack strings once opened. */
    var midiPortNames: List<String> = emptyList()
        private set

    fun open(): String? {
        if (connection != null) return null
        val c = um.openDevice(device) ?: return "openDevice failed (no permission?)"
        connection = c
        raw = c.rawDescriptors ?: ByteArray(0)
        val p = NativeBridge.UsbPlan(NativeBridge.nativeUsbPlan(raw, true))
        plan = p
        midiPortNames = (0 until p.midiPortCount).map { i ->
            val s = p.portStringIndex.getOrElse(i) { 0 }
            val jack = if (s != 0) readString(s) else null
            if (jack != null) JackNames.remember(device, i, jack)
            NativeBridge.nativeAlsaMidiPortName(cardName, jack, i)
        }
        Log.i(TAG, "opened ${device.deviceName} \"$cardName\": audio=${p.audioOk} " +
            "midi=${p.midiOk}${if (p.midiUmp) " (UMP)" else ""} " +
            "audioIf=${p.audioInterfaces} midiIf=${p.midiInterfaces} all=${p.allInterfaces}")
        return null
    }

    private fun iface(id: Int): UsbInterface? {
        var any: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val f = device.getInterface(i)
            if (f.id != id) continue
            if (f.alternateSetting == 0) return f
            if (any == null) any = f
        }
        return any
    }

    /** Claims whatever in [ids] is not claimed yet. Null on success. */
    fun claim(ids: Collection<Int>): String? {
        val c = connection ?: return "not open"
        for (id in ids.sorted()) {
            if (id in claimed) continue
            val f = iface(id) ?: return "interface $id not found"
            if (!c.claimInterface(f, true)) return "claimInterface($id) refused"
            claimed += id
            Log.i(TAG, "claimed interface $id of \"$cardName\"")
        }
        return null
    }

    /** Releases [ids] and hands each back to its kernel driver. */
    fun release(ids: Collection<Int>) {
        val c = connection ?: return
        for (id in ids.sortedDescending()) {
            if (id !in claimed) continue
            iface(id)?.let { c.releaseInterface(it) }
            claimed -= id
            // Descending, so AudioControl (0) is reattached LAST: snd-usb-audio
            // creates its card when it probes interface 0, and by then the
            // streaming and MIDI interfaces are free for it to take as well.
            NativeBridge.nativeUsbReconnectKernel(fd, id)
        }
    }

    /** A USB reset: drivers are unbound and rebound. The last resort back to ALSA. */
    fun reset(): Boolean = fd >= 0 && NativeBridge.nativeUsbReset(fd)

    fun close() {
        release(claimed.toList())
        connection?.close()
        connection = null
    }

    fun altSetting(iface: Int): Int = if (fd >= 0) NativeBridge.nativeUsbAltSetting(fd, iface) else -1

    private fun readString(index: Int): String? {
        val c = connection ?: return null
        val buf = ByteArray(255)
        // GET_DESCRIPTOR(STRING) in US English, as snd-usb-audio's usb_string() asks.
        val n = c.controlTransfer(0x80, 0x06, 0x0300 or index, 0x0409, buf, buf.size, 500)
        if (n < 2) return null
        val len = minOf(n, buf[0].toInt() and 0xFF)
        return String(buf, 2, (len - 2).coerceAtLeast(0), Charsets.UTF_16LE).trim().ifEmpty { null }
    }

    companion object {
        const val TAG = "bos-usb"

        fun alsaCardName(d: UsbDevice): String =
            NativeBridge.nativeAlsaCardName(d.productName, d.vendorId, d.productId)

        /** Whether the device has an audio streaming interface with an iso OUT endpoint. */
        fun hasAudioOut(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { i ->
            val f = d.getInterface(i)
            f.interfaceClass == UsbConstants.USB_CLASS_AUDIO && f.interfaceSubclass == 2 &&
                (0 until f.endpointCount).any { e ->
                    val ep = f.getEndpoint(e)
                    ep.direction == UsbConstants.USB_DIR_OUT &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                }
        }

        /** Whether the device has a MIDI streaming interface. */
        fun hasMidi(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { i ->
            val f = d.getInterface(i)
            f.interfaceClass == UsbConstants.USB_CLASS_AUDIO && f.interfaceSubclass == 3
        }
    }
}

/**
 * Jack names learned from a device while it was open in a direct mode, kept so
 * the ALSA-mode list can show the same ALSA names -- in ALSA mode the app has
 * no permission to read the strings itself. Keyed by VID:PID and port.
 */
object JackNames {
    private val names = HashMap<String, String>()
    fun remember(d: UsbDevice, port: Int, name: String) { names["${d.vendorId}:${d.productId}:$port"] = name }
    fun lookup(d: UsbDevice, port: Int): String? = names["${d.vendorId}:${d.productId}:$port"]
}

/**
 * Waits until the kernel -- and on this tablet the DSP behind it -- has really
 * let go of a device's streaming interfaces.
 *
 * WHY THIS IS NEEDED. Closing the AAudio stream does not free the interface.
 * AudioFlinger keeps the HAL stream open through its standby delay, and with
 * Qualcomm's offload the ADSP owns the transfer ring until that session is
 * torn down (see the recycle work in HANDOFF, which measured this: a close and
 * immediate reopen never reached the device at all). Claiming the interface
 * while that is still live would be tearing it out from under an active DSP
 * session.
 *
 * So: watch the interface's current altsetting in sysfs and wait for alt 0,
 * the zero-bandwidth setting, which is what a finished stream leaves. If the
 * app may not read sysfs, fall back to the measured standby hold.
 */
object InterfaceRelease {
    /** AudioFlinger's standby, as measured by the recycle hold: 4 s. */
    const val FALLBACK_HOLD_MS = 4000L
    const val TIMEOUT_MS = 10_000L

    /**
     * Blocks (call it off the main thread) until every interface in [ids] of
     * [d] reads alt 0, or the timeout. Reports progress as seconds waited.
     * Returns true if release was OBSERVED, false if it timed out or had to be
     * assumed from the fixed hold.
     *
     * [assumeBusy]: an ALSA stream was running on this device a moment ago.
     * When sysfs cannot be read, only then is the fixed hold paid -- a cold
     * start into a direct mode has nothing to wait for, and would otherwise
     * cost four seconds at every launch for no reason.
     */
    fun awaitIdle(d: UsbDevice, config: Int, ids: Collection<Int>, assumeBusy: Boolean,
                  progress: (Double, String) -> Unit): Boolean {
        val dir = sysfsDir(d)
        val t0 = SystemClock.uptimeMillis()
        if (dir == null || ids.any { readAlt(dir, config, it) == null }) {
            if (!assumeBusy) return false
            // Not readable from this app: take the measured hold instead.
            while (SystemClock.uptimeMillis() - t0 < FALLBACK_HOLD_MS) {
                progress((SystemClock.uptimeMillis() - t0) / 1000.0, "waiting for audio standby")
                SystemClock.sleep(200)
            }
            return false
        }
        while (true) {
            val busy = ids.filter { (readAlt(dir, config, it) ?: 0) != 0 }
            val waited = SystemClock.uptimeMillis() - t0
            if (busy.isEmpty()) {
                Log.i(UsbSession.TAG, "interfaces $ids idle after $waited ms")
                return true
            }
            if (waited > TIMEOUT_MS) {
                Log.e(UsbSession.TAG, "interfaces $busy still streaming after $waited ms")
                return false
            }
            progress(waited / 1000.0, "waiting for interface ${busy.joinToString()} to be released")
            SystemClock.sleep(100)
        }
    }

    /** /sys/bus/usb/devices/<port path> for this device, found by bus and device number. */
    private fun sysfsDir(d: UsbDevice): File? = try {
        // deviceName is /dev/bus/usb/BBB/DDD.
        val parts = d.deviceName.split('/').takeLast(2)
        val bus = parts[0].toInt()
        val dev = parts[1].toInt()
        File("/sys/bus/usb/devices").listFiles()?.firstOrNull { f ->
            !f.name.contains(':') &&
                runCatching { File(f, "busnum").readText().trim().toInt() == bus &&
                    File(f, "devnum").readText().trim().toInt() == dev }.getOrDefault(false)
        }
    } catch (_: Exception) { null }

    private fun readAlt(dir: File, config: Int, iface: Int): Int? = runCatching {
        File("${dir.path}:$config.$iface/bAlternateSetting").readText().trim().toInt()
    }.getOrNull()
}
