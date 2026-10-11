package be.mdw.bagosynths

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Patches: the whole rig, written down.
 *
 * Until now nothing survived closing the app. That was tolerable while the only
 * state was "which SFZ is loaded"; setBfree made it untenable, because a
 * registration is something you build over a few minutes and then want back.
 *
 * Three decisions worth knowing about:
 *
 * **Parameters are stored by NAME, not by index.** An index is what changes when
 * someone inserts a value into `SetBfreeNode::Param`, and a patch file that
 * silently means something different after a refactor is worse than no patch
 * file. Names cost a few bytes and a lookup.
 *
 * **The topology is written but not yet rebuilt.** The graph is still constructed
 * in code, so loading applies parameters to the nodes that exist rather than
 * building the ones the file describes. Writing the edges anyway means today's
 * patches stay readable once the editor can create nodes — and it makes the
 * format honest about what a patch *is*, rather than growing into it later.
 *
 * **Files live in internal storage.** `filesDir`, not the external directory the
 * instrument library uses. Instruments are content a human puts there over adb;
 * patches are the app's own state, and internal storage avoids the mode-770
 * ownership trap that cost an evening with the instrument library.
 */
object PatchStore {

    private const val TAG = "bos-patch"
    private const val VERSION = 1

    /** The patch restored on launch. Written on every pause, so it is always current. */
    private const val LAST = "last.json"

    private fun dir(context: Context): File =
        File(context.filesDir, "patches").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** Saved patches, newest first, excluding the auto-saved one. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != LAST }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    // ------------------------------------------------------------------ capture

    /**
     * Reads the live graph into JSON. Runs on the UI thread and touches only the
     * parameter accessors, which are plain reads of values the nodes already hold
     * — no audio-thread involvement at all.
     */
    fun capture(name: String, instrumentId: String): JSONObject {
        val nodes = JSONArray()
        for (i in 0 until NativeBridge.nativeNodeCount()) {
            val params = JSONObject()
            for (p in 0 until NativeBridge.nativeNodeParamCount(i)) {
                val paramName = NativeBridge.nativeNodeParamName(i, p)
                if (paramName.isEmpty()) continue
                params.put(paramName, NativeBridge.nativeGetNodeParam(i, p).toDouble())
            }
            nodes.put(JSONObject().apply {
                put("ordinal", i)
                put("type", NativeBridge.nativeNodeType(i))
                put("params", params)
            })
        }

        val edges = JSONArray()
        val flat = NativeBridge.nativeEdges()
        var i = 0
        while (i + 3 < flat.size) {
            edges.put(JSONObject().apply {
                put("from", flat[i]); put("fromPin", flat[i + 1])
                put("to", flat[i + 2]); put("toPin", flat[i + 3])
            })
            i += 4
        }

        return JSONObject().apply {
            put("version", VERSION)
            put("name", name)
            put("saved", System.currentTimeMillis())
            put("instrument", instrumentId)
            // Surge's patch, like the instrument, is content rather than a
            // parameter: it is a file in a library that may or may not be
            // installed, so it is stored by its stable "Category/Name" address
            // and applied through the same asynchronous load the UI uses.
            put("surgePatch", NativeBridge.nativeSurgeCurrentPatchName())
            put("dexedVoice", NativeBridge.nativeDexedCurrentVoiceName())
            // Same reasoning again, twice over: which SoundFont FILE is loaded,
            // and which preset within it. Both are content that may not be
            // installed on the next machine, so both go in by name.
            put("fluidFont", NativeBridge.nativeFluidCurrentFont())
            put("fluidPreset", NativeBridge.nativeFluidCurrentPresetName())
            put("nodes", nodes)
            put("edges", edges)
        }
    }

    // -------------------------------------------------------------------- apply

    /**
     * Applies a patch to the live graph.
     *
     * Nodes are matched by **type and ordinal among that type** rather than by the
     * bare ordinal, so a patch still lands correctly if a node is inserted
     * earlier in the graph. Anything the file mentions and the graph does not
     * have is skipped and logged rather than treated as an error: a patch from a
     * newer build should degrade, not refuse.
     *
     * Returns the instrument id the patch wants, or null if it names none. The
     * caller decides whether to load it, because loading is asynchronous and this
     * function is not.
     */
    fun apply(patch: JSONObject): String? {
        // type -> the ordinals in the live graph, in order
        val liveByType = HashMap<String, MutableList<Int>>()
        for (i in 0 until NativeBridge.nativeNodeCount()) {
            liveByType.getOrPut(NativeBridge.nativeNodeType(i)) { ArrayList() }.add(i)
        }

        val seenByType = HashMap<String, Int>()
        val nodes = patch.optJSONArray("nodes") ?: JSONArray()

        for (n in 0 until nodes.length()) {
            val node = nodes.optJSONObject(n) ?: continue
            val type = node.optString("type")
            val nth = seenByType.getOrDefault(type, 0)
            seenByType[type] = nth + 1

            val target = liveByType[type]?.getOrNull(nth)
            if (target == null) {
                Log.i(TAG, "patch has $type #$nth and the graph does not; skipped")
                continue
            }

            // Build name -> index once per node, then apply by name.
            val indexOf = HashMap<String, Int>()
            for (p in 0 until NativeBridge.nativeNodeParamCount(target)) {
                indexOf[NativeBridge.nativeNodeParamName(target, p)] = p
            }

            val params = node.optJSONObject("params") ?: continue
            for (key in params.keys()) {
                val index = indexOf[key]
                if (index == null) {
                    Log.i(TAG, "$type has no parameter '$key'; skipped")
                    continue
                }
                NativeBridge.nativeSetNodeParam(target, index, params.optDouble(key).toFloat())
            }
        }

        val instrument = patch.optString("instrument")
        return if (instrument.isNullOrEmpty()) null else instrument
    }

    // --------------------------------------------------------------- disk

    fun write(context: Context, fileName: String, patch: JSONObject): Boolean = try {
        file(context, fileName).writeText(patch.toString(2))
        Log.i(TAG, "wrote $fileName")
        true
    } catch (e: Exception) {
        // A patch that fails to save must not take the instrument down with it.
        Log.e(TAG, "could not write $fileName: ${e.message}")
        false
    }

    fun read(file: File): JSONObject? = try {
        JSONObject(file.readText())
    } catch (e: Exception) {
        Log.e(TAG, "could not read ${file.name}: ${e.message}")
        null
    }

    /** Saves the current state as the patch to restore next launch. */
    fun saveLast(context: Context, instrumentId: String) {
        write(context, LAST, capture("last", instrumentId))
    }

    fun readLast(context: Context): JSONObject? {
        val f = file(context, LAST)
        return if (f.exists()) read(f) else null
    }

    /**
     * Saves under a name derived from the current time, so two saves never
     * collide and the list sorts sensibly. Returns the file name.
     */
    fun saveNamed(context: Context, instrumentId: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "patch-$stamp.json"
        write(context, name, capture(name.removeSuffix(".json"), instrumentId))
        return name
    }
}
