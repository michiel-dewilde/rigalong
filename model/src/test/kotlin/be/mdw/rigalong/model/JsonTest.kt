package be.mdw.rigalong.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {

    // RFC 8785 Appendix B: IEEE 754 bit patterns and their canonical text.
    private val rfcNumbers = listOf(
        "0000000000000000" to "0",
        "8000000000000000" to "0",
        "0000000000000001" to "5e-324",
        "8000000000000001" to "-5e-324",
        "7fefffffffffffff" to "1.7976931348623157e+308",
        "ffefffffffffffff" to "-1.7976931348623157e+308",
        "4340000000000000" to "9007199254740992",
        "c340000000000000" to "-9007199254740992",
        "4430000000000000" to "295147905179352830000",
        "44b52d02c7e14af5" to "9.999999999999997e+22",
        "44b52d02c7e14af6" to "1e+23",
        "44b52d02c7e14af7" to "1.0000000000000001e+23",
        "444b1ae4d6e2ef4e" to "999999999999999700000",
        "444b1ae4d6e2ef4f" to "999999999999999900000",
        "444b1ae4d6e2ef50" to "1e+21",
        "3eb0c6f7a0b5ed8c" to "9.999999999999997e-7",
        "3eb0c6f7a0b5ed8d" to "0.000001",
        "41b3de4355555553" to "333333333.3333332",
        "41b3de4355555554" to "333333333.33333325",
        "41b3de4355555555" to "333333333.3333333",
        "41b3de4355555556" to "333333333.3333334",
        "41b3de4355555557" to "333333333.33333343",
        "becbf647612f3696" to "-0.0000033333333333333333",
        "43143ff3c1cb0959" to "1424953923781206.2",
    )

    @Test
    fun rfc8785Numbers() {
        for ((bits, want) in rfcNumbers) {
            val d = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(bits, 16))
            assertEquals(want, Json.Num(d).canonical(), "bits $bits")
        }
    }

    @Test
    fun rfc8785Example() {
        // RFC 8785 section 3.2.4 (numbers and string escapes; the RFC's sort example
        // with non-BMP keys is covered by sortsByUtf16CodeUnits). '~' stands for a
        // backslash, so no tool on the way can reinterpret the escapes.
        val input = """{"numbers":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001],
            "string":"~u20ac${'$'}~u000F~u000aA'~u0042~u0022~u005c~~~"~/","literals":[null,true,false]}"""
            .replace('~', 92.toChar())
        val want = """{"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],"string":"€${'$'}~u000f~nA'B~"~~~~~"/"}"""
            .replace('~', 92.toChar())
        assertEquals(want, Json.parse(input).canonical())
    }

    @Test
    fun sortsByUtf16CodeUnits() {
        val o = Json.obj("😀" to Json.num(1), "€" to Json.num(2), "a" to Json.num(3), "B" to Json.num(4))
        assertEquals("{\"B\":4,\"a\":3,\"€\":2,\"😀\":1}", o.canonical())
    }

    @Test
    fun float32ReadsBackToTheSameHash() {
        val stored = Json.float32(0.3f)
        assertEquals("0.3", stored.canonical())
        // A value as an engine hands it back: the same float32, widened.
        val readBack = Json.float32((0.3f).toDouble().toFloat())
        assertEquals(stored.canonical(), readBack.canonical())
        assertEquals("0.1", Json.float32(0.1f).canonical())
        assertEquals("-12.5", Json.float32(-12.5f).canonical())
        assertEquals("1e-7", Json.float32(1e-7f).canonical())
        for (i in 0..1000) {
            val f = i / 997.0f
            val text = Json.float32(f).canonical()
            assertEquals(f, text.toFloat(), "float32 $f printed as $text")
        }
    }

    @Test
    fun roundTripsThroughParse() {
        val o = Json.obj("x" to Json.Arr(listOf(Json.num(1), Json.str("a\nb"), Json.Bool(false))),
            "nested" to Json.obj("k" to Json.Null))
        assertEquals(o.canonical(), Json.parse(o.pretty()).canonical())
    }

    @Test
    fun rejectsDuplicateMembersAndGarbage() {
        assertFailsWith<IllegalArgumentException> { Json.parse("""{"a":1,"a":2}""") }
        assertFailsWith<IllegalArgumentException> { Json.parse("""{"a":1} x""") }
        assertFailsWith<IllegalArgumentException> { Json.parse("""[1,]""") }
    }
}
