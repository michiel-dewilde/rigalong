package be.mdw.bagosynths.model

import be.mdw.bagosynths.model.LiveSetSwitcher.Change
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RackTest {

    private val store = Store(Files.createTempDirectory("bos-rack").toFile())

    private val draft = RackSong(
        instances = listOf(
            RackInstance("sfizz", RackEngine.SFIZZ, RackSlot(preset = "salamander-grand", level = 0.9)),
            RackInstance("hammond", RackEngine.HAMMOND, RackSlot(preset = "Jimmy Smith", level = 0.7,
                params = mapOf("Upper 16" to 8.0, "Leslie" to 2.0))),
        ),
        liveSets = listOf(
            RackLiveSet("Verse", mapOf("sfizz" to RackPart(), "hammond" to RackPart(plays = false))),
            RackLiveSet("Split", mapOf(
                "hammond" to RackPart(keyHigh = 59, transpose = 12),
                "sfizz" to RackPart(keyLow = 60, level = 1.0))),
        ),
    )

    @Test
    fun aRackSongRoundTrips() {
        val song = draft.toObjects(store)
        val back = assertNotNull(RackSong.fromObjects(store, song))
        assertEquals(draft.instances, back.instances)
        assertEquals(draft.liveSets.map { it.name }, back.liveSets.map { it.name })
        assertEquals(draft.liveSets[1].parts["hammond"], back.liveSets[1].parts["hammond"])
        assertEquals(false, back.liveSets[0].parts["hammond"]?.plays)
    }

    @Test
    fun aRackSongSwitchesLikeAnySong() {
        val song = draft.toObjects(store)
        val liveSets = song.liveSets.map { store.getTyped<LiveSet>(it.toRef().digest) }
        val s = LiveSetSwitcher(song, liveSets)
        s.select(0)
        val cs = s.select(1)
        assertTrue(Change.WireSet("hammond.midi", true, 1.0) in cs, "the organ plays in the split")
        val p = cs.filterIsInstance<Change.Param>().associate { it.key to it.value }
        assertEquals(59.0, p["hammond.zone.keyHigh"])
        assertEquals(12.0, p["hammond.zone.transpose"])
        assertEquals(60.0, p["sfizz.zone.keyLow"])
        assertEquals(1.0, p["sfizz.Level"])
    }

    @Test
    fun cp88ActionsRoundTrip() {
        val sound = store.put(byteArrayOf(1, 2, 3))
        val d = draft.copy(liveSets = listOf(
            draft.liveSets[0].copy(cp88 = RackCp88("select", page = 3, set = 5)),
            draft.liveSets[1].copy(cp88 = RackCp88("own", sound = sound)),
        ))
        val back = RackSong.fromObjects(store, d.toObjects(store))!!
        assertEquals(RackCp88("select", 3, 5), back.liveSets[0].cp88)
        assertEquals("own", back.liveSets[1].cp88.mode)
        assertEquals(sound, back.liveSets[1].cp88.sound)
        // The song reaches its CP-88 sound, so export carries it.
        val c = store.closure(listOf(Ref(store.putTyped(d.toObjects(store)))))
        assertTrue(sound in c.types.keys)
    }

    @Test
    fun theLeadSheetTravelsWithTheSong() {
        val pdf = store.put("%PDF-1.4 test".toByteArray())
        val song = draft.copy(leadSheet = pdf).toObjects(store)
        assertEquals(pdf, RackSong.fromObjects(store, song)!!.leadSheet)
        val c = store.closure(listOf(Ref(store.putTyped(song))))
        assertEquals("application/pdf", c.types[pdf])
    }

    @Test
    fun theReverbTravelsWithTheSong() {
        val wet = draft.copy(reverb = mapOf("Mix" to 0.4, "Room size" to 0.9))
        val back = assertNotNull(RackSong.fromObjects(store, wet.toObjects(store)))
        assertEquals(wet.reverb, back.reverb)
        // A song that leaves the reverb alone is the same object as before the
        // reverb could be set: no song changes hash by this alone.
        assertEquals(Node(kind = "reverb"), draft.toObjects(store).nodes["mix"])
    }

    @Test
    fun anInsertEffectTravelsWithItsEngine() {
        val fx = RackFx("delay", mapOf("Left" to -1.0, "Left sync" to 1.0, "Mix" to 0.3))
        val wet = draft.withSlot("sfizz", draft.instance("sfizz")!!.slot.copy(fx = fx))
        val song = wet.toObjects(store)
        assertEquals("fx.delay", song.nodes["sfizz.fx"]?.kind)
        assertEquals("sfizz.fx:out", song.wires["sfizz.audio"]?.from)
        val back = assertNotNull(RackSong.fromObjects(store, song))
        assertEquals(fx, back.instance("sfizz")?.slot?.fx)
        assertEquals(null, back.instance("hammond")?.slot?.fx)
        // Without one, the engine is wired as it always was.
        assertEquals(null, draft.toObjects(store).nodes["sfizz.fx"])
    }

    @Test
    fun eachLiveSetSaysHowMuchGoesThroughTheEffect() {
        val fx = RackFx("delay")
        val withFx = draft.copy(
            instances = draft.withSlot("sfizz", draft.instance("sfizz")!!.slot.copy(fx = fx)).instances,
            liveSets = listOf(
                draft.liveSets[0],
                draft.liveSets[1].copy(parts = draft.liveSets[1].parts +
                    ("sfizz" to draft.liveSets[1].parts["sfizz"]!!.copy(fxAmount = 0.0)))),
        )
        val song = withFx.toObjects(store)
        val sets = song.liveSets.map { store.getTyped<LiveSet>(it.toRef().digest) }
        // Every live set carries the amount, so switching always recalls it.
        assertEquals(listOf(1.0, 0.0), sets.map { it.overrides["sfizz.fx.Amount"] })
        val back = assertNotNull(RackSong.fromObjects(store, song))
        assertEquals(0.0, back.liveSets[1].parts["sfizz"]?.fxAmount)
        // No effect, no amount.
        val plain = draft.toObjects(store).liveSets.map { store.getTyped<LiveSet>(it.toRef().digest) }
        assertTrue(plain.none { "sfizz.fx.Amount" in it.overrides })
    }

    @Test
    fun aMasterEffectSitsBetweenTheMixAndTheOutput() {
        val hall = RackFx("reverb", mapOf("Mix" to 0.25))
        val song = draft.copy(masterFx = hall).toObjects(store)
        assertEquals("mix.fx:out", song.wires["mix.out"]?.from)
        assertEquals(hall, RackSong.fromObjects(store, song)?.masterFx)
        assertEquals("mix:out", draft.toObjects(store).wires["mix.out"]?.from)
    }

    @Test
    fun aProgramChangeOnSelectRoundTrips() {
        val pc = RackProgram(channel = 3, program = 12, bankMsb = 0, bankLsb = 5)
        assertEquals("b20000b22005c20b", pc.hex())
        assertEquals(pc, RackProgram.parse(pc.hex()))
        assertEquals(RackProgram(1, 128), RackProgram.parse(RackProgram(1, 128).hex()))
        assertEquals(null, RackProgram.parse("b0076e"))          // a volume change is not one
        val song = draft.copy(liveSets = listOf(draft.liveSets[0].copy(program = pc), draft.liveSets[1])).toObjects(store)
        val back = assertNotNull(RackSong.fromObjects(store, song))
        assertEquals(pc, back.liveSets[0].program)
        assertEquals(null, back.liveSets[1].program)
        val switcher = LiveSetSwitcher(song, song.liveSets.map { store.getTyped<LiveSet>(it.toRef().digest) })
        assertTrue(switcher.select(0).contains(LiveSetSwitcher.Change.Send("Keys", "b20000b22005c20b")))
    }

    @Test
    fun aSongWithOneInstanceOfEachEngineKeepsItsHash() {
        // The shape songs had before instances were first class, written by hand.
        val nodes = draft.toObjects(store).nodes
        assertEquals(setOf("in", "mix", "out", "sfizz", "sfizz.zone", "hammond", "hammond.zone"), nodes.keys)
        assertEquals(null, nodes["sfizz"]?.label)
        assertEquals(draft, RackSong.fromObjects(store, draft.toObjects(store))?.let { back ->
            back.copy(instances = back.instances.sortedBy { if (it.id == "sfizz") 0 else 1 })
        })
    }

    @Test
    fun twoPianosAreTwoInstances() {
        val two = draft.plus(RackEngine.SFIZZ, RackSlot(preset = "salamander-tight", level = 0.5))
        val second = two.instances.last()
        assertEquals("sfizz-2", second.id)
        assertEquals("Piano 2", two.displayName(second))
        assertEquals("Piano 1", two.displayName(two.instance("sfizz")!!))
        val named = two.copy(instances = two.instances.map { if (it.id == "sfizz-2") it.copy(label = "Bright") else it },
            liveSets = two.liveSets.map { it.copy(parts = it.parts + ("sfizz-2" to RackPart(keyLow = 72))) })
        val song = named.toObjects(store)
        assertEquals("sfizz", song.nodes["sfizz-2"]?.kind)
        assertEquals("Bright", song.nodes["sfizz-2"]?.label)
        val back = assertNotNull(RackSong.fromObjects(store, song))
        assertEquals(listOf("sfizz", "sfizz-2", "hammond"), back.instances.map { it.id })
        assertEquals("salamander-tight", back.instance("sfizz-2")?.slot?.preset)
        assertEquals(72, back.liveSets[0].parts["sfizz-2"]?.keyLow)
        // Each switches on its own.
        val sets = song.liveSets.map { store.getTyped<LiveSet>(it.toRef().digest) }
        assertEquals(72.0, sets[0].overrides["sfizz-2.zone.keyLow"])
        // Removing one takes it out everywhere.
        val one = back.minus("sfizz-2")
        assertTrue(one.liveSets.none { "sfizz-2" in it.parts })
        assertEquals(null, one.toObjects(store).nodes["sfizz-2"])
    }

    @Test
    fun theSameDraftIsTheSameSong() {
        assertEquals(store.putTyped(draft.toObjects(store)), store.putTyped(draft.toObjects(store)))
    }
}
