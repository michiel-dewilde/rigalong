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
            RackInstance("setbfree", RackEngine.SETBFREE, RackSlot(preset = "Jimmy Smith", level = 0.7,
                params = mapOf("Upper 16" to 8.0, "Leslie" to 2.0))),
        ),
        liveSets = listOf(
            RackLiveSet("Verse", mapOf("sfizz" to RackPart(), "setbfree" to RackPart(plays = false))),
            RackLiveSet("Split", mapOf(
                "setbfree" to RackPart(keyHigh = 59, transpose = 12),
                "sfizz" to RackPart(keyLow = 60, level = 1.0))),
        ),
    )

    @Test
    fun aRackSongRoundTrips() {
        val song = draft.toObjects(store)
        val back = assertNotNull(RackSong.fromObjects(store, song))
        assertEquals(draft.instances, back.instances)
        assertEquals(draft.liveSets.map { it.name }, back.liveSets.map { it.name })
        assertEquals(draft.liveSets[1].parts["setbfree"], back.liveSets[1].parts["setbfree"])
        assertEquals(false, back.liveSets[0].parts["setbfree"]?.plays)
    }

    @Test
    fun aRackSongSwitchesLikeAnySong() {
        val song = draft.toObjects(store)
        val liveSets = song.liveSets.map { store.getTyped<LiveSet>(it.toRef().digest) }
        val s = LiveSetSwitcher(song, liveSets)
        s.select(0)
        val cs = s.select(1)
        assertTrue(Change.WireSet("setbfree.midi", true, 1.0) in cs, "setBfree plays in the split")
        val p = cs.filterIsInstance<Change.Param>().associate { it.key to it.value }
        assertEquals(59.0, p["setbfree.zone.keyHigh"])
        assertEquals(12.0, p["setbfree.zone.transpose"])
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
        assertEquals(null, back.instance("setbfree")?.slot?.fx)
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
        assertEquals(setOf("in", "mix", "out", "sfizz", "sfizz.zone", "setbfree", "setbfree.zone"), nodes.keys)
        assertEquals(null, nodes["sfizz"]?.label)
        assertEquals(draft, RackSong.fromObjects(store, draft.toObjects(store))?.let { back ->
            back.copy(instances = back.instances.sortedBy { if (it.id == "sfizz") 0 else 1 })
        })
    }

    @Test
    fun twoSfizzAreTwoInstances() {
        val two = draft.plus(RackEngine.SFIZZ, RackSlot(preset = "salamander-tight", level = 0.5))
        val second = two.instances.last()
        assertEquals("sfizz-2", second.id)
        assertEquals("sfizz 2", two.displayName(second))
        assertEquals("sfizz 1", two.displayName(two.instance("sfizz")!!))
        val named = two.copy(instances = two.instances.map { if (it.id == "sfizz-2") it.copy(label = "Bright") else it },
            liveSets = two.liveSets.map { it.copy(parts = it.parts + ("sfizz-2" to RackPart(keyLow = 72))) })
        val song = named.toObjects(store)
        assertEquals("sfizz", song.nodes["sfizz-2"]?.kind)
        assertEquals("Bright", song.nodes["sfizz-2"]?.label)
        val back = assertNotNull(RackSong.fromObjects(store, song))
        assertEquals(listOf("sfizz", "sfizz-2", "setbfree"), back.instances.map { it.id })
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

    // ------------------------------------------- a live set's own sound values

    // "Upper 16" the patch sets; "Upper 8" it leaves to the preset, heard at 8.
    private val tweaked = draft.withLiveSetValue(1, "setbfree", "Upper 16", 4.0, songValue = 8.0)
        .withLiveSetValue(1, "setbfree", "Upper 8", 6.0, songValue = 8.0)

    @Test
    fun theSongKeepsTheValueThePatchLeavesToItsPreset() {
        val slot = tweaked.instance("setbfree")!!.slot
        assertEquals(mapOf("Upper 8" to 8.0), slot.songValues, "not Upper 16: the patch has that")
        assertEquals(draft.instance("setbfree")!!.slot.params, slot.params, "the patch is untouched")
        assertEquals(8.0, tweaked.songValue("setbfree", "Upper 16"))
        assertEquals(8.0, tweaked.songValue("setbfree", "Upper 8"))
        // A second value of its own keeps the first song value.
        val again = tweaked.withLiveSetValue(1, "setbfree", "Upper 8", 2.0, songValue = 6.0)
        assertEquals(mapOf("Upper 8" to 8.0), again.instance("setbfree")!!.slot.songValues)
        // No live set differs any more: the song value goes too.
        val back = tweaked.withPart(1, "setbfree", tweaked.liveSets[1].parts["setbfree"]!!.copy(params = emptyMap()))
        assertEquals(emptyMap(), back.instance("setbfree")!!.slot.songValues)
    }

    @Test
    fun aLiveSetsOwnValuesRoundTrip() {
        val back = RackSong.fromObjects(store, tweaked.toObjects(store))!!
        assertEquals(mapOf("Upper 16" to 4.0, "Upper 8" to 6.0), back.liveSets[1].parts["setbfree"]?.params)
        assertEquals(emptyMap(), back.liveSets[0].parts["setbfree"]?.params)
        assertEquals(tweaked, back)
    }

    @Test
    fun noOwnValuesKeepsTheSongsHash() {
        // Songs written before live sets had sound values are the same objects.
        assertEquals(store.putTyped(draft.toObjects(store)),
            store.putTyped(draft.withPart(1, "setbfree", draft.liveSets[1].parts["setbfree"]!!).toObjects(store)))
    }

    @Test
    fun leavingALiveSetPutsTheSongsValueBack() {
        val song = tweaked.toObjects(store)
        val liveSets = song.liveSets.map { store.getTyped<LiveSet>(it.toRef().digest) }
        val s = LiveSetSwitcher(song, liveSets)
        s.select(0)
        val into = s.select(1).filterIsInstance<Change.Param>().associate { it.key to it.value }
        assertEquals(4.0, into["setbfree.Upper 16"])
        assertEquals(6.0, into["setbfree.Upper 8"])
        val back = s.select(0).filterIsInstance<Change.Param>().associate { it.key to it.value }
        assertEquals(8.0, back["setbfree.Upper 16"], "the patch's value")
        assertEquals(8.0, back["setbfree.Upper 8"], "the preset's value")
    }

    @Test
    fun useInSongMovesTheValueIntoThePatch() {
        val s = tweaked.useInSong(1, "setbfree", "Upper 8")
        assertEquals(6.0, s.instance("setbfree")!!.slot.params["Upper 8"])
        assertEquals(mapOf("Upper 16" to 4.0), s.liveSets[1].parts["setbfree"]!!.params)
        assertEquals(emptyMap(), s.instance("setbfree")!!.slot.songValues, "the patch has it now")
        // Already the song's: nothing more to move.
        assertEquals(s, s.useInSong(1, "setbfree", "Upper 8"))
    }

    @Test
    fun aLayerForOneLiveSet() {
        val (s, id) = tweaked.layerFor(1, "setbfree")
        assertEquals("setbfree-2", id)
        val layer = s.instance(id)!!
        assertEquals("Jimmy Smith", layer.slot.preset)
        assertEquals(mapOf("Upper 16" to 4.0, "Leslie" to 2.0, "Upper 8" to 6.0), layer.slot.params)
        assertEquals(emptyMap(), layer.slot.songValues)
        val split = s.liveSets[1]
        assertEquals(false, split.parts["setbfree"]!!.plays)
        val part = split.parts[id]!!
        assertTrue(part.plays)
        assertEquals(59, part.keyHigh, "it plays where the organ played")
        assertEquals(12, part.transpose)
        assertEquals(emptyMap(), part.params)
        assertEquals(false, s.liveSets[0].parts[id]!!.plays, "and nowhere else")
        assertEquals(s, RackSong.fromObjects(store, s.toObjects(store)))
    }

    @Test
    fun aLayerOfAnUnchangedLibraryPatchKeepsItsLink() {
        val linked = draft.withSlot("sfizz", draft.instance("sfizz")!!.slot.copy(libraryPatch = Digest.of(byteArrayOf(7))))
        val (s, id) = linked.layerFor(1, "sfizz")
        assertEquals(linked.instance("sfizz")!!.slot.libraryPatch, s.instance(id)!!.slot.libraryPatch)
        val (t, id2) = tweaked.withSlot("setbfree", tweaked.instance("setbfree")!!.slot.copy(libraryPatch = Digest.of(byteArrayOf(7))))
            .layerFor(1, "setbfree")
        assertEquals(null, t.instance(id2)!!.slot.libraryPatch, "changed here: a sound of its own")
    }

    @Test
    fun controlsRoundTripAndLeaveOtherSongsAlone() {
        val c = draft.withControl("setbfree", "Upper 16", 21).withControl("sfizz", "Level", 7)
        assertEquals(mapOf("Upper 16" to 21), c.instance("setbfree")!!.controls)
        val song = c.toObjects(store)
        assertEquals(listOf("sfizz.Level", "setbfree.Upper 16"), song.controls.map { it.target })
        assertEquals(c, RackSong.fromObjects(store, song))
        val off = c.withControl("setbfree", "Upper 16", null).withControl("sfizz", "Level", null)
        assertEquals(draft, off)
        assertEquals(store.putTyped(draft.toObjects(store)), store.putTyped(off.toObjects(store)))
    }
}
