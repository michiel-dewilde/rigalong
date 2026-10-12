package com.rigalong.model

import java.io.File

/**
 * One version of a library entry (FORMAT.md section 4). The first version of an
 * entry has no [entry] and no [previous]; its own digest is the entry's
 * identity from then on. A version with no [target] marks the entry deleted.
 *
 * [previous] is a REFERENCE: the history travels with the entry (export, sync)
 * and keeps its objects alive. [entry] and [derivedFrom] are WEAK LINKS, plain
 * digest strings that closure, export and gc do not follow: an entry's identity
 * and a fork's origin are known, but drag nothing along. So a version with no
 * [previous] -- [Store.forgetHistory] -- orphans everything before it.
 */
data class EntryVersion(
    val entry: Digest?,
    val name: String,
    val target: Ref?,
    val previous: Digest?,
    val author: String,
    val time: String,
    val tags: List<String> = emptyList(),
    val description: String? = null,
    /** The version of another entry this one was forked from (a weak link), or null. */
    val derivedFrom: Digest? = null,
) {
    fun toJson(): Json.Obj = Json.obj(
        "@type" to Json.str(TYPE),
        "entry" to entry?.let { Json.str(it.toString()) },
        "name" to Json.str(name),
        "target" to target?.toJson(),
        "previous" to previous?.let { Ref(it).toJson() },
        "author" to Json.str(author),
        "time" to Json.str(time),
        "tags" to tags.takeIf { it.isNotEmpty() }?.let { t -> Json.Arr(t.map { Json.str(it) }) },
        "description" to description?.let { Json.str(it) },
        "derivedFrom" to derivedFrom?.let { Json.str(it.toString()) },
    )

    companion object {
        const val TYPE = "rigalong.entry/1"

        fun fromJson(j: Json): EntryVersion {
            val o = j.obj()
            require(o["@type"]?.str() == TYPE) { "not an entry version" }
            return EntryVersion(
                // A reference in versions written before 2026-10-11, a weak link since.
                entry = o["entry"]?.let { if (it is Json.Str) Digest.parse(it.value) else Ref.fromJson(it).digest },
                name = o.req("name").str(),
                target = o["target"]?.let { Ref.fromJson(it) },
                previous = o["previous"]?.let { Ref.fromJson(it).digest },
                author = o.req("author").str(),
                time = o.req("time").str(),
                tags = o["tags"]?.arr()?.map { it.str() } ?: emptyList(),
                description = o["description"]?.str(),
                derivedFrom = (o["derivedFrom"] as? Json.Str)?.let { Digest.parse(it.value) },
            )
        }
    }
}

/** A library entry as the store sees it: its identity and its latest version. */
data class Entry(val id: Digest, val latest: Digest, val version: EntryVersion)

/**
 * The content-addressed store, laid out as an OCI Image Layout (FORMAT.md
 * section 7, PLAN.md D3): `blobs/sha256/<hex>` for every object, one manifest
 * per entry version listing its whole closure, and `index.json` mapping each
 * entry's identity (a tag) to its latest manifest.
 *
 * Not thread-safe: one writer at a time, which is the app's library thread.
 */
class Store(val root: File) {
    private val blobs = File(root, "blobs/sha256")
    private val indexFile = File(root, "index.json")

    init {
        blobs.mkdirs()
        val layout = File(root, "oci-layout")
        if (!layout.exists()) layout.writeText("""{"imageLayoutVersion":"1.0.0"}""")
        if (!indexFile.exists()) writeIndex(emptyMap())
    }

    // ------------------------------------------------------------- objects

    fun put(bytes: ByteArray): Digest {
        val d = Digest.of(bytes)
        val f = blobFile(d)
        if (!f.exists()) {
            val tmp = File(blobs, ".${d.hex}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                if (!f.exists()) error("could not store ${d.short}")
            }
        }
        return d
    }

    fun put(json: Json): Digest = put(json.canonicalBytes())

    fun has(d: Digest): Boolean = blobFile(d).exists()
    fun get(d: Digest): ByteArray? = blobFile(d).takeIf { it.exists() }?.readBytes()
    fun getJson(d: Digest): Json = Json.parse(get(d) ?: error("missing object ${d.short}"))
    fun size(d: Digest): Long = blobFile(d).length()

    fun allBlobs(): List<Digest> =
        blobs.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.map { Digest(it.name) }
            ?: emptyList()

    private fun blobFile(d: Digest) = File(blobs, d.hex)

    /** The app's own JSON object at [d], or null for a binary object. */
    fun appObject(d: Digest): Json.Obj? {
        val bytes = get(d) ?: return null
        if (bytes.isEmpty() || bytes[0] != '{'.code.toByte()) return null
        val j = runCatching { Json.parse(bytes) }.getOrNull() as? Json.Obj ?: return null
        val t = (j["@type"] as? Json.Str)?.value ?: return null
        return if (t.startsWith(MediaTypes.TYPE_PREFIX)) j else null
    }

    /**
     * Every object reachable from [roots], roots included, with the best media
     * type known for each. Missing objects are reported, not fatal: content
     * left out of an export on purpose (FORMAT.md section 7) is missing here.
     */
    fun closure(roots: Collection<Ref>): Closure {
        val types = LinkedHashMap<Digest, String>()
        val missing = LinkedHashSet<Digest>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            if (r.digest in types || r.digest in missing) continue
            if (!has(r.digest)) { missing += r.digest; continue }
            val obj = appObject(r.digest)
            types[r.digest] = when {
                obj != null -> MediaTypes.ofType(obj.req("@type").str())
                r.type != null -> MediaTypes.ofType(r.type)
                else -> MediaTypes.OCTET
            }
            obj?.refs()?.let { queue.addAll(it) }
        }
        return Closure(types, missing)
    }

    data class Closure(val types: Map<Digest, String>, val missing: Set<Digest>)

    // ------------------------------------------------------------- entries

    /** Entry identity (hex) -> digest of its latest manifest. */
    private fun readIndex(): Map<String, Digest> {
        val o = Json.parse(indexFile.readText()).obj()
        val out = LinkedHashMap<String, Digest>()
        for (m in o["manifests"]?.arr() ?: emptyList()) {
            val mo = m.obj()
            val tag = mo["annotations"]?.obj()?.get(REF_NAME)?.str() ?: continue
            out[tag] = Digest.parse(mo.req("digest").str())
        }
        return out
    }

    private fun writeIndex(tags: Map<String, Digest>) {
        val manifests = tags.toSortedMap().map { (tag, d) ->
            Json.obj(
                "mediaType" to Json.str(MANIFEST),
                "digest" to Json.str(d.toString()),
                "size" to Json.num(if (has(d)) size(d) else 0L),
                "annotations" to Json.obj(REF_NAME to Json.str(tag)),
            )
        }
        val index = Json.obj(
            "schemaVersion" to Json.num(2),
            "mediaType" to Json.str(INDEX),
            "manifests" to Json.Arr(manifests),
        )
        val tmp = File(root, ".index.json.tmp")
        tmp.writeText(index.pretty())
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            if (!tmp.renameTo(indexFile)) error("could not write index.json")
        }
    }

    private fun manifestOf(manifest: Digest): Json.Obj = getJson(manifest).obj()

    private fun versionOfManifest(manifest: Digest): Digest =
        Digest.parse(manifestOf(manifest).req("config").obj().req("digest").str())

    fun entries(): List<Entry> = readIndex().mapNotNull { (tag, manifest) ->
        if (tag.length != 64) return@mapNotNull null                 // a divergence tag
        val v = versionOfManifest(manifest)
        Entry(Digest(tag), v, EntryVersion.fromJson(getJson(v)))
    }

    fun entry(id: Digest): Entry? = entries().firstOrNull { it.id == id }

    /** Versions of an entry, newest first, following `previous`. */
    fun history(version: Digest): List<Pair<Digest, EntryVersion>> {
        val out = ArrayList<Pair<Digest, EntryVersion>>()
        var cur: Digest? = version
        while (cur != null && has(cur)) {
            val v = EntryVersion.fromJson(getJson(cur))
            out += cur to v
            cur = v.previous
        }
        return out
    }

    /** Stores [version] and makes it the latest of its entry. Returns its digest. */
    fun commit(version: EntryVersion): Digest {
        val d = put(version.toJson())
        val id = version.entry ?: d
        publish(id, d)
        return d
    }

    /** A new entry pointing at [target]. Returns the entry's identity. */
    fun create(name: String, target: Ref, author: String, time: String,
               tags: List<String> = emptyList(), description: String? = null, derivedFrom: Digest? = null): Digest =
        commit(EntryVersion(null, name, target, null, author, time, tags, description, derivedFrom))

    /**
     * Drops entry [id]'s history: a version with its current content and no
     * [EntryVersion.previous]. Nothing before it travels with the entry any more
     * (export, sync), and gc may reclaim it; a weak link to the forgotten
     * version is kept as [EntryVersion.derivedFrom]. Not undoable.
     */
    fun forgetHistory(id: Digest, author: String, time: String): Digest {
        val cur = entry(id) ?: error("no entry ${id.short}")
        val v = cur.version
        return commit(EntryVersion(id, v.name, v.target, null, author, time, v.tags, v.description, derivedFrom = cur.latest))
    }

    /** A new version of entry [id]; members not given are carried over. */
    fun update(id: Digest, author: String, time: String, target: Ref? = null, name: String? = null,
               tags: List<String>? = null, description: String? = null, delete: Boolean = false): Digest {
        val cur = entry(id) ?: error("no entry ${id.short}")
        val v = cur.version
        return commit(EntryVersion(
            entry = id,
            name = name ?: v.name,
            target = if (delete) null else target ?: v.target,
            previous = cur.latest,
            author = author,
            time = time,
            tags = tags ?: v.tags,
            description = description ?: v.description,
        ))
    }

    /** Writes the manifest for entry version [version] and points tag [id] at it. */
    private fun publish(id: Digest, version: Digest, tag: String = id.hex) {
        val v = EntryVersion.fromJson(getJson(version))
        val c = closure(listOf(Ref(version)))
        val layers = c.types.filterKeys { it != version }.map { (d, type) ->
            Json.obj("mediaType" to Json.str(type), "digest" to Json.str(d.toString()), "size" to Json.num(size(d)))
        }
        val artifactType = v.target?.let { t -> c.types[t.digest] ?: t.type?.let(MediaTypes::ofType) }
            ?: MediaTypes.ENTRY
        val manifest = Json.obj(
            "schemaVersion" to Json.num(2),
            "mediaType" to Json.str(MANIFEST),
            "artifactType" to Json.str(artifactType),
            "config" to Json.obj(
                "mediaType" to Json.str(MediaTypes.ENTRY),
                "digest" to Json.str(version.toString()),
                "size" to Json.num(size(version))),
            "layers" to Json.Arr(layers),
            "annotations" to Json.obj(TITLE to Json.str(v.name)),
        )
        val m = put(manifest)
        writeIndex(readIndex() + (tag to m))
    }

    /** Manifests the index points at, and everything they list. */
    fun live(): Set<Digest> {
        val keep = HashSet<Digest>()
        for (m in readIndex().values) {
            if (!has(m)) continue
            keep += m
            val mo = manifestOf(m)
            keep += Digest.parse(mo.req("config").obj().req("digest").str())
            for (l in mo["layers"]?.arr() ?: emptyList()) keep += Digest.parse(l.obj().req("digest").str())
        }
        return keep
    }

    /**
     * Removes every object no entry reaches (I8.14). [keepAlso] protects objects
     * in use but not yet committed. Never to run while playing (the app's call).
     */
    fun gc(keepAlso: Set<Digest> = emptySet()): Int {
        val keep = live() + keepAlso
        var removed = 0
        for (d in allBlobs()) if (d !in keep && blobFile(d).delete()) removed++
        return removed
    }

    // ---------------------------------------------------- merge (import, sync)

    sealed class MergeResult {
        object Same : MergeResult()
        object Added : MergeResult()
        object FastForward : MergeResult()
        object KeptLocal : MergeResult()
        data class Diverged(val tag: String) : MergeResult()
    }

    /**
     * Adopts manifest [manifest] (already stored, with its closure) as a version
     * of its entry. Never resolves a divergence silently (I8.21): the incoming
     * version is kept under its own tag and the local one stays latest.
     */
    fun merge(manifest: Digest): MergeResult {
        val version = versionOfManifest(manifest)
        val v = EntryVersion.fromJson(getJson(version))
        val id = v.entry ?: version
        val index = readIndex()
        val local = index[id.hex]
        if (local == null) { writeIndex(index + (id.hex to manifest)); return MergeResult.Added }
        val localVersion = versionOfManifest(local)
        if (localVersion == version) return MergeResult.Same
        if (history(version).any { it.first == localVersion }) {
            writeIndex(index + (id.hex to manifest)); return MergeResult.FastForward
        }
        if (history(localVersion).any { it.first == version }) return MergeResult.KeptLocal
        val tag = "${id.hex}-d-${version.short}"
        writeIndex(index + (tag to manifest))
        return MergeResult.Diverged(tag)
    }

    /** Tags of the form `<entry>-d-<version>`: incoming versions that diverged. */
    fun divergences(): Map<Digest, List<Digest>> =
        readIndex().filterKeys { it.length > 64 }.entries
            .groupBy({ Digest(it.key.substring(0, 64)) }, { versionOfManifest(it.value) })

    /** The user chose [version] (a diverged one, or the local latest) for entry [id]. */
    fun resolve(id: Digest, chosen: Digest, author: String, time: String): Digest {
        val chosenVersion = EntryVersion.fromJson(getJson(chosen))
        val d = update(id, author, time, target = chosenVersion.target, name = chosenVersion.name,
            tags = chosenVersion.tags, description = chosenVersion.description,
            delete = chosenVersion.target == null)
        writeIndex(readIndex().filterKeys { !(it.length > 64 && it.startsWith(id.hex)) })
        return d
    }

    internal fun indexTags(): Map<String, Digest> = readIndex()

    companion object {
        const val MANIFEST = "application/vnd.oci.image.manifest.v1+json"
        const val INDEX = "application/vnd.oci.image.index.v1+json"
        const val REF_NAME = "org.opencontainers.image.ref.name"
        const val TITLE = "org.opencontainers.image.title"
    }
}
