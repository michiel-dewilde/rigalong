package com.rigalong.model.devices.cp88

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The CP-88's .X9* files against cp-liveset: the fixtures in resources/cp88
 * were built by cp-liveset itself (tools/gen_cp88x9.py) from its synthetic
 * sample sounds, so reading must give cp-liveset's sounds and writing its bytes.
 */
class Cp88X9Test {

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/cp88/$name")!!.use { it.readBytes() }

    /** page to set to the sample's stored form, as cp-liveset builds it. */
    private val samples: Map<Pair<Int, Int>, ByteArray> by lazy {
        String(resource("samples.syx.hex")).lines().filter { it.isNotBlank() }.associate { line ->
            val (p, s, hex) = line.split(' ')
            (p.toInt() to s.toInt()) to ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }

    @Test
    fun readsASingleSound() {
        val slots = Cp88X9.read(resource("sample-3-5.X9S"))
        assertEquals(1, slots.size)
        val s = slots[0]
        assertEquals(3 to 5, s.page to s.set)
        assertEquals("Test 3-5", s.name)
        assertContentEquals(samples[3 to 5], s.sound)
    }

    @Test
    fun writesASingleSoundAsCpLivesetDoes() {
        assertContentEquals(resource("sample-3-5.X9S"), Cp88X9.x9s(3, 5, samples[3 to 5]!!))
    }

    @Test
    fun aPageReadsAndWritesWithInitSoundInTheGaps() {
        val file = resource("sample-page-7.X9P")
        val slots = Cp88X9.read(file)
        assertEquals((1..8).toList(), slots.map { it.set })
        for (s in listOf(1, 2, 6)) assertContentEquals(samples[7 to s], slots[s - 1].sound)
        assertEquals("Init Sound", slots[2].name)
        assertContentEquals(Cp88X9.INIT_SOUND, slots[2].sound)
        assertContentEquals(file, Cp88X9.x9p(7, listOf(1, 2, 6).associateWith { samples[7 to it]!! }))
    }

    @Test
    fun readsLiveSetAllButNotItsInitLayer() {
        val file = java.util.zip.GZIPInputStream(javaClass.getResourceAsStream("/cp88/sample-all.X9L.gz")!!).use { it.readBytes() }
        val slots = Cp88X9.read(file)
        assertEquals(160, slots.size, "pages 1-20, sets 1-8; the ELSE/DLSE init layer is not read")
        val byPlace = slots.associateBy { it.page to it.set }
        for ((place, sound) in samples) assertContentEquals(sound, byPlace[place]!!.sound, "sound $place")
        assertContentEquals(Cp88X9.INIT_SOUND, byPlace[1 to 1]!!.sound)
    }

    @Test
    fun aRecordRoundTripsTheSoundsItCanHold() {
        for (sound in samples.values) assertContentEquals(sound, Cp88X9.soundOf(Cp88X9.recordOf(sound)))
        // The app's own silent sound, made with cp-liveset, too.
        assertContentEquals(Cp88.SILENT, Cp88X9.soundOf(Cp88X9.recordOf(Cp88.SILENT)))
    }

    @Test
    fun aSoundReadFromAFileIsTheSoundReadOverMidi() {
        // The stored form is Cp88.normalize's: header and footer at the current buffer.
        val s = Cp88X9.read(resource("sample-3-5.X9S"))[0].sound
        assertContentEquals(s, Cp88.normalize(Cp88.split(s)))
    }

    @Test
    fun otherFilesAreRefused() {
        assertFailsWith<IllegalArgumentException> { Cp88X9.read("not a file".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { Cp88X9.read(ByteArray(200)) }
    }

    /**
     * A real keyboard file, when given: CP88_X9_FILE an .X9* file and
     * CP88_X9_EXPECT what cp-liveset reads from it ("page set hex" lines of
     * the stored form, as tools/gen_cp88x9.py writes them). Skipped otherwise,
     * so nobody's own sounds have to live in the repository.
     */
    @Test
    fun readsARealFileAsCpLivesetDoes() {
        val file = System.getenv("CP88_X9_FILE") ?: return
        val expect = java.io.File(System.getenv("CP88_X9_EXPECT") ?: return).readLines().filter { it.isNotBlank() }
        val slots = Cp88X9.read(java.io.File(file).readBytes()).associateBy { it.page to it.set }
        assertEquals(expect.size, slots.size)
        for (line in expect) {
            val (p, s, hex) = line.split(' ')
            val want = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            assertContentEquals(want, slots[p.toInt() to s.toInt()]!!.sound, "$p:$s")
        }
        println("CP88_X9_FILE: ${slots.size} sounds read as cp-liveset reads them")
    }
}
