package com.rigalong.model.devices.cp88

import com.rigalong.model.Digest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Cp88Test {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    // A Live Set Sound named "Test Sound", written by cp-liveset for slot 40:8
    // (codec.sound_to_messages(0, 40, 8, init_sound() renamed)).
    private val slot40x8 = "f043007f1c0004080e27073cf7|f043007f1c000808007f000104000074f7|f043007f1c0018082040004000000000000000401c000000000000400000003cf7|f043007f1c0034084600005465737420536f756e6420202020200200000000014037007f0b04007f007f000040404000004040014040404040404042f7|f043007f1c001408460100000b07040000000000000000000000001bf7|f043007f1c0014084a000001004040007f0064400000001f3f00002cf7|f043007f1c0014084a010000014040007f0064400000001f3f00002bf7|f043007f1c0014084a020000024040007f0064400000001f3f000029f7|f043007f1c0014084a030000034040007f0064400000001f3f000027f7|f043007f1c001c08500000000000000000000100407f4000420000000101010100000062f7|f043007f1c001c08500100040000000000000000407f400042000000010101010000005ef7|f043007f1c001c08500200080000000000000000407f400042000a00010101010000004ff7|f043007f1c002008501000000100190000400000000000000000000000000000000000000000003ef7|f043007f1c0020085011000001000000000000000040400000404000400000000000000000000056f7|f043007f1c0020085012000001000000000000000000000000000000000000000040404040000015f7|f043007f1c0014085020000001004001000000400000000000000006f7|f043007f1c0014085021000001004001000000400000000000000005f7|f043007f1c0014085022000001004001000000400000000000000004f7|f043007f1c0004080f27073bf7".split('|').map(::hex)

    @Test
    fun cpLivesetsDumpsParseAndRebuildIdentically() {
        for (m in slot40x8) {
            val d = Cp88.parse(m)!!
            assertContentEquals(m, Cp88.build(d.address, d.data))
        }
    }

    @Test
    fun aDumpWithABadChecksumIsRejected() {
        val m = slot40x8[3].copyOf()
        m[m.size - 2] = ((m[m.size - 2] + 1) and 0x7F).toByte()
        assertNull(Cp88.parse(m))
    }

    @Test
    fun normalisedSoundsDoNotDependOnTheirSlot() {
        val sound = Cp88.normalize(slot40x8)
        val again = Cp88.normalize(Cp88.retarget(sound, 3, 2))
        assertContentEquals(sound, again)
        assertEquals(Digest.of(sound), Digest.of(again))
        val header = Cp88.parse(Cp88.split(sound).first())!!
        assertContentEquals(intArrayOf(0x0E, 0x7F, 0x00), header.address)
    }

    @Test
    fun retargetingPutsTheSlotInHeaderAndFooterOnly() {
        val sound = Cp88.normalize(slot40x8)
        val back = Cp88.retarget(sound, 40, 8)
        assertEquals(19, back.size)
        for (i in back.indices) assertContentEquals(slot40x8[i], back[i], "message $i")
    }

    @Test
    fun theNameComesFromTheCommonBlock() {
        assertEquals("Test Sound", Cp88.name(Cp88.normalize(slot40x8)))
    }

    @Test
    fun requestsAndSelectionsAreTheBytesTheCp88Answers() {
        assertContentEquals(hex("f043207f1c080e7f00f7"), Cp88.dumpRequest())
        assertContentEquals(hex("f043207f1c080e0100f7"), Cp88.dumpRequest(2, 1))
        assertContentEquals(hex("b0003fb02027c007"), Cp88.selectMessages(40, 8))
    }

    @Test
    fun aRenamedSoundDiffersInItsNameOnly() {
        val sound = Cp88.normalize(slot40x8)
        val renamed = Cp88.rename(sound, "Verse")
        assertEquals("Verse", Cp88.name(renamed))
        val before = Cp88.split(sound)
        val after = Cp88.split(renamed)
        assertEquals(19, after.size)
        var changed = 0
        for (i in before.indices) {
            // Every dump still has a checksum the CP-88 accepts: a bad one is stored as Init Sound's block.
            val a = assertNotNull(Cp88.parse(after[i]), "dump $i")
            val b = assertNotNull(Cp88.parse(before[i]))
            assertContentEquals(b.address, a.address)
            if (!after[i].contentEquals(before[i])) {
                changed++
                assertEquals("Verse".padEnd(15, ' '), String(a.data, 0, 15, Charsets.US_ASCII))
                assertContentEquals(b.data.copyOfRange(15, b.data.size), a.data.copyOfRange(15, a.data.size))
            }
        }
        assertEquals(1, changed)
        // Renamed back, it is the sound it was (its own name padded the same way).
        assertEquals("Test Sound", Cp88.name(Cp88.rename(renamed, "Test Sound")))
        // A blank name changes nothing.
        assertContentEquals(sound, Cp88.rename(sound, "  "))
        // The silent sound under a live set's name is still whole.
        assertContentEquals(Cp88.rename(Cp88.SILENT, "Chorus"), Cp88.normalize(Cp88.retarget(Cp88.rename(Cp88.SILENT, "Chorus"), 40, 3)))
    }

    @Test
    fun aNameIsCutAndFoldedToWhatTheDisplayShows() {
        assertEquals("Rhodes and pad", Cp88.displayName("Rhodes and pad"))
        assertEquals("A very long nam", Cp88.displayName("A very long name indeed"))
        assertEquals("Cafe creme", Cp88.displayName("Café crème"))
        assertEquals("Intro - 'A'", Cp88.displayName("Intro – ‘A’"))
        assertEquals("? piano", Cp88.displayName("♫ piano"))
        assertEquals("Piano", Cp88.displayName("  Piano\t"))
        assertEquals("", Cp88.displayName(" "))
    }

    @Test
    fun theSilentSoundIsWholeAndNamed() {
        assertEquals(19, Cp88.split(Cp88.SILENT).size)
        assertEquals("Silent", Cp88.name(Cp88.SILENT))
        assertContentEquals(Cp88.SILENT, Cp88.normalize(Cp88.retarget(Cp88.SILENT, 40, 3)))
    }

    @Test
    fun anIncompleteSoundIsRefused() {
        assertFailsWith<IllegalArgumentException> { Cp88.normalize(slot40x8.dropLast(1)) }
    }
}
