package be.mdw.rigalong.model

import java.math.BigDecimal
import java.math.MathContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Surge XT's .fxp, as its SurgeSynthesizer::savePatchToPath writes it and
 * loadPatchByPath reads it (src/common/PatchFileHeaderStructs.h):
 *   0   "CcnK", byteSize (BE; Surge writes 0), "FPCh", version 1, "cjs3",
 *       fxVersion 1, numPrograms 1, prgName[28], chunkSize (BE)
 *   60  the chunk: "sub3", xmlsize (LE), wtsize[2][3] (LE)
 *   92  the patch XML, then the wavetables
 * Only the XML changes here, so everything after it is copied as it is.
 */
object SurgeFxp {
    private const val HEADER = 60
    private const val PATCH_HEADER = 32

    class Parts(val header: ByteArray, val xml: String, val tail: ByteArray)

    /** The XML is read byte for byte (Latin-1), so what is not edited stays exactly as it was. */
    fun parts(fxp: ByteArray): Parts {
        require(EngineFiles.kind(fxp) == EngineFiles.Kind.SURGE_PATCH) { "not a Surge XT patch" }
        require(fxp.size >= HEADER + PATCH_HEADER && String(fxp, HEADER, 4, Charsets.ISO_8859_1) == "sub3") {
            "a Surge XT patch of an older form (no \"sub3\" chunk)"
        }
        val xmlSize = ByteBuffer.wrap(fxp, HEADER + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val xmlAt = HEADER + PATCH_HEADER
        require(xmlSize >= 0 && xmlAt + xmlSize <= fxp.size) { "the patch's XML runs past its end" }
        return Parts(fxp.copyOfRange(0, xmlAt), String(fxp, xmlAt, xmlSize, Charsets.ISO_8859_1),
            fxp.copyOfRange(xmlAt + xmlSize, fxp.size))
    }

    fun join(p: Parts, name: String?): ByteArray {
        val xml = p.xml.toByteArray(Charsets.ISO_8859_1)
        val out = p.header + xml + p.tail
        val b = ByteBuffer.wrap(out)
        // The sizes move by what the XML grew. Surge reads chunkSize and
        // ignores byteSize, which its factory patches do not keep as file
        // size - 8; shifting both leaves an untouched patch byte-identical.
        val grew = xml.size - b.order(ByteOrder.LITTLE_ENDIAN).getInt(HEADER + 4)
        b.putInt(HEADER + 4, xml.size)
        b.order(ByteOrder.BIG_ENDIAN).putInt(56, b.getInt(56) + grew)
        if (b.getInt(4) != 0) b.putInt(4, b.getInt(4) + grew)
        if (name != null) {
            // prgName: 28 bytes, NUL-padded, cut on a character boundary.
            var bytes = name.toByteArray(Charsets.UTF_8)
            var n = name.length
            while (bytes.size > 27) bytes = name.substring(0, --n).toByteArray(Charsets.UTF_8)
            for (i in 0 until 28) out[28 + i] = bytes.getOrElse(i) { 0 }
        }
        return out
    }

    /**
     * [fxp] with its macros at [macros] (index 0-7 to the 0-1 value a Surge
     * node's "Macro n" holds) and, if [name] is given, called that.
     */
    fun withMacros(fxp: ByteArray, macros: Map<Int, Double>, name: String? = null): ByteArray {
        val p = parts(fxp)
        var xml = p.xml
        if (macros.isNotEmpty()) {
            val cc = CUSTOM_CONTROLLER.find(xml)
            val inner = cc?.groups?.get(2)
            val seen = HashSet<Int>()
            var body = ENTRY.replace(inner?.value ?: "") { m ->
                val tag = m.value
                val i = attr(tag, "i")?.toIntOrNull()
                val scene = attr(tag, "scene")?.toIntOrNull() ?: 0
                val x = macros[i]
                if (i == null || x == null || scene != 0) tag
                else {
                    seen += i
                    // What set_target01 keeps, and save_xml writes: a bipolar macro runs -1..1.
                    val v = if (attr(tag, "bipolar") == "1") 2 * x - 1 else x
                    setAttr(tag, "v", decimal(v))
                }
            }
            // A macro the patch leaves out is, to Surge, unipolar at 0: say ours.
            for ((i, x) in macros.toSortedMap()) if (i in 0 until 8 && i !in seen) body += "<entry i=\"$i\" v=\"${decimal(x)}\" />"
            xml = when {
                inner != null -> xml.substring(0, inner.range.first) + body + xml.substring(inner.range.last + 1)
                cc != null -> xml.substring(0, cc.range.first) + "<customcontroller>$body</customcontroller>" + xml.substring(cc.range.last + 1)
                else -> {
                    val end = xml.lastIndexOf("</patch>")
                    require(end >= 0) { "the patch's XML has no end" }
                    xml.substring(0, end) + "<customcontroller>$body</customcontroller>" + xml.substring(end)
                }
            }
        }
        if (name != null) {
            val meta = Regex("<meta\\b[^>]*>").find(xml)
            if (meta != null) {
                val escaped = String(escape(name).toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
                xml = xml.substring(0, meta.range.first) + setAttr(meta.value, "name", escaped) + xml.substring(meta.range.last + 1)
            }
        }
        return join(Parts(p.header, xml, p.tail), name)
    }

    /** The values of the macros in [fxp], 0-1 as a Surge node's "Macro n" holds them; one left out is 0. */
    fun macros(fxp: ByteArray): Map<Int, Double> {
        val out = (0 until 8).associateWith { 0.0 }.toMutableMap()
        val inner = CUSTOM_CONTROLLER.find(parts(fxp).xml)?.groups?.get(2)?.value ?: return out
        for (m in ENTRY.findAll(inner)) {
            val i = attr(m.value, "i")?.toIntOrNull() ?: continue
            if (i !in 0 until 8 || (attr(m.value, "scene")?.toIntOrNull() ?: 0) != 0) continue
            val v = attr(m.value, "v")?.toDoubleOrNull() ?: continue
            out[i] = if (attr(m.value, "bipolar") == "1") (v + 1) / 2 else v
        }
        return out
    }

    private val CUSTOM_CONTROLLER = Regex("<customcontroller\\b[^>]*?(/>|>(.*?)</customcontroller>)", RegexOption.DOT_MATCHES_ALL)
    private val ENTRY = Regex("<entry\\b[^>]*>")

    /** The patch's name in its XML. */
    fun name(fxp: ByteArray): String? {
        val meta = Regex("<meta\\b[^>]*>").find(parts(fxp).xml) ?: return null
        return attr(meta.value, "name")?.let { unescape(String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)) }
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\s$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun setAttr(tag: String, name: String, value: String): String {
        val rx = Regex("(\\s$name\\s*=\\s*\")[^\"]*(\")")
        rx.find(tag)?.let { return tag.substring(0, it.range.first) + it.groupValues[1] + value + it.groupValues[2] + tag.substring(it.range.last + 1) }
        val end = Regex("\\s*/?>$").find(tag)!!.range.first
        return tag.substring(0, end) + " $name=\"$value\"" + tag.substring(end)
    }

    /** As Surge writes a double: six significant digits, C locale. */
    private fun decimal(v: Double): String =
        BigDecimal(v).round(MathContext(6)).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
}
