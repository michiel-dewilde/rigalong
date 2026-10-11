package be.mdw.bagosynths.model

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Export and import (FORMAT.md section 7, I8.27): an OCI Image Layout holding
 * the chosen entries, as one tar archive. The same format as the store and as
 * a registry, offline: `oras` and other OCI tools can read it.
 */
object Exchange {

    /** Writes the latest versions of [entryIds], with their closures, to [out]. */
    fun export(store: Store, entryIds: Collection<Digest>, out: OutputStream) {
        val tags = store.indexTags().filterKeys { tag -> entryIds.any { tag.startsWith(it.hex) } }
        val blobs = LinkedHashSet<Digest>()
        for (manifest in tags.values) {
            blobs += manifest
            val mo = store.getJson(manifest).obj()
            blobs += Digest.parse(mo.req("config").obj().req("digest").str())
            for (l in mo["layers"]?.arr() ?: emptyList()) blobs += Digest.parse(l.obj().req("digest").str())
        }
        val tar = TarWriter(out)
        tar.file("oci-layout", """{"imageLayoutVersion":"1.0.0"}""".toByteArray())
        val manifests = tags.toSortedMap().map { (tag, d) ->
            Json.obj(
                "mediaType" to Json.str(Store.MANIFEST),
                "digest" to Json.str(d.toString()),
                "size" to Json.num(store.size(d)),
                "annotations" to Json.obj(Store.REF_NAME to Json.str(tag)),
            )
        }
        val index = Json.obj("schemaVersion" to Json.num(2), "mediaType" to Json.str(Store.INDEX),
            "manifests" to Json.Arr(manifests))
        tar.file("index.json", index.pretty().toByteArray())
        for (d in blobs) {
            val bytes = store.get(d) ?: continue        // left out on purpose (I8.30)
            tar.file("blobs/sha256/${d.hex}", bytes)
        }
        tar.finish()
    }

    data class ImportReport(val objects: Int, val results: Map<String, Store.MergeResult>)

    /**
     * Reads an export into [store]. Every blob is verified against its name
     * BEFORE anything is merged; one bad blob rejects the whole archive.
     */
    fun import(store: Store, input: InputStream): ImportReport {
        val files = TarReader.readAll(input)
        for ((path, bytes) in files) {
            if (!path.startsWith("blobs/sha256/")) continue
            val want = Digest(path.removePrefix("blobs/sha256/"))
            val got = Digest.of(bytes)
            require(want == got) { "blob ${want.short} does not match its content (${got.short})" }
        }
        var count = 0
        for ((path, bytes) in files) {
            if (path.startsWith("blobs/sha256/")) { store.put(bytes); count++ }
        }
        val index = files["index.json"]?.let { Json.parse(it).obj() } ?: error("no index.json")
        val results = LinkedHashMap<String, Store.MergeResult>()
        for (m in index["manifests"]?.arr() ?: emptyList()) {
            val mo = m.obj()
            val tag = mo["annotations"]?.obj()?.get(Store.REF_NAME)?.str() ?: continue
            results[tag] = store.merge(Digest.parse(mo.req("digest").str()))
        }
        return ImportReport(count, results)
    }
}

/** POSIX ustar, regular files only: all an OCI layout needs. */
internal class TarWriter(private val out: OutputStream) {
    fun file(path: String, data: ByteArray) {
        require(path.length < 100) { "path too long for ustar: $path" }
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(h, off)
        fun octal(off: Int, len: Int, v: Long) = put(off, v.toString(8).padStart(len - 1, '0'))
        put(0, path)
        octal(100, 8, "644".toLong(8))
        octal(108, 8, 0)
        octal(116, 8, 0)
        octal(124, 12, data.size.toLong())
        octal(136, 12, 0)                       // mtime 0: the same content, the same archive
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = '0'.code.toByte()
        put(257, "ustar")
        h[262] = 0
        put(263, "00")
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(148, sum.toString(8).padStart(6, '0'))
        h[154] = 0
        h[155] = ' '.code.toByte()
        out.write(h)
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        out.write(ByteArray(pad))
    }

    fun finish() {
        out.write(ByteArray(1024))
        out.flush()
    }
}

internal object TarReader {
    fun readAll(input: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val h = ByteArray(512)
        while (true) {
            if (!readFully(input, h)) break
            if (h.all { it.toInt() == 0 }) break
            val name = String(h, 0, 100, Charsets.US_ASCII).trimEnd('\u0000')
            val prefix = String(h, 345, 155, Charsets.US_ASCII).trimEnd('\u0000')
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            val size = String(h, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').ifEmpty { "0" }.toLong(8)
            val type = h[156].toInt().toChar()
            val data = ByteArray(size.toInt())
            require(readFully(input, data)) { "truncated archive at $path" }
            val pad = ((512 - size % 512) % 512).toInt()
            if (pad > 0) readFully(input, ByteArray(pad))
            if (type == '0' || type == '\u0000') out[path.removePrefix("./")] = data
        }
        return out
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) return n == 0 && false
            n += r
        }
        return true
    }
}

internal fun ByteArrayOutputStream.bytes(): ByteArray = toByteArray()
