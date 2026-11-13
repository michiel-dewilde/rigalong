package com.rigalong.play

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
 * selections choose the app's live sets. When a song opens, a device that can
 * own sounds gets the song in its reserved areas: live set n's sound in slot
 * n, the ninth on in the next area, UNDER THE LIVE SET'S NAME, so that its
 * display says what the whole rig is playing (Michiel, 2026-11-12). That
 * sound is the live set's own one from the library, a copy of the stored
 * sound of the device's that the live set selects, or silence where the
 * device follows the app and the live set gives it nothing -- and in every
 * reserved slot the song has no live set for, so that no button of the
 * device brings back a sound of another song (S4.12). Only slots whose
 * content changed are written. Selecting a live set selects its slot, and
 * nothing else: reserved slots are the one way a song's sounds reach a
 * device on the stage (S4.11; the device's current sound is for importing
 * and for hearing a sound while a song is set up). How many live sets have a
 * slot is how many areas are reserved, which is the player's to say.
 *
 * The song is written when it opens: eight slots go out in under a tenth of
 * a second and the device plays on from its current sound meanwhile. (Until
 * 2026-11-12 the next song was written ahead into a second area; it saved
 * that tenth.)
 *
 * Before the app first writes into an area, what the area held is read back
 * and kept in the library's device sounds: nobody's own sounds are lost to a
 * reserved page they forgot about.
 *
 * Everything that goes out goes through one queue, in order, off the UI
 * thread: a selection never lands in the middle of a sound being written.
 * MIDI goes out on the direct USB path (the one MIDI out the app has).
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

    /** Per device name: what the open song gives it. Set and read on the queue. */
    private val plans = HashMap<String, Plan>()
    /** Per device name: live set index -> the (area, slot) its sound is in. */
    private val owned = ConcurrentHashMap<String, Map<Int, Pair<Int, Int>>>()
    /** Per device name: what the app selected last, and when, so the device echoing it back is not the player. */
    private val lastSent = ConcurrentHashMap<String, Pair<Map<String, Int>, Long>>()
    /**
     * Per device name: whether it confirms what it stored ([DeviceSounds.stored]).
     * Learnt from the first write; until then, and for one that does not, a
     * write counts once it is sent. On the queue only.
     */
    private val receipts = HashMap<String, Boolean>()
    /**
     * Stored sounds of a device's own that live sets select, as read from it
     * (device name and address -> the sound), to be copied under the live
     * sets' names. Read once and kept until [forget]: a read takes the device
     * a sixth of a second. On the queue only.
     */
    private val copies = HashMap<String, ByteArray>()
    /** The same keys, for what the device did not give: not asked again until [forget]. */
    private val unread = HashSet<String>()

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

    /** A device of [drv]'s kind, with its last two areas reserved (on a CP-88, sixteen live sets); Setup has more or fewer. */
    fun add(drv: DeviceDriver) {
        var name = drv.defaultName
        var n = 2
        while (devices.any { it.name == name }) name = "${drv.defaultName} ${n++}"
        update(devices + ExternalSynth(name = name, kind = drv.kind, midiOut = "Keys", midiIn = "Keys",
            reservedAreas = drv.sounds?.area?.range?.let { r -> listOf(r.last - 1, r.last).filter { it in r }.distinct() }.orEmpty(),
            selectsLiveSets = drv.selection(0, 0, 0) != null || drv.sounds != null))
    }

    private fun send(bytes: ByteArray) = runCatching { NativeBridge.nativeUsbMidiSend(bytes) }.getOrDefault(-1) > 0

    private fun say(s: String, log: Boolean = true) {
        main.post { status = s }
        if (log) note(s)
    }

    /**
     * The reserved areas of [d] that a song's sounds go to, if it can own
     * sounds, in the device's own order: the first live sets in the lowest,
     * the next in the one after, as on the device's own page buttons.
     */
    private fun areas(d: ExternalSynth): List<Int> =
        if (driver(d).sounds == null) emptyList() else d.reservedAreas.distinct().sorted()

    // ------------------------------------------------------- song and live set

    /** What one live set gives a device: a sound the library has ([own], or silence), or a stored sound of the device's own to copy. */
    private class Want(val name: String, val sound: ByteArray?, val copyOf: Map<String, Int>?, val own: Boolean = false)

    /**
     * For a device that can own sounds: live set index -> what it gets
     * there. An index past the song's last live set is a reserved slot kept
     * silent.
     */
    private class Plan(val d: ExternalSynth, val s: DeviceSounds, val areas: List<Int>, val wants: Map<Int, Want>) {
        /** How many live sets have a slot. */
        val capacity get() = areas.size * s.slots
        /** The (area, slot) of live set [index], or null past the last reserved area. */
        fun place(index: Int): Pair<Int, Int>? = areas.getOrNull(index / s.slots)?.let { it to index % s.slots + 1 }
        /** "page 39+40" */
        val where get() = "${s.area.label} ${areas.joinToString("+")}"
    }

    // On the caller's thread: the store is read here, never on the queue.
    private fun plansFor(liveSets: List<LiveSet>): List<Plan> = devices.mapNotNull { d ->
        val s = driver(d).sounds ?: return@mapNotNull null
        val areas = areas(d).ifEmpty { return@mapNotNull null }
        val wants = HashMap<Int, Want>()
        for ((i, ls) in liveSets.withIndex()) {
            val a = ls.devices[d.name]
            // A linked sound is the library's latest (a rename or an edit there is followed); else the live set's own.
            val own = if (a?.mode == "own") lib.deviceSoundNow(a.soundEntry?.let { Digest.parse(it) }, a.sound?.toRef()?.digest) else null
            when {
                own != null -> wants[i] = Want(ls.name, own, null, own = true)
                // A stored sound in a reserved area is the app's own writing: not copied onto itself.
                a?.mode == "select" -> if (s.slotOf(a.address)?.first !in areas) wants[i] = Want(ls.name, null, a.address)
                d.selectsLiveSets -> wants[i] = Want(ls.name, s.silent, null)
            }
        }
        // The reserved slots this song has no live set for: silent, under the silent sound's own name.
        if (d.selectsLiveSets) for (i in liveSets.size until areas.size * s.slots) wants[i] = Want("", s.silent, null)
        Plan(d, s, areas, wants)
    }

    /** [w]'s sound under its live set's name, or null if it is a copy and the device did not give the original. On the queue. */
    private fun sound(p: Plan, w: Want): ByteArray? {
        val base = w.sound ?: w.copyOf?.let { original(p, it) } ?: return null
        return p.s.named(base, w.name)
    }

    /** The stored sound at [address] of [p]'s device, as the device has it: read once, then from [copies]. */
    private fun original(p: Plan, address: Map<String, Int>): ByteArray? {
        val (area, slot) = p.s.slotOf(address) ?: return null
        val key = "${p.d.name} $area:$slot"
        copies[key]?.let { return it }
        if (key in unread) return null
        return runCatching { read(p.d, p.s, p.s.dumpRequest(area, slot)) }
            .onFailure {
                unread += key
                note("${p.d.name}: could not read ${driver(p.d).describe(address)} to copy it (${it.message}); selected where it is")
            }
            .getOrNull()?.also { copies[key] = it }
    }

    /**
     * The song's sounds into every device that can own them (S5.3, S4.11):
     * live set n's in slot n of the reserved areas, under the live set's
     * name. Only the slots that differ are written ([forget] makes every slot
     * count as different).
     */
    fun writeSong(liveSets: List<LiveSet>) {
        val all = plansFor(liveSets)
        queue.execute {
            plans.clear()
            val o = HashMap<String, Map<Int, Pair<Int, Int>>>()
            for (p in all) {
                plans[p.d.name] = p
                // Only the areas this song writes into are read back first.
                for (area in p.wants.keys.mapNotNull { p.place(it)?.first }.distinct().sorted()) backupOnce(p.d, p.s, area)
                var written = 0; var kept = 0; var failed = 0
                val good = HashMap<Int, Pair<Int, Int>>()
                for ((index, want) in p.wants.toSortedMap()) {
                    val (area, slot) = p.place(index) ?: break
                    val bytes = sound(p, want) ?: continue
                    when (write(p, area, slot, bytes)) {
                        true -> { written++; good[index] = area to slot }
                        null -> { kept++; good[index] = area to slot }
                        false -> failed++
                    }
                }
                o[p.d.name] = good
                when {
                    // The player's own limit, and the player's to lift: the device is left where it is from there on.
                    p.wants.keys.any { it >= p.capacity } ->
                        say("${p.d.name}: only live sets 1-${p.capacity} have a slot (${p.where} reserved; Setup reserves more)")
                    failed > 0 -> say("${p.d.name}: could not write $failed slot(s) -- it did not confirm them, or MIDI out is not on direct USB")
                    else -> say("${p.d.name}: ${p.where}, $written slot(s) written, $kept unchanged", log = written > 0)
                }
            }
            owned.clear()
            owned.putAll(o)
        }
    }

    /** [bytes] into [slot] of [area]: true written, null already there, false not confirmed. */
    private fun write(p: Plan, area: Int, slot: Int, bytes: ByteArray): Boolean? {
        val key = slotKey(p.d, area, slot)
        val digest = Digest.of(bytes).hex
        if (prefs.getString(key, null) == digest) return null
        // Once more if the device does not say it stored it: the slot is only taken as written when it does.
        return if ((1..2).any { p.s.write(bytes, area, slot).all(::send) && stored(p, area, slot) }) {
            prefs.edit().putString(key, digest).apply()
            true
        } else {
            prefs.edit().remove(key).apply()
            false
        }
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

    /**
     * Forget what the devices' reserved areas hold, what was read from them
     * and whether they send receipts, so the next song reads and rewrites it
     * all: it may be another unit than before, or the same one without what
     * was written to it. Behind whatever the queue is still writing, so that
     * an earlier song's last slots are not put back on record after the
     * forgetting.
     */
    fun forget() {
        val all = devices.toList()
        queue.execute {
            val e = prefs.edit()
            for (d in all) {
                val s = driver(d).sounds ?: continue
                for (area in d.reservedAreas) for (n in 1..s.slots) e.remove(slotKey(d, area, n))
            }
            e.apply()
            copies.clear()
            unread.clear()
            receipts.clear()
        }
    }

    /**
     * Live set [index] selected: each device goes to the slot that holds this
     * live set's sound. A stored sound that has no slot -- the device owns no
     * sounds, the original could not be read to be copied, or the live set is
     * past the last reserved slot -- is selected where it is. Otherwise a live
     * set without a slot leaves the device alone.
     */
    fun liveSetSelected(index: Int, liveSet: LiveSet?) {
        val actions = devices.map { it to liveSet?.devices?.get(it.name) }
        queue.execute {
            for ((d, a) in actions) {
                val p = plans[d.name]
                val at = owned[d.name]?.get(index)
                when {
                    p != null && at != null -> select(d, p.s.slotAddress(at.first, at.second))
                    a?.mode == "select" -> select(d, a.address)
                }
            }
        }
    }

    /** Where [d] goes when the app lets go of it: its home (Setup), or its driver's first stored sound. */
    fun home(d: ExternalSynth): Map<String, Int> =
        d.home.ifEmpty { driver(d).address.filter { !it.optional }.associate { it.key to it.range.first } }

    /**
     * The app lets go of the devices it gives sounds to: each goes to its
     * home, a sound of its own, and is an ordinary instrument again (Michiel,
     * 2026-11-13: off the stage and on exit the CP-88 returns to normal). The
     * reserved areas keep what was written; the next live set selected takes
     * the device back.
     */
    fun release() {
        val all = devices.toList()
        queue.execute {
            for (d in all) {
                if (driver(d).sounds == null || areas(d).isEmpty()) continue
                select(d, home(d))
            }
        }
    }

    private fun select(d: ExternalSynth, address: Map<String, Int>) {
        val drv = driver(d)
        val ok = send(drv.select(address, d.channel))
        lastSent[d.name] = address to System.currentTimeMillis()
        Log.i(TAG, "${d.name} -> ${drv.describe(address)}${if (ok) "" else " FAILED"}")
        say(if (ok) "${d.name} -> ${drv.describe(address)}" else "${d.name} not reachable (MIDI out needs a direct USB mode)", log = false)
    }

    /** MIDI out has come up under an open song: every device is brought in line ([forget], then the song again). */
    fun midiOutReady(liveSets: List<LiveSet>, index: Int) {
        forget()
        writeSong(liveSets)
        liveSetSelected(index, liveSets.getOrNull(index))
        if (devices.isNotEmpty()) queue.execute { note("MIDI out ready: the devices brought in line ($status)") }
    }

    /**
     * TEST (PROVISIONAL.md): does a slot written and selected at once play
     * what was just written? Writes [a] and [b] in turn into the last slot of
     * the last reserved area, selects it after each delay, reads the current sound
     * back and compares names. One line per try.
     */
    fun writeSelectTest(d: ExternalSynth, a: ByteArray, b: ByteArray, delays: List<Int>): List<String> =
        queue.submit(Callable {
            val s = driver(d).sounds!!
            val area = areas(d).last()
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
            val all = areas(d).ifEmpty { return -1 }
            // A slot of a reserved area: the live set that has it.
            for ((k, area) in all.withIndex()) for (slot in 1..s.slots) {
                if (s.slotAddress(area, slot) == address) return k * s.slots + slot - 1
            }
            // Anywhere else: the same slot of the first reserved area (the
            // player pressing set n on another page still means live set n).
            return (1..s.slots).firstOrNull { s.slotAddress(all[0], it).filterKeys { k -> k != s.area.key } ==
                address.filterKeys { k -> k != s.area.key } }?.minus(1) ?: -1
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
     * The song's first area on [d] as the app writes it, as a file the device
     * loads itself (a CP-88's .X9P): slot n holds live set n's own sound under the
     * live set's name, silence (named too) where the device follows the app,
     * the device's neutral sound otherwise -- also where the live set selects
     * a stored sound of the device's, which only the device has.
     * (extension, bytes), or null if its driver writes no such file.
     */
    fun areaFile(d: ExternalSynth, song: RackSong): Pair<String, ByteArray>? {
        val s = driver(d).sounds ?: return null
        val sets = HashMap<Int, ByteArray>()
        for ((i, ls) in song.liveSets.take(s.slots).withIndex()) {
            val p = ls.devices[d.name]
            val own = if (p?.mode == "own") lib.deviceSoundNow(p.soundEntry, p.sound) else null
            (own ?: if (d.selectsLiveSets && p?.mode != "select") s.silent else null)?.let { sets[i + 1] = s.named(it, ls.name) }
        }
        val area = areas(d).firstOrNull() ?: s.area.range.last
        return s.formats.firstNotNullOfOrNull { it.writeArea(area, sets) }
    }

    private fun slotKey(d: ExternalSynth, area: Int, slot: Int) = "device.${d.kind}.${d.name}.$area.$slot"

    companion object {
        private const val KEY = "devices"
        private const val TAG = "rigalong-devices"
        /** How long a write waits for the receipt: the CP-88 sends it 1-8 ms after the footer (LOG.md 2026-10-12). */
        private const val RECEIPT_MS = 300L
    }
}
