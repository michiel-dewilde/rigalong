package be.mdw.bagosynths.model

import java.security.MessageDigest

/** An object's identity: SHA-256 over its bytes (FORMAT.md section 1). */
@JvmInline
value class Digest(val hex: String) {
    init {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "not a lowercase SHA-256 hex digest: $hex"
        }
    }

    override fun toString() = "sha256:$hex"
    val short: String get() = hex.substring(0, 12)

    companion object {
        fun of(bytes: ByteArray): Digest {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            return Digest(d.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        }

        fun parse(s: String): Digest {
            require(s.startsWith("sha256:")) { "not a sha256 digest: $s" }
            return Digest(s.substring(7))
        }
    }
}

/**
 * A reference (FORMAT.md section 3): always `{"@ref": "sha256:...", "@type": ...}`,
 * so generic code finds every reference of any object without knowing its type.
 */
data class Ref(val digest: Digest, val type: String? = null) {
    fun toJson(): Json.Obj = Json.obj(
        "@ref" to Json.str(digest.toString()),
        "@type" to type?.let { Json.str(it) })

    companion object {
        fun fromJson(j: Json): Ref {
            val o = j.obj()
            return Ref(Digest.parse(o.req("@ref").str()), (o["@type"] as? Json.Str)?.value)
        }
    }
}

/** Every reference anywhere inside this value, in document order. */
fun Json.refs(): List<Ref> {
    val out = ArrayList<Ref>()
    fun walk(v: Json) {
        when (v) {
            is Json.Obj -> {
                if (v.members.containsKey("@ref")) out += Ref.fromJson(v)
                else v.members.values.forEach(::walk)
            }
            is Json.Arr -> v.items.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return out
}

/** The app's JSON object types and their media types (FORMAT.md section 8). */
object MediaTypes {
    const val TYPE_PREFIX = "bagosynths."
    const val OCTET = "application/octet-stream"
    const val ENTRY = "application/vnd.bagosynths.entry.v1+json"
    const val CP88_SOUND = "application/vnd.bagosynths.cp88sound.v1"

    /** `bagosynths.song/1` -> `application/vnd.bagosynths.song.v1+json`. */
    fun ofType(atType: String): String {
        if (!atType.startsWith(TYPE_PREFIX)) return atType       // already a media type
        val slash = atType.lastIndexOf('/')
        require(slash > 0) { "type without a schema version: $atType" }
        val kind = atType.substring(TYPE_PREFIX.length, slash)
        val version = atType.substring(slash + 1)
        return "application/vnd.bagosynths.$kind.v$version+json"
    }
}
