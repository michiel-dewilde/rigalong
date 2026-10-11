package be.mdw.bagosynths.model.devices

import be.mdw.bagosynths.model.devices.cp88.Cp88
import be.mdw.bagosynths.model.devices.cp88.Cp88Driver
import be.mdw.bagosynths.model.devices.cp88.Cp88X9
import be.mdw.bagosynths.model.devices.cp88.Cp88Yaml
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
