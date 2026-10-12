package com.rigalong.ui

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rigalong.NativeBridge
import com.rigalong.model.Digest
import com.rigalong.model.ExternalSynth
import com.rigalong.model.ExternalSynths
import com.rigalong.model.Library
import com.rigalong.model.LiveSet
import com.rigalong.model.RackSong
import com.rigalong.model.devices.DeviceDriver
import com.rigalong.model.devices.DeviceSounds
import com.rigalong.model.devices.Devices
import com.rigalong.model.devices.FoundSound
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The external devices this rig drives (I4.7, I6.7, I10.1), each through its
 * driver (model/devices): what the songs' live sets do to them, done.
 *
 * Per device, from the environment ([ExternalSynth]): its MIDI channel, the
 * areas reserved for the app's sounds (a CP-88's pages) and whether its own
 * selections choose the app's live sets. When a song opens, every device that
 * can own sounds plays the song from a reserved area -- live set n's sound in
 * slot n, silence where it follows the app and a live set gives it nothing --
 * and only slots whose content changed are written. With two areas, the next
 * song of the setlist is written into the other one while this one plays, so
 * the next song switch writes nothing (M4: no gap). Selecting a live set
 * selects on each device what the live set says.
 *
 * Before the app first writes into an area, what the area held is read back
 * and kept in the library's device sounds: nobody's own sounds are lost to a
 * reserved page they forgot about.
 *
 * Everything that goes out goes through one queue, in order, off the UI
 * thread: a selection never lands in the middle of a sound being written, and
 * a newer song cancels a preload between two sounds. MIDI goes out on the
 * direct USB path (the one MIDI out the app has).
 */
class DeviceRig(private val lib: Library, private val prefs: SharedPreferences, private val note: (String) -> Unit) {

    /** The devices, as the environment has them; kept between sessions. */
    val devices = mutableStateListOf<ExternalSynth>().apply {
        addAll(runCatching { ExternalSynths.fromText(prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList()))
    }

    /** One line on the last device action, for the stage. */
    var status by mutableStateOf(""); private set

    private val main = Handler(Looper.getMainLooper())
    private val queue = Executors.newSingleThreadExecutor { r -> Thread(r, "device-rig") }
    /** Bumped by every song: a preload of an older one stops between two sounds. */
    @Volatile private var generation = 0

    /** Per device name: live set index -> the slot of its active area its sound is in. */
    private val owned = ConcurrentHashMap<String, Map<Int, Int>>()
    /** Per device name: the area the current song plays from. */
    private val active = ConcurrentHashMap<String, Int>()
    /** Per device name: what the app selected last, and when, so the device echoing it back is not the player. */
    private val lastSent = ConcurrentHashMap<String, Pair<Map<String, Int>, Long>>()
    /**
     * Per device name: whether it confirms what it stored ([DeviceSounds.stored]).
     * Learnt from the first write; until then, and for one that does not, a
     * write counts once it is sent. On the queue only.
     */
    private val receipts = HashMap<String, Boolean>()

    fun driver(d: ExternalSynth): DeviceDriver = Devices.driver(d.kind) ?: com.rigalong.model.devices.MidiDeviceDriver

    fun update(list: List<ExternalSynth>) {
        devices.clear()
        devices.addAll(list)
        prefs.edit().putString(KEY, ExternalSynths.toText(list)).apply()
    }

    fun replace(old: ExternalSynth, new: ExternalSynth) = update(devices.map { if (it == old) new else it })

    /** A device of a recognised kind, connected for the first time: added with its driver's defaults. */
    fun recognised(usbName: String) {
        val drv = Devices.recognise(usbName) ?: return
        if (devices.any { it.kind == drv.kind }) return
        add(drv)
        note("${drv.label} recognised: added as \"${drv.defaultName}\"")
    }

    /** A device of [drv]'s kind, with the last two areas reserved (the current song and the next). */
    fun add(drv: DeviceDriver) {
        var name = drv.defaultName
        var n = 2
        while (devices.any { it.name == name }) name = "${drv.defaultName} ${n++}"
        val areas = drv.sounds?.area?.range?.let { r -> pairOf(r.last, r) } ?: emptyList()
        update(devices + ExternalSynth(name = name, kind = drv.kind, midiOut = "Keys", midiIn = "Keys",
            reservedAreas = areas, selectsLiveSets = drv.selection(0, 0, 0) != null || drv.sounds != null))
    }

    private fun send(bytes: ByteArray) = runCatching { NativeBridge.nativeUsbMidiSend(bytes) }.getOrDefault(-1) > 0

    private fun say(s: String, log: Boolean = true) {
        main.post { status = s }
        if (log) note(s)
    }

    /** The reserved areas of [d] that songs' sounds go to (one or two), if it can own sounds. */
    private fun areas(d: ExternalSynth): List<Int> =
        if (driver(d).sounds == null) emptyList() else d.reservedAreas.distinct().take(2)

    // ------------------------------------------------------- song and live set

    /** Per device that can own sounds: slot -> what live set slot-1 wants there (own sound, or silence). */
    private class Plan(val d: ExternalSynth, val s: DeviceSounds, val slots: Map<Int, ByteArray>, val tooMany: Boolean)

    // On the caller's thread: the store is read here, never on the queue.
    private fun plans(liveSets: List<LiveSet>): List<Plan> = devices.mapNotNull { d ->
        val s = driver(d).sounds ?: return@mapNotNull null
        if (areas(d).isEmpty()) return@mapNotNull null
        val slots = HashMap<Int, ByteArray>()
        for (i in 0 until minOf(s.slots, liveSets.size)) {
            val a = liveSets[i].devices[d.name]
            val own = if (a?.mode == "own") a.sound?.toRef()?.digest?.let { lib.store.get(it) } else null
            (own ?: if (d.selectsLiveSets && a?.mode != "select") s.silent else null)?.let { slots[i + 1] = it }
        }
        Plan(d, s, slots, liveSets.drop(s.slots).any { it.devices[d.name]?.mode == "own" })
    }

    /** Slots of [area] already holding what [p] wants there. */
    private fun matching(p: Plan, area: Int) =
        p.slots.count { (slot, bytes) -> prefs.getString(slotKey(p.d, area, slot), null) == Digest.of(bytes).hex }

    /**
     * The song's sounds into every device that can own them (S5.3, S4.11).
     * It plays from whichever reserved area already holds most of them -- the
     * one the last song preloaded it into, normally -- and only the slots that
     * differ are written ([forget] makes every slot count as different).
     */
    fun writeSong(liveSets: List<LiveSet>) {
        val gen = ++generation
        val plans = plans(liveSets)
        queue.execute {
            val o = HashMap<String, Map<Int, Int>>()
            for (p in plans) {
                val choices = areas(p.d)
                val was = active[p.d.name]
                val area = choices.maxWithOrNull(compareBy<Int>({ matching(p, it) }, { if (it == was) 1 else 0 }, { -choices.indexOf(it) }))!!
                backupOnce(p.d, p.s, area)
                val (written, kept, failed, good) = write(p, area, gen = null)!!
                active[p.d.name] = area
                o[p.d.name] = good.associateBy { it - 1 }
                when {
                    p.tooMany -> say("${p.d.name}: only live sets 1-${p.s.slots} can own a sound (one ${p.s.area.label})")
                    failed > 0 -> say("${p.d.name}: could not write $failed slot(s) -- it did not confirm them, or MIDI out is not on direct USB")
                    else -> say("${p.d.name}: ${p.s.area.label} $area, $written slot(s) written, $kept unchanged", log = written > 0)
                }
            }
            owned.clear()
            owned.putAll(o)
            Log.i(TAG, "song $gen: areas $active")
        }
    }

    /**
     * The setlist's next song into each device's other reserved area, behind
     * whatever is queued, so that opening it writes nothing. Stops between two
     * sounds when another song opens first.
     */
    fun preload(next: List<LiveSet>?) {
        if (next == null) return
        val gen = generation
        val plans = plans(next)
        queue.execute {
            for (p in plans) {
                val other = areas(p.d).firstOrNull { it != active[p.d.name] } ?: continue
                if (generation != gen) return@execute
                backupOnce(p.d, p.s, other)
                val (written, kept, failed) = write(p, other, gen) ?: return@execute
                if (written + failed > 0) note("${p.d.name}: next song into ${p.s.area.label} $other, " +
                    "$written slot(s) written, $kept unchanged" + if (failed > 0) ", $failed failed" else "")
            }
        }
    }

    private data class Written(val written: Int, val kept: Int, val failed: Int, val good: Set<Int>)

    /** [p]'s slots into [area] where they differ, or null if song [gen] was overtaken. */
    private fun write(p: Plan, area: Int, gen: Int?): Written? {
        var written = 0; var kept = 0; var failed = 0
        val good = HashSet<Int>()
        for ((slot, bytes) in p.slots.toSortedMap()) {
            if (gen != null && generation != gen) return null
            val key = slotKey(p.d, area, slot)
            val digest = Digest.of(bytes).hex
            if (prefs.getString(key, null) == digest) { kept++; good += slot; continue }
            // Once more if the device does not say it stored it: the slot is only taken as written when it does.
            if ((1..2).any { p.s.write(bytes, area, slot).all(::send) && stored(p, area, slot) }) {
                written++; good += slot; prefs.edit().putString(key, digest).apply()
            } else {
                failed++; prefs.edit().remove(key).apply()
            }
        }
        return Written(written, kept, failed, good)
    }

    /**
     * Whether [p]'s device stored [slot] of [area], just written: its receipt,
     * a few ms after the last byte. One that has not sent receipts yet is
     * given [RECEIPT_MS] once to show it does. Other replies left waiting are
     * dropped here, as [read] would.
     */
    private fun stored(p: Plan, area: Int, slot: Int): Boolean {
        val name = p.d.name
        if (receipts[name] == false) return true
        val end = System.currentTimeMillis() + RECEIPT_MS
        while (System.currentTimeMillis() < end) {
            val m = NativeBridge.nativeUsbSysexDrain()
            if (m == null) { Thread.sleep(1); continue }
            val got = p.s.stored(m) ?: continue
            if (receipts.put(name, true) == null) Log.i(TAG, "$name confirms what it stores")
            if (got == area to slot) return true
        }
        if (receipts[name] == true) {
            Log.w(TAG, "$name: no receipt for $area:$slot")
            return false
        }
        receipts[name] = false
        Log.i(TAG, "$name sent no receipt: its writes count once sent")
        return true
    }

    /**
     * What [area] held before the app's first write there, read back slot by
     * slot and kept in the library's device sounds ("a sound the library
     * already has is not added twice"). Once per device and area; tried again
     * next time if the device did not answer.
     */
    private fun backupOnce(d: ExternalSynth, s: DeviceSounds, area: Int) {
        val key = "device.backup.${d.kind}.${d.name}.$area"
        if (prefs.getBoolean(key, false)) return
        val found = ArrayList<FoundSound>()
        var why = ""
        for (slot in 1..s.slots) {
            val sound = runCatching { read(d, s, s.dumpRequest(area, slot)) }.onFailure { why = it.message ?: it.toString() }
                .getOrNull() ?: break
            found += FoundSound(s.name(sound), sound, "$area:$slot")
        }
        if (found.size < s.slots) {
            note("${d.name}: could not read ${s.area.label} $area back before writing it " +
                "(${found.size} of ${s.slots} slots answered: $why)")
            return
        }
        prefs.edit().putBoolean(key, true).apply()
        main.post {
            val added = runCatching { lib.keepDeviceSounds(s.mediaType, found) }.getOrDefault(0)
            note("${d.name}: ${s.area.label} $area kept in the library before the first write ($added new sound(s))")
        }
    }

    /** Sends [request] and collects the one sound that answers it; throws with the reason. On the queue only. */
    private fun read(d: ExternalSynth, s: DeviceSounds, request: ByteArray): ByteArray {
        while (NativeBridge.nativeUsbSysexDrain() != null) { /* stale replies */ }
        check(send(request)) { "MIDI out failed: ${d.name} must be in a direct USB mode" }
        val got = ArrayList<ByteArray>()
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end) {
            val m = NativeBridge.nativeUsbSysexDrain()
            if (m == null) Thread.sleep(5) else {
                got += m
                val sound = try { s.collect(got) } catch (e: IllegalArgumentException) {
                    // What came, by address, to tell a stray message from a reply that is wrong.
                    val seen = got.joinToString(" ") { x -> if (x.size > 11) "%02x%02x%02x".format(x[8], x[9], x[10]) else "(${x.size})" }
                    throw IllegalArgumentException("${e.message}: $seen")
                }
                sound?.let { return it }
            }
        }
        error("${d.name} answered with ${got.size} message(s), not a whole sound")
    }

    /** Forget what the devices' reserved areas hold, so the next song rewrites them all. */
    fun forget() {
        val e = prefs.edit()
        for (d in devices) {
            val s = driver(d).sounds ?: continue
            for (area in areas(d)) for (n in 1..s.slots) e.remove(slotKey(d, area, n))
        }
        e.apply()
    }

    /**
     * Live set [index] selected: each device does what the live set says --
     * selects a stored sound, or its own in the reserved area. A device that
     * follows the app and is left alone goes to this live set's slot, which
     * holds silence.
     */
    fun liveSetSelected(index: Int, liveSet: LiveSet?) {
        val actions = devices.map { it to liveSet?.devices?.get(it.name) }
        queue.execute {
            for ((d, a) in actions) when (a?.mode) {
                "select" -> select(d, a.address)
                "own" -> ownSlot(d, index)?.let { select(d, it) }
                else -> if (d.selectsLiveSets) ownSlot(d, index)?.let { select(d, it) }
            }
        }
    }

    private fun ownSlot(d: ExternalSynth, index: Int): Map<String, Int>? {
        val slot = owned[d.name]?.get(index) ?: return null
        return driver(d).sounds?.slotAddress(active[d.name] ?: return null, slot)
    }

    private fun select(d: ExternalSynth, address: Map<String, Int>) {
        val drv = driver(d)
        val ok = send(drv.select(address, d.channel))
        lastSent[d.name] = address to System.currentTimeMillis()
        Log.i(TAG, "${d.name} -> ${drv.describe(address)}${if (ok) "" else " FAILED"}")
        say(if (ok) "${d.name} -> ${drv.describe(address)}" else "${d.name} not reachable (MIDI out needs a direct USB mode)", log = false)
    }

    /** MIDI out has come up: maybe another unit than before, so every device is brought in line. */
    fun midiOutReady(liveSets: List<LiveSet>, index: Int, next: List<LiveSet>?) {
        forget()
        queue.execute { receipts.clear() }
        writeSong(liveSets)
        liveSetSelected(index, liveSets.getOrNull(index))
        preload(next)
        if (devices.isNotEmpty()) queue.execute { note("MIDI out ready: the devices brought in line ($status)") }
    }

    /**
     * TEST (PROVISIONAL.md): does a slot written and selected at once play
     * what was just written? Writes [a] and [b] in turn into [slot] of the
     * second reserved area, selects it after each delay, reads the current
     * sound back and compares names. One line per try.
     */
    fun writeSelectTest(d: ExternalSynth, a: ByteArray, b: ByteArray, delays: List<Int>): List<String> =
        queue.submit(Callable {
            val s = driver(d).sounds!!
            val area = areas(d).getOrNull(1) ?: areas(d).first()
            val slot = s.slots
            val out = ArrayList<String>()
            for ((i, ms) in delays.withIndex()) {
                val sound = if (i % 2 == 0) a else b
                val t0 = System.nanoTime()
                val ok = s.write(sound, area, slot).all(::send)
                val wrote = (System.nanoTime() - t0) / 1e6
                if (ms > 0) Thread.sleep(ms.toLong())
                send(driver(d).select(s.slotAddress(area, slot), d.channel))
                val got = runCatching { s.name(read(d, s, s.dumpRequest())) }.getOrElse { "(${it.message})" }
                out += "write ${"%.1f".format(wrote)} ms${if (ok) "" else " FAILED"}, select after $ms ms: " +
                    "plays '${got.trim()}', wanted '${s.name(sound).trim()}'" + if (got == s.name(sound)) " OK" else " STALE"
                prefs.edit().remove(slotKey(d, area, slot)).apply()
            }
            out
        }).get()

    /** Waits until everything queued so far has gone out (tests, and reading the outcome). */
    fun settle() = runCatching { queue.submit {}.get() }

    /**
     * A program selection that came in (bank MSB/LSB, program): if a device
     * that follows the app reports one of its slots this way, the live set
     * of that slot; null otherwise. Our own selection coming back within two
     * seconds is not the player choosing. [describe] receives what it was.
     */
    fun followed(msb: Int, lsb: Int, program: Int, describe: (String) -> Unit): Int? {
        for (d in devices) {
            if (!d.selectsLiveSets) continue
            val drv = driver(d)
            val address = drv.selection(msb, lsb, program) ?: continue
            val sent = lastSent[d.name]
            if (sent != null && sent.first == address && System.currentTimeMillis() - sent.second < 2000) return -1
            describe("${d.name} ${drv.describe(address)}")
            val s = drv.sounds ?: return -1
            val area = active[d.name] ?: areas(d).firstOrNull() ?: return -1
            // Its slot in the active area; failing that, the same slot of the area it is on
            // (the player pressing set n on another page still means live set n).
            return (1..s.slots).firstOrNull { s.slotAddress(area, it) == address }?.minus(1)
                ?: (1..s.slots).firstOrNull { s.slotAddress(area, it).filterKeys { k -> k != s.area.key } ==
                    address.filterKeys { k -> k != s.area.key } }?.minus(1)
                ?: -1
        }
        return null
    }

    // ------------------------------------------------------------ own sounds

    /** The devices that can own sounds of [mediaType], for playing and importing them. */
    fun withSounds(mediaType: String? = null): List<ExternalSynth> =
        devices.filter { d -> driver(d).sounds?.let { mediaType == null || it.mediaType == mediaType } == true }

    /**
     * Reads [d]'s current sound -- unsaved edits and panel state included
     * (V2) -- into the store. Blocking, a fraction of a second (more behind a
     * write in progress): off the UI thread. Returns the sound and its name,
     * or throws with the reason.
     */
    fun importCurrent(d: ExternalSynth): Pair<Digest, String> {
        val s: DeviceSounds = driver(d).sounds ?: error("${d.name} has no sounds of its own to read")
        val whole = try {
            queue.submit(Callable { read(d, s, s.dumpRequest()) }).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
        return lib.store.put(whole) to s.name(whole)
    }

    /** [sound] into [d]'s current sound, to hear it; no stored slot is touched. */
    fun audition(d: ExternalSynth, sound: ByteArray): Boolean {
        val s = driver(d).sounds ?: return false
        return runCatching { queue.submit(Callable { s.audition(sound).all(::send) }).get() }.getOrDefault(false)
    }

    /**
     * The song's area on [d] as the app writes it, as a file the device loads
     * itself (a CP-88's .X9P): slot n holds live set n's own sound, silence
     * where the device follows the app, the device's neutral sound otherwise.
     * (extension, bytes), or null if its driver writes no such file.
     */
    fun areaFile(d: ExternalSynth, song: RackSong): Pair<String, ByteArray>? {
        val s = driver(d).sounds ?: return null
        val sets = HashMap<Int, ByteArray>()
        for ((i, ls) in song.liveSets.take(s.slots).withIndex()) {
            val p = ls.devices[d.name]
            val own = if (p?.mode == "own") p.sound?.let { lib.store.get(it) } else null
            (own ?: if (d.selectsLiveSets && p?.mode != "select") s.silent else null)?.let { sets[i + 1] = it }
        }
        val area = active[d.name] ?: areas(d).firstOrNull() ?: s.area.range.last
        return s.formats.firstNotNullOfOrNull { it.writeArea(area, sets) }
    }

    private fun slotKey(d: ExternalSynth, area: Int, slot: Int) = "device.${d.kind}.${d.name}.$area.$slot"

    companion object {
        private const val KEY = "devices"
        private const val TAG = "rigalong-devices"
        /** How long a write waits for the receipt: the CP-88 sends it 1-8 ms after the footer (LOG.md 2026-10-12). */
        private const val RECEIPT_MS = 300L

        /** [area] and its neighbour for the next song: the one below it, or above at the bottom of [range]. */
        fun pairOf(area: Int, range: IntRange): List<Int> =
            listOf(area, if (area > range.first) area - 1 else area + 1).filter { it in range }.distinct()
    }
}
