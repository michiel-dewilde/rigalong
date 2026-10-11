package be.mdw.bagosynths.model


/**
 * JSON values, a parser, and the canonical writer (RFC 8785, JCS).
 *
 * Hand-written rather than taken from a library because the canonical form IS
 * the identity of every object in the library (docs/FORMAT.md section 2): a
 * dependency that changed how it prints a number would silently change every
 * hash. This file is the one place that decides it, and it is tested against
 * the RFC's own examples.
 */
sealed class Json {
    object Null : Json()
    data class Bool(val value: Boolean) : Json()
    data class Num(val value: Double) : Json() {
        init {
            require(!value.isNaN() && !value.isInfinite()) { "JSON has no NaN or infinity" }
        }
    }
    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val members: Map<String, Json>) : Json() {
        operator fun get(key: String): Json? = members[key]
    }

    /** The canonical bytes: what is hashed and what is stored. */
    fun canonicalBytes(): ByteArray = canonical().toByteArray(Charsets.UTF_8)

    fun canonical(): String = StringBuilder().also { Canonical.write(this, it) }.toString()

    /** Indented, for people; never hashed. Members keep their canonical order. */
    fun pretty(): String = StringBuilder().also { Canonical.writePretty(this, it, 0) }.toString()

    companion object {
        fun parse(text: String): Json = Parser(text).parseDocument()
        fun parse(bytes: ByteArray): Json = parse(String(bytes, Charsets.UTF_8))

        fun obj(vararg members: Pair<String, Json?>): Obj =
            Obj(members.filter { it.second != null }.associate { it.first to it.second!! })

        fun str(s: String) = Str(s)
        fun num(d: Double) = Num(d)
        fun num(i: Long) = Num(i.toDouble())
        fun num(i: Int) = Num(i.toDouble())

        /**
         * A float32 parameter value, written as the shortest decimal that reads
         * back as the same float32 (FORMAT.md section 2), so a value read back
         * from an engine hashes like the value that was stored.
         */
        fun float32(f: Float): Num = Num(Canonical.shortestFloat32(f).toDouble())
    }
}

// Typed accessors, failing loudly: a malformed object must not half-load.
fun Json.obj(): Json.Obj = this as? Json.Obj ?: error("expected an object, got $this")
fun Json.arr(): List<Json> = (this as? Json.Arr)?.items ?: error("expected an array, got $this")
fun Json.str(): String = (this as? Json.Str)?.value ?: error("expected a string, got $this")
fun Json.num(): Double = (this as? Json.Num)?.value ?: error("expected a number, got $this")
fun Json.bool(): Boolean = (this as? Json.Bool)?.value ?: error("expected a boolean, got $this")
fun Json.Obj.req(key: String): Json = members[key] ?: error("missing member '$key'")

internal object Canonical {

    fun write(v: Json, out: StringBuilder) {
        when (v) {
            Json.Null -> out.append("null")
            is Json.Bool -> out.append(if (v.value) "true" else "false")
            is Json.Num -> out.append(number(v.value))
            is Json.Str -> string(v.value, out)
            is Json.Arr -> {
                out.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is Json.Obj -> {
                out.append('{')
                sortedKeys(v).forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    string(k, out)
                    out.append(':')
                    write(v.members.getValue(k), out)
                }
                out.append('}')
            }
        }
    }

    fun writePretty(v: Json, out: StringBuilder, depth: Int) {
        val pad = "  ".repeat(depth + 1)
        when (v) {
            is Json.Arr -> {
                if (v.items.isEmpty()) { out.append("[]"); return }
                out.append("[\n")
                v.items.forEachIndexed { i, item ->
                    out.append(pad)
                    writePretty(item, out, depth + 1)
                    out.append(if (i < v.items.size - 1) ",\n" else "\n")
                }
                out.append("  ".repeat(depth)).append(']')
            }
            is Json.Obj -> {
                if (v.members.isEmpty()) { out.append("{}"); return }
                out.append("{\n")
                val keys = sortedKeys(v)
                keys.forEachIndexed { i, k ->
                    out.append(pad)
                    string(k, out)
                    out.append(": ")
                    writePretty(v.members.getValue(k), out, depth + 1)
                    out.append(if (i < keys.size - 1) ",\n" else "\n")
                }
                out.append("  ".repeat(depth)).append('}')
            }
            else -> write(v, out)
        }
    }

    // RFC 8785 section 3.2.3: members sorted by their names as arrays of UTF-16
    // code units. Kotlin's String comparison is exactly that.
    private fun sortedKeys(o: Json.Obj): List<String> = o.members.keys.sorted()

    // RFC 8785 section 3.2.2.2.
    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') {
                    out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private const val HEX = "0123456789abcdef"

    /**
     * ECMAScript's Number.prototype.toString (RFC 8785 section 3.2.2.3): the
     * shortest digits that read back as the same double, laid out by the
     * ECMAScript rules.
     *
     * Found by rounding the EXACT binary value to increasing precision with
     * BigDecimal. Not `String.format("%e")`: the JDK rounds that from its own
     * shortest-digit routine, which Android's runtime implements differently,
     * so the same double could print -- and hash -- differently on the tablet
     * and on a laptop. BigDecimal arithmetic is exact everywhere, and the first
     * precision that round-trips gives both the shortest and the closest digits.
     */
    fun number(d: Double): String {
        if (d == 0.0) return "0"                       // and -0
        val a = kotlin.math.abs(d)
        val (digits, n) = shortestDigits(a) { it.toDouble() == a }
        return layout(if (d < 0) "-" else "", digits, n)
    }

    /** The shortest decimal string that reads back as the same float32. */
    fun shortestFloat32(f: Float): String {
        require(!f.isNaN() && !f.isInfinite()) { "no NaN or infinity" }
        if (f == 0.0f) return "0"
        val a = kotlin.math.abs(f)
        val (digits, n) = shortestDigits(a.toDouble()) { it.toFloat() == a }
        return layout(if (f < 0) "-" else "", digits, n)
    }

    /** Significant digits (no trailing zeros) and n, with value = 0.d1d2..dk x 10^n. */
    private fun shortestDigits(d: Double, roundTrips: (String) -> Boolean): Pair<String, Int> {
        val exact = java.math.BigDecimal(kotlin.math.abs(d))
        for (p in 1..17) {
            val r = exact.round(java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN))
            if (!roundTrips(r.toString())) continue
            val unscaled = r.unscaledValue().toString()
            val digits = unscaled.trimEnd('0')
            // value = unscaled x 10^-scale = 0.<unscaled> x 10^(len - scale)
            return digits to unscaled.length - r.scale()
        }
        error("no round-tripping representation for $d")
    }

    private fun layout(sign: String, digits: String, n: Int): String {
        val k = digits.length
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exp = n - 1
                val e = if (exp >= 0) "e+$exp" else "e$exp"
                if (k == 1) digits + e else digits[0] + "." + digits.substring(1) + e
            }
        }
        return sign + body
    }
}

private class Parser(private val s: String) {
    private var i = 0

    fun parseDocument(): Json {
        val v = value()
        ws()
        if (i != s.length) fail("trailing characters")
        return v
    }

    private fun fail(what: String): Nothing = throw IllegalArgumentException("JSON: $what at offset $i")

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
    }

    private fun value(): Json {
        ws()
        if (i >= s.length) fail("unexpected end")
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(string())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> number()
        }
    }

    private fun literal(word: String, v: Json): Json {
        if (!s.startsWith(word, i)) fail("expected $word")
        i += word.length
        return v
    }

    private fun obj(): Json.Obj {
        i++
        val members = LinkedHashMap<String, Json>()
        ws()
        if (i < s.length && s[i] == '}') { i++; return Json.Obj(members) }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("expected a member name")
            val key = string()
            if (members.containsKey(key)) fail("duplicate member '$key'")
            ws()
            if (i >= s.length || s[i] != ':') fail("expected ':'")
            i++
            members[key] = value()
            ws()
            if (i >= s.length) fail("unterminated object")
            if (s[i] == ',') { i++; continue }
            if (s[i] == '}') { i++; return Json.Obj(members) }
            fail("expected ',' or '}'")
        }
    }

    private fun arr(): Json.Arr {
        i++
        val items = ArrayList<Json>()
        ws()
        if (i < s.length && s[i] == ']') { i++; return Json.Arr(items) }
        while (true) {
            items += value()
            ws()
            if (i >= s.length) fail("unterminated array")
            if (s[i] == ',') { i++; continue }
            if (s[i] == ']') { i++; return Json.Arr(items) }
            fail("expected ',' or ']'")
        }
    }

    private fun string(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("bad \\u escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> fail("bad escape \\$e")
                    }
                }
                c < ' ' -> fail("control character in string")
                else -> sb.append(c)
            }
        }
    }

    private fun number(): Json.Num {
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    s[i] == '+' || s[i] == '-')) i++
        val text = s.substring(start, i)
        if (text.isEmpty() || text == "-") fail("expected a value")
        return Json.Num(text.toDoubleOrNull() ?: fail("bad number '$text'"))
    }
}
