package com.rigalong.model.devices.cp88

/**
 * CP-88 Live Set Sounds over SysEx (REQUIREMENTS S4.9-S4.11, I4.16; LOG.md
 * 2026-10-11 V1-V3), after cp-liveset's sysex.py and the CP88/CP73
 * Supplementary Manual v2.00:
 *
 *   Bulk Dump          F0 43 0n 7F 1C bh bl 08 ah am al dd..dd cc F7
 *   Bulk Dump Request  F0 43 2n 7F 1C 08 ah am al F7
 *
 * Byte count bh bl covers address, data and checksum; the checksum makes the
 * low 7 bits of (08 + address + data + checksum) zero. One Live Set Sound is
 * 19 dumps: a header at 0E pp n, 17 data blocks at fixed addresses, a footer
 * at 0F pp n -- so only the header and footer say which slot it is. The
 * Current Sound Buffer is pp n = 7F 00.
 *
 * A sound is kept as one object (FORMAT.md section 5): the 19 dumps
 * concatenated, device number 0, header and footer at the current buffer. The
 * same sound read from any slot is then the same object; where it goes is
 * decided when it is written ([retarget]).
 */
object Cp88 {
    const val MEDIA_TYPE = "application/vnd.rigalong.cp88sound.v1"
    const val LIVE_SET_BANK_MSB = 0x3F
    private const val MODEL = 0x08
    private const val HEADER = 0x0E
    private const val FOOTER = 0x0F
    private val COMMON = intArrayOf(0x46, 0x00, 0x00)

    data class Dump(val address: IntArray, val data: ByteArray)

    /**
     * A SILENT Live Set Sound: Init Sound with all three sections switched off
     * and at volume 0, reverb and delay off, named "Silent" (built with
     * cp-liveset). When the CP-88's own buttons select the app's live sets,
     * the CP-88 also switches to whatever its slot holds; every slot a song does
     * not give a sound of its own gets this one, so the keyboard adds nothing
     * of its own (Michiel, 2026-10-11).
     */
    val SILENT: ByteArray by lazy {
        val hex = (
        "f043007f1c0004080e00006af7f043007f1c000808007f000104000074f7f043007f1c00180820400040000000000000" +
        "00401c000000000000400000003cf7f043007f1c00340846000053696c656e7420202020202020202002000000000140" +
        "37007f0b04007f007f00004040400000404000404040404040401df7f043007f1c001408460100000b07040000000000" +
        "000000000000001bf7f043007f1c0014084a000001004040007f0064400000001f3f00002cf7f043007f1c0014084a01" +
        "0000014040007f0064400000001f3f00002bf7f043007f1c0014084a020000024040007f0064400000001f3f000029f7" +
        "f043007f1c0014084a030000034040007f0064400000001f3f000027f7f043007f1c001c085000000000000000000000" +
        "0040004000420000000101010100000062f7f043007f1c001c0850010004000000000000000040004000420000000101" +
        "01010000005df7f043007f1c001c085002000800000000000000004000400042000a00010101010000004ef7f043007f" +
        "1c002008501000000100190000400000000000000000000000000000000000000000003ef7f043007f1c002008501100" +
        "0001000000000000000040400000404000400000000000000000000056f7f043007f1c00200850120000010000000000" +
        "00000000000000000000000000000040404040000015f7f043007f1c0014085020000001004001000000400000000000" +
        "000006f7f043007f1c0014085021000001004001000000400000000000000005f7f043007f1c00140850220000010040" +
        "01000000400000000000000004f7f043007f1c0004080f000069f7"
        )
        normalize(split(ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }))
    }

    /** A Bulk Dump Request for slot page:set (1-based), or for the current sound if [page] is null. */
    fun dumpRequest(page: Int? = null, set: Int = 1): ByteArray {
        val (pp, n) = slot(page, set)
        return bytes(0xF0, 0x43, 0x20, 0x7F, 0x1C, MODEL, HEADER, pp, n, 0xF7)
    }

    /** Bank Select MSB 63, LSB page - 1, Program Change set - 1 (V1). */
    fun selectMessages(page: Int, set: Int, channel: Int = 1): ByteArray {
        require(page in 1..40 && set in 1..8 && channel in 1..16)
        val ch = channel - 1
        return bytes(0xB0 or ch, 0x00, LIVE_SET_BANK_MSB, 0xB0 or ch, 0x20, page - 1, 0xC0 or ch, set - 1)
    }

    /** Parses one Bulk Dump; null if it is not one, or its checksum is wrong. */
    fun parse(m: ByteArray): Dump? {
        if (m.size < 13 || u(m[0]) != 0xF0 || u(m[1]) != 0x43 || (u(m[2]) and 0xF0) != 0x00 ||
            u(m[3]) != 0x7F || u(m[4]) != 0x1C || u(m[7]) != MODEL || u(m.last()) != 0xF7) return null
        val count = (u(m[5]) shl 7) or u(m[6])
        if (count != m.size - 9) return null
        val address = intArrayOf(u(m[8]), u(m[9]), u(m[10]))
        val data = m.copyOfRange(11, m.size - 2)
        if (checksum(address, data) != u(m[m.size - 2])) return null
        return Dump(address, data)
    }

    fun build(address: IntArray, data: ByteArray, device: Int = 0): ByteArray {
        val count = data.size + 4
        val out = ArrayList<Int>()
        out += listOf(0xF0, 0x43, device and 0x0F, 0x7F, 0x1C, (count shr 7) and 0x7F, count and 0x7F, MODEL)
        out += address.toList()
        data.forEach { out += u(it) }
        out += checksum(address, data)
        out += 0xF7
        return ByteArray(out.size) { out[it].toByte() }
    }

    /** Splits concatenated SysEx messages. */
    fun split(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = -1
        for (i in bytes.indices) {
            when (u(bytes[i])) {
                0xF0 -> start = i
                0xF7 -> if (start >= 0) { out += bytes.copyOfRange(start, i + 1); start = -1 }
            }
        }
        return out
    }

    /**
     * The 19 dumps of one sound, as received from any slot or the current
     * buffer, into the stored form. Throws if they are not one whole sound.
     */
    fun normalize(messages: List<ByteArray>): ByteArray {
        val dumps = messages.map { parse(it) ?: throw IllegalArgumentException("not a valid bulk dump") }
        require(dumps.size == 19) { "a Live Set Sound is 19 dumps, got ${dumps.size}" }
        require(dumps.first().address[0] == HEADER && dumps.last().address[0] == FOOTER) {
            "header first and footer last expected"
        }
        val out = java.io.ByteArrayOutputStream()
        for (d in dumps) {
            val a = if (d.address[0] == HEADER || d.address[0] == FOOTER) intArrayOf(d.address[0], 0x7F, 0x00) else d.address
            out.write(build(a, d.data))
        }
        return out.toByteArray()
    }

    /**
     * One sound out of what came in, or null while it is incomplete: from the
     * latest header to its footer, every dump that is not one of a sound's 17
     * blocks left out. After slots are written the CP-88 sends dumps of its
     * own at 0B 10 00 (LOG.md 2026-10-12), which arrive ahead of a reply.
     */
    fun collect(messages: List<ByteArray>): ByteArray? {
        val dumps = messages.filter { m -> parse(m)?.address?.let { it[0] == HEADER || it[0] == FOOTER || isBlock(it) } == true }
        val start = dumps.indexOfLast { parse(it)!!.address[0] == HEADER }
        if (start < 0) return null
        val sound = dumps.drop(start)
        val end = sound.indexOfFirst { parse(it)!!.address[0] == FOOTER }
        return if (end < 0) null else normalize(sound.take(end + 1))
    }

    private fun isBlock(a: IntArray): Boolean {
        val b = Cp88X9Tables.BLOCKS
        // The current buffer's soundmondo block is at 00 7F 00 too; a slot's at 00 pp 0n.
        return (0 until b.size / 4).any { i -> a[0] == b[4 * i] && (i == 0 || (a[1] == b[4 * i + 1] && a[2] == b[4 * i + 2])) }
    }

    /** A stored sound as the dumps that write it into slot page:set (1-based). */
    fun retarget(sound: ByteArray, page: Int, set: Int): List<ByteArray> {
        val (pp, n) = slot(page, set)
        return split(sound).map { m ->
            val d = parse(m) ?: throw IllegalArgumentException("corrupt CP-88 sound")
            if (d.address[0] == HEADER || d.address[0] == FOOTER) build(intArrayOf(d.address[0], pp, n), d.data) else m
        }
    }

    /** The sound's name, from its common block (15 characters, padding trimmed). */
    fun name(sound: ByteArray): String {
        for (m in split(sound)) {
            val d = parse(m) ?: continue
            if (d.address.contentEquals(COMMON) && d.data.size >= 15) {
                return String(d.data, 0, 15, Charsets.US_ASCII).trimEnd('\u0000', ' ')
            }
        }
        return ""
    }

    private fun slot(page: Int?, set: Int): Pair<Int, Int> {
        if (page == null) return 0x7F to 0x00
        require(page in 1..40 && set in 1..8) { "page 1-40, set 1-8" }
        return (page - 1) to (set - 1)
    }

    private fun checksum(address: IntArray, data: ByteArray): Int {
        var total = MODEL + address.sum()
        data.forEach { total += u(it) }
        return (-total) and 0x7F
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
