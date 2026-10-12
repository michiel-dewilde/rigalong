package be.mdw.rigalong

import java.io.File

/**
 * The instrument library.
 *
 * Instruments are **content, not code**: they live outside the APK, are addressed
 * by a stable `id`, and are resolved to a path only at load time. A saved patch
 * therefore records `"salamander-grand"`, never a file path — so patches stay
 * small and portable, the same patch works on another device with the library
 * installed elsewhere, and a missing library is a nameable condition ("missing:
 * salamander-grand") rather than a broken path.
 *
 * Layout — one folder per instrument, the folder name being the id:
 *
 *     instruments/
 *       salamander-grand/
 *         instrument.json            (optional)
 *         SalamanderGrandPianoV3.sfz
 *         44.1khz16bit/...
 *       test-saw/
 *         test-saw.sfz
 *
 * A bare `.sfz` sitting directly in an instruments folder is also accepted, with
 * its filename as the id, so dropping a single file in still works.
 */
data class Instrument(
    val id: String,
    val name: String,
    val sfz: File,
    val sizeBytes: Long,
) {
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1_073_741_824 -> "%.1f GB".format(sizeBytes / 1_073_741_824.0)
            sizeBytes >= 1_048_576 -> "%d MB".format(sizeBytes / 1_048_576)
            else -> "%d kB".format(sizeBytes / 1024)
        }
}

object InstrumentLibrary {

    /** Scans the given roots. Later roots do not override earlier ids. */
    fun scan(roots: List<File>): List<Instrument> {
        val found = LinkedHashMap<String, Instrument>()

        for (root in roots) {
            if (!root.isDirectory) continue
            val entries = root.listFiles() ?: continue

            for (entry in entries.sortedBy { it.name.lowercase() }) {
                val instrument = when {
                    entry.isDirectory -> fromPack(entry)
                    entry.extension.equals("sfz", true) ->
                        Instrument(entry.nameWithoutExtension, entry.nameWithoutExtension,
                            entry, entry.length())
                    else -> null
                }
                if (instrument != null) found.putIfAbsent(instrument.id, instrument)
            }
        }
        return found.values.toList()
    }

    private fun fromPack(dir: File): Instrument? {
        val manifest = File(dir, "instrument.json").takeIf { it.isFile }?.let { parse(it) }

        val sfz = manifest?.get("sfz")
            ?.let { File(dir, it) }?.takeIf { it.isFile }
            ?: pickEntrySfz(dir)
            ?: return null

        val name = manifest?.get("name") ?: dir.name
        val id = manifest?.get("id") ?: dir.name

        return Instrument(id, name, sfz, dirSize(dir))
    }

    /**
     * Picks the entry .sfz when a pack ships several. Salamander, for instance,
     * ships both a standard and a "Retuned" variant; the plain one is the sane
     * default, and a manifest can always override.
     */
    private fun pickEntrySfz(dir: File): File? {
        val candidates = dir.listFiles { f -> f.isFile && f.extension.equals("sfz", true) }
            ?.sortedBy { it.name.lowercase() } ?: return null
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { f ->
            val n = f.name.lowercase()
            !n.contains("retuned") && !n.contains("alt") && !n.contains("test")
        } ?: candidates.first()
    }

    /** Deliberately minimal: a few flat string keys, no JSON dependency. */
    private fun parse(file: File): Map<String, String> = runCatching {
        val text = file.readText()
        Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }.getOrDefault(emptyMap())

    private fun dirSize(dir: File): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)
}
