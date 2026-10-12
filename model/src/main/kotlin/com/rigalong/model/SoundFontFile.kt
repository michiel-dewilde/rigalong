package com.rigalong.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SoundFonts (SF2, and SF3: the same with Ogg Vorbis samples), read as far as
 * cutting one preset out of a font needs: the preset, the instruments it
 * plays, and the samples those play -- a font of its own that sounds as that
 * preset did, without the other 127 and their samples.
 *
 * Layout (SoundFont 2.04, section 4): RIFF "sfbk" holding LIST "INFO",
 * LIST "sdta" (smpl, and sm24 for 24-bit fonts) and LIST "pdta": phdr, pbag,
 * pmod, pgen, inst, ibag, imod, igen, shdr, each ending in a terminal record.
 * A sample's start and end count 16-bit points into smpl, and its loop is
 * absolute; an SF3 sample compressed with Vorbis counts BYTES instead, and
 * its loop is relative to its own start (FluidSynth's fluid_defsfont.c).
 */
object SoundFontFile {
    class Preset(val bank: Int, val program: Int, val name: String)

    private const val GEN_INSTRUMENT = 41
    private const val GEN_KEY_RANGE = 43
    private const val GEN_VEL_RANGE = 44
    private const val GEN_SAMPLE = 53
    private const val VORBIS = 0x10
    private const val ROM = 0x8000

    private class Chunk(val id: String, val at: Long, val size: Int)

    private class Font(val file: RandomAccessFile) {
        val info = ArrayList<Pair<String, ByteArray>>()
        var smpl: Chunk? = null
        var sm24: Chunk? = null
        val pdta = HashMap<String, ByteBuffer>()

        fun read(at: Long, n: Int): ByteArray { val b = ByteArray(n); file.seek(at); file.readFully(b); return b }
        fun table(id: String, record: Int): ByteBuffer {
            val b = pdta[id] ?: error("the font has no $id")
            require(b.capacity() % record == 0 && b.capacity() >= record) { "the font's $id is damaged" }
            return b
        }
    }

    private fun chunks(f: RandomAccessFile, from: Long, to: Long): List<Chunk> {
        val out = ArrayList<Chunk>()
        var at = from
        val h = ByteArray(8)
        while (at + 8 <= to) {
            f.seek(at); f.readFully(h)
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(size >= 0 && at + 8 + size <= to) { "chunk $id runs past its end" }
            out += Chunk(id, at + 8, size)
            at += 8 + size + (size and 1)
        }
        return out
    }

    private fun open(file: File): Font {
        val raf = RandomAccessFile(file, "r")
        try {
            val f = Font(raf)
            val head = f.read(0, 12)
            require(String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "sfbk") {
                "not a SoundFont"
            }
            val end = minOf(raf.length(), 8L + (ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL))
            for (list in chunks(raf, 12, end)) {
                if (list.id != "LIST" || list.size < 4) continue
                val kind = String(f.read(list.at, 4), Charsets.ISO_8859_1)
                for (c in chunks(raf, list.at + 4, list.at + list.size)) when (kind) {
                    "INFO" -> f.info += c.id to f.read(c.at, c.size)
                    "sdta" -> when (c.id) { "smpl" -> f.smpl = c; "sm24" -> f.sm24 = c }
                    "pdta" -> f.pdta[c.id] = ByteBuffer.wrap(f.read(c.at, c.size)).order(ByteOrder.LITTLE_ENDIAN)
                }
            }
            return f
        } catch (e: Throwable) {
            raf.close(); throw e
        }
    }

    private fun name20(b: ByteBuffer, at: Int): String {
        val bytes = ByteArray(20) { b.get(at + it) }
        val n = bytes.indexOf(0).let { if (it < 0) 20 else it }
        return String(bytes, 0, n, Charsets.ISO_8859_1).trimEnd()
    }

    private fun u16(b: ByteBuffer, at: Int) = b.getShort(at).toInt() and 0xFFFF

    private fun presetsOf(f: Font): List<Preset> {
        val phdr = f.table("phdr", 38)
        return List(phdr.capacity() / 38 - 1) { Preset(u16(phdr, it * 38 + 22), u16(phdr, it * 38 + 20), name20(phdr, it * 38)) }
    }

    /** The presets of [file] in FluidNode's order (by bank, then program) -- what a patch's `program` counts. */
    fun presets(file: File): List<Preset> = open(file).let { f -> f.file.use { _ -> sorted(f) } }

    private fun sorted(f: Font): List<Preset> = presetsOf(f).sortedWith(compareBy({ it.bank }, { it.program }))

    /** The font's version, from ifil: 2 for SF2, 3 for SF3. */
    fun majorVersion(file: File): Int = open(file).let { f ->
        f.file.use { _ -> f.info.firstOrNull { it.first == "ifil" }?.second?.let { it[0].toInt() and 0xFF or ((it[1].toInt() and 0xFF) shl 8) } ?: 2 }
    }

    /**
     * A font holding only the preset a FluidSynth patch's [index] picks in
     * [file], with the instruments and samples it plays. The preset keeps its
     * bank and program; it and the font are called [name], and the font keeps
     * the source's INFO (its copyright and credits) otherwise. [semitones]
     * transposes it as FluidSynth plays a transposed patch: key n sounds
     * note n + semitones, its samples and all (see [transpose]).
     */
    fun onePreset(file: File, index: Int, name: String, semitones: Int = 0): ByteArray {
        val f = open(file)
        f.file.use { _ -> return cut(f, index, name, semitones) }
    }

    private class Gen(val oper: Int, var amount: Int)

    /** Key-scaled envelope times: (time, its key scaling), modulation and volume envelopes, hold and decay. */
    private val KEY_SCALED = listOf(27 to 31, 28 to 32, 35 to 39, 36 to 40)
    private const val GEN_KEYNUM = 46
    private const val GEN_ROOT_KEY = 58
    private const val GEN_COARSE_TUNE = 51
    private const val GEN_FINE_TUNE = 52
    private const val GEN_SCALE_TUNING = 56

    /**
     * One level's zones (a preset's, or an instrument's: [terminal] is the
     * generator that ends each of its zones) transposed as FluidNode does it:
     * key n plays note n + [t]. So each zone answers [t] keys lower, a zone
     * left with no key at all goes, an instrument zone's sample sounds as if
     * pressed [t] keys higher (its root key, from [rootOf] its sample, moves
     * down), and a key-scaled hold or decay is kept as the note had it. A zone
     * with a fixed key (keynum) sounds as it did and keeps its own.
     */
    private fun transpose(zones: MutableList<Pair<MutableList<Gen>, List<ByteArray>>>, t: Int, terminal: Int, rootOf: (Int) -> Int?) {
        val global = zones.firstOrNull()?.first?.takeIf { z -> z.none { it.oper == terminal } }
        fun value(gens: List<Gen>, oper: Int) = gens.firstOrNull { it.oper == oper }?.amount ?: global?.firstOrNull { it.oper == oper }?.amount
        fun set(gens: MutableList<Gen>, oper: Int, v: Int) {
            val own = gens.firstOrNull { it.oper == oper }
            if (own != null) own.amount = v
            else gens.add(gens.indexOfFirst { it.oper == terminal }.let { if (it < 0) gens.size else it }, Gen(oper, v))
        }
        val gone = ArrayList<Pair<MutableList<Gen>, List<ByteArray>>>()
        for (zone in zones) {
            val gens = zone.first
            gens.firstOrNull { it.oper == GEN_KEY_RANGE }?.let { r ->
                val lo = (r.amount and 0xFF) - t
                val hi = ((r.amount shr 8) and 0xFF) - t
                if (hi < 0 || lo > 127) { if (gens !== global) gone += zone }
                else r.amount = lo.coerceIn(0, 127) or (hi.coerceIn(0, 127) shl 8)
            }
            if (gens === global || gens.none { it.oper == terminal }) continue
            if ((value(gens, GEN_KEYNUM) ?: -1) >= 0) continue
            // An unset time is the level's default: -12000 timecents in an instrument, no offset in a preset.
            // Not held to the generator's range: the key's scaling is added before FluidSynth limits it.
            for ((time, scaling) in KEY_SCALED) {
                val k = value(gens, scaling) ?: 0
                if (k != 0) set(gens, time, ((value(gens, time) ?: if (terminal == GEN_SAMPLE) -12000 else 0) - k * t).coerceIn(-32768, 32767))
            }
            if (terminal == GEN_SAMPLE) {
                val own = value(gens, GEN_ROOT_KEY)?.takeIf { it >= 0 }
                val root = own ?: rootOf(gens.first { it.oper == GEN_SAMPLE }.amount and 0xFFFF) ?: continue
                val moved = root - t
                val kept = moved.coerceIn(0, 127)
                set(gens, GEN_ROOT_KEY, kept)
                if (kept != moved) {
                    // Past the keyboard's end the root key stops there, and the zone's tune makes up the rest.
                    val cents = (kept - moved) * (value(gens, GEN_SCALE_TUNING) ?: 100) +
                        (value(gens, GEN_COARSE_TUNE) ?: 0) * 100 + (value(gens, GEN_FINE_TUNE) ?: 0)
                    set(gens, GEN_COARSE_TUNE, Math.floorDiv(cents, 100))
                    set(gens, GEN_FINE_TUNE, Math.floorMod(cents, 100))
                }
            }
        }
        zones.removeAll { z -> gone.any { it === z } }
    }

    private fun cut(f: Font, index: Int, name: String, semitones: Int): ByteArray {
        val phdr = f.table("phdr", 38); val pbag = f.table("pbag", 4); val pmod = f.table("pmod", 10); val pgen = f.table("pgen", 4)
        val inst = f.table("inst", 22); val ibag = f.table("ibag", 4); val imod = f.table("imod", 10); val igen = f.table("igen", 4)
        val shdr = f.table("shdr", 46)
        val smpl = f.smpl ?: error("the font has no samples")

        // Which preset: FluidSynth's sorted list names a bank and program, and
        // FluidSynth plays the first preset in the file with that address.
        val all = presetsOf(f)
        val wanted = sorted(f).getOrNull(index) ?: error("the font has no preset $index")
        val p = all.indexOfFirst { it.bank == wanted.bank && it.program == wanted.program }

        // Zones as generator and modulator records, by their bag ranges.
        fun zones(bag: ByteBuffer, gen: ByteBuffer, mod: ByteBuffer, from: Int, to: Int) = (from until to).map { z ->
            val g0 = u16(bag, z * 4); val g1 = u16(bag, z * 4 + 4)
            val m0 = u16(bag, z * 4 + 2); val m1 = u16(bag, z * 4 + 6)
            require(g0 <= g1 && g1 * 4 <= gen.capacity() && m0 <= m1 && m1 * 10 <= mod.capacity()) { "the font's zones are damaged" }
            val gens = (g0 until g1).map { Gen(u16(gen, it * 4), gen.getShort(it * 4 + 2).toInt()) }.toMutableList()
            val mods = (m0 until m1).map { m -> ByteArray(10) { mod.get(m * 10 + it) } }
            gens to mods
        }

        val presetZones = zones(pbag, pgen, pmod, u16(phdr, p * 38 + 24), u16(phdr, p * 38 + 38 + 24)).toMutableList()
        if (semitones != 0) transpose(presetZones, semitones, GEN_INSTRUMENT) { null }

        // The instruments it plays, numbered anew in the order first met.
        val instMap = LinkedHashMap<Int, Int>()
        for ((gens, _) in presetZones) for (g in gens) if (g.oper == GEN_INSTRUMENT) {
            require(g.amount and 0xFFFF < inst.capacity() / 22 - 1) { "the font's preset plays a missing instrument" }
            g.amount = instMap.getOrPut(g.amount and 0xFFFF) { instMap.size }
        }
        val instZones = instMap.keys.map { i -> zones(ibag, igen, imod, u16(inst, i * 22 + 20), u16(inst, i * 22 + 22 + 20)).toMutableList() }
        if (semitones != 0) for (zs in instZones) transpose(zs, semitones, GEN_SAMPLE) { s ->
            // A sample's own root key; 255 (unpitched) and the like count as 60, as FluidSynth has it.
            (shdr.get(s * 46 + 40).toInt() and 0xFF).let { if (it > 127) 60 else it }
        }

        // The samples those play, and their stereo partners.
        val sampleCount = shdr.capacity() / 46 - 1
        val sampleMap = LinkedHashMap<Int, Int>()
        val queue = ArrayDeque<Int>()
        fun want(s: Int) { require(s in 0 until sampleCount) { "the font plays a missing sample" }; if (s !in sampleMap) { sampleMap[s] = sampleMap.size; queue += s } }
        for (zs in instZones) for ((gens, _) in zs) for (g in gens) if (g.oper == GEN_SAMPLE) want(g.amount and 0xFFFF)
        require(sampleMap.isNotEmpty()) { "nothing of that preset sounds${if (semitones != 0) " transposed that far" else ""}" }
        while (queue.isNotEmpty()) {
            val s = queue.removeFirst()
            val type = u16(shdr, s * 46 + 44)
            if (type and (2 or 4 or 8) != 0) want(u16(shdr, s * 46 + 42))
        }
        for (zs in instZones) for ((gens, _) in zs) for (g in gens) if (g.oper == GEN_SAMPLE) g.amount = sampleMap.getValue(g.amount and 0xFFFF)

        // Sample data, each range once, and the sample headers moved onto it.
        val smplOut = ByteArrayOutputStream()
        val sm24Out = f.sm24?.let { ByteArrayOutputStream() }
        val placed = HashMap<Triple<Long, Long, Boolean>, Long>()
        val shdrOut = ByteArrayOutputStream()
        for (s in sampleMap.keys) {
            val at = s * 46
            fun u32(o: Int) = shdr.getInt(at + o).toLong() and 0xFFFFFFFFL
            val start = u32(20); val end = u32(24)
            val type = u16(shdr, at + 44)
            val r = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 46) r.put(i, shdr.get(at + i))
            r.putShort(42, (if (type and (2 or 4 or 8) != 0) sampleMap[u16(shdr, at + 42)] ?: 0 else 0).toShort())
            if (type and ROM != 0) {
                // In a synth's ROM, not the file: FluidSynth starts no voice on
                // it, here as in the source, so its header goes as it was.
                shdrOut.write(r.array())
                continue
            }
            val vorbis = type and VORBIS != 0
            require(end >= start) { "the font's sample ${name20(shdr, at)} is damaged" }
            val newStart = placed.getOrPut(Triple(start, end, vorbis)) {
                if (vorbis) {
                    require(end <= smpl.size) { "the font's sample ${name20(shdr, at)} runs past its data" }
                    smplOut.size().toLong().also { smplOut.write(f.read(smpl.at + start, (end - start).toInt())) }
                } else {
                    require(end * 2 <= smpl.size) { "the font's sample ${name20(shdr, at)} runs past its data" }
                    if (smplOut.size() % 2 != 0) smplOut.write(0)
                    val points = (end - start).toInt()
                    val pos = smplOut.size() / 2L
                    smplOut.write(f.read(smpl.at + start * 2, points * 2))
                    smplOut.write(ByteArray(46 * 2))                  // the 46 zero points every sample is followed by
                    sm24Out?.let { o ->
                        val sm24 = f.sm24!!
                        while (o.size() < pos) o.write(0)
                        o.write(if (end <= sm24.size) f.read(sm24.at + start, points) else ByteArray(points))
                        o.write(ByteArray(46))
                    }
                    pos
                }
            }
            // A Vorbis sample's loop is its own; a plain one's moves with it.
            val shift = if (vorbis) 0L else newStart - start
            r.putInt(20, newStart.toInt()); r.putInt(24, (newStart + end - start).toInt())
            r.putInt(28, (u32(28) + shift).toInt()); r.putInt(32, (u32(32) + shift).toInt())
            shdrOut.write(r.array())
        }
        shdrOut.write(terminal("EOS", 46))

        // pdta, each table ending in its terminal record.
        fun gens(out: ByteArrayOutputStream, gens: List<Gen>) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            for (g in gens) { b.putShort(0, g.oper.toShort()); b.putShort(2, g.amount.toShort()); out.write(b.array()) }
        }
        fun bag(out: ByteArrayOutputStream, gen: Int, mod: Int) =
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(gen.toShort()).putShort(mod.toShort()).array())

        val pbagOut = ByteArrayOutputStream(); val pgenOut = ByteArrayOutputStream(); val pmodOut = ByteArrayOutputStream()
        var g = 0; var m = 0
        for ((gs, ms) in presetZones) {
            bag(pbagOut, g, m); gens(pgenOut, gs); ms.forEach(pmodOut::write); g += gs.size; m += ms.size
        }
        bag(pbagOut, g, m); pgenOut.write(ByteArray(4)); pmodOut.write(ByteArray(10))
        val phdrOut = ByteArrayOutputStream()
        phdrOut.write(ByteBuffer.allocate(38).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(name20Bytes(name)); putShort(wanted.program.toShort()); putShort(wanted.bank.toShort()); putShort(0)
            for (i in 26 until 38) put(i, phdr.get(p * 38 + i))       // library, genre, morphology
        }.array())
        phdrOut.write(ByteBuffer.allocate(38).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(name20Bytes("EOP")); putShort(0); putShort(0); putShort(presetZones.size.toShort())
        }.array())

        val instOut = ByteArrayOutputStream(); val ibagOut = ByteArrayOutputStream()
        val igenOut = ByteArrayOutputStream(); val imodOut = ByteArrayOutputStream()
        g = 0; m = 0
        var zoneCount = 0
        for ((k, i) in instMap.keys.withIndex()) {
            val r = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            for (b in 0 until 20) r.put(b, inst.get(i * 22 + b))
            r.putShort(20, zoneCount.toShort())
            instOut.write(r.array())
            for ((gs, ms) in instZones[k]) {
                bag(ibagOut, g, m); gens(igenOut, gs); ms.forEach(imodOut::write); g += gs.size; m += ms.size
            }
            zoneCount += instZones[k].size
        }
        instOut.write(ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(name20Bytes("EOI")); putShort(zoneCount.toShort())
        }.array())
        bag(ibagOut, g, m); igenOut.write(ByteArray(4)); imodOut.write(ByteArray(10))

        // INFO: the source's, with its name ours; ifil, isng and INAM first, as the spec lists them.
        val infoOut = ByteArrayOutputStream()
        fun sub(out: ByteArrayOutputStream, id: String, data: ByteArray) {
            out.write(id.toByteArray(Charsets.ISO_8859_1))
            out.write(le32(data.size)); out.write(data)
            if (data.size % 2 != 0) out.write(0)
        }
        val src = f.info.toMap()
        sub(infoOut, "ifil", src["ifil"] ?: byteArrayOf(2, 0, 1, 0))
        sub(infoOut, "isng", src["isng"] ?: zstr("EMU8000"))
        sub(infoOut, "INAM", zstr(name))
        for ((id, data) in f.info) if (id !in setOf("ifil", "isng", "INAM")) sub(infoOut, id, data)

        val sdta = ByteArrayOutputStream()
        sub(sdta, "smpl", smplOut.toByteArray())
        sm24Out?.let { o -> while (o.size() < (smplOut.size() + 1) / 2) o.write(0); sub(sdta, "sm24", o.toByteArray()) }

        val pdta = ByteArrayOutputStream()
        sub(pdta, "phdr", phdrOut.toByteArray()); sub(pdta, "pbag", pbagOut.toByteArray())
        sub(pdta, "pmod", pmodOut.toByteArray()); sub(pdta, "pgen", pgenOut.toByteArray())
        sub(pdta, "inst", instOut.toByteArray()); sub(pdta, "ibag", ibagOut.toByteArray())
        sub(pdta, "imod", imodOut.toByteArray()); sub(pdta, "igen", igenOut.toByteArray())
        sub(pdta, "shdr", shdrOut.toByteArray())

        val body = ByteArrayOutputStream()
        body.write("sfbk".toByteArray(Charsets.ISO_8859_1))
        for ((kind, data) in listOf("INFO" to infoOut, "sdta" to sdta, "pdta" to pdta)) {
            sub(body, "LIST", kind.toByteArray(Charsets.ISO_8859_1) + data.toByteArray())
        }
        val out = ByteArrayOutputStream()
        sub(out, "RIFF", body.toByteArray())
        return out.toByteArray()
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    /** A zero-terminated INFO string, padded to an even length. */
    private fun zstr(s: String): ByteArray {
        val b = s.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray()
        return b + ByteArray(if (b.size % 2 == 0) 2 else 1)
    }

    private fun name20Bytes(s: String): ByteArray {
        val b = ByteArray(20)
        s.toByteArray(Charsets.ISO_8859_1).take(19).toByteArray().copyInto(b)
        return b
    }

    private fun terminal(name: String, size: Int) = ByteArray(size).also { name20Bytes(name).copyInto(it) }
}
