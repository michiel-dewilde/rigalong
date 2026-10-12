package com.rigalong.model

/**
 * The engines' own files, told apart by what they are (their first bytes),
 * not by their names: a SoundFont for FluidSynth, a DX7 bank for Dexed, a
 * Surge XT patch. Import installs them where the engine looks; this says what
 * a file is, and what it is called there.
 */
object EngineFiles {
    enum class Kind(val engine: String, val extension: String) {
        /** RIFF "sfbk": SF2, or SF3 (the same with Ogg Vorbis samples). */
        SOUNDFONT("FluidSynth", "sf2"),
        /** A DX7 32-voice bulk dump: F0 43 0n 09 20 00, 4096 bytes of voices, checksum, F7. */
        DX7_BANK("Dexed", "syx"),
        /** One DX7 voice (F0 43 0n 00 01 1B ...): Dexed loads banks only. */
        DX7_VOICE("Dexed", "syx"),
        /** Surge's patch: a VST2 program chunk, "CcnK" ... "FPCh" ... "cjs3". */
        SURGE_PATCH("Surge XT", "fxp"),
    }

    const val DX7_BANK_BYTES = 4104
    const val DX7_VOICE_BYTES = 163

    fun kind(bytes: ByteArray): Kind? {
        fun at(i: Int, s: String) = bytes.size >= i + s.length && String(bytes, i, s.length, Charsets.ISO_8859_1) == s
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            at(0, "RIFF") && at(8, "sfbk") -> Kind.SOUNDFONT
            bytes.size == DX7_BANK_BYTES && u(0) == 0xF0 && u(1) == 0x43 && u(3) == 0x09 &&
                u(4) == 0x20 && u(5) == 0x00 && u(bytes.size - 1) == 0xF7 -> Kind.DX7_BANK
            bytes.size == DX7_VOICE_BYTES && u(0) == 0xF0 && u(1) == 0x43 && u(3) == 0x00 &&
                u(4) == 0x01 && u(5) == 0x1B && u(bytes.size - 1) == 0xF7 -> Kind.DX7_VOICE
            at(0, "CcnK") && at(8, "FPCh") && at(16, "cjs3") -> Kind.SURGE_PATCH
            else -> null
        }
    }

    /**
     * The file name to install [fileName] as: its own name, made safe for a
     * folder, with the kind's extension -- an SF3 keeps .sf3, which is what
     * FluidSynth's users call it.
     */
    fun installName(fileName: String, kind: Kind): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val ext = base.substringAfterLast('.', "").lowercase()
        val stem = (if (ext.isNotEmpty()) base.substringBeforeLast('.') else base)
            .replace(Regex("[^A-Za-z0-9 ._()+-]+"), "_").trim(' ', '.', '_').ifEmpty { kind.engine }
        val extension = if (kind == Kind.SOUNDFONT && ext == "sf3") "sf3" else kind.extension
        return "$stem.$extension"
    }

    /**
     * Where [bytes] goes in [dir], under [name]: that name, or "name 2",
     * "name 3"... if another file has it. Null if [dir] already holds exactly
     * these bytes under one of those names (installed before).
     */
    fun place(dir: java.io.File, name: String, bytes: ByteArray): java.io.File? {
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.')
        var n = 1
        while (true) {
            val f = java.io.File(dir, if (n == 1) name else "$stem $n.$ext")
            if (!f.exists()) return f
            if (f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes)) return null
            n++
        }
    }
}
