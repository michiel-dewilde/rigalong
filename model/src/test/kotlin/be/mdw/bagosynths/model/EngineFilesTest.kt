package be.mdw.bagosynths.model

import be.mdw.bagosynths.model.EngineFiles.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineFilesTest {

    private fun riff(form: String) = "RIFF".toByteArray() + ByteArray(4) + form.toByteArray() + ByteArray(64)

    private fun dx7Bank(): ByteArray {
        val b = ByteArray(EngineFiles.DX7_BANK_BYTES)
        listOf(0xF0, 0x43, 0x00, 0x09, 0x20, 0x00).forEachIndexed { i, v -> b[i] = v.toByte() }
        b[b.size - 1] = 0xF7.toByte()
        return b
    }

    @Test
    fun filesAreKnownByWhatTheyAre() {
        assertEquals(Kind.SOUNDFONT, EngineFiles.kind(riff("sfbk")))
        assertNull(EngineFiles.kind(riff("WAVE")), "a WAV is a RIFF too")
        assertEquals(Kind.DX7_BANK, EngineFiles.kind(dx7Bank()))
        assertNull(EngineFiles.kind(dx7Bank().copyOf(4000)), "a cut-off bank")
        val voice = ByteArray(EngineFiles.DX7_VOICE_BYTES).also {
            listOf(0xF0, 0x43, 0x00, 0x00, 0x01, 0x1B).forEachIndexed { i, v -> it[i] = v.toByte() }
            it[it.size - 1] = 0xF7.toByte()
        }
        assertEquals(Kind.DX7_VOICE, EngineFiles.kind(voice))
        val fxp = "CcnK".toByteArray() + ByteArray(4) + "FPCh".toByteArray() + ByteArray(4) + "cjs3".toByteArray() + ByteArray(40)
        assertEquals(Kind.SURGE_PATCH, EngineFiles.kind(fxp))
        assertNull(EngineFiles.kind(ByteArray(0)))
        // A real Surge XT patch, when the source tree has its content.
        val real = java.io.File("../third_party/surge/resources/data/patches_3rdparty/A.Liv/Basses/808er Than 808.fxp")
        if (real.exists()) assertEquals(Kind.SURGE_PATCH, EngineFiles.kind(real.readBytes()))
    }

    @Test
    fun installNamesAreSafeAndKeepTheirKind() {
        assertEquals("FluidR3_GM.sf2", EngineFiles.installName("FluidR3_GM.sf2", Kind.SOUNDFONT))
        assertEquals("MuseScore_General.sf3", EngineFiles.installName("MuseScore_General.SF3", Kind.SOUNDFONT))
        assertEquals("piano.sf2", EngineFiles.installName("piano", Kind.SOUNDFONT))
        assertEquals("rom1a.syx", EngineFiles.installName("/sdcard/Download/rom1a.SYX", Kind.DX7_BANK))
        assertEquals("My_Lead.fxp", EngineFiles.installName("My:Lead?.fxp", Kind.SURGE_PATCH))
        assertEquals("Dexed.syx", EngineFiles.installName("..syx", Kind.DX7_BANK))
    }

    @Test
    fun aNameInUseGetsANumberAndTheSameFileIsNotInstalledTwice() {
        val dir = kotlin.io.path.createTempDirectory("enginefiles").toFile()
        try {
            val a = byteArrayOf(1, 2, 3)
            val first = EngineFiles.place(dir, "bank.syx", a)!!
            assertEquals("bank.syx", first.name)
            first.writeBytes(a)
            assertNull(EngineFiles.place(dir, "bank.syx", a), "already there")
            assertEquals("bank 2.syx", EngineFiles.place(dir, "bank.syx", byteArrayOf(9))!!.name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
