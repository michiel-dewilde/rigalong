package be.mdw.rigalong.model

/**
 * DX7 voices as cartridges hold them: 128 packed bytes each, 32 to a bank
 * (the 4104-byte bulk dump every DX7 librarian and Dexed loads). Packed layout
 * per voice, from the DX7's service manual:
 *   0-101    six operators of 17 bytes, OP6 first
 *   102-109  pitch EG rates and levels
 *   110      algorithm      111  key sync << 3 | feedback
 *   112-115  LFO speed, delay, pitch and amplitude mod depth
 *   116      pitch mod sensitivity << 4 | LFO wave << 1 | LFO key sync
 *   117      transpose, 0-48 (24 is none)
 *   118-127  name, ten ASCII characters
 */
object Dx7 {
    const val VOICE_BYTES = 128
    const val VOICES_PER_BANK = 32
    private const val TRANSPOSE = 117
    private const val NAME = 118
    private const val NAME_BYTES = 10

    /**
     * INIT VOICE, as Dexed has it (PluginData.cpp): one sine (OP1, algorithm
     * 1) at full level, everything else neutral.
     */
    val INIT_VOICE: ByteArray = ByteArray(VOICE_BYTES).also { v ->
        for (op in 0 until 6) {
            val o = op * 17
            for (i in 0 until 4) v[o + i] = 99                    // EG rates
            for (i in 4 until 7) v[o + i] = 99                    // EG levels 1-3; level 4 stays 0
            v[o + 12] = (7 shl 3).toByte()                        // detune 7 (none), rate scaling 0
            v[o + 14] = if (op == 5) 99 else 0                    // output level: OP1 only
            v[o + 15] = (1 shl 1).toByte()                        // frequency coarse 1, ratio mode
        }
        for (i in 102 until 106) v[i] = 99                        // pitch EG rates
        for (i in 106 until 110) v[i] = 50                        // pitch EG levels (50 is no change)
        v[111] = (1 shl 3).toByte()                               // oscillator key sync on
        v[112] = 35                                               // LFO speed
        v[116] = ((3 shl 4) or 1).toByte()                        // pitch mod sensitivity 3, LFO key sync
        v[TRANSPOSE] = 24
        "INIT VOICE".toByteArray(Charsets.US_ASCII).copyInto(v, NAME)
    }

    fun name(voice: ByteArray): String =
        String(CharArray(NAME_BYTES) { val c = voice[NAME + it].toInt() and 0x7F; if (c in 32..126) c.toChar() else ' ' }).trimEnd()

    /**
     * [voice] named [name] (ASCII, cut to ten characters) and transposed by
     * [semitones] more, as far as the DX7's two octaves either way reach.
     */
    fun voice(voice: ByteArray, name: String? = null, semitones: Int = 0): ByteArray {
        require(voice.size == VOICE_BYTES) { "a packed DX7 voice is $VOICE_BYTES bytes, not ${voice.size}" }
        val v = voice.copyOf()
        v[TRANSPOSE] = ((v[TRANSPOSE].toInt() and 0x7F) + semitones).coerceIn(0, 48).toByte()
        if (name != null) for (i in 0 until NAME_BYTES) {
            val c = name.getOrNull(i)?.code ?: 32
            v[NAME + i] = (if (c in 32..126) c else 32).toByte()
        }
        return v
    }

    /** A 32-voice bulk dump of [voices], INIT VOICE in the slots they leave free. */
    fun bank(voices: List<ByteArray>): ByteArray {
        require(voices.size <= VOICES_PER_BANK) { "a bank holds $VOICES_PER_BANK voices" }
        val data = ByteArray(VOICES_PER_BANK * VOICE_BYTES)
        for (i in 0 until VOICES_PER_BANK) {
            val v = voices.getOrNull(i) ?: INIT_VOICE
            require(v.size == VOICE_BYTES) { "a packed DX7 voice is $VOICE_BYTES bytes" }
            v.copyInto(data, i * VOICE_BYTES)
        }
        // F0, Yamaha, channel 1, format 9 (32 voices), 4096 bytes; the data; checksum; F7.
        val header = byteArrayOf(0xF0.toByte(), 0x43, 0x00, 0x09, 0x20, 0x00)
        val sum = data.sumOf { it.toInt() and 0x7F }
        return header + data + byteArrayOf(((128 - (sum and 0x7F)) and 0x7F).toByte(), 0xF7.toByte())
    }

    /** The voices of a 32-voice bulk dump. */
    fun voices(bank: ByteArray): List<ByteArray> {
        require(EngineFiles.kind(bank) == EngineFiles.Kind.DX7_BANK) { "not a 32-voice DX7 bank" }
        return List(VOICES_PER_BANK) { bank.copyOfRange(6 + it * VOICE_BYTES, 6 + (it + 1) * VOICE_BYTES) }
    }
}
