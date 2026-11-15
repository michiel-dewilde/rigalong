package com.rigalong

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build

/**
 * What is plugged in, as [AudioRouter] chooses from it: the audio outputs and
 * the MIDI input ports, each by its ALSA name, whether Android lists the
 * device or this process owns it over USB (it has no ALSA card then, and
 * Android no longer lists it). Asks Android each time; keeps nothing.
 *
 * [ownedMidiPorts]: the MIDI port names of the USB devices this process has
 * open, by device name -- the open session read the jack strings.
 */
internal class DeviceLists(private val context: Context,
                           private val ownedMidiPorts: () -> Map<String, List<String>>) {

    private val usb get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** An audio output, by its ALSA card name. `usb` set when it is a USB device. */
    class OutputChoice(val name: String, val alsa: AudioDeviceInfo?, val usb: UsbDevice?)

    /** A MIDI input port, by its ALSA port name ("CP88/CP73 MIDI 1"). */
    class MidiChoice(val name: String, val card: String, val port: Int,
                     val info: MidiDeviceInfo?, val usb: UsbDevice?)

    fun isUsbType(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /** The UsbDevice behind an AAudio device, matched by product name. */
    fun usbFor(d: AudioDeviceInfo, usbDevices: Collection<UsbDevice>): UsbDevice? {
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
    fun outputs(): List<OutputChoice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usbDevices = usb.deviceList.values
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
    fun midiInputs(): List<MidiChoice> {
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
        for (u in usb.deviceList.values) {
            if (!UsbSession.hasMidi(u)) continue
            val card = UsbSession.alsaCardName(u)
            val names = ownedMidiPorts()[u.deviceName]
                ?: listOf(NativeBridge.nativeAlsaMidiPortName(card, JackNames.lookup(u, 0), 0))
            names.forEachIndexed { p, n -> if (n !in out) out[n] = MidiChoice(n, card, p, null, u) }
        }
        return out.values.toList()
    }

    /**
     * Whether Android has [d] back: its ALSA card among the outputs and its
     * MIDI ports listed, for as far as the device has either.
     */
    fun androidSees(d: UsbDevice): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audio = !UsbSession.hasAudioOut(d) || am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { isUsbType(it) && usbFor(it, listOf(d)) != null }
        val midi = !UsbSession.hasMidi(d) || midiInputs().any { it.info != null && it.usb?.deviceName == d.deviceName }
        return audio && midi
    }

    private fun MidiDeviceInfo.midiName(): String =
        properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "device"

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
    fun rateOf(d: AudioDeviceInfo?): Int {
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
    fun formatOf(d: AudioDeviceInfo?): Int {
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
}
