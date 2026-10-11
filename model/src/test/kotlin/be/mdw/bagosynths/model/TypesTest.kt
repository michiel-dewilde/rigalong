package be.mdw.bagosynths.model

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypesTest {

    @Test
    fun typedObjectsRoundTripWithStableHashes() {
        val store = Store(Files.createTempDirectory("bos-types").toFile())
        val patch = Patch(engine = "hammond", params = mapOf("drawbar.16" to f32(0.875f), "leslie" to 1.0))
        val p = store.putTyped(patch)
        assertEquals(patch, store.getTyped<Patch>(p))

        val verse = LiveSet(name = "Verse", overrides = mapOf("organ.drawbar.16" to f32(0.5f)),
            wires = mapOf("organToOut" to WireState(on = false)))
        val v = store.putTyped(verse)
        val song = Song(
            nodes = mapOf(
                "in" to Node(kind = "port.midiIn", port = "Keys"),
                "organ" to Node(kind = "hammond", patch = RefJ.of(p, Patch.TYPE)),
                "out" to Node(kind = "port.audioOut", port = "Main Out"),
            ),
            wires = mapOf("keysToOrgan" to Wire("in:midi", "organ:midi"), "organToOut" to Wire("organ:out", "out:in")),
            recall = mapOf("organ.leslie" to Recall.KEEP),
            liveSets = listOf(RefJ.of(v, LiveSet.TYPE)),
            tempo = Tempo(bpm = 120.0),
        )
        val s = store.putTyped(song)
        val back = store.getTyped<Song>(s)
        assertEquals(song, back)
        assertEquals(s, store.putTyped(back), "re-encoding gives the same object")

        // The song reaches its live set and its patch, generically.
        val c = store.closure(listOf(Ref(s)))
        assertEquals(setOf(s, v, p), c.types.keys)
    }

    @Test
    fun defaultsAreNeverWritten() {
        val text = Types.toJson(LiveSet(name = "A")).canonical()
        assertEquals("""{"@type":"bagosynths.liveset/1","name":"A"}""", text)
        assertFalse("null" in Types.toJson(Song()).canonical())
    }

    @Test
    fun cp88ProfileMatchesFbx() {
        val j = Types.toJson(UsbProfile.CP88).canonical()
        assertTrue("\"startFill\":176" in j)
        assertTrue("\"nominal\":44.1" in j)
        assertEquals(UsbProfile.CP88, Types.fromJson<UsbProfile>(Json.parse(j)))
    }
}
