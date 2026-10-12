package be.mdw.rigalong.model

import kotlin.math.roundToInt

/**
 * A library patch as its engine's own file, for the engine on another
 * computer (or this tablet's Import): a DX7 bank for Dexed, an .fxp for
 * Surge XT, a SoundFont for FluidSynth. What the file can say of the patch
 * goes in -- the sound, its name, its transpose, Surge's macros -- and what it
 * cannot (level, bend range, effect sends) stays behind: those are how this
 * rig plays the sound, not the sound.
 *
 * The engines' content is found by the app (by the name the patch keeps, as
 * the engine itself would load it); this makes the file of it.
 */
object PatchExport {
    /** The file a patch for [engine] saves as, or null where its sound is not one file (sfizz, setBfree, Plaits). */
    fun kind(engine: String): EngineFiles.Kind? = when (RackEngine.ofKey(engine)) {
        RackEngine.DEXED -> EngineFiles.Kind.DX7_BANK
        RackEngine.SURGE -> EngineFiles.Kind.SURGE_PATCH
        RackEngine.FLUID -> EngineFiles.Kind.SOUNDFONT
        else -> null
    }

    /** The file name for patch [name]: an SF3 source gives an SF3 (its samples stay Vorbis). */
    fun fileName(name: String, kind: EngineFiles.Kind, sf3: Boolean = false): String =
        EngineFiles.installName("$name.${if (sf3) "sf3" else kind.extension}", kind)

    private fun transpose(p: Patch) = p.params["Transpose"]?.roundToInt() ?: 0

    /** Dexed: the patch's voice ([voice], 128 packed bytes as its cartridge has it) in a bank of its own, slot 1. */
    fun dexed(p: Patch, name: String, voice: ByteArray): ByteArray =
        Dx7.bank(listOf(Dx7.voice(voice, name, transpose(p))))

    /** Surge XT: the patch's file ([fxp]) with the patch's macros, called [name]. */
    fun surge(p: Patch, name: String, fxp: ByteArray): ByteArray {
        val macros = (1..8).mapNotNull { n -> p.params["Macro $n"]?.let { n - 1 to it.coerceIn(0.0, 1.0) } }.toMap()
        return SurgeFxp.withMacros(fxp, macros, name)
    }

    /** FluidSynth: the patch's preset cut out of its font ([font]), transposed, called [name]. */
    fun fluid(p: Patch, name: String, font: java.io.File): ByteArray =
        SoundFontFile.onePreset(font, p.program ?: 0, name, transpose(p))
}
