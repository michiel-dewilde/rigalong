package com.rigalong

import android.content.Context
import android.content.SharedPreferences

/**
 * What the engines play from, on this device: the SFZ instruments sfizz
 * loads, and the folders Dexed, FluidSynth and Surge XT read their banks,
 * SoundFonts and patches from -- brought up at start, added to by Import, and
 * read back for a patch saved as its engine's own file.
 */
class EngineContent(private val context: Context, private val prefs: SharedPreferences) {

    private val filesDir get() = context.filesDir
    private val assets get() = context.assets
    private fun getExternalFilesDir(type: String?) = context.getExternalFilesDir(type)

    /** Every engine's content, once per process: the first instrument, then Surge XT, Dexed and FluidSynth. */
    fun start() {
        loadFirstInstrument()
        startSurge()
        startDexed()
        startFluid()
    }

    /**
     * Brings the DX7 up. The 33 cartridges that ship in the APK are seeded into
     * internal storage once; anything the user drops into the external `dx7/`
     * folder is read as well, so a personal collection sits alongside rather
     * than replacing the built-in one.
     */
    private fun startDexed() {
        val builtin = java.io.File(filesDir, "dx7").apply { mkdirs() }
        if (builtin.listFiles { f -> f.name.endsWith(".syx") }.isNullOrEmpty()) {
            runCatching {
                assets.list("dx7")?.forEach { name ->
                    assets.open("dx7/$name").use { input ->
                        java.io.File(builtin, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }.onFailure { android.util.Log.e("rigalong-ui", "could not seed dx7 banks: $it") }
        }
        val external = java.io.File(getExternalFilesDir(null), "dx7").apply { mkdirs() }
        NativeBridge.nativeLoadDexedBanks(
            arrayOf(builtin.absolutePath, external.absolutePath)
        )
    }

    /**
     * Brings FluidSynth up and lists the SoundFonts it can see.
     *
     * SoundFonts are CONTENT, like the SFZ instruments: they have their own
     * licences, a General MIDI font is 30 MB or more, and none of that belongs
     * in the APK. So nothing is seeded here -- the folder is created, and
     * `tools/install_soundfont.sh` is how a font gets into it.
     */
    private fun startFluid() {
        val builtin = java.io.File(filesDir, "soundfonts").apply { mkdirs() }
        val external = java.io.File(getExternalFilesDir(null), "soundfonts").apply { mkdirs() }
        NativeBridge.nativeInitFluid(
            arrayOf(builtin.absolutePath, external.absolutePath)
        )
    }

    /**
     * Brings Surge up. Asynchronous on purpose: constructing it scans 641
     * patches and 188 wavetables, 41 ms of file work that has no business on the
     * way through start-up. Until it finishes, the node renders silence and
     * everything else plays normally.
     *
     * The content lives beside the instrument library rather than in the APK.
     * Surge takes a filesystem path, so it cannot read out of an APK at all, and
     * keeping it outside means the full 483 MB library can be swapped in without
     * a rebuild. See tools/install_surge_data.sh.
     */
    private fun startSurge() {
        val data = java.io.File(getExternalFilesDir(null), "surge-xt")
        // Writable and app-private: sst-plugininfra's homePath() THROWS if HOME
        // is unset, and Android makes no promise about it.
        val home = java.io.File(filesDir, "surge-home").apply { mkdirs() }
        NativeBridge.nativeInitSurge(data.absolutePath, home.absolutePath)
    }

    // -------------------------------------------------- sfizz's instruments

    /**
     * The SFZ instruments installed: the app's own (seeded from its assets) and
     * the folder a player fills, by Import or with adb:
     *   /sdcard/Android/data/com.rigalong/files/instruments/
     */
    var library: List<Instrument> = emptyList()
        private set

    /** What the first sfizz was last given here: what "as loaded" means for a new song. */
    var currentInstrument: Instrument? = null
        private set

    /**
     * Rescans the library and loads the remembered instrument by **id**. The id
     * is what a patch stores; the path is resolved here and never persisted.
     */
    private fun loadFirstInstrument() {
        // Two places are searched, in order:
        //   1. the app's private files dir, seeded from assets on first run, so
        //      the app always makes a sound out of the box;
        //   2. the external instruments dir, for libraries pushed by the user.
        //
        // Note the external dir is NOT reliable for adb push: a folder created by
        // `adb shell mkdir` is owned by the shell user and the app cannot read it.
        // Create it from here first, then push into it.
        val privateDir = java.io.File(filesDir, "instruments").apply { mkdirs() }
        val externalDir = java.io.File(getExternalFilesDir(null), "instruments").apply { mkdirs() }

        copyAssetInstruments(privateDir)

        // External first: a real library beats the seeded fallback of the same id.
        library = InstrumentLibrary.scan(listOf(externalDir, privateDir))

        val wanted = prefs.getString("instrumentId", null)
        val chosen = library.firstOrNull { it.id == wanted } ?: library.firstOrNull()
        if (chosen != null) select(chosen)
    }

    /** [instrument] into the first sfizz, remembered for the next start; the heads of its samples warmed. */
    fun select(instrument: Instrument) {
        currentInstrument = instrument
        prefs.edit().putString("instrumentId", instrument.id).apply()
        NativeBridge.nativeLoadInstrument(instrument.id, instrument.sfz.absolutePath)
        warmInstrument(instrument)
    }

    fun select(id: String) { library.firstOrNull { it.id == id }?.let { select(it) } }

    /** The SFZ file of installed instrument [id], or null. */
    fun path(id: String): String? = library.firstOrNull { it.id == id }?.sfz?.absolutePath

    /** Sample heads are being read into the page cache, and how much so far. */
    @Volatile var warming = false
        private set
    @Volatile var warmedMb = 0
        private set

    /**
     * Reads every sample file of the library so the OS page cache holds them.
     *
     * Measured cause of the remaining dropouts: a single note-on on a COLD sample
     * cost 2.5 ms on the audio thread — a blocking disk read, not computation.
     * Note-on budgeting cannot help with that, because one expensive call is
     * already too late by the time it is noticed. The only real fix is that the
     * data is already in RAM when the key goes down.
     *
     * Ordinary priority, and deliberately NOT pinned to the fast cores: it must
     * never compete with the audio thread. Finishing in 4 s instead of 2 s costs
     * nothing; stealing a big core costs a glitch.
     */
    private fun warmInstrument(instrument: Instrument) {
        // Only the HEAD of each sample is read, not the whole library.
        //
        // What blocks the render thread is the note-START path: sfizz reaching for
        // a sample's preloaded head that is not resident yet. Measured cost of one
        // such read: up to 12 ms, which overruns even a 12 ms ring reserve.
        // Sustained streaming further into a sample is handled by the ring and
        // does not need warming.
        //
        // sfizz preloads 65536 frames per sample, which is 256 kB at 16-bit
        // stereo, so 512 kB per file covers it with margin. For Salamander that is
        // ~330 MB read in about a second, instead of 1.2 GB over a minute.
        if (warming) return
        warming = true
        warmedMb = 0
        Thread {
            val root = instrument.sfz.parentFile?.parentFile ?: instrument.sfz.parentFile
            val buf = ByteArray(1 shl 18)
            val headBytesPerFile = 512L * 1024L
            var total = 0L
            try {
                root?.walkTopDown()
                    ?.filter { it.isFile && it.extension.lowercase() in setOf("wav", "flac", "ogg") }
                    ?.forEach { f ->
                        f.inputStream().use { input ->
                            var fromThisFile = 0L
                            while (fromThisFile < headBytesPerFile) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                fromThisFile += n
                                total += n
                                warmedMb = (total / (1L shl 20)).toInt()
                            }
                        }
                    }
            } catch (_: Exception) {
                // Best effort: a failure here costs performance, never correctness.
            }
            warming = false
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** Seeds the private instruments folder from assets, once. */
    private fun copyAssetInstruments(target: java.io.File) {
        val names = runCatching { assets.list("instruments") }.getOrNull() ?: return
        for (name in names) {
            val dest = java.io.File(target, name)
            if (dest.exists()) continue
            runCatching {
                assets.open("instruments/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    // ------------------------------------------------- Import and file export

    /** An engine's own file put where its engine looks ([com.rigalong.ui.EngineHost.installEngineFile]). */
    fun installEngineFile(kind: com.rigalong.model.EngineFiles.Kind, fileName: String, bytes: ByteArray): String? {
        val dir = when (kind) {
            com.rigalong.model.EngineFiles.Kind.SOUNDFONT -> java.io.File(getExternalFilesDir(null), "soundfonts")
            com.rigalong.model.EngineFiles.Kind.DX7_BANK -> java.io.File(getExternalFilesDir(null), "dx7")
            com.rigalong.model.EngineFiles.Kind.SURGE_PATCH -> java.io.File(
                NativeBridge.nativeSurgeUserPatchesPath().ifEmpty { error("Surge XT is still starting; try again in a moment") },
                "Imported")
            com.rigalong.model.EngineFiles.Kind.DX7_VOICE -> error("a single DX7 voice")
        }.apply { mkdirs() }
        val target = com.rigalong.model.EngineFiles.place(dir,
            com.rigalong.model.EngineFiles.installName(fileName, kind), bytes) ?: return null
        // Whole or not at all: an engine scanning the folder never sees half a file.
        val part = java.io.File(dir, ".${target.name}.part")
        part.writeBytes(bytes)
        require(part.renameTo(target)) { "could not put ${target.name} in place" }
        NativeBridge.nativeRescanContent()
        return target.nameWithoutExtension
    }

    /** A patch as its engine's own file ([com.rigalong.ui.EngineHost.exportPatch]). */
    fun exportPatch(patch: com.rigalong.model.Patch, name: String): Pair<String, ByteArray>? {
        val export = com.rigalong.model.PatchExport
        val kind = export.kind(patch.engine) ?: return null
        // A patch "as loaded" names no sound of its own: what plays now is it.
        val p = if (!patch.preset.isNullOrEmpty()) patch else when (kind) {
            com.rigalong.model.EngineFiles.Kind.DX7_BANK -> patch.copy(preset = NativeBridge.nativeDexedCurrentVoiceName())
            com.rigalong.model.EngineFiles.Kind.SURGE_PATCH -> patch.copy(preset = NativeBridge.nativeSurgeCurrentPatchName())
            else -> patch.copy(preset = NativeBridge.nativeFluidCurrentFont(),
                program = patch.program ?: NativeBridge.nativeFluidCurrentPreset().coerceAtLeast(0))
        }
        val preset = p.preset?.takeIf { it.isNotEmpty() } ?: error("${kind.engine} has no sound loaded")
        return when (kind) {
            com.rigalong.model.EngineFiles.Kind.DX7_BANK -> {
                val voice = NativeBridge.nativeDexedVoicePacked(preset) ?: error("Dexed has no voice “$preset”")
                export.fileName(name, kind) to export.dexed(p, name, voice)
            }
            com.rigalong.model.EngineFiles.Kind.SURGE_PATCH -> {
                val path = NativeBridge.nativeSurgePatchPath(preset).ifEmpty { error("Surge XT has no patch “$preset”") }
                export.fileName(name, kind) to export.surge(p, name, java.io.File(path).readBytes())
            }
            com.rigalong.model.EngineFiles.Kind.SOUNDFONT -> {
                val font = java.io.File(NativeBridge.nativeFluidFontPath(preset).ifEmpty { error("FluidSynth has no font “$preset”") })
                export.fileName(name, kind, sf3 = font.extension.equals("sf3", ignoreCase = true)) to export.fluid(p, name, font)
            }
            com.rigalong.model.EngineFiles.Kind.DX7_VOICE -> null
        }
    }

    /** An SFZ pack from a zip into the instruments folder ([com.rigalong.ui.EngineHost.importInstrumentZip]). */
    fun importInstrumentZip(input: java.io.InputStream, fileName: String): String {
        val id = fileName.substringBeforeLast('.').lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "instrument" }
        val root = java.io.File(getExternalFilesDir(null), "instruments").apply { mkdirs() }
        val target = java.io.File(root, id)
        require(!target.exists()) { "an instrument called '$id' is already installed" }
        val staging = java.io.File(root, ".$id.part").apply { deleteRecursively(); mkdirs() }
        try {
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = java.io.File(staging, e.name)
                    // No entry may land outside the staging folder (zip slip).
                    require(out.canonicalPath.startsWith(staging.canonicalPath + java.io.File.separator)) {
                        "the zip has a path outside its folder: ${e.name}"
                    }
                    if (e.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
            // A zip of one folder: that folder is the pack.
            val top = staging.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            val pack = if (top.size == 1 && top[0].isDirectory) top[0] else staging
            require(pack.walk().any { it.isFile && it.extension.equals("sfz", true) }) { "no .sfz file in the zip" }
            require(pack.renameTo(target)) { "could not move the pack into place" }
        } finally {
            staging.deleteRecursively()
        }
        val privateDir = java.io.File(filesDir, "instruments")
        library = InstrumentLibrary.scan(listOf(root, privateDir))
        return id
    }
}
