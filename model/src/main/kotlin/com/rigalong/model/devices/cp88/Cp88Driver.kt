package com.rigalong.model.devices.cp88

import com.rigalong.model.DeviceMatch
import com.rigalong.model.FbxProfile
import com.rigalong.model.UsbProfile
import com.rigalong.model.devices.AddressField
import com.rigalong.model.devices.DeviceDriver
import com.rigalong.model.devices.DeviceSounds
import com.rigalong.model.devices.FoundSound
import com.rigalong.model.devices.SoundFormat

/**
 * The Yamaha CP88/CP73 as a device: its Live Sets addressed page 1-40, set
 * 1-8 and selected by Bank Select MSB 63 / LSB page-1 / Program Change set-1;
 * its own buttons reporting the same; its Live Set Sounds read over SysEx,
 * written into a reserved page and kept in the library ([Cp88]); its .X9*
 * files ([Cp88X9]) and cp-liveset's YAML ([Cp88Yaml]) and JSON ([Cp88Json]).
 */
object Cp88Driver : DeviceDriver {
    override val kind = "yamaha.cp88"
    override val label = "Yamaha CP88/CP73"
    override val defaultName = "CP-88"

    override fun recognises(usbName: String) = usbName.contains("CP88", true) || usbName.contains("CP73", true)

    override val address = listOf(
        AddressField("page", "page", 1..40),
        AddressField("set", "set", 1..8),
    )

    override fun describe(address: Map<String, Int>) = "${address["page"] ?: 1}:${address["set"] ?: 1}"

    /** FBX_PROFILE_CP88, measured on Michiel's unit (CP88-USB-AUDIO.md). */
    override val usbAudio = UsbProfile(
        match = DeviceMatch(vendor = 0x0499, name = "CP88/CP73"),
        fbx = FbxProfile(1, 1, 0x07, 2, 1, 0x86, 6, 45, 44.1, 176, 4, 132.7, 135.5, 1.0),
        measuredOn = "CP88, 2026-10-04, cp88bench",
    )

    override fun select(address: Map<String, Int>, channel: Int) =
        Cp88.selectMessages(address["page"] ?: 1, address["set"] ?: 1, channel)

    // Its Live Set buttons send exactly what selects one (V1).
    override fun selection(msb: Int, lsb: Int, program: Int): Map<String, Int>? =
        if (msb == Cp88.LIVE_SET_BANK_MSB && lsb in 0..39 && program in 0..7) mapOf("page" to lsb + 1, "set" to program + 1) else null

    override val sounds = object : DeviceSounds {
        override val mediaType = Cp88.MEDIA_TYPE
        override val area = AddressField("page", "page", 1..40)
        override val slots = 8
        override fun slotAddress(area: Int, slot: Int) = mapOf("page" to area, "set" to slot)
        override fun name(sound: ByteArray) = Cp88.name(sound)
        override fun write(sound: ByteArray, area: Int, slot: Int) = Cp88.retarget(sound, area, slot)
        override fun audition(sound: ByteArray) = Cp88.split(sound)
        override val silent get() = Cp88.SILENT
        override val neutral get() = Cp88X9.INIT_SOUND
        override fun dumpRequest(area: Int?, slot: Int) = Cp88.dumpRequest(area, slot)
        override fun collect(messages: List<ByteArray>) = Cp88.collect(messages)
        override fun stored(message: ByteArray) = Cp88.stored(message)
        override val formats = listOf(X9Format, YamlFormat, JsonFormat)
    }

    /** The keyboard's own files: .X9S/.X9P/.X9L/.X9A read, .X9S and .X9P written. */
    object X9Format : SoundFormat {
        override val label = "X9"
        override fun recognises(fileName: String, bytes: ByteArray) =
            bytes.size >= 11 && String(bytes, 0, 11, Charsets.ISO_8859_1) == "YAMAHA-YSFC"
        override fun read(bytes: ByteArray) = Cp88X9.read(bytes).map { FoundSound(it.name, it.sound, "${it.page}:${it.set}") }
        override fun writeOne(sound: ByteArray) = "X9S" to Cp88X9.x9s(1, 1, sound)
        override fun writeArea(area: Int, sounds: Map<Int, ByteArray>) = "X9P" to Cp88X9.x9p(area, sounds)
    }

    /** cp-liveset's YAML: documents read and written, one sound edited as text. */
    object YamlFormat : SoundFormat {
        override val label = "YAML"
        override fun recognises(fileName: String, bytes: ByteArray) =
            bytes.size < 4_000_000 && Cp88Yaml.isDocument(bytes.toString(Charsets.UTF_8))
        override fun read(bytes: ByteArray) = Cp88Yaml.readDocument(bytes.toString(Charsets.UTF_8))
            .map { FoundSound(it.name, it.sound, "${it.page}:${it.set}") }
        override fun writeOne(sound: ByteArray) = "yaml" to Cp88Yaml.document(mapOf((1 to 1) to sound)).toByteArray()
        override fun writeArea(area: Int, sounds: Map<Int, ByteArray>) =
            "yaml" to Cp88Yaml.document(sounds.mapKeys { (set, _) -> area to set }).toByteArray()
        override val editable = true
        override val description = "cp-liveset's YAML: only what differs from the standard sound is listed. " +
            "Add a line to change anything else; names or numbers both work."
        override fun toText(sound: ByteArray) = Cp88Yaml.write(sound)
        override fun fromText(text: String) = Cp88Yaml.read(text)
    }

    /** cp-liveset's full JSON: every byte, read and written. */
    object JsonFormat : SoundFormat {
        override val label = "JSON"
        override fun recognises(fileName: String, bytes: ByteArray) =
            bytes.size < 16_000_000 && Cp88Json.isDocument(bytes.toString(Charsets.UTF_8))
        override fun read(bytes: ByteArray) = Cp88Json.readDocument(bytes.toString(Charsets.UTF_8))
            .map { FoundSound(it.name, it.sound, "${it.page}:${it.set}") }
        override fun writeOne(sound: ByteArray) = "json" to Cp88Json.document(mapOf((1 to 1) to sound)).toByteArray()
        override fun writeArea(area: Int, sounds: Map<Int, ByteArray>) =
            "json" to Cp88Json.document(sounds.mapKeys { (set, _) -> area to set }).toByteArray()
    }
}
