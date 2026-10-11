package be.mdw.bagosynths.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {

    private fun tempStore(): Store = Store(Files.createTempDirectory("bos-store").toFile())

    private fun patch(level: Float, sample: Ref? = null) = Json.obj(
        "@type" to Json.str("bagosynths.patch/1"),
        "engine" to Json.str("sfizz"),
        "params" to Json.obj("level" to Json.float32(level)),
        "state" to sample?.toJson(),
    )

    private fun song(vararg patches: Ref) = Json.obj(
        "@type" to Json.str("bagosynths.song/1"),
        "instances" to Json.obj(*patches.mapIndexed { i, p -> "i$i" to p.toJson() }.toTypedArray()),
    )

    @Test
    fun sameContentIsOneObject() {
        val s = tempStore()
        val a = s.put(patch(0.5f))
        val b = s.put(Json.parse(patch(0.5f).pretty()))      // reformatted, same content
        assertEquals(a, b)
        assertEquals(1, s.allBlobs().size)
    }

    @Test
    fun referencesAreFoundGenerically() {
        val s = tempStore()
        val wav = s.put(byteArrayOf(1, 2, 3))
        val p = s.put(patch(0.5f, Ref(wav, "audio/wav")))
        val song = s.put(song(Ref(p, "bagosynths.patch/1")))
        val c = s.closure(listOf(Ref(song)))
        assertEquals(setOf(song, p, wav), c.types.keys)
        assertEquals("application/vnd.bagosynths.song.v1+json", c.types[song])
        assertEquals("application/vnd.bagosynths.patch.v1+json", c.types[p])
        assertEquals("audio/wav", c.types[wav])
        assertTrue(c.missing.isEmpty())
    }

    @Test
    fun entriesKeepHistoryAndNamesStayOutsideTheHash() {
        val s = tempStore()
        val p1 = s.put(patch(0.5f))
        val id = s.create("Warm piano", Ref(p1), "Michiel", "2026-10-11T01:00:00Z")
        val p2 = s.put(patch(0.7f))
        s.update(id, "Michiel", "2026-10-11T01:05:00Z", target = Ref(p2))
        s.update(id, "Michiel", "2026-10-11T01:06:00Z", name = "Bright piano")

        val e = assertNotNull(s.entry(id))
        assertEquals("Bright piano", e.version.name)
        assertEquals(p2, e.version.target?.digest)
        val h = s.history(e.latest)
        assertEquals(3, h.size)
        assertEquals(p1, h.last().second.target?.digest)
        assertEquals(id, h.last().first)                 // the first version is the identity
        // Renaming did not touch the patch object.
        assertEquals(p2, h[0].second.target?.digest)
    }

    @Test
    fun gcKeepsHistoryAndDropsOrphans() {
        val s = tempStore()
        val p1 = s.put(patch(0.5f))
        val id = s.create("P", Ref(p1), "M", "t1")
        s.update(id, "M", "t2", target = Ref(s.put(patch(0.6f))))
        val orphan = s.put(patch(0.9f))
        s.gc()
        assertTrue(s.has(p1), "an old version's target stays reachable through history")
        assertTrue(!s.has(orphan))
        assertNotNull(s.entry(id))
    }

    @Test
    fun exportImportIsLosslessAndVerified() {
        val a = tempStore()
        val wav = a.put(ByteArray(2000) { it.toByte() })
        val p = a.put(patch(0.25f, Ref(wav, "audio/wav")))
        val id = a.create("Set", Ref(a.put(song(Ref(p)))), "M", "t")
        val out = ByteArrayOutputStream()
        Exchange.export(a, listOf(id), out)

        val b = tempStore()
        val report = Exchange.import(b, ByteArrayInputStream(out.toByteArray()))
        assertIs<Store.MergeResult.Added>(report.results[id.hex])
        assertEquals(a.entry(id)?.latest, b.entry(id)?.latest)
        assertTrue(b.has(wav) && b.has(p))
        // Importing again changes nothing.
        val again = Exchange.import(b, ByteArrayInputStream(out.toByteArray()))
        assertIs<Store.MergeResult.Same>(again.results[id.hex])

        // A tampered blob rejects the whole archive.
        val tampered = out.toByteArray().also { bytes ->
            val i = indexOf(bytes, ByteArray(2000) { it.toByte() }.copyOfRange(100, 140))
            bytes[i] = (bytes[i] + 1).toByte()
        }
        assertFailsWith<IllegalArgumentException> { Exchange.import(tempStore(), ByteArrayInputStream(tampered)) }
    }

    @Test
    fun fastForwardAndDivergence() {
        val a = tempStore()
        val id = a.create("P", Ref(a.put(patch(0.1f))), "M", "t1")
        val base = ByteArrayOutputStream().also { Exchange.export(a, listOf(id), it) }.toByteArray()

        val b = tempStore()
        Exchange.import(b, ByteArrayInputStream(base))
        // b moves ahead; a imports it: fast-forward.
        b.update(id, "M", "t2", target = Ref(b.put(patch(0.2f))))
        val ahead = ByteArrayOutputStream().also { Exchange.export(b, listOf(id), it) }.toByteArray()
        assertIs<Store.MergeResult.FastForward>(Exchange.import(a, ByteArrayInputStream(ahead)).results[id.hex])

        // Both move from the same version: a divergence, kept, never resolved silently.
        a.update(id, "M", "t3", target = Ref(a.put(patch(0.3f))))
        b.update(id, "M", "t3b", target = Ref(b.put(patch(0.4f))))
        val theirs = ByteArrayOutputStream().also { Exchange.export(b, listOf(id), it) }.toByteArray()
        val r = Exchange.import(a, ByteArrayInputStream(theirs)).results[id.hex]
        assertIs<Store.MergeResult.Diverged>(r)
        val mine = a.entry(id)!!.version.target
        val div = a.divergences()[id]
        assertEquals(1, div?.size)
        // The user picks theirs.
        a.resolve(id, div!!.first(), "M", "t4")
        assertEquals(b.entry(id)!!.version.target, a.entry(id)!!.version.target)
        assertTrue(mine != a.entry(id)!!.version.target)
        assertNull(a.divergences()[id])
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        error("not found")
    }

    @Suppress("unused")
    private fun File.tree(): List<String> = walk().filter { it.isFile }.map { it.relativeTo(this).path }.toList()
}
