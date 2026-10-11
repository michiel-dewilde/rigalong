package be.mdw.bagosynths.model.devices

import be.mdw.bagosynths.model.devices.cp88.Cp88Driver

/*
 * External instruments (REQUIREMENTS I4.7, I10.1): a keyboard or sound module
 * the app drives over MIDI next to its own engines. The core knows a device
 * only through a [DeviceDriver]: what kind it is, how one of its stored sounds
 * is addressed and selected, whether it reports its own selections, and --
 * if it can -- how the app gives it sounds of its own. A song says per live
 * set what each device does ([be.mdw.bagosynths.model.DeviceAction]); the
 * environment says which devices there are and how they are set up
 * ([be.mdw.bagosynths.model.ExternalSynth]).
 *
 * A driver is the one place a device's particulars live. [MidiDeviceDriver]
 * is any keyboard or module that takes a program change; the Yamaha CP88/CP73
 * ([Cp88Driver]) is a driver on top of the same model with everything its
 * SysEx allows. Another instrument is another driver in [Devices.drivers].
 */

/** One number of a device's address for a stored sound: the CP-88's page or set, a module's program or bank. */
data class AddressField(
    val key: String,
    val label: String,
    val range: IntRange,
    /** Absent from an address unless set (a bank select a module may not need). */
    val optional: Boolean = false,
)

/** A kind of external instrument: the app's whole knowledge of it. */
interface DeviceDriver {
    /** Stable, stored in songs and environments: "midi", "yamaha.cp88". */
    val kind: String
    /** For people: "MIDI device", "Yamaha CP88/CP73". */
    val label: String
    /** What a new device of this kind is called in an environment: "MIDI device", "CP-88". */
    val defaultName: String

    /** Whether a connected USB device of this name is one of these (for suggesting it). */
    fun recognises(usbName: String): Boolean = false

    /** How one of its stored sounds is addressed. */
    val address: List<AddressField>

    /** The address as people read it: "3:5", "program 12, bank 0/5". */
    fun describe(address: Map<String, Int>): String

    /** The MIDI that selects the stored sound at [address], on [channel] (1-16). */
    fun select(address: Map<String, Int>, channel: Int): ByteArray

    /**
     * A selection that came FROM the device -- bank MSB/LSB and a program
     * change, as its own buttons send them -- as its address, or null if the
     * device does not report selections or this is not one.
     */
    fun selection(msb: Int, lsb: Int, program: Int): Map<String, Int>? = null

    /** Sounds of its own the app can give it, or null if it has none. */
    val sounds: DeviceSounds? get() = null

    /**
     * How its audio interface wants to be driven on the direct USB path, as
     * measured, or null for the transport's defaults. (The transport's own
     * table, UsbAudioOut::profileFor, is what runs; this is the record.)
     */
    val usbAudio: be.mdw.bagosynths.model.UsbProfile? get() = null
}

/**
 * A device the app can give sounds of its own (S4.9-S4.11): each kept in the
 * library as one object in the driver's own stored form, written into an
 * area of the device reserved for the app when a song opens -- live set n's
 * sound in slot n -- and selected from there.
 */
interface DeviceSounds {
    /** The library's media type for one of its sounds. */
    val mediaType: String
    /** What one reserved area is called and where it can be: the CP-88's page, 1-40. */
    val area: AddressField
    /** Sounds per area: the first [slots] live sets of a song can own one. */
    val slots: Int
    /** The address of [slot] (1-based) of [area], to select it. */
    fun slotAddress(area: Int, slot: Int): Map<String, Int>

    /** The sound's own name, as the device shows it. */
    fun name(sound: ByteArray): String
    /** The MIDI that writes [sound] into [slot] of [area]. */
    fun write(sound: ByteArray, area: Int, slot: Int): List<ByteArray>
    /** The MIDI that puts [sound] in the device's current sound, to hear it, no stored slot touched. */
    fun audition(sound: ByteArray): List<ByteArray>

    /** What a slot holds when the device follows the app and a live set gives it nothing: silence. */
    val silent: ByteArray
    /** What a slot holds that the app leaves to the device: its own neutral sound. */
    val neutral: ByteArray

    /** Asks for the device's current sound; the reply goes to [collect]. */
    fun dumpRequest(): ByteArray
    /** The reply so far as one sound, or null while it is incomplete; messages that are not part of one are ignored. */
    fun collect(messages: List<ByteArray>): ByteArray?

    /** Files of the device's own and text forms, for import, export and editing. */
    val formats: List<SoundFormat> get() = emptyList()
}

/** A sound found in a file, with where the file kept it ("3:5"). */
data class FoundSound(val name: String, val sound: ByteArray, val where: String)

/**
 * A way to keep a device's sounds outside the app: a file the device itself
 * reads, or text a person edits. Each part is optional.
 */
interface SoundFormat {
    /** "X9", "YAML". */
    val label: String
    /** Whether [bytes] (named [fileName]) is one of these, by its content. */
    fun recognises(fileName: String, bytes: ByteArray): Boolean
    /** Every sound in such a file; throws IllegalArgumentException with a reason. */
    fun read(bytes: ByteArray): List<FoundSound>

    /** One sound as a file: (extension, bytes), or null if this format does not write single sounds. */
    fun writeOne(sound: ByteArray): Pair<String, ByteArray>? = null
    /** An area as a file -- slot n holding sounds[n] -- as (extension, bytes), or null. */
    fun writeArea(area: Int, sounds: Map<Int, ByteArray>): Pair<String, ByteArray>? = null

    /** Editable text: one sound as text, and back (throws IllegalArgumentException where the text is wrong). */
    val editable: Boolean get() = false
    /** One line for the person editing the text: what it is and how it works. */
    val description: String get() = ""
    fun toText(sound: ByteArray): String = throw UnsupportedOperationException()
    fun fromText(text: String): ByteArray = throw UnsupportedOperationException()
}

/** The drivers the app knows. */
object Devices {
    val drivers: List<DeviceDriver> = listOf(MidiDeviceDriver, Cp88Driver)

    fun driver(kind: String): DeviceDriver? = drivers.firstOrNull { it.kind == kind }

    /** The driver whose sounds have [mediaType]. */
    fun forSounds(mediaType: String): DeviceDriver? = drivers.firstOrNull { it.sounds?.mediaType == mediaType }

    /** The specific driver for a connected USB device, if any knows it. */
    fun recognise(usbName: String): DeviceDriver? = drivers.firstOrNull { it.recognises(usbName) }

    /** A file's sounds, by whichever driver's format recognises it: (driver, sounds). */
    fun readFile(fileName: String, bytes: ByteArray): Pair<DeviceDriver, List<FoundSound>>? {
        for (d in drivers) for (f in d.sounds?.formats.orEmpty()) {
            if (f.recognises(fileName, bytes)) return d to f.read(bytes)
        }
        return null
    }
}

/**
 * Any keyboard or sound module that takes a program change (I6.7): a program
 * 1-128 with an optional bank select. What every device can do; a specific
 * driver adds what its own messages allow.
 */
object MidiDeviceDriver : DeviceDriver {
    override val kind = "midi"
    override val label = "MIDI device"
    override val defaultName = "MIDI device"
    override val address = listOf(
        AddressField("program", "program", 1..128),
        AddressField("bankMsb", "bank MSB", 0..127, optional = true),
        AddressField("bankLsb", "bank LSB", 0..127, optional = true),
    )

    override fun describe(address: Map<String, Int>): String {
        val bank = listOfNotNull(address["bankMsb"], address["bankLsb"])
        return "program ${address["program"] ?: 1}" + if (bank.isEmpty()) "" else ", bank ${bank.joinToString("/")}"
    }

    override fun select(address: Map<String, Int>, channel: Int): ByteArray {
        val ch = (channel - 1).coerceIn(0, 15)
        val out = ArrayList<Int>()
        address["bankMsb"]?.let { out += listOf(0xB0 or ch, 0x00, it.coerceIn(0, 127)) }
        address["bankLsb"]?.let { out += listOf(0xB0 or ch, 0x20, it.coerceIn(0, 127)) }
        out += listOf(0xC0 or ch, ((address["program"] ?: 1) - 1).coerceIn(0, 127))
        return ByteArray(out.size) { out[it].toByte() }
    }
    // No selection(): a program change from a plain MIDI device is not a report
    // of its own state; turning those into live set switches is the switching
    // sources' job (I10.4), not a driver's.
}
