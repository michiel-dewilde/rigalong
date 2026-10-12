package be.mdw.rigalong.model.devices.cp88

import be.mdw.rigalong.model.devices.cp88.Cp88X9Tables as T

/**
 * The CP88/CP73's own files (REQUIREMENTS I4.17), after cp-liveset's
 * x9format.py: YSFC containers holding Live Set Sounds, as the keyboard's
 * File menu saves and loads them on a USB stick.
 *
 *   .X9S  one Live Set Sound          read and written
 *   .X9P  a Live Set page (8 sets)    read and written
 *   .X9L  all Live Set pages 1-20     read
 *   .X9A  the whole backup            read (its Live Set Sounds)
 *
 * A container is a 64-byte header, a catalogue of (tag, offset) entries, and
 * chunks: ELST lists the sounds (an "Entr" per sound: data offset, id bytes
 * 00 3F page-1 set-1, the name), DLST holds a 1024-byte "Data" record per
 * sound whose first 367 bytes are the sound. Those bytes map onto the 17
 * SysEx data blocks the app keeps a sound as (Cp88), by the generated
 * tables (tools/gen_cp88x9.py): the same sound read over MIDI or from a file
 * is the same object. A record leaves out what the keyboard does not store
 * there; those bytes read as 0, as in cp-liveset.
 */
object Cp88X9 {
    private val MAGIC = "YAMAHA-YSFC".toByteArray(Charsets.US_ASCII)
    private const val DATA_SIZE = 1024
    private const val STRIDE = 8 + DATA_SIZE
    private val VERSION = "6.0.0".toByteArray(Charsets.US_ASCII)

    /** One Live Set Sound found in a file: where it was, its name, and the sound in the stored form. */
    data class Slot(val page: Int, val set: Int, val name: String, val sound: ByteArray)

    /** Every Live Set Sound in an .X9S/.X9P/.X9L/.X9A file, in file order. Throws if it is none of those. */
    fun read(file: ByteArray): List<Slot> {
        require(file.size >= 0x40 && file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not a CP88/CP73 file (.X9*)" }
        val catSize = u32(file, 0x20)
        require(catSize % 8 == 0 && 0x40 + catSize <= file.size) { "unrecognised .X9 file: bad catalogue" }
        val catalogue = HashMap<String, Int>()
        for (o in 0x40 until 0x40 + catSize step 8) catalogue[String(file, o, 4, Charsets.US_ASCII)] = u32(file, o + 4)
        val elst = catalogue["ELST"] ?: throw IllegalArgumentException("no Live Set Sounds in this file")
        val dlst = catalogue["DLST"] ?: throw IllegalArgumentException("no Live Set data in this file")
        require(tag(file, elst) == "ELST" && tag(file, dlst) == "DLST") { "unrecognised .X9 file: catalogue offsets" }
        val out = ArrayList<Slot>()
        val count = u32(file, elst + 8)
        var pos = elst + 12
        repeat(count) {
            require(tag(file, pos) == "Entr") { "unrecognised .X9 file: malformed entry" }
            val len = u32(file, pos + 4)
            val payload = pos + 8
            pos += 8 + len
            if (u(file[payload + 8]) != 0x00 || u(file[payload + 9]) != 0x3F) return@repeat   // not a Live Set Sound
            val page = u(file[payload + 10]) + 1
            val set = u(file[payload + 11]) + 1
            if (page !in 1..40 || set !in 1..8) return@repeat
            val blob = dlst + 8 + u32(file, payload + 4)
            require(tag(file, blob - 8) == "Data" && blob + T.BLOB_SIZE <= file.size) {
                "unrecognised .X9 file: a data offset does not point at a record"
            }
            val sound = soundOf(file.copyOfRange(blob, blob + T.BLOB_SIZE))
            out += Slot(page, set, Cp88.name(sound), sound)
        }
        require(out.isNotEmpty()) { "no Live Set Sounds in this file" }
        return out
    }

    /** An .X9S file: [sound] (stored form) as Live Set Sound page:set. */
    fun x9s(page: Int, set: Int, sound: ByteArray): ByteArray {
        require(page in 1..40 && set in 1..8) { "page 1-40, set 1-8" }
        return container(listOf(Triple(page, set, sound)))
    }

    /** An .X9P file: page [page], set n holding sounds[n] (1-8), or the Init Sound where it has none. */
    fun x9p(page: Int, sounds: Map<Int, ByteArray>): ByteArray {
        require(page in 1..40) { "page 1-40" }
        return container((1..8).map { s -> Triple(page, s, sounds[s] ?: T.INIT_SOUND) })
    }

    /** cp-liveset's Init Sound, stored form: what a page's unused sets hold. */
    val INIT_SOUND: ByteArray get() = T.INIT_SOUND.copyOf()

    // ------------------------------------------------------------- the record

    /** A 367-byte record as the 19 dumps of the stored form (Cp88). */
    fun soundOf(blob: ByteArray): ByteArray {
        require(blob.size >= T.BLOB_SIZE) { "a Live Set Sound record is ${T.BLOB_SIZE} bytes" }
        val blocks = Array(T.BLOCKS.size / 4) { ByteArray(T.BLOCKS[4 * it + 3]) }
        val f = T.FIELDS
        for (i in f.indices step 3) blocks[f[i]][f[i + 1]] = blob[f[i + 2]]
        val b = T.BITS
        for (i in b.indices step 4) {
            val v = (u(blocks[b[i]][b[i + 1]]) or ((u(blob[b[i + 3]]) and 1) shl b[i + 2]))
            blocks[b[i]][b[i + 1]] = v.toByte()
        }
        val r = T.REPACK
        for (i in r.indices step 4) {
            val v = (u(blob[r[i + 3]]) shl 8) or u(blob[r[i + 2]])
            blocks[r[i]][r[i + 1]] = (v shr 7).toByte()
            blocks[r[i]][r[i + 1] + 1] = (v and 0x7F).toByte()
        }
        val x = T.FIXED
        for (i in x.indices step 3) blocks[x[i]][x[i + 1]] = x[i + 2].toByte()
        System.arraycopy(blob, 4, blocks[T.NAME_BLOCK], T.NAME_OFFSET, 15)
        return storedOf(blocks)
    }

    /** The stored form (Cp88) of 17 data blocks in table order: header, blocks, footer, at the current buffer. */
    internal fun storedOf(blocks: Array<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(Cp88.build(intArrayOf(0x0E, 0x7F, 0x00), ByteArray(0)))
        for (k in blocks.indices) {
            out.write(Cp88.build(intArrayOf(T.BLOCKS[4 * k], T.BLOCKS[4 * k + 1], T.BLOCKS[4 * k + 2]), blocks[k]))
        }
        out.write(Cp88.build(intArrayOf(0x0F, 0x7F, 0x00), ByteArray(0)))
        return out.toByteArray()
    }

    /** A sound (stored form) as a 367-byte record; what the record has no place for is left out. */
    fun recordOf(sound: ByteArray): ByteArray {
        val blocks = blocksOf(sound)
        val blob = T.BASELINE.copyOf()
        System.arraycopy(blocks[T.NAME_BLOCK], T.NAME_OFFSET, blob, 4, 15)
        val f = T.FIELDS
        for (i in f.indices step 3) blob[f[i + 2]] = blocks[f[i]][f[i + 1]]
        val b = T.BITS
        for (i in b.indices step 4) blob[b[i + 3]] = ((u(blocks[b[i]][b[i + 1]]) shr b[i + 2]) and 1).toByte()
        val r = T.REPACK
        for (i in r.indices step 4) {
            val v = (u(blocks[r[i]][r[i + 1]]) shl 7) or u(blocks[r[i]][r[i + 1] + 1])
            blob[r[i + 2]] = (v and 0xFF).toByte()
            blob[r[i + 3]] = ((v shr 8) and 0xFF).toByte()
        }
        return blob
    }

    /** The 17 data blocks of a stored sound, in table order. */
    internal fun blocksOf(sound: ByteArray): Array<ByteArray> {
        val byAddress = HashMap<Int, ByteArray>()
        for (m in Cp88.split(sound)) {
            val d = Cp88.parse(m) ?: throw IllegalArgumentException("corrupt CP-88 sound")
            byAddress[(d.address[0] shl 16) or (d.address[1] shl 8) or d.address[2]] = d.data
        }
        return Array(T.BLOCKS.size / 4) { k ->
            val key = (T.BLOCKS[4 * k] shl 16) or (T.BLOCKS[4 * k + 1] shl 8) or T.BLOCKS[4 * k + 2]
            val d = byAddress[key] ?: throw IllegalArgumentException("a CP-88 sound without block %06x".format(key))
            require(d.size == T.BLOCKS[4 * k + 3]) { "block %06x has ${d.size} bytes".format(key) }
            d
        }
    }

    // ---------------------------------------------------------- the container

    private fun container(items: List<Triple<Int, Int, ByteArray>>): ByteArray {
        val entries = java.io.ByteArrayOutputStream()
        entries.write(be32(items.size))
        val data = java.io.ByteArrayOutputStream()
        data.write(be32(items.size))
        items.forEachIndexed { i, (page, set, sound) ->
            // The entry names the sound without its trailing NULs; trailing spaces stay
            // (cp-liveset's model does the same, so a name padded either way round-trips).
            val name = recordOf(sound).copyOfRange(4, 19).let { n -> n.copyOf(n.indexOfLast { it != 0.toByte() } + 1) }
            val payload = be32(DATA_SIZE) + be32(12 + i * STRIDE) + byteArrayOf(0x00, 0x3F, (page - 1).toByte(), (set - 1).toByte()) +
                name + byteArrayOf(0)
            entries.write("Entr".toByteArray(Charsets.US_ASCII)); entries.write(be32(payload.size)); entries.write(payload)
            val record = recordOf(sound).copyOf(DATA_SIZE)
            for (k in T.BLOB_SIZE until DATA_SIZE) record[k] = 0xFF.toByte()
            data.write("Data".toByteArray(Charsets.US_ASCII)); data.write(be32(DATA_SIZE)); data.write(record)
        }
        val chunks = listOf("ELST" to entries.toByteArray(), "DLST" to data.toByteArray())
        val header = ByteArray(0x40)
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.size)
        System.arraycopy(VERSION, 0, header, 0x10, VERSION.size)
        System.arraycopy(be32(8 * chunks.size), 0, header, 0x20, 4)
        for (k in 0x24 until 0x30) header[k] = 0xFF.toByte()
        for (k in 0x34 until 0x40) header[k] = 0xFF.toByte()
        val out = java.io.ByteArrayOutputStream()
        out.write(header)
        var offset = 0x40 + 8 * chunks.size
        for ((tag, body) in chunks) {
            out.write(tag.toByteArray(Charsets.US_ASCII)); out.write(be32(offset))
            offset += 8 + body.size
        }
        for ((tag, body) in chunks) {
            out.write(tag.toByteArray(Charsets.US_ASCII)); out.write(be32(body.size)); out.write(body)
        }
        return out.toByteArray()
    }

    private fun tag(b: ByteArray, o: Int) = if (o + 4 <= b.size) String(b, o, 4, Charsets.US_ASCII) else ""
    private fun u32(b: ByteArray, o: Int): Int {
        require(o + 4 <= b.size) { "unrecognised .X9 file: truncated" }
        return (u(b[o]) shl 24) or (u(b[o + 1]) shl 16) or (u(b[o + 2]) shl 8) or u(b[o + 3])
    }
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u(b: Byte) = b.toInt() and 0xFF
}
