package com.rigalong.model.devices.cp88

import com.rigalong.model.devices.cp88.Cp88YamlTables as Y
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.schema.CoreSchema

/**
 * A Live Set Sound in cp-liveset's concise YAML form (cp88-cp73-liveset-yaml-v1;
 * its docs/yaml-format.md and yamlformat.py): only what differs from the
 * factory "standard" values, decoded to names, booleans and signed offsets and
 * grouped, with anything no rule covers kept exact in a `raw:` map. Written
 * byte for byte as cp-liveset writes it (ruamel.yaml's style; checked against
 * fixtures cp-liveset made, tools/gen_cp88x9.py), and read as cp-liveset reads
 * it, so a sound edited here or with cp-liveset is the same text.
 *
 * The name keeps trailing NULs and drops trailing spaces, where JSON does the
 * opposite; on read it is space-padded to 15 again and the NULs stripped, so a
 * name padded either way comes back as it was.
 */
object Cp88Yaml {
    const val FORMAT = "cp88-cp73-liveset-yaml-v1"

    /** One sound (stored form, Cp88) as YAML, starting at column 0. */
    fun write(sound: ByteArray): String = emit(toTree(Values.of(sound)))

    /**
     * The sound (stored form) in [text]: a sound's mapping as [write] gives it, or
     * a whole document holding one sound. Throws IllegalArgumentException with
     * a message for the person editing it.
     */
    fun read(text: String): ByteArray {
        val root = asMap(load(text), "the sound")
        if ("format" !in root) return fromTree(root).stored()
        val slots = readDocument(root)
        require(slots.size == 1) { "this document holds ${slots.size} sounds; one is needed" }
        return slots[0].sound
    }

    /** A cp88-cp73-liveset-yaml-v1 document of [sounds] by (page, set), as cp-liveset writes it. */
    fun document(sounds: Map<Pair<Int, Int>, ByteArray>): String {
        val out = YMap()
        out["format"] = FORMAT
        val pages = YMap()
        for ((page, set) in sounds.keys.sortedWith(compareBy({ it.first }, { it.second }))) {
            require(page in 1..40 && set in 1..8) { "page 1-40, set 1-8" }
            pages.map(page)[set] = toTree(Values.of(sounds[page to set]!!))
        }
        out["pages"] = pages
        return emit(out)
    }

    /** Every sound in a cp88-cp73-liveset-yaml-v1 document, in document order. */
    fun readDocument(text: String): List<Cp88X9.Slot> = readDocument(asMap(load(text), "the document"))

    /** Whether [text] looks like a cp-liveset YAML document (for picking a reader by content). */
    fun isDocument(text: String): Boolean =
        Regex("""(?m)^format:\s*['"]?${Regex.escape(FORMAT)}['"]?\s*(#.*)?$""").containsMatchIn(text)

    private fun readDocument(root: Map<*, *>): List<Cp88X9.Slot> {
        require(root["format"] == FORMAT) { "not a $FORMAT document (format: ${root["format"]})" }
        val out = ArrayList<Cp88X9.Slot>()
        for ((p, sets) in asMap(root["pages"] ?: emptyMap<Any, Any>(), "pages")) {
            val page = int(p, "page")
            for ((s, node) in asMap(sets ?: emptyMap<Any, Any>(), "page $page")) {
                val set = int(s, "set")
                require(page in 1..40 && set in 1..8) { "page $page set $set: page 1-40, set 1-8" }
                val sound = try {
                    fromTree(asMap(node, "page $page set $set")).stored()
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("page $page set $set: ${e.message}", e)
                }
                out += Cp88X9.Slot(page, set, Cp88.name(sound), sound)
            }
        }
        require(out.isNotEmpty()) { "no Live Set Sounds in this document" }
        return out
    }

    // ------------------------------------------------------------ the values

    /** A sound's fields by "block.field", as cp-liveset's model holds them. */
    private class Values {
        val v = HashMap<String, Int>()
        var name = ""                                   // trailing NULs stripped
        var tempo: List<Int> = Y.DEFAULT_TEMPO.toList()  // additional.tempo_raw
        /** Where a decoded value came from in the YAML, for messages. */
        val where = HashMap<String, String>()

        operator fun get(key: String): Int = v[key] ?: error("no field $key")

        fun stored(): ByteArray {
            require(name.length <= 15) { "name: \"$name\" is longer than 15 characters" }
            require(name.all { it in ' '..'\u007f' }) { "name: \"$name\" has a character the CP-88 cannot show" }
            require(tempo.size == 2 && tempo.all { it in 0..127 }) { "${where["additional.tempo_raw"] ?: "tempo"}: $tempo" }
            val blocks = Array(Y.BLOCK_KEYS.size) { ByteArray(Cp88X9Tables.BLOCKS[4 * it + 3]) }
            for ((k, block) in Y.BLOCK_KEYS.withIndex()) for ((field, off) in Y.FIELDS[k]) when (field) {
                "name" -> name.forEachIndexed { i, c -> blocks[k][off + i] = c.code.toByte() }
                "tempo_raw" -> { blocks[k][off] = tempo[0].toByte(); blocks[k][off + 1] = tempo[1].toByte() }
                else -> {
                    val key = "$block.$field"
                    val x = this[key]
                    require(x in 0..127) { "${where[key] ?: key}: $x is out of range" }
                    blocks[k][off] = x.toByte()
                }
            }
            return Cp88X9.storedOf(blocks)
        }

        companion object {
            fun of(sound: ByteArray): Values {
                val blocks = Cp88X9.blocksOf(sound)
                val out = Values()
                for ((k, block) in Y.BLOCK_KEYS.withIndex()) for ((field, off) in Y.FIELDS[k]) when (field) {
                    "name" -> out.name = String(blocks[k], off, 15, Charsets.ISO_8859_1).trimEnd('\u0000')
                    "tempo_raw" -> out.tempo = listOf(u(blocks[k][off]), u(blocks[k][off + 1]))
                    else -> out.v["$block.$field"] = u(blocks[k][off])
                }
                return out
            }

            /** Every field at its standard value (0 where there is none). */
            fun defaults(): Values {
                val out = Values()
                for ((k, block) in Y.BLOCK_KEYS.withIndex()) for ((field, _) in Y.FIELDS[k]) {
                    if (field != "name" && field != "tempo_raw") out.v["$block.$field"] = Y.DEFAULTS["$block.$field"] ?: 0
                }
                return out
            }
        }
    }

    private fun default(key: String): Int = Y.DEFAULTS[key] ?: error("no standard value for $key")

    // ------------------------------------------------------------- the specs

    private enum class C { ID, BOOL, SIGNED, PLUS1, NOTE, ENUM }

    /** One field's place in the YAML and how its raw value reads there (yamlformat.py's FieldSpec). */
    private class Spec(val field: String, val path: List<String>, val c: C = C.ID, val table: Map<Int, String>? = null) {
        val reverse: Map<String, Int>? = table?.entries?.associate { (k, v) -> v to k }

        fun enc(raw: Int): Any = when (c) {
            C.ID -> raw
            C.BOOL -> raw != 0
            C.SIGNED -> raw - 64
            C.PLUS1 -> raw + 1
            C.NOTE -> noteName(raw)
            C.ENUM -> table!![raw] ?: raw
        }

        fun dec(value: Any?, where: String): Int = when (c) {
            C.ID -> int(value, where)
            C.BOOL -> if (truthy(value)) 1 else 0
            C.SIGNED -> int(value, where) + 64
            C.PLUS1 -> int(value, where) - 1
            C.NOTE -> if (value is String) NOTES[value] ?: throw IllegalArgumentException("$where: no note \"$value\"")
                      else int(value, where)
            C.ENUM -> if (value is String) reverse!![value]
                ?: throw IllegalArgumentException("$where: \"$value\" is none of ${table!!.values.joinToString(", ")}")
                      else int(value, where)
        }

        fun at(prefix: List<String>) = Spec(field, prefix + path, c, table)
    }

    private fun spec(field: String, path: String, c: C = C.ID, table: Map<Int, String>? = null) =
        Spec(field, path.split('.'), c, table)

    private val SECTION_COMMON = listOf(
        spec("split_mode", "split_mode", C.ENUM, Y.SPLIT_MODE),
        spec("octave_shift", "octave_shift", C.SIGNED),
        spec("section_volume", "volume"),
        spec("tone", "tone"),
        spec("pitch_bend_range", "pitch_bend_range", C.SIGNED),
        spec("pitch_modulation_depth", "pitch_modulation_depth"),
        spec("receive_expression", "receive.expression", C.BOOL),
        spec("receive_sustain", "receive.sustain", C.BOOL),
        spec("receive_sostenuto", "receive.sostenuto", C.BOOL),
        spec("receive_soft", "receive.soft", C.BOOL),
        spec("delay_depth", "delay_depth"),
        spec("reverb_depth", "reverb_depth"),
    )

    private val SECTION_SPECIFIC = listOf(
        spec("piano_damper_resonance_switch", "damper_resonance.on", C.BOOL),
        spec("piano_damper_resonance_damper_control", "damper_resonance.control"),
        spec("piano_damper_resonance_dry_wet_balance", "damper_resonance.balance"),
        spec("piano_effect_switch", "piano_effect.on", C.BOOL),
        spec("piano_effect_type", "piano_effect.type", C.ENUM, Y.PIANO_EFFECT_TYPE),
        spec("piano_effect_depth", "piano_effect.depth"),
        spec("epiano_effect1_switch", "epiano_effect1.on", C.BOOL),
        spec("epiano_effect1_type", "epiano_effect1.type", C.ENUM, Y.EPIANO_EFFECT1_TYPE),
        spec("epiano_effect1_depth", "epiano_effect1.depth"),
        spec("epiano_effect1_rate", "epiano_effect1.rate"),
        spec("epiano_effect2_switch", "epiano_effect2.on", C.BOOL),
        spec("epiano_effect2_type", "epiano_effect2.type", C.ENUM, Y.EPIANO_EFFECT2_TYPE),
        spec("epiano_effect2_depth", "epiano_effect2.depth"),
        spec("epiano_effect2_speed", "epiano_effect2.speed"),
        spec("epiano_drive_switch", "epiano_drive.on", C.BOOL),
        spec("epiano_drive", "epiano_drive.depth"),
        spec("sub_effect_switch", "sub_effect.on", C.BOOL),
        spec("sub_effect_type", "sub_effect.type", C.ENUM, Y.SUB_EFFECT_TYPE),
        spec("sub_effect_depth", "sub_effect.depth"),
        spec("sub_effect_speed", "sub_effect.speed"),
        spec("sub_attack", "sub_envelope.attack"),
        spec("sub_release", "sub_envelope.release"),
    )

    /** Under its own section a group drops the section's name ("sub_effect" is "effect" under sub:). */
    private val SECTION_SPECIFIC_BY_SEC: Map<String, List<Spec>> = SECTIONS.associateWith { sec ->
        SECTION_SPECIFIC.map { sp ->
            val head = sp.path[0]
            if (head.startsWith("${sec}_")) Spec(sp.field, listOf(head.removePrefix("${sec}_")) + sp.path.drop(1), sp.c, sp.table)
            else sp
        }
    }

    private val SECTION_ADDITIONAL = listOf(
        spec("mono_poly", "mono_poly", C.ENUM, Y.MONO_POLY),
        spec("portamento_switch", "portamento.on", C.BOOL),
        spec("portamento_time", "portamento.time"),
        spec("portamento_mode", "portamento.mode", C.ENUM, Y.PORTAMENTO_MODE),
        spec("portamento_time_mode", "portamento.time_mode", C.ENUM, Y.PORTAMENTO_TIME_MODE),
        spec("pan", "pan", C.SIGNED),
    )

    /** Fields of the common block that belong to a section ("{sec}" is the section). */
    private val TOUCH = listOf(
        spec("{sec}_touch_sensitivity_depth", "touch_sensitivity.depth"),
        spec("{sec}_touch_sensitivity_offset", "touch_sensitivity.offset"),
        spec("{sec}_pitch_modulation_speed", "pitch_modulation_speed"),
    )

    /** Shown even at their standard value: the physical Delay and Reverb switches. */
    private val ALWAYS_SHOW = setOf("delay_switch", "reverb_switch")

    /** The top-level common: group, block "common" unless marked "additional." (tempo is its own case). */
    private val COMMON = listOf(
        spec("zone_mode_switch", "zone_mode", C.BOOL),
        spec("advanced_zone_mode_switch", "advanced_zone_mode", C.BOOL),
        spec("live_set_eq_mode_switch", "live_set_eq_mode", C.BOOL),
        spec("modulation_lever_assign", "modulation_lever.assign", C.ENUM, Y.CC_NAMES),
        spec("modulation_lever_limit_low", "modulation_lever.limit_low"),
        spec("modulation_lever_limit_high", "modulation_lever.limit_high"),
        spec("tg_transpose", "tg_transpose", C.SIGNED),
        spec("split_point", "split_point", C.NOTE),
        spec("fc1_assign", "fc1.assign", C.ENUM, Y.CC_NAMES),
        spec("fc1_limit_low", "fc1.limit_low"),
        spec("fc1_limit_high", "fc1.limit_high"),
        spec("fc2_assign", "fc2.assign", C.ENUM, Y.CC_NAMES),
        spec("fc2_limit_low", "fc2.limit_low"),
        spec("fc2_limit_high", "fc2.limit_high"),
        spec("depth_knob_section_select", "depth_knob_section", C.ENUM, Y.DEPTH_KNOB_SECTION),
        spec("delay_switch", "delay.on", C.BOOL),
        spec("delay_type", "delay.type", C.ENUM, Y.DELAY_TYPE),
        spec("delay_feedback", "delay.feedback"),
        spec("delay_time", "delay.time"),
        spec("additional.tempo_raw", "delay.tempo"),
        spec("additional.tempo_delay_time", "delay.tempo_delay_time", C.ENUM, Y.TEMPO_DELAY_TIME),
        spec("reverb_switch", "reverb.on", C.BOOL),
        spec("reverb_time", "reverb.time"),
    )

    private val MASTER_EQ = listOf(
        spec("eq_gain1", "low", C.SIGNED),
        spec("eq_gain3", "mid", C.SIGNED),
        spec("eq_frequency3", "mid_freq", C.ENUM, Y.EQ_FREQ3),
        spec("eq_gain5", "high", C.SIGNED),
        spec("eq_on_off", "on", C.BOOL),
    )

    private val ZONE = listOf(
        spec("zone_switch", "on", C.BOOL),
        spec("transmit_channel", "transmit_channel", C.PLUS1),
        spec("transpose_octave", "transpose_octave", C.SIGNED),
        spec("transpose_semitone", "transpose_semitone", C.SIGNED),
        spec("note_limit_low", "note_limit.low", C.NOTE),
        spec("note_limit_high", "note_limit.high", C.NOTE),
        spec("midi_volume", "volume"),
        spec("midi_pan", "pan", C.SIGNED),
        // Bank MSB and LSB are two values on the panel too, not one 14-bit number.
        spec("midi_bank_msb", "bank.msb"),
        spec("midi_bank_lsb", "bank.lsb"),
        spec("midi_program_number", "program", C.PLUS1),
    )

    private val TRANSMIT1 = listOf("bank_select" to 0, "program_change" to 1, "volume" to 2, "pan" to 3, "note" to 4)
    private val TRANSMIT2 = listOf("pitch_bend" to 0, "modulation" to 1, "fc1" to 2, "fc2" to 3, "foot_switch" to 4, "sustain" to 5)

    private val SECTIONS get() = listOf("piano", "epiano", "sub")
    private val CATEGORY_BASE = mapOf("piano" to 0, "epiano" to 4, "sub" to 8)
    private const val ADVANCED = "Advanced Mode"

    // Tempo: [msb, lsb] is V = msb*128 + lsb, V/10 BPM; shown as BPM over the panel's 42.0-240.0.
    private const val TEMPO_MIN = 420
    private const val TEMPO_MAX = 2400

    private fun encTempo(raw: List<Int>): Any {
        val v = raw[0] * 128 + raw[1]
        return if (v in TEMPO_MIN..TEMPO_MAX) v / 10.0 else raw
    }

    private fun decTempo(value: Any?, where: String): List<Int> {
        if (value is List<*>) return value.map { int(it, where) }
        val bpm = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: throw IllegalArgumentException("$where: \"$value\" is not a tempo")
        val v = Math.rint(bpm * 10).toLong()   // Python's round(): half to even
        require(v in 0..16383) { "$where: $value is out of range" }
        return listOf((v / 128).toInt(), (v % 128).toInt())
    }

    // ------------------------------------------------------- sound to YAML

    private fun encodeMain(f: Values, specs: List<Spec>, prefix: String, out: YMap, consumed: MutableSet<String>): List<Pair<Spec, Int>> {
        val deferred = ArrayList<Pair<Spec, Int>>()
        for (sp in specs) {
            consumed += sp.field
            val raw = f["$prefix.${sp.field}"]
            if (raw == default("$prefix.${sp.field}")) {
                if (sp.path.last() == "on") deferred += sp to raw
                continue
            }
            out.setPath(sp.path, sp.enc(raw))
        }
        return deferred
    }

    /** An "on" left out at its standard value is shown after all, first, when its group has other entries. */
    private fun finalizeOn(out: YMap, deferred: List<Pair<Spec, Int>>) {
        for ((sp, raw) in deferred) {
            val groupPath = sp.path.dropLast(1)
            val group = if (groupPath.isEmpty()) out else out.getPath(groupPath) as? YMap
            if (group != null && group.items.isNotEmpty()) {
                out.setPath(sp.path, sp.enc(raw))
                group.moveToFront("on")
            }
        }
    }

    private fun encode(f: Values, specs: List<Spec>, prefix: String, out: YMap, consumed: MutableSet<String>) =
        finalizeOn(out, encodeMain(f, specs, prefix, out, consumed))

    private fun advancedNumber(voice: Int, extra: Int) = if (voice == 0x7F) 128 + extra else voice + 1

    private fun advancedValue(number: Int): Any = Y.ADVANCED_VOICES[number] ?: number

    private fun categories(sec: String) = Y.SECTION_CATEGORIES.getValue(sec)

    private fun sectionToYaml(sec: String, f: Values, consumed: Map<String, MutableSet<String>>): YMap {
        val p = "section_${sec}_common"
        val out = YMap()
        out["on"] = f["$p.section_switch"] != default("$p.section_switch")
        consumed.getValue(p) += listOf(
            "section_switch", "current_category", "advanced_sound_mode_switch", "advanced_sound_mode_voice_number",
            "advanced_sound_mode_extra_voice_number", "category1_voice_number", "category2_voice_number",
            "category3_voice_number", "category4_voice_number",
        )
        val advanced = f["$p.advanced_sound_mode_switch"] != default("$p.advanced_sound_mode_switch")
        val catRaw = f["$p.current_category"] - CATEGORY_BASE.getValue(sec)
        val active = if (catRaw in 0..3) catRaw else 0
        val advNumber = advancedNumber(f["$p.advanced_sound_mode_voice_number"], f["$p.advanced_sound_mode_extra_voice_number"])
        val advDefault = advancedNumber(default("$p.advanced_sound_mode_voice_number"), default("$p.advanced_sound_mode_extra_voice_number"))

        /** A category's voice: its name and number (the number the panel shows), or just the number. */
        fun voice(i: Int): Pair<Any, String?> {
            val raw = f["$p.category${i + 1}_voice_number"]
            val voices = categories(sec)[i].second
            return if (raw in voices.indices) voices[raw] to "${raw + 1}" else (raw + 1) to null
        }

        val activeCategory: String
        val activeValue: Pair<Any, String?>
        if (advanced) {
            activeCategory = ADVANCED
            activeValue = advancedValue(advNumber) to null   // the panel shows no Advanced Mode number
        } else {
            activeCategory = categories(sec)[active].first
            activeValue = voice(active)
        }
        out["category"] = activeCategory
        val others = ArrayList<Triple<String, Any, String?>>()
        for (i in 0 until 4) {
            if (i == active && !advanced) continue
            if (f["$p.category${i + 1}_voice_number"] == 0) continue
            val (value, comment) = voice(i)
            others += Triple(categories(sec)[i].first, value, comment)
        }
        if (!advanced && advNumber != advDefault) others += Triple(ADVANCED, advancedValue(advNumber), null)
        if (others.isNotEmpty()) {
            val instrument = YMap()
            instrument[activeCategory] = activeValue.first
            activeValue.second?.let { instrument.comment(activeCategory, it) }
            for ((name, value, comment) in others) {
                instrument[name] = value
                comment?.let { instrument.comment(name, it) }
            }
            out["instrument"] = instrument
        } else {
            out["instrument"] = activeValue.first
            activeValue.second?.let { out.comment("instrument", it) }
        }
        encode(f, SECTION_COMMON, p, out, consumed.getValue(p))
        encode(f, SECTION_SPECIFIC_BY_SEC.getValue(sec), "section_${sec}_specific", out, consumed.getValue("section_${sec}_specific"))
        encode(f, SECTION_ADDITIONAL, "section_${sec}_additional", out, consumed.getValue("section_${sec}_additional"))
        return out
    }

    private fun zoneToYaml(f: Values, zz: Int, consumed: MutableSet<String>): YMap {
        val p = "zone_$zz"
        val out = YMap()
        val deferred = encodeMain(f, ZONE, p, out, consumed)
        val transmit = YMap()
        for ((bits, field) in listOf(TRANSMIT1 to "transmit_switches_1", TRANSMIT2 to "transmit_switches_2")) {
            val sw = f["$p.$field"]
            val def = default("$p.$field")
            for ((name, bit) in bits) {
                val b = (sw shr bit) and 1
                if (b != ((def shr bit) and 1)) transmit[name] = b == 1
            }
            consumed += field
        }
        if (transmit.items.isNotEmpty()) out["transmit"] = transmit
        finalizeOn(out, deferred)
        return out
    }

    private fun toTree(f: Values): YMap {
        val out = YMap()
        out["name"] = f.name.padEnd(15, '\u0000').trimEnd(' ')
        val consumed = Y.BLOCK_KEYS.associateWith { HashSet<String>() }
        consumed.getValue("common") += "name"
        for (sec in SECTIONS) {
            val secOut = sectionToYaml(sec, f, consumed)
            for (sp in TOUCH) {
                val field = sp.field.replace("{sec}", sec)
                consumed.getValue("common") += field
                val raw = f["common.$field"]
                if (raw == default("common.$field")) continue
                secOut.setPath(sp.path, sp.enc(raw))
            }
            out[sec] = secOut
            for (part in listOf("common", "specific", "additional")) rawFallback(out, f, "section_${sec}_$part", consumed)
        }
        val common = YMap()
        for (sp in COMMON) {
            val (block, field) = if (sp.field.startsWith("additional.")) "additional" to sp.field.removePrefix("additional.")
                                 else "common" to sp.field
            consumed.getValue(block) += field
            if (field == "tempo_raw") {
                if (f.tempo != Y.DEFAULT_TEMPO.toList()) common.setPath(sp.path, encTempo(f.tempo))
                continue
            }
            val raw = f["$block.$field"]
            if (raw == default("$block.$field") && field !in ALWAYS_SHOW) continue
            common.setPath(sp.path, sp.enc(raw))
        }
        if (common.items.isNotEmpty()) out["common"] = common
        val eq = YMap()
        encode(f, MASTER_EQ, "master_eq", eq, consumed.getValue("master_eq"))
        if (eq.items.isNotEmpty()) out["master_eq"] = eq
        for (block in listOf("common", "additional", "master_eq", "soundmondo")) rawFallback(out, f, block, consumed)
        val zones = YMap()
        for (zz in 0 until 4) {
            val zone = zoneToYaml(f, zz, consumed.getValue("zone_$zz"))
            if (zone.items.isNotEmpty()) zones[zz + 1] = zone
            rawFallback(out, f, "zone_$zz", consumed)
        }
        if (zones.items.isNotEmpty()) out["zones"] = zones
        return out
    }

    /** Whatever no rule showed and is not at its standard value, exact, under raw: "block.field". */
    private fun rawFallback(out: YMap, f: Values, block: String, consumed: Map<String, Set<String>>) {
        val k = Y.BLOCK_KEYS.indexOf(block)
        for ((field, _) in Y.FIELDS[k]) {
            if (field in consumed.getValue(block) || field == "name") continue
            if (field == "tempo_raw") {
                if (f.tempo != Y.DEFAULT_TEMPO.toList()) out.map("raw")["$block.$field"] = f.tempo
                continue
            }
            val value = f["$block.$field"]
            if (value != (Y.DEFAULTS["$block.$field"] ?: 0)) out.map("raw")["$block.$field"] = value
        }
    }

    // ------------------------------------------------------- YAML to sound

    private fun decode(f: Values, specs: List<Spec>, prefix: String, src: Map<*, *>, where: String) {
        for (sp in specs) {
            val (value, found) = getPath(src, sp.path)
            if (!found) continue
            val at = where + sp.path.joinToString(".")
            f.v["$prefix.${sp.field}"] = sp.dec(value, at)
            f.where["$prefix.${sp.field}"] = at
        }
    }

    private fun sectionFromYaml(sec: String, src: Map<*, *>, f: Values) {
        val p = "section_${sec}_common"
        f.v["$p.section_switch"] = if (truthy(src["on"])) 1 else default("$p.section_switch")
        val (catValue, hasCategory) = getPath(src, listOf("category"))
        val category = if (hasCategory) catValue else categories(sec)[0].first
        val (instrument, hasInstrument) = getPath(src, listOf("instrument"))
        val slots: Map<*, *> = when {
            hasInstrument && instrument is Map<*, *> -> instrument
            hasInstrument -> mapOf(category to instrument)
            else -> emptyMap<Any, Any>()
        }
        val advanced = category == ADVANCED
        f.v["$p.advanced_sound_mode_switch"] = if (advanced) 1 else default("$p.advanced_sound_mode_switch")
        if (advanced) {
            f.v["$p.current_category"] = default("$p.current_category")
        } else {
            val i = categories(sec).indexOfFirst { it.first == category }
            require(i >= 0) { "$sec.category: \"$category\" is none of ${(categories(sec).map { it.first } + ADVANCED).joinToString(", ")}" }
            f.v["$p.current_category"] = CATEGORY_BASE.getValue(sec) + i
        }
        for (i in 0 until 4) {
            val (name, voices) = categories(sec)[i]
            val at = "$sec.instrument"
            f.v["$p.category${i + 1}_voice_number"] = if (name in slots) {
                when (val value = slots[name]) {
                    is String -> voices.indexOf(value).also { require(it >= 0) { "$at: no $name voice \"$value\"" } }
                    else -> int(value, at) - 1
                }
            } else 0
            f.where["$p.category${i + 1}_voice_number"] = at
        }
        if (ADVANCED in slots) {
            val value = slots[ADVANCED]
            val number = if (value is String) Y.ADVANCED_VOICES.entries.firstOrNull { it.value == value }?.key
                ?: throw IllegalArgumentException("$sec.instrument: no voice \"$value\"")
                else int(value, "$sec.instrument")
            require(number in 1..130) { "$sec.instrument: Advanced Mode voice $number is not 1-130" }
            f.v["$p.advanced_sound_mode_voice_number"] = if (number <= 127) number - 1 else 0x7F
            f.v["$p.advanced_sound_mode_extra_voice_number"] = if (number <= 127) 0 else number - 128
        }
        decode(f, SECTION_COMMON, p, src, "$sec.")
        decode(f, SECTION_SPECIFIC_BY_SEC.getValue(sec), "section_${sec}_specific", src, "$sec.")
        decode(f, SECTION_ADDITIONAL, "section_${sec}_additional", src, "$sec.")
        for (sp in TOUCH) {
            val field = sp.field.replace("{sec}", sec)
            val (value, found) = getPath(src, sp.path)
            if (found) {
                f.v["common.$field"] = sp.dec(value, "$sec.${sp.path.joinToString(".")}")
                f.where["common.$field"] = "$sec.${sp.path.joinToString(".")}"
            }
        }
    }

    private fun fromTree(src: Map<*, *>): Values {
        val f = Values.defaults()
        // As cp-liveset: the name as text, space-padded to 15, trailing NULs then stripped.
        f.name = pyStr(src["name"] ?: "", "name").padEnd(15, ' ').trimEnd('\u0000')
        decode(f, MASTER_EQ, "master_eq", section(src, "master_eq"), "master_eq.")
        val common = section(src, "common")
        for (sp in COMMON) {
            val (value, found) = getPath(common, sp.path)
            if (!found) continue
            val at = "common." + sp.path.joinToString(".")
            if (sp.field == "additional.tempo_raw") {
                f.tempo = decTempo(value, at)
                f.where["additional.tempo_raw"] = at
                continue
            }
            val key = if (sp.field.startsWith("additional.")) sp.field else "common.${sp.field}"
            f.v[key] = sp.dec(value, at)
            f.where[key] = at
        }
        val zones = section(src, "zones")
        for (zz in 0 until 4) {
            val zone = asMap(zones[zz + 1] ?: emptyMap<Any, Any>(), "zones.${zz + 1}")
            decode(f, ZONE, "zone_$zz", zone, "zones.${zz + 1}.")
            val (transmit, has) = getPath(zone, listOf("transmit"))
            for ((bits, field) in listOf(TRANSMIT1 to "transmit_switches_1", TRANSMIT2 to "transmit_switches_2")) {
                var sw = default("zone_$zz.$field")
                if (has && transmit is Map<*, *>) for ((name, bit) in bits) {
                    if (name in transmit) sw = if (truthy(transmit[name])) sw or (1 shl bit) else sw and (1 shl bit).inv()
                }
                f.v["zone_$zz.$field"] = sw
            }
        }
        for (sec in SECTIONS) sectionFromYaml(sec, section(src, sec), f)
        // raw: last, over everything else. An unknown key is refused, where cp-liveset
        // ignores one whose block it does not know.
        for ((key, value) in section(src, "raw")) {
            val k = key.toString()
            val block = k.substringBefore('.')
            val field = k.substringAfter('.', "")
            val b = Y.BLOCK_KEYS.indexOf(block)
            require(b >= 0 && Y.FIELDS[b].any { it.first == field }) { "raw: no field $k" }
            when (field) {
                "name" -> f.name = pyStr(value, "raw.$k").trimEnd('\u0000')
                "tempo_raw" -> f.tempo = (value as? List<*> ?: throw IllegalArgumentException("raw.$k: [msb, lsb]"))
                    .map { int(it, "raw.$k") }
                else -> f.v[k] = int(value, "raw.$k")
            }
            f.where[k] = "raw.$k"
        }
        return f
    }

    // ------------------------------------------------------------ helpers

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private fun noteName(raw: Int) = NOTE_NAMES[raw % 12] + (raw / 12 - 2)
    private val NOTES: Map<String, Int> by lazy { (0..127).associateBy { noteName(it) } }

    private fun u(b: Byte) = b.toInt() and 0xFF

    /** Python's truth value, which cp-liveset tests switches by. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        else -> true
    }

    /** A whole number, taken as leniently as cp-liveset's model takes one. */
    private fun int(v: Any?, where: String): Int {
        val n: Long? = when (v) {
            is Boolean -> if (v) 1 else 0
            is Int -> v.toLong()
            is Long -> v
            is java.math.BigInteger -> if (v.bitLength() < 32) v.toLong() else null
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e9) v.toLong() else null
            is String -> v.trim().toLongOrNull()
            else -> null
        }
        require(n != null && n in Int.MIN_VALUE..Int.MAX_VALUE) { "$where: \"${v ?: ""}\" is not a whole number" }
        return n.toInt()
    }

    /** Python's str() of a scalar, as cp-liveset makes a name of whatever the YAML holds. */
    private fun pyStr(v: Any?, where: String): String = when (v) {
        null -> "None"
        is String -> v
        is Boolean -> if (v) "True" else "False"
        is Double -> when {
            v.isNaN() -> "nan"
            v.isInfinite() -> if (v > 0) "inf" else "-inf"
            else -> v.toString()
        }
        is Number -> v.toString()
        else -> throw IllegalArgumentException("$where: expected text")
    }

    private fun section(src: Map<*, *>, key: String): Map<*, *> = asMap(src[key] ?: emptyMap<Any, Any>(), key)

    private fun asMap(v: Any?, what: String): Map<*, *> =
        v as? Map<*, *> ?: throw IllegalArgumentException("$what: expected a mapping (key: value lines)")

    private fun getPath(root: Map<*, *>, path: List<String>): Pair<Any?, Boolean> {
        var cur: Any? = root
        for (key in path) {
            if (cur !is Map<*, *> || key !in cur) return null to false
            cur = cur[key]
        }
        return cur to true
    }

    private fun load(text: String): Any? = try {
        Load(LoadSettings.builder().setSchema(CoreSchema()).build()).loadFromString(text)
    } catch (e: YamlEngineException) {
        throw IllegalArgumentException(e.message?.trim() ?: "not YAML", e)
    }

    // ------------------------------------------------------ the YAML tree

    /** A mapping in insertion order, with end-of-line comments: ruamel's CommentedMap, as far as used. */
    private class YMap {
        val items = LinkedHashMap<Any, Any>()
        val comments = HashMap<Any, String>()

        operator fun set(key: Any, value: Any) { items[key] = value }

        fun map(key: Any): YMap = items.getOrPut(key) { YMap() } as YMap

        fun setPath(path: List<String>, value: Any) {
            var m = this
            for (k in path.dropLast(1)) m = m.map(k)
            m[path.last()] = value
        }

        fun getPath(path: List<String>): Any? {
            var cur: Any? = this
            for (k in path) cur = (cur as? YMap)?.items?.get(k) ?: return null
            return cur
        }

        fun moveToFront(key: Any) {
            val v = items.remove(key) ?: return
            val rest = LinkedHashMap(items)
            items.clear()
            items[key] = v
            items.putAll(rest)
        }

        /**
         * ruamel puts the first comment of a map two spaces after its value, and
         * one at column 0 -- which comes out one space after -- when the key before
         * it, or one sorting before it, already has a comment.
         */
        fun comment(key: String, text: String) {
            val keys = items.keys.toList()
            val i = keys.indexOf(key)
            val pre = if (i > 0) keys[i - 1] else null
            val post = if (pre != null && i + 1 < keys.size) keys[i + 1] else null
            var found = (pre != null && pre in comments) || (post != null && post in comments)
            if (!found) for (k in keys) {
                if ((k as String) >= key) break
                if (k in comments) { found = true; break }
            }
            comments[key] = (if (found) " # " else "  # ") + text
        }
    }

    /** Block style, two-space mappings, sequences indented two with their dashes: make_yaml()'s output. */
    private fun emit(m: YMap, indent: Int = 0, sb: StringBuilder = StringBuilder()): String {
        val pad = " ".repeat(indent)
        for ((k, v) in m.items) {
            sb.append(pad).append(scalar(k, key = true)).append(':')
            when {
                v is YMap && v.items.isEmpty() -> sb.append(" {}\n")
                v is YMap -> { sb.append('\n'); emit(v, indent + 2, sb) }
                v is List<*> && v.isEmpty() -> sb.append(" []\n")
                v is List<*> -> { sb.append('\n'); for (x in v) sb.append(pad).append("  - ").append(scalar(x!!, false)).append('\n') }
                else -> { sb.append(' ').append(scalar(v, false)); m.comments[k]?.let { sb.append(it) }; sb.append('\n') }
            }
        }
        return sb.toString()
    }

    private fun scalar(v: Any, key: Boolean): String = when (v) {
        is Boolean -> if (v) "true" else "false"
        is Int, is Long -> v.toString()
        is Double -> v.toString()
        is String -> YamlScalar.write(v, key)
        else -> error("cannot write ${v::class}")
    }
}

/**
 * How ruamel.yaml (YAML 1.2) writes a string: plain where that reads back as the
 * same string, else single-quoted, else double-quoted with escapes. A port of
 * its Emitter.analyze_scalar / choose_scalar_style and VersionedResolver.
 */
internal object YamlScalar {
    private const val WHITESPACE = "\u0000 \t\r\n\u0085  "
    private const val BREAKS = "\n\u0085  "

    /** (first characters, whether "" is one, the pattern): the implicit types a plain scalar can turn into. */
    private val RESOLVERS = listOf(
        Triple("tTfF", false, Regex("true|True|TRUE|false|False|FALSE")),
        Triple("-+0123456789.", false, Regex(
            """[-+]?(?:[0-9][0-9_]*)\.[0-9_]*(?:[eE][-+]?[0-9]+)?|[-+]?(?:[0-9][0-9_]*)(?:[eE][-+]?[0-9]+)""" +
                """|[-+]?\.[0-9_]+(?:[eE][-+][0-9]+)?|[-+]?\.(?:inf|Inf|INF)|\.(?:nan|NaN|NAN)""")),
        Triple("-+0123456789", false, Regex("""[-+]?0b[0-1_]+|[-+]?0o?[0-7_]+|[-+]?[0-9_]+|[-+]?0x[0-9a-fA-F_]+""")),
        Triple("<", false, Regex("<<")),
        Triple("~nN", true, Regex("~|null|Null|NULL|")),
        Triple("0123456789", false, Regex(
            """[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]""" +
                """|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?(?:[Tt]|[ \t]+)[0-9][0-9]?:[0-9][0-9]:[0-9][0-9]""" +
                """(?:\.[0-9]*)?(?:[ \t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?""")),
        Triple("=", false, Regex("=")),
        Triple("!&*", false, Regex("""!|&|\*""")),
    )

    private val ESCAPES = mapOf(
        '\u0000' to "0", '\u0007' to "a", '\u0008' to "b", '\u0009' to "t", '\n' to "n", '\u000B' to "v",
        '\u000C' to "f", '\r' to "r", '\u001B' to "e", '"' to "\"", '\\' to "\\", '\u0085' to "N",
        ' ' to "_", ' ' to "L", ' ' to "P",
    )

    fun write(s: String, key: Boolean): String {
        val a = analyze(s)
        if (readsAsString(s) && !(key && (a.empty || a.multiline)) && a.blockPlain) return s
        if ('\'' in s || '\n' in s) return doubleQuoted(s)
        if (a.singleQuoted && !(key && a.multiline)) return "'" + s.replace("'", "''") + "'"
        return doubleQuoted(s)
    }

    private fun readsAsString(s: String): Boolean = RESOLVERS.none { (first, empty, re) ->
        (if (s.isEmpty()) empty else s[0] in first) && re.matches(s)
    }

    private class Analysis(val empty: Boolean, val multiline: Boolean, val blockPlain: Boolean, val singleQuoted: Boolean)

    private fun printable(ch: Char) = ch == '\u0085' || ch in ' '..'퟿' || ch in ''..'�'

    private fun analyze(s: String): Analysis {
        if (s.isEmpty()) return Analysis(empty = true, multiline = false, blockPlain = true, singleQuoted = true)
        var blockIndicators = s.startsWith("---") || s.startsWith("...")
        var lineBreaks = false
        var special = false
        var leadingSpace = false
        var leadingBreak = false
        var trailingSpace = false
        var trailingBreak = false
        var breakSpace = false
        var spaceBreak = false
        var precededByWhitespace = true
        var followedByWhitespace = s.length == 1 || s[1] in WHITESPACE
        var previousSpace = false
        var previousBreak = false
        for ((index, ch) in s.withIndex()) {
            if (index == 0) {
                if (ch in "#,[]{}&*!|>'\"%@`") blockIndicators = true
                if (ch in "?:" && followedByWhitespace) blockIndicators = true
                if (ch == '-' && followedByWhitespace) blockIndicators = true
            } else {
                if (ch == ':' && followedByWhitespace) blockIndicators = true
                if (ch == '#' && precededByWhitespace) blockIndicators = true
            }
            if (ch in BREAKS) lineBreaks = true
            if (!(ch == '\n' || ch in ' '..'~')) {
                if (!(printable(ch) && ch != '﻿')) special = true
            }
            when {
                ch == ' ' -> {
                    if (index == 0) leadingSpace = true
                    if (index == s.length - 1) trailingSpace = true
                    if (previousBreak) breakSpace = true
                    previousSpace = true; previousBreak = false
                }
                ch in BREAKS -> {
                    if (index == 0) leadingBreak = true
                    if (index == s.length - 1) trailingBreak = true
                    if (previousSpace) spaceBreak = true
                    previousSpace = false; previousBreak = true
                }
                else -> { previousSpace = false; previousBreak = false }
            }
            precededByWhitespace = ch in WHITESPACE
            followedByWhitespace = index + 2 >= s.length || s[index + 2] in WHITESPACE
        }
        var blockPlain = !(leadingSpace || leadingBreak || trailingSpace || trailingBreak)
        var singleQuoted = true
        if (breakSpace) { blockPlain = false; singleQuoted = false }
        if (special) { blockPlain = false; singleQuoted = false }
        else if (spaceBreak) { blockPlain = false; singleQuoted = false }
        if (lineBreaks) blockPlain = false
        if (blockIndicators) blockPlain = false
        return Analysis(empty = false, multiline = lineBreaks, blockPlain = blockPlain, singleQuoted = singleQuoted)
    }

    private fun doubleQuoted(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            if (ch in "\"\\\u0085  ﻿" || !(ch in ' '..'~' || ch in ' '..'퟿' || ch in ''..'�')) {
                sb.append('\\').append(ESCAPES[ch] ?: if (ch.code <= 0xFF) "x%02X".format(ch.code) else "u%04X".format(ch.code))
            } else sb.append(ch)
        }
        return sb.append('"').toString()
    }
}
