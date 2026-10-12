package be.mdw.rigalong.model.devices.cp88

import be.mdw.rigalong.model.Json
import be.mdw.rigalong.model.devices.cp88.Cp88YamlTables as Y

/**
 * cp-liveset's JSON form of Live Set Sounds (`cp88-cp73-liveset-v1`,
 * docs/json-format.md there): every field of all 17 blocks as its raw 0-127
 * value, reserved bytes included, so it is byte-exact both ways.
 *
 * Written as cp-liveset writes it (`json.dump(doc, indent=2)` and a newline,
 * pages and sets in order), and read as it reads it: pydantic's lax rules
 * for a value (5.0, "5" and true pass, 5.5 and null do not), every field
 * required and no others, the name at most 15 characters in 0x20-0x7F after
 * trailing NULs are stripped. Fixtures: tools/gen_cp88x9.py.
 */
object Cp88Json {
    const val FORMAT = "cp88-cp73-liveset-v1"

    /** The document holding [sounds] by (page, set). */
    fun document(sounds: Map<Pair<Int, Int>, ByteArray>): String {
        val out = StringBuilder("{\n  \"format\": \"$FORMAT\",\n  \"pages\": ")
        val pages = sounds.keys.sortedWith(compareBy({ it.first }, { it.second })).groupBy { it.first }
        if (pages.isEmpty()) out.append("{}") else {
            out.append("{\n")
            pages.entries.forEachIndexed { i, (page, keys) ->
                out.append("    \"$page\": {\n")
                keys.forEachIndexed { j, key ->
                    out.append("      \"${key.second}\": ")
                    sound(sounds.getValue(key), out, 6)
                    out.append(if (j < keys.size - 1) ",\n" else "\n")
                }
                out.append("    }").append(if (i < pages.size - 1) ",\n" else "\n")
            }
            out.append("  }")
        }
        return out.append("\n}\n").toString()
    }

    /** Every sound in a document, in page:set order. Throws IllegalArgumentException with a reason. */
    fun readDocument(text: String): List<Cp88X9.Slot> {
        val doc = try { Json.parse(text) } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("not JSON: ${e.message}")
        }
        require(doc is Json.Obj && (doc["format"] as? Json.Str)?.value == FORMAT) { "not a cp-liveset JSON document" }
        val found = LinkedHashMap<Pair<Int, Int>, ByteArray>()
        // As cp-liveset: `doc.get("pages") or {}`.
        val pages = doc["pages"]
        if (pages !is Json.Obj) {
            require(pages == null || pages == Json.Null || isEmpty(pages)) { "pages: expected an object" }
            return emptyList()
        }
        for ((p, sets) in pages.members) {
            val page = number(p, "page \"$p\"")
            require(page in 1..40) { "page $page: pages are 1-40" }
            require(sets is Json.Obj) { "page $page: expected an object of sets" }
            for ((s, node) in sets.members) {
                val set = number(s, "set \"$s\" of page $page")
                require(set in 1..8) { "$page:$set: sets are 1-8" }
                found[page to set] = readSound(node, "$page:$set")
            }
        }
        return found.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (k, sound) -> Cp88X9.Slot(k.first, k.second, Cp88.name(sound), sound) }
    }

    /** Whether [text] looks like one of these documents (cheap: no full parse). */
    fun isDocument(text: String): Boolean =
        text.trimStart().startsWith("{") && Regex("\"format\"\\s*:\\s*\"${Regex.escape(FORMAT)}\"").containsMatchIn(text.take(4096))

    // ------------------------------------------------------------- writing ---

    // The blocks as the document nests them: (path under the sound, block index).
    private val ZONES = (0..3).map { Y.BLOCK_KEYS.indexOf("zone_$it") }
    private val SECTIONS = listOf("piano", "epiano", "sub")
    private val PARTS = listOf("common", "specific", "additional")
    private fun sectionBlock(section: String, part: String) = Y.BLOCK_KEYS.indexOf("section_${section}_$part")

    private fun sound(sound: ByteArray, out: StringBuilder, indent: Int) {
        val blocks = Cp88X9.blocksOf(sound)
        val pad = " ".repeat(indent)
        out.append("{\n")
        for (top in listOf("soundmondo", "master_eq", "common", "additional")) {
            out.append("$pad  \"$top\": ")
            block(blocks, Y.BLOCK_KEYS.indexOf(top), out, indent + 2)
            out.append(",\n")
        }
        out.append("$pad  \"zones\": [\n")
        ZONES.forEachIndexed { i, k ->
            out.append("$pad    ")
            block(blocks, k, out, indent + 4)
            out.append(if (i < ZONES.size - 1) ",\n" else "\n")
        }
        out.append("$pad  ],\n$pad  \"sections\": {\n")
        SECTIONS.forEachIndexed { i, section ->
            out.append("$pad    \"$section\": {\n")
            PARTS.forEachIndexed { j, part ->
                out.append("$pad      \"$part\": ")
                block(blocks, sectionBlock(section, part), out, indent + 6)
                out.append(if (j < PARTS.size - 1) ",\n" else "\n")
            }
            out.append("$pad    }").append(if (i < SECTIONS.size - 1) ",\n" else "\n")
        }
        out.append("$pad  }\n$pad}")
    }

    private fun block(blocks: Array<ByteArray>, k: Int, out: StringBuilder, indent: Int) {
        val pad = " ".repeat(indent)
        val data = blocks[k]
        out.append("{\n")
        Y.FIELDS[k].forEachIndexed { i, (field, off) ->
            out.append("$pad  \"$field\": ")
            when (field) {
                "name" -> string(nameOf(data, off), out)
                "tempo_raw" -> out.append("[\n$pad    ${data[off]},\n$pad    ${data[off + 1]}\n$pad  ]")
                else -> out.append(data[off].toInt())
            }
            out.append(if (i < Y.FIELDS[k].size - 1) ",\n" else "\n")
        }
        out.append("$pad}")
    }

    /** The 15-byte name with trailing NULs stripped, as cp-liveset decodes it. */
    private fun nameOf(data: ByteArray, off: Int): String =
        String(data, off, 15, Charsets.ISO_8859_1).trimEnd('\u0000')

    /** As Python's json with ensure_ascii: printable ASCII as is, all else escaped. */
    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000c' -> out.append("\\f")
            in ' '..'~' -> out.append(c)
            else -> out.append("\\u").append("%04x".format(c.code))
        }
        out.append('"')
    }

    // ------------------------------------------------------------- reading ---

    private fun readSound(node: Json, where: String): ByteArray {
        val s = strict(node, where, listOf("soundmondo", "master_eq", "common", "additional", "zones", "sections"))
        val blocks = Array(Y.BLOCK_KEYS.size) { ByteArray(Cp88X9Tables.BLOCKS[4 * it + 3]) }
        for (top in listOf("soundmondo", "master_eq", "common", "additional")) {
            readBlock(s.getValue(top), Y.BLOCK_KEYS.indexOf(top), blocks, "$where $top")
        }
        val zones = s.getValue("zones")
        require(zones is Json.Arr && zones.items.size == 4) { "$where zones: expected a list of 4" }
        zones.items.forEachIndexed { i, z -> readBlock(z, ZONES[i], blocks, "$where zones[$i]") }
        val sections = strict(s.getValue("sections"), "$where sections", SECTIONS)
        for (section in SECTIONS) {
            val parts = strict(sections.getValue(section), "$where sections.$section", PARTS)
            for (part in PARTS) readBlock(parts.getValue(part), sectionBlock(section, part), blocks, "$where sections.$section.$part")
        }
        return Cp88X9.storedOf(blocks)
    }

    private fun readBlock(node: Json, k: Int, blocks: Array<ByteArray>, where: String) {
        val fields = strict(node, where, Y.FIELDS[k].map { it.first })
        for ((field, off) in Y.FIELDS[k]) {
            val v = fields.getValue(field)
            when (field) {
                "name" -> {
                    val name = ((v as? Json.Str)?.value ?: throw IllegalArgumentException("$where name: expected a string"))
                        .trimEnd('\u0000')
                    require(name.length <= 15) { "$where name: \"$name\" is longer than 15 characters" }
                    require(name.all { it in ' '..'\u007f' }) { "$where name: \"$name\" has a character the CP-88 cannot show" }
                    name.forEachIndexed { i, c -> blocks[k][off + i] = c.code.toByte() }
                }
                "tempo_raw" -> {
                    require(v is Json.Arr && v.items.size == 2) { "$where tempo_raw: expected a list of 2" }
                    v.items.forEachIndexed { i, x -> blocks[k][off + i] = byte(x, "$where tempo_raw[$i]") }
                }
                else -> blocks[k][off] = byte(v, "$where $field")
            }
        }
    }

    /** An object with exactly [keys]: pydantic's extra="forbid", every field required. */
    private fun strict(node: Json, where: String, keys: List<String>): Map<String, Json> {
        require(node is Json.Obj) { "$where: expected an object" }
        val missing = keys.filter { it !in node.members }
        require(missing.isEmpty()) { "$where: ${missing.joinToString()} missing" }
        val extra = node.members.keys.filter { it !in keys }
        require(extra.isEmpty()) { "$where: ${extra.joinToString()} is not a field" }
        return node.members
    }

    /** A 0-127 value, by pydantic's lax rules for an int: a whole number, a string of digits, or a boolean. */
    private fun byte(v: Json, where: String): Byte {
        val n: Long = when (v) {
            is Json.Num -> v.value.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()
            is Json.Bool -> if (v.value) 1L else 0L
            is Json.Str -> v.value.trim().takeIf { Regex("[+-]?[0-9]+").matches(it) }?.toLongOrNull()
            else -> null
        } ?: throw IllegalArgumentException("$where: expected a whole number, got $v")
        require(n in 0..127) { "$where: $n is out of range (0-127)" }
        return n.toByte()
    }

    /** A page or set key, as Python's int() reads it. */
    private fun number(key: String, what: String): Int =
        key.trim().takeIf { Regex("[+-]?[0-9]+").matches(it) }?.toIntOrNull()
            ?: throw IllegalArgumentException("$what: expected a number")

    private fun isEmpty(v: Json) = when (v) {
        is Json.Arr -> v.items.isEmpty()
        is Json.Str -> v.value.isEmpty()
        is Json.Num -> v.value == 0.0
        is Json.Bool -> !v.value
        else -> false
    }
}
