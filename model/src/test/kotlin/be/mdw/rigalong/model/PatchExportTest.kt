package be.mdw.rigalong.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatchExportTest {
    // ------------------------------------------------------------------ DX7

    /** Dexed's INIT VOICE (PluginData.cpp), unpacked: 21 bytes per operator, OP6 first, then the rest. */
    private val dexedInitVoice = intArrayOf(
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
        99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 99, 0, 1, 0, 7,
        99, 99, 99, 99, 50, 50, 50, 50, 0, 0, 1, 35, 0, 0, 0, 1, 0, 3, 24,
        73, 78, 73, 84, 32, 86, 79, 73, 67, 69,
    )

    /** The DX7's packing of an unpacked (155-byte) voice into a cartridge's 128. */
    private fun pack(u: IntArray): ByteArray {
        val p = ByteArray(128)
        for (op in 0 until 6) {
            val s = op * 21; val d = op * 17
            for (i in 0..10) p[d + i] = u[s + i].toByte()
            p[d + 11] = ((u[s + 12] shl 2) or u[s + 11]).toByte()
            p[d + 12] = ((u[s + 20] shl 3) or u[s + 13]).toByte()
            p[d + 13] = ((u[s + 15] shl 2) or u[s + 14]).toByte()
            p[d + 14] = u[s + 16].toByte()
            p[d + 15] = ((u[s + 18] shl 1) or u[s + 17]).toByte()
            p[d + 16] = u[s + 19].toByte()
        }
        for (i in 0 until 8) p[102 + i] = u[126 + i].toByte()
        p[110] = u[134].toByte()
        p[111] = ((u[136] shl 3) or u[135]).toByte()
        for (i in 0 until 4) p[112 + i] = u[137 + i].toByte()
        p[116] = ((u[143] shl 4) or (u[142] shl 1) or u[141]).toByte()
        p[117] = u[144].toByte()
        for (i in 0 until 10) p[118 + i] = u[145 + i].toByte()
        return p
    }

    @Test
    fun initVoiceIsDexeds() {
        assertContentEquals(pack(dexedInitVoice), Dx7.INIT_VOICE)
        assertEquals("INIT VOICE", Dx7.name(Dx7.INIT_VOICE))
    }

    private fun dexedBanks(): List<Pair<String, ByteArray>> {
        val zip = File("../third_party/dexed/assets/builtin_pgm.zip")
        assertTrue(zip.isFile, "Dexed's cartridges at $zip")
        return ZipFile(zip).use { z ->
            z.entries().toList().filter { it.name.endsWith(".syx") }.map { it.name to z.getInputStream(it).readBytes() }
        }
    }

    @Test
    fun dexedsCartridgesRebuildByteForByte() {
        val banks = dexedBanks().filter { it.second.size == EngineFiles.DX7_BANK_BYTES && it.second[2].toInt() == 0 }
        assertTrue(banks.size >= 30, "${banks.size} banks")
        for ((name, b) in banks) assertContentEquals(b, Dx7.bank(Dx7.voices(b)), name)
    }

    @Test
    fun aDexedPatchIsABankOfOne() {
        val (_, bank) = dexedBanks().first()
        val voice = Dx7.voices(bank)[3]
        val patch = Patch(engine = "dexed", preset = "Dexed_01/${Dx7.name(voice)}", params = mapOf("Transpose" to -5.0, "Level" to 0.7))
        val out = PatchExport.dexed(patch, "Warm Bell (song)", voice)
        assertEquals(EngineFiles.Kind.DX7_BANK, EngineFiles.kind(out))
        val voices = Dx7.voices(out)
        assertEquals("Warm Bell ", String(voices[0], 118, 10, Charsets.US_ASCII))
        assertEquals((voice[117] - 5).toByte(), voices[0][117])
        // Only the name and transpose differ from the cartridge's voice.
        assertContentEquals(voice.copyOfRange(0, 117), voices[0].copyOfRange(0, 117))
        for (i in 1 until 32) assertContentEquals(Dx7.INIT_VOICE, voices[i])
        assertContentEquals(out, Dx7.bank(voices))   // and its checksum is the one a rebuild gives
        assertEquals("Warm Bell (song).syx", PatchExport.fileName("Warm Bell (song)", EngineFiles.Kind.DX7_BANK))
        // The DX7's two octaves either way are as far as a transpose goes.
        assertEquals(48, Dx7.voice(voice, semitones = 100)[117].toInt())
        assertEquals(0, Dx7.voice(voice, semitones = -100)[117].toInt())
    }

    // -------------------------------------------------------------- Surge XT

    private fun surgePatches(): List<File> {
        val dir = File("../third_party/surge/resources/data/patches_factory")
        assertTrue(dir.isDirectory, "Surge XT's factory patches at $dir")
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".fxp") }.sorted().toList()
    }

    @Test
    fun surgePatchesComeApartAndTogetherUnchanged() {
        val files = surgePatches()
        assertTrue(files.size > 100, "${files.size} patches")
        for (f in files) {
            val b = f.readBytes()
            val p = SurgeFxp.parts(b)
            val back = SurgeFxp.join(p, null)
            // Surge's own saves leave byteSize 0; ours then keep it 0 and change nothing.
            assertContentEquals(b, back, f.name)
            assertEquals(8, SurgeFxp.macros(b).size, f.name)
        }
    }

    @Test
    fun aSurgePatchCarriesItsMacrosAndName() {
        val files = surgePatches()
        for (f in files.take(40) + files.filter { "bipolar=\"1\"" in SurgeFxp.parts(it.readBytes()).xml }.take(5)) {
            val b = f.readBytes()
            val before = SurgeFxp.macros(b)
            val patch = Patch(engine = "surge", preset = "x/${f.nameWithoutExtension}",
                params = mapOf("Macro 1" to 0.25, "Macro 8" to 1.0, "Level" to 0.5))
            val out = PatchExport.surge(patch, "Lead & <Pad> \"ü\"", b)
            assertEquals(EngineFiles.Kind.SURGE_PATCH, EngineFiles.kind(out))
            val after = SurgeFxp.macros(out)
            assertEquals(0.25, after.getValue(0), 1e-6, f.name)
            assertEquals(1.0, after.getValue(7), 1e-6, f.name)
            for (i in 1..6) assertEquals(before.getValue(i), after.getValue(i), 1e-9, f.name)
            assertEquals("Lead & <Pad> \"ü\"", SurgeFxp.name(out))
            // The wavetables and everything else after the XML are the source's.
            assertContentEquals(SurgeFxp.parts(b).tail, SurgeFxp.parts(out).tail)
            // chunkSize keeps its distance to the file's end.
            assertEquals(ByteBuffer.wrap(b, 56, 4).int - b.size, ByteBuffer.wrap(out, 56, 4).int - out.size)
            assertEquals("Lead & <Pad> \"ü\"", String(out, 28, 28, Charsets.UTF_8).trimEnd('\u0000'))
        }
    }

    @Test
    fun aBipolarMacroIsWrittenAsSurgeKeepsIt() {
        val f = surgePatches().first { "bipolar=\"1\"" in SurgeFxp.parts(it.readBytes()).xml }
        val xml = SurgeFxp.parts(f.readBytes()).xml
        val i = Regex("<entry\\b[^>]*\\bi=\"(\\d)\"[^>]*bipolar=\"1\"").find(xml)!!.groupValues[1].toInt()
        val out = SurgeFxp.withMacros(f.readBytes(), mapOf(i to 0.25))
        val entry = Regex("<entry\\b[^>]*\\bi=\"$i\"[^>]*>").find(SurgeFxp.parts(out).xml)!!.value
        assertTrue("v=\"-0.5\"" in entry, entry)
    }

    // ------------------------------------------------------------- SoundFonts

    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun name20(s: String) = ByteArray(20).also { s.toByteArray().copyInto(it) }
    private fun chunk(id: String, data: ByteArray) = id.toByteArray() + le32(data.size) + data + ByteArray(data.size % 2)
    private fun list(kind: String, vararg chunks: ByteArray) = chunk("LIST", kind.toByteArray() + chunks.fold(ByteArray(0)) { a, b -> a + b })

    private class Zone(val gens: List<Pair<Int, Int>>)
    private class Smp(val name: String, val points: ShortArray, val loop: Pair<Int, Int>, val type: Int = 1, val link: Int = 0)

    /** A small SF2 written by hand, to the letter of the spec. */
    private fun font(presets: List<Triple<String, Pair<Int, Int>, List<Zone>>>, insts: List<Pair<String, List<Zone>>>, samples: List<Smp>): ByteArray {
        val smpl = ByteArrayOutputStream(); val shdr = ByteArrayOutputStream()
        for (s in samples) {
            val start = smpl.size() / 2
            for (v in s.points) smpl.write(le16(v.toInt()))
            smpl.write(ByteArray(92))
            shdr.write(name20(s.name) + le32(start) + le32(start + s.points.size) + le32(start + s.loop.first) + le32(start + s.loop.second) +
                le32(44100) + byteArrayOf(60, 0) + le16(s.link) + le16(s.type))
        }
        shdr.write(name20("EOS") + ByteArray(26))
        fun zones(items: List<List<Zone>>): Triple<ByteArray, ByteArray, List<Int>> {
            val bag = ByteArrayOutputStream(); val gen = ByteArrayOutputStream(); val starts = ArrayList<Int>()
            var g = 0; var z = 0
            for (zs in items) {
                starts += z
                for (zone in zs) { bag.write(le16(g) + le16(0)); for ((o, a) in zone.gens) gen.write(le16(o) + le16(a)); g += zone.gens.size; z++ }
            }
            starts += z
            bag.write(le16(g) + le16(0)); gen.write(ByteArray(4))
            return Triple(bag.toByteArray(), gen.toByteArray(), starts)
        }
        val (pbag, pgen, pstart) = zones(presets.map { it.third })
        val (ibag, igen, istart) = zones(insts.map { it.second })
        val phdr = ByteArrayOutputStream()
        presets.forEachIndexed { i, (n, bp, _) -> phdr.write(name20(n) + le16(bp.second) + le16(bp.first) + le16(pstart[i]) + ByteArray(12)) }
        phdr.write(name20("EOP") + le16(0) + le16(0) + le16(pstart.last()) + ByteArray(12))
        val inst = ByteArrayOutputStream()
        insts.forEachIndexed { i, (n, _) -> inst.write(name20(n) + le16(istart[i])) }
        inst.write(name20("EOI") + le16(istart.last()))
        return chunk("RIFF", "sfbk".toByteArray() +
            list("INFO", chunk("ifil", le16(2) + le16(1)), chunk("isng", "EMU8000\u0000".toByteArray()),
                chunk("INAM", "Test font\u0000".toByteArray()), chunk("ICOP", "CC0, the tester\u0000".toByteArray())) +
            list("sdta", chunk("smpl", smpl.toByteArray())) +
            list("pdta", chunk("phdr", phdr.toByteArray()), chunk("pbag", pbag), chunk("pmod", ByteArray(10)), chunk("pgen", pgen),
                chunk("inst", inst.toByteArray()), chunk("ibag", ibag), chunk("imod", ByteArray(10)), chunk("igen", igen),
                chunk("shdr", shdr.toByteArray())))
    }

    /** What a SoundFont says, read back plainly: for checking, not for playing. */
    private class Read(bytes: ByteArray) {
        val tables = HashMap<String, ByteBuffer>()
        val info = LinkedHashMap<String, String>()
        var smpl = ByteArray(0)

        init {
            fun walk(from: Int, to: Int, kind: String) {
                var at = from
                while (at + 8 <= to) {
                    val id = String(bytes, at, 4, Charsets.ISO_8859_1)
                    val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    when {
                        id == "LIST" -> walk(at + 12, at + 8 + size, String(bytes, at + 8, 4, Charsets.ISO_8859_1))
                        kind == "INFO" -> info[id] = String(bytes, at + 8, size, Charsets.ISO_8859_1).trimEnd('\u0000')
                        id == "smpl" -> smpl = bytes.copyOfRange(at + 8, at + 8 + size)
                        kind == "pdta" -> tables[id] = ByteBuffer.wrap(bytes.copyOfRange(at + 8, at + 8 + size)).order(ByteOrder.LITTLE_ENDIAN)
                    }
                    at += 8 + size + (size and 1)
                }
            }
            assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("sfbk", String(bytes, 8, 4))
            assertEquals(bytes.size - 8, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            walk(12, bytes.size, "")
        }

        fun u16(t: String, at: Int) = tables.getValue(t).getShort(at).toInt() and 0xFFFF
        fun s16(t: String, at: Int) = tables.getValue(t).getShort(at).toInt()
        fun u32(t: String, at: Int) = tables.getValue(t).getInt(at).toLong() and 0xFFFFFFFFL
        fun name(t: String, at: Int) = String(ByteArray(20) { tables.getValue(t).get(at + it) }, Charsets.ISO_8859_1).substringBefore('\u0000').trimEnd()
        fun count(t: String, record: Int) = tables.getValue(t).capacity() / record - 1

        fun zones(bag: String, gen: String, from: Int, to: Int) = (from until to).map { z ->
            (u16(bag, z * 4) until u16(bag, z * 4 + 4)).map { u16(gen, it * 4) to s16(gen, it * 4 + 2) }
        }
        fun presetZones(p: Int) = zones("pbag", "pgen", u16("phdr", p * 38 + 24), u16("phdr", p * 38 + 38 + 24))
        fun instZones(i: Int) = zones("ibag", "igen", u16("inst", i * 22 + 20), u16("inst", i * 22 + 22 + 20))

        /** A sample's sound: its points (or Vorbis bytes), its loop relative to its start, rate, pitch, type. */
        fun sample(s: Int): List<Any> {
            val at = s * 46
            val type = u16("shdr", at + 44)
            val start = u32("shdr", at + 20); val end = u32("shdr", at + 24)
            if (type and 0x8000 != 0) return (0 until 46).map { tables.getValue("shdr").get(at + it) }.filterIndexed { i, _ -> i !in 42..43 }
            val vorbis = type and 0x10 != 0
            val data = if (vorbis) smpl.copyOfRange(start.toInt(), end.toInt()) else smpl.copyOfRange(start.toInt() * 2, end.toInt() * 2)
            val rel = if (vorbis) 0L else start
            return listOf(data.toList(), u32("shdr", at + 28) - rel, u32("shdr", at + 32) - rel, u32("shdr", at + 36),
                tables.getValue("shdr").get(at + 40), tables.getValue("shdr").get(at + 41), type, name("shdr", at))
        }
    }

    /**
     * The voices [font] starts for [key] at [vel] on preset [p]: each its
     * sample's sound, its pitch from the sample's root in cents, and every
     * other generator as it ends up -- the instrument's (zone over global)
     * beside the preset's (likewise), key-scaled times worked out for the key.
     */
    private fun voices(r: Read, p: Int, key: Int, vel: Int): List<List<Any>> {
        fun range(z: List<Pair<Int, Int>>, global: List<Pair<Int, Int>>?, oper: Int): IntRange {
            val a = (z.firstOrNull { it.first == oper } ?: global?.firstOrNull { it.first == oper })?.second ?: return 0..127
            return (a and 0xFF)..((a shr 8) and 0xFF)
        }
        fun effective(zs: List<List<Pair<Int, Int>>>, terminal: Int, z: List<Pair<Int, Int>>): Map<Int, Int> {
            val global = zs.firstOrNull()?.takeIf { g -> g.none { it.first == terminal } }
            return (global.orEmpty() + z).filter { it.first !in setOf(43, 44, terminal) }.toMap()
        }
        val out = ArrayList<List<Any>>()
        val pz = r.presetZones(p)
        val pGlobal = pz.firstOrNull()?.takeIf { g -> g.none { it.first == 41 } }
        for (z in pz) {
            if (z.none { it.first == 41 } || key !in range(z, pGlobal, 43) || vel !in range(z, pGlobal, 44)) continue
            val i = z.last().second and 0xFFFF
            val pe = effective(pz, 41, z).toMutableMap()
            val iz = r.instZones(i)
            val iGlobal = iz.firstOrNull()?.takeIf { g -> g.none { it.first == 53 } }
            for (y in iz) {
                if (y.none { it.first == 53 } || key !in range(y, iGlobal, 43) || vel !in range(y, iGlobal, 44)) continue
                val s = y.last().second and 0xFFFF
                val ie = effective(iz, 53, y).toMutableMap()
                val k = ie[46]?.takeIf { it >= 0 } ?: key
                for ((time, scaling) in listOf(27 to 31, 28 to 32, 35 to 39, 36 to 40)) {
                    ie[time] = (ie[time] ?: -12000) + (ie[scaling] ?: 0) * (60 - k); ie.remove(scaling)
                    pe[time] = (pe[time] ?: 0) + (pe[scaling] ?: 0) * (60 - k); pe.remove(scaling)
                }
                val root = ie.remove(58)?.takeIf { it >= 0 } ?: (r.tables.getValue("shdr").get(s * 46 + 40).toInt() and 0xFF).let { if (it > 127) 60 else it }
                // The pitch from the sample's own, in cents: key, root key, scale and the zone's tune together.
                val cents = (k - root) * (ie.remove(56) ?: 100) + (ie.remove(51) ?: 0) * 100 + (ie.remove(52) ?: 0)
                val partner = r.u16("shdr", s * 46 + 42).takeIf { r.u16("shdr", s * 46 + 44) and 14 != 0 }?.let { r.sample(it) }
                out += listOf(r.sample(s), partner ?: "", cents, ie.toSortedMap().toString(), pe.toSortedMap().toString())
            }
        }
        return out
    }

    /**
     * That [cut] plays as preset [p] of [src] does, transposed by
     * [semitones] as FluidSynth plays it: key n of the cut starts the voices
     * key n + semitones of the source does, at every key and some velocities.
     */
    private fun assertPlaysAs(src: Read, p: Int, cut: Read, semitones: Int, audible: Boolean = true) {
        assertEquals(1, cut.count("phdr", 38))
        assertEquals(src.u16("phdr", p * 38 + 20), cut.u16("phdr", 20))
        assertEquals(src.u16("phdr", p * 38 + 22), cut.u16("phdr", 22))
        var heard = 0
        for (key in 0..127) {
            if (key + semitones !in 0..127) continue
            for (vel in listOf(1, 64, 127)) {
                val want = voices(src, p, key + semitones, vel)
                val got = voices(cut, 0, key, vel)
                // Pitch and generators first: a sample's bytes make an unreadable message.
                assertEquals(want.map { it.drop(2) }, got.map { it.drop(2) }, "key $key velocity $vel")
                assertEquals(want, got, "key $key velocity $vel")
                heard += want.size
            }
        }
        assertTrue(heard > 0 || !audible || voices(src, p, 60, 100).isEmpty(), "nothing plays")
    }

    private fun ramp(n: Int, k: Int) = ShortArray(n) { (it * k - 1000).toShort() }

    private val testFont by lazy {
        font(
            presets = listOf(
                Triple("Other", 0 to 5, listOf(Zone(listOf(41 to 2)))),
                Triple("Stereo", 0 to 1, listOf(
                    Zone(listOf(51 to 2)),                                           // global: up a tone
                    Zone(listOf(43 to (63 shl 8), 41 to 1)),                         // keys 0-63
                    Zone(listOf(43 to ((127 shl 8) or 64), 51 to -1, 41 to 0)))),    // keys 64-127, own tune
                Triple("Dup", 0 to 1, listOf(Zone(listOf(41 to 2)))),
                Triple("Drums", 128 to 0, listOf(Zone(listOf(41 to 0)))),
            ),
            insts = listOf(
                "Mono inst" to listOf(Zone(listOf(48 to 10, 40 to 50)), Zone(listOf(43 to (60 shl 8), 53 to 0))),
                "Stereo inst" to listOf(Zone(listOf(17 to -500, 53 to 1)), Zone(listOf(17 to 500, 53 to 2))),
                "Other inst" to listOf(Zone(listOf(53 to 3))),
            ),
            samples = listOf(
                Smp("Mono", ramp(100, 7), 10 to 90),
                Smp("L", ramp(50, 11), 5 to 45, type = 4, link = 2),
                Smp("R", ramp(50, 13), 5 to 45, type = 2, link = 1),
                Smp("Unused", ramp(30, 3), 0 to 29),
            ),
        )
    }

    private fun temp(bytes: ByteArray, ext: String = "sf2") = File.createTempFile("font", ".$ext").apply { deleteOnExit(); writeBytes(bytes) }

    @Test
    fun presetsAreInFluidSynthsOrder() {
        val ps = SoundFontFile.presets(temp(testFont))
        assertEquals(listOf("Stereo", "Dup", "Other", "Drums"), ps.map { it.name })
        assertEquals(listOf(0 to 1, 0 to 1, 0 to 5, 128 to 0), ps.map { it.bank to it.program })
    }

    @Test
    fun onePresetIsCutWithWhatItPlays() {
        val src = Read(testFont)
        val bytes = SoundFontFile.onePreset(temp(testFont), 0, "My Pad", semitones = 3)
        assertEquals(EngineFiles.Kind.SOUNDFONT, EngineFiles.kind(bytes))
        val cut = Read(bytes)
        assertPlaysAs(src, 1, cut, 3)
        assertEquals("My Pad", cut.name("phdr", 0))
        assertEquals(2, cut.count("inst", 22))
        assertEquals(listOf("L", "R", "Mono"), (0 until cut.count("shdr", 46)).map { cut.name("shdr", it * 46) })
        // The source's credits stay; the font is called what the patch is.
        assertEquals("My Pad", cut.info["INAM"]); assertEquals("CC0, the tester", cut.info["ICOP"])
        assertEquals(listOf("ifil", "isng", "INAM", "ICOP"), cut.info.keys.toList())
        // The zones as written: each answers three keys lower, and sounds its sample three keys higher.
        assertEquals(listOf(listOf(51 to 2), listOf(43 to (60 shl 8), 41 to 0), listOf(43 to ((124 shl 8) or 61), 51 to -1, 41 to 1)),
            cut.presetZones(0))
        assertEquals(listOf(listOf(17 to -500, 58 to 57, 53 to 0), listOf(17 to 500, 58 to 57, 53 to 1)), cut.instZones(0))
        // The key-scaled decay stays as the note had it: 50 per key, three keys.
        assertEquals(listOf(listOf(48 to 10, 40 to 50), listOf(43 to (57 shl 8), 36 to -12150, 58 to 57, 53 to 2)), cut.instZones(1))
        // Every sample is followed by its 46 zero points.
        val end = cut.u32("shdr", 2 * 46 + 24).toInt()
        assertEquals(cut.smpl.size, (end + 46) * 2)
    }

    @Test
    fun aZoneTransposedOutOfReachGoes() {
        // Down 70: the upper zone's keys would all lie below the keyboard. The root
        // keys stop at its top (60 + 70), and a tune of -3 makes up the rest.
        val cut = Read(SoundFontFile.onePreset(temp(testFont), 0, "Low", semitones = -70))
        assertPlaysAs(Read(testFont), 1, cut, -70)
        assertEquals(listOf(listOf(51 to 2), listOf(43 to ((127 shl 8) or 70), 41 to 0)), cut.presetZones(0))
        assertEquals(listOf("L", "R"), (0 until cut.count("shdr", 46)).map { cut.name("shdr", it * 46) })
        assertEquals(listOf(listOf(17 to -500, 58 to 127, 51 to -3, 52 to 0, 53 to 0), listOf(17 to 500, 58 to 127, 51 to -3, 52 to 0, 53 to 1)),
            cut.instZones(0))
        // Up 70: what is left answers keys its instrument has no sample for, so nothing would sound.
        val e = kotlin.runCatching { SoundFontFile.onePreset(temp(testFont), 0, "High", semitones = 70) }.exceptionOrNull()
        assertEquals("nothing of that preset sounds transposed that far", e?.message)
    }

    @Test
    fun aDuplicateAddressPlaysTheFirstInTheFile() {
        val cut = Read(SoundFontFile.onePreset(temp(testFont), 1, "Dup?"))
        assertPlaysAs(Read(testFont), 1, cut, 0)
        assertEquals(listOf(listOf(51 to 2), listOf(43 to (63 shl 8), 41 to 0), listOf(43 to ((127 shl 8) or 64), 51 to -1, 41 to 1)),
            cut.presetZones(0))
    }

    @Test
    fun aFluidSynthPatchIsItsPresetAlone() {
        val patch = Patch(engine = "fluid", preset = "Test.sf2", program = 3, params = mapOf("Transpose" to -12.0, "Reverb" to 0.4))
        val cut = Read(PatchExport.fluid(patch, "Kit", temp(testFont)))
        assertPlaysAs(Read(testFont), 3, cut, -12)
        assertEquals(listOf("Mono"), (0 until cut.count("shdr", 46)).map { cut.name("shdr", it * 46) })
        assertEquals("Kit.sf3", PatchExport.fileName("Kit", EngineFiles.Kind.SOUNDFONT, sf3 = true))
    }

    /**
     * Real fonts, when given: SOUNDFONT_DIR a folder of .sf2/.sf3 files (the
     * workspace's content/soundfonts holds one font as both). Every preset of
     * each is cut and checked against its source.
     */
    @Test
    fun realFontsCutPresetForPreset() {
        val dir = System.getenv("SOUNDFONT_DIR") ?: return
        val files = File(dir).listFiles { f -> f.extension.lowercase() in setOf("sf2", "sf3") }.orEmpty().sorted()
        assertTrue(files.isNotEmpty(), "no fonts in $dir")
        for (f in files) {
            val src = Read(f.readBytes())
            val sorted = SoundFontFile.presets(f)
            val all = (0 until src.count("phdr", 38)).map { src.u16("phdr", it * 38 + 22) to src.u16("phdr", it * 38 + 20) }
            for ((k, p) in sorted.withIndex()) {
                val t = listOf(0, 7, -12)[k % 3]
                val bytes = SoundFontFile.onePreset(f, k, p.name, semitones = t)
                val cut = Read(bytes)
                assertPlaysAs(src, all.indexOf(p.bank to p.program), cut, t)
                assertEquals(src.info["ifil"], cut.info["ifil"], f.name)
                assertTrue(bytes.size < f.length() || sorted.size == 1, "${f.name} preset $k is no smaller")
            }
            println("${f.name}: ${sorted.size} presets cut")
        }
    }

    @Test
    fun onlyFileEnginesExport() {
        assertEquals(EngineFiles.Kind.DX7_BANK, PatchExport.kind("dexed"))
        assertEquals(EngineFiles.Kind.SURGE_PATCH, PatchExport.kind("surge"))
        assertEquals(EngineFiles.Kind.SOUNDFONT, PatchExport.kind("fluid"))
        for (e in listOf("sfizz", "setbfree", "plaits")) assertEquals(null, PatchExport.kind(e))
        assertNotNull(RackEngine.ofKey("plaits"))
    }
}
