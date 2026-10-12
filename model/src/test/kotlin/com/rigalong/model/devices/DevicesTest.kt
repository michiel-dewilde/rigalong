package com.rigalong.model.devices

import com.rigalong.model.devices.cp88.Cp88
import com.rigalong.model.devices.cp88.Cp88Driver
import com.rigalong.model.devices.cp88.Cp88X9
import com.rigalong.model.devices.cp88.Cp88Yaml
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The device model: a plain MIDI device, and the CP88/CP73 as a driver on top of the same model. */
class DevicesTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun aMidiDeviceSelectsByProgramAndOptionalBank() {
        val d = MidiDeviceDriver
        assertEquals("b20000b22005c20b", hex(d.select(mapOf("program" to 12, "bankMsb" to 0, "bankLsb" to 5), channel = 3)))
        assertEquals("c07f", hex(d.select(mapOf("program" to 128), channel = 1)))
        assertEquals("program 12, bank 0/5", d.describe(mapOf("program" to 12, "bankMsb" to 0, "bankLsb" to 5)))
        assertNull(d.selection(0, 0, 5), "a plain device's program change is not a report of its own")
        assertNull(d.sounds)
    }

    @Test
    fun theCp88IsADriverWithEverythingItsMidiAllows() {
        val d = Cp88Driver
        assertSame(d, Devices.driver("yamaha.cp88"))
        assertSame(d, Devices.recognise("CP88/CP73"))
        assertNull(Devices.recognise("Scarlett 2i2 USB"))
        // Selecting a live set, and its own buttons reporting one.
        assertContentEquals(Cp88.selectMessages(3, 5, 2), d.select(mapOf("page" to 3, "set" to 5), channel = 2))
        assertEquals("3:5", d.describe(mapOf("page" to 3, "set" to 5)))
        assertEquals(mapOf("page" to 40, "set" to 1), d.selection(63, 39, 0))
        assertNull(d.selection(0, 39, 0), "bank 0 is not a live set")
        // Its own sounds: written into page:set, collected from a dump reply.
        val s = assertNotNull(d.sounds)
        assertSame(d, Devices.forSounds(Cp88.MEDIA_TYPE))
        assertEquals(mapOf("page" to 40, "set" to 3), s.slotAddress(40, 3))
        assertEquals(Cp88.retarget(Cp88.SILENT, 40, 3).map(::hex), s.write(Cp88.SILENT, 40, 3).map(::hex))
        assertEquals("Silent", s.name(s.silent))
        val reply = Cp88.split(Cp88.SILENT)
        assertNull(s.collect(reply.take(18)), "an incomplete reply is no sound yet")
        assertContentEquals(Cp88.SILENT, s.collect(listOf(byteArrayOf(0xF0.toByte(), 0x7E, 0xF7.toByte())) + reply))
        // Receipts for earlier writes (0B 10 00) ahead of a reply are not part of it.
        val stray = receipt("0f2307")
        assertContentEquals(Cp88.SILENT, s.collect(listOf(stray, stray) + reply))
        // A slot's reply (page 36) comes back in the stored form.
        assertContentEquals(Cp88.SILENT, s.collect(listOf(stray) + Cp88.retarget(Cp88.SILENT, 36, 1)))
    }

    /** As the CP-88 sent it, after page 36 set 8 was written: F0 43 00 7F 1C 00 0A 08 0B 10 00 00 0F 23 07 00 00 24 F7. */
    private fun receipt(footer: String) = Cp88.build(intArrayOf(0x0B, 0x10, 0x00),
        ("00" + footer + "0000").chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    @Test
    fun theCp88SaysWhichSlotItStored() {
        val s = assertNotNull(Cp88Driver.sounds)
        assertEquals("f043007f1c000a080b1000000f2307000024f7", hex(receipt("0f2307")))
        assertEquals(36 to 8, s.stored(receipt("0f2307")))
        assertEquals(1 to 1, s.stored(receipt("0f0000")))
        assertEquals(40 to 8, s.stored(receipt("0f2707")))
        assertNull(s.stored(receipt("0f7f00")), "the current buffer is no slot")
        assertNull(s.stored(receipt("0e2307")), "only a footer is stored")
        assertNull(s.stored(Cp88.split(Cp88.SILENT).last()), "a footer itself is no receipt")
        assertNull(MidiDeviceDriver.sounds?.stored(receipt("0f2307")))
    }

    @Test
    fun aDevicesFilesAreReadThroughItsFormats() {
        val x9s = Cp88X9.x9s(3, 5, Cp88.SILENT)
        val (driver, sounds) = assertNotNull(Devices.readFile("a.X9S", x9s))
        assertSame(Cp88Driver, driver)
        assertEquals(listOf("3:5"), sounds.map { it.where })
        assertContentEquals(Cp88.SILENT, sounds[0].sound)
        val yaml = Cp88Yaml.document(mapOf((2 to 4) to Cp88.SILENT)).toByteArray()
        assertEquals(listOf("2:4"), assertNotNull(Devices.readFile("set.yaml", yaml)).second.map { it.where })
        assertNull(Devices.readFile("notes.txt", "hello".toByteArray()))
        val text = Cp88Driver.sounds.formats.single { it.editable }
        assertContentEquals(Cp88.SILENT, text.fromText(text.toText(Cp88.SILENT)))
        assertEquals("X9P", Cp88Driver.X9Format.writeArea(40, mapOf(1 to Cp88.SILENT))?.first)
    }
}
