package be.mdw.rigalong.model.devices.cp88

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CP-88 sounds as cp-liveset's YAML, against what cp-liveset itself writes and
 * reads (fixtures in resources/cp88 by tools/gen_cp88x9.py): its samples, the
 * Init Sound, names that need every kind of quoting, and random sounds that
 * reach the instrument maps, enum numbers, tempo lists and the raw map.
 */
class Cp88YamlTest {

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/cp88/$name")!!.use { it.readBytes() }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** (stored sound, how cp-liveset reads it back, cp-liveset's YAML of it). */
    private val writes: List<Triple<ByteArray, String, String>> by lazy {
        val text = java.util.zip.GZIPInputStream(resource("yaml-write.txt.gz").inputStream()).readBytes().toString(Charsets.UTF_8)
        text.split(Regex("(?m)^=== ")).filter { it.isNotEmpty() }.map { case ->
            val head = case.substringBefore('\n').split(' ', limit = 2)
            Triple(hex(head[0]), head[1], case.substringAfter('\n'))
        }
    }

    @Test
    fun writesEverySoundAsCpLivesetDoes() {
        assertTrue(writes.size > 300)
        for ((sound, _, yaml) in writes) assertEquals(yaml, Cp88Yaml.write(sound), "for ${Cp88.name(sound)}")
    }

    @Test
    fun readsItsYamlBackAsCpLivesetDoes() {
        var exact = 0
        for ((sound, how, yaml) in writes) {
            when {
                how == "exact" -> { assertContentEquals(sound, Cp88Yaml.read(yaml), yaml); exact++ }
                // Random bytes cp-liveset's form normalises (a switch of 5 reads back as 1): the same here.
                how.startsWith("lossy ") -> assertContentEquals(hex(how.removePrefix("lossy ")), Cp88Yaml.read(yaml), yaml)
                else -> assertFailsWith<IllegalArgumentException>(yaml) { Cp88Yaml.read(yaml) }
            }
        }
        assertTrue(exact > 100)
    }

    @Test
    fun readsHandWrittenYamlAsCpLivesetDoes() {
        val text = resource("yaml-read.txt").toString(Charsets.UTF_8)
        val cases = text.split(Regex("(?m)^--- ")).filter { it.isNotEmpty() }
        assertTrue(cases.size > 10)
        for (case in cases) {
            val expect = case.substringBefore('\n')
            val yaml = case.substringAfter('\n')
            if (expect.startsWith("ERROR")) {
                val e = assertFailsWith<IllegalArgumentException>(yaml) { Cp88Yaml.read(yaml) }
                assertTrue(!e.message.isNullOrBlank())
            } else {
                assertContentEquals(hex(expect), Cp88Yaml.read(yaml), yaml)
            }
        }
    }

    @Test
    fun theNamePaddingRoundTripsEitherWay() {
        // The YAML keeps NULs and drops spaces: "Init Sound" is NUL-padded, the samples space-padded.
        val init = Cp88X9.INIT_SOUND
        assertTrue(Cp88Yaml.write(init).startsWith("name: \"Init Sound\\0\\0\\0\\0\\0\"\n"))
        assertContentEquals(init, Cp88Yaml.read(Cp88Yaml.write(init)))
        val (spaced, _, yaml) = writes.first { Cp88.name(it.first) == "Test 3-5" && it.third.startsWith("name: Test 3-5\n") }
        assertContentEquals(spaced, Cp88Yaml.read(yaml))
    }

    @Test
    fun writesADocumentAsCpLivesetDoes() {
        val samples = Cp88X9.read(java.util.zip.GZIPInputStream(resource("sample-all.X9L.gz").inputStream()).readBytes())
            .associate { (it.page to it.set) to it.sound }
        val pick = listOf(3, 7).flatMap { p -> listOf(1, 4, 8).map { p to it } }.associateWith { samples[it]!! } +
            ((1 to 1) to Cp88X9.INIT_SOUND)
        val doc = resource("yaml-doc.yaml").toString(Charsets.UTF_8)
        assertEquals(doc, Cp88Yaml.document(pick))
        assertTrue(Cp88Yaml.isDocument(doc))
        val back = Cp88Yaml.readDocument(doc)
        assertEquals(listOf(1 to 1, 3 to 1, 3 to 4, 3 to 8, 7 to 1, 7 to 4, 7 to 8), back.map { it.page to it.set })
        for (s in back) assertContentEquals(pick[s.page to s.set], s.sound)
    }

    @Test
    fun aOneSoundDocumentReadsAsTheSound() {
        val sound = writes[5].first
        assertContentEquals(sound, Cp88Yaml.read(Cp88Yaml.document(mapOf((2 to 3) to sound))))
        assertFailsWith<IllegalArgumentException> { Cp88Yaml.read(Cp88Yaml.document(mapOf((1 to 1) to sound, (1 to 2) to sound))) }
    }

    /**
     * A real keyboard file, when given: CP88_X9_FILE an .X9* file and
     * CP88_YAML_EXPECT cp-liveset's YAML document of it. Skipped otherwise, so
     * nobody's own sounds have to live in the repository.
     */
    @Test
    fun writesARealFileAsCpLivesetDoes() {
        val file = System.getenv("CP88_X9_FILE") ?: return
        val expect = java.io.File(System.getenv("CP88_YAML_EXPECT") ?: return).readText()
        val slots = Cp88X9.read(java.io.File(file).readBytes())
        val sounds = slots.associate { (it.page to it.set) to it.sound }
        assertEquals(expect, Cp88Yaml.document(sounds))
        val back = Cp88Yaml.readDocument(expect).associate { (it.page to it.set) to it.sound }
        for ((place, sound) in sounds) assertContentEquals(sound, back[place], "$place")
        println("CP88_YAML_EXPECT: ${sounds.size} sounds written and read as cp-liveset does")
    }

    @Test
    fun mistakesAreSaidWhereTheyAre() {
        val e = assertFailsWith<IllegalArgumentException> { Cp88Yaml.read("name: X\npiano:\n  volume: 300\n") }
        assertTrue("piano.volume" in e.message!!, e.message)
        val f = assertFailsWith<IllegalArgumentException> { Cp88Yaml.read("name: X\npiano:\n  volume: [\n") }
        assertTrue("line" in f.message!!, f.message)
        assertFailsWith<IllegalArgumentException> { Cp88Yaml.read("- a list\n") }
        assertFailsWith<IllegalArgumentException> { Cp88Yaml.read("name: X\nraw:\n  common.nope: 1\n") }
    }
}
