package be.mdw.bagosynths.model.devices.cp88

import be.mdw.bagosynths.model.devices.Devices
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CP-88 sounds as cp-liveset's full JSON, against what cp-liveset itself
 * writes and reads (fixtures in resources/cp88 by tools/gen_cp88x9.py): the
 * same 339 sounds as the YAML test, each alone in a document at a page:set
 * that varies; a document over several pages; and hand-written documents,
 * the values and shapes it accepts and refuses among them.
 */
class Cp88JsonTest {

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/cp88/$name")!!.use { it.readBytes() }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** (page, set, stored sound, cp-liveset's document of it alone). */
    private val writes: List<Pair<Triple<Int, Int, ByteArray>, String>> by lazy {
        val text = java.util.zip.GZIPInputStream(resource("json-write.txt.gz").inputStream()).readBytes().toString(Charsets.UTF_8)
        text.split(Regex("(?m)^=== ")).filter { it.isNotEmpty() }.map { case ->
            val head = case.substringBefore('\n').split(' ')
            Triple(head[0].toInt(), head[1].toInt(), hex(head[2])) to case.substringAfter('\n')
        }
    }

    @Test
    fun writesEverySoundAsCpLivesetDoes() {
        assertTrue(writes.size > 300)
        for ((key, json) in writes) {
            val (page, set, sound) = key
            assertEquals(json, Cp88Json.document(mapOf((page to set) to sound)), "for ${Cp88.name(sound)}")
        }
    }

    @Test
    fun readsEveryDocumentBackExactly() {
        for ((key, json) in writes) {
            val (page, set, sound) = key
            val slots = Cp88Json.readDocument(json)
            assertEquals(listOf(page to set), slots.map { it.page to it.set })
            assertContentEquals(sound, slots[0].sound, json)
            assertEquals(Cp88.name(sound), slots[0].name)
        }
    }

    @Test
    fun aDocumentOverSeveralPagesRoundTrips() {
        val text = resource("json-doc.json").toString(Charsets.UTF_8)
        val slots = Cp88Json.readDocument(text)
        assertEquals(listOf(1 to 1, 3 to 1, 3 to 4, 3 to 8, 7 to 1, 7 to 4, 7 to 8, 10 to 2), slots.map { it.page to it.set })
        assertEquals(text, Cp88Json.document(slots.associate { (it.page to it.set) to it.sound }))
        assertContentEquals(Cp88X9.INIT_SOUND, slots.single { it.page == 10 }.sound)
    }

    @Test
    fun readsHandWrittenDocumentsAsCpLivesetDoes() {
        val text = resource("json-read.txt").toString(Charsets.UTF_8)
        val cases = text.split(Regex("(?m)^--- ")).filter { it.isNotEmpty() }
        assertTrue(cases.size > 30)
        for (case in cases) {
            val expect = case.substringBefore('\n')
            val json = case.substringAfter('\n')
            if (expect.startsWith("ERROR")) {
                val e = assertFailsWith<IllegalArgumentException>(json) { Cp88Json.readDocument(json) }
                assertTrue(!e.message.isNullOrBlank())
            } else {
                val want = expect.split(' ').filter { it.isNotEmpty() }.map {
                    val (where, sound) = it.split('=')
                    val (p, s) = where.split(':').map(String::toInt)
                    Triple(p, s, sound)
                }
                val got = Cp88Json.readDocument(json)
                assertEquals(want.map { it.first to it.second }, got.map { it.page to it.set }, json)
                for ((w, g) in want.zip(got)) assertContentEquals(hex(w.third), g.sound, json)
            }
        }
    }

    /**
     * A real keyboard file, when given: CP88_X9_FILE an .X9* file and
     * CP88_JSON_EXPECT cp-liveset's JSON document of it. Skipped otherwise, so
     * nobody's own sounds have to live in the repository.
     */
    @Test
    fun writesARealFileAsCpLivesetDoes() {
        val file = System.getenv("CP88_X9_FILE") ?: return
        // cp-liveset writes in text mode: CRLF where it ran on Windows.
        val expect = java.io.File(System.getenv("CP88_JSON_EXPECT") ?: return).readText().replace("\r\n", "\n")
        val sounds = Cp88X9.read(java.io.File(file).readBytes()).associate { (it.page to it.set) to it.sound }
        assertEquals(expect, Cp88Json.document(sounds))
        val back = Cp88Json.readDocument(expect).associate { (it.page to it.set) to it.sound }
        for ((place, sound) in sounds) assertContentEquals(sound, back[place], "$place")
        println("CP88_JSON_EXPECT: ${sounds.size} sounds written and read as cp-liveset does")
    }

    @Test
    fun theDriverReadsAndWritesJsonFiles() {
        val text = resource("json-doc.json")
        val (driver, sounds) = Devices.readFile("set.json", text)!!
        assertEquals(Cp88Driver, driver)
        assertEquals(8, sounds.size)
        assertEquals("10:2", sounds.last().where)
        val (ext, bytes) = Cp88Driver.JsonFormat.writeArea(7, mapOf(1 to sounds[4].sound))!!
        assertEquals("json", ext)
        assertEquals(listOf(7 to 1), Cp88Json.readDocument(bytes.toString(Charsets.UTF_8)).map { it.page to it.set })
        // A YAML document is not mistaken for one of these, nor the other way round.
        assertTrue(!Cp88Json.isDocument(Cp88Yaml.document(mapOf((1 to 1) to Cp88X9.INIT_SOUND))))
        assertTrue(!Cp88Yaml.isDocument(text.toString(Charsets.UTF_8)))
    }
}
