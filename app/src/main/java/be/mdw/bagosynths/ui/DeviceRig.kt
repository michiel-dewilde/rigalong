package be.mdw.bagosynths.ui

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import be.mdw.bagosynths.NativeBridge
import be.mdw.bagosynths.model.Digest
import be.mdw.bagosynths.model.ExternalSynth
import be.mdw.bagosynths.model.ExternalSynths
import be.mdw.bagosynths.model.Library
import be.mdw.bagosynths.model.LiveSet
import be.mdw.bagosynths.model.RackSong
import be.mdw.bagosynths.model.devices.DeviceDriver
import be.mdw.bagosynths.model.devices.DeviceSounds
import be.mdw.bagosynths.model.devices.Devices

/**
 * The external devices this rig drives (I4.7, I6.7, I10.1), each through its
 * driver (model/devices): what the songs' live sets do to them, done.
 *
 * Per device, from the environment ([ExternalSynth]): its MIDI channel, the
 * area reserved for the app's sounds (a CP-88's page) and whether its own
 * selections choose the app's live sets. When a song opens, every device that
 * can own sounds gets the song's in its reserved area -- live set n's in slot
 * n, silence where it follows the app and a live set gives it nothing -- and
 * only slots whose content changed are written. Selecting a live set selects
 * on each device what the live set says.
 *
 * MIDI goes out on the direct USB path (the one MIDI out the app has).
 */
class DeviceRig(private val lib: Library, private val prefs: SharedPreferences, private val note: (String) -> Unit) {

    /** The devices, as the environment has them; kept between sessions. */
    val devices = mutableStateListOf<ExternalSynth>().apply {
        addAll(runCatching { ExternalSynths.fromText(prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList()))
    }

    /** One line on the last device action, for the stage. */
    var status by mutableStateOf(""); private set

    /** Per device name: live set index -> the slot its sound was written to. */
    private val owned = HashMap<String, HashMap<Int, Int>>()
    /** Per device name: what the app selected last, and when, so the device echoing it back is not the player. */
    private val lastSent = HashMap<String, Pair<Map<String, Int>, Long>>()

    fun driver(d: ExternalSynth): DeviceDriver = Devices.driver(d.kind) ?: be.mdw.bagosynths.model.devices.MidiDeviceDriver

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

    fun add(drv: DeviceDriver) {
        var name = drv.defaultName
        var n = 2
        while (devices.any { it.name == name }) name = "${drv.defaultName} ${n++}"
        val areas = drv.sounds?.area?.range?.last?.let { listOf(it) } ?: emptyList()
        update(devices + ExternalSynth(name = name, kind = drv.kind, midiOut = "Keys", midiIn = "Keys",
            reservedAreas = areas, selectsLiveSets = drv.selection(0, 0, 0) != null || drv.sounds != null))
    }

    private fun send(bytes: ByteArray) = runCatching { NativeBridge.nativeUsbMidiSend(bytes) }.getOrDefault(-1) > 0

    /** The reserved area of [d] that songs' sounds go to, if it can own sounds and has one. */
    private fun area(d: ExternalSynth): Int? = driver(d).sounds?.let { d.reservedAreas.firstOrNull() }

    // ------------------------------------------------------- song and live set

    /**
     * The song's sounds into every device that can own them (S5.3, S4.11).
     * Slot n of the reserved area gets live set n's own sound; where the
     * device follows the app, every other slot gets the driver's silent sound,
     * so its own buttons never bring in a sound of its own. A slot already
     * holding the same content is not written again ([forget] resets that).
     */
    fun writeSong(liveSets: List<LiveSet>) {
        owned.clear()
        for (d in devices) {
            val s = driver(d).sounds ?: continue
            val area = area(d) ?: continue
            val slots = HashMap<Int, Int>()
            owned[d.name] = slots
            var written = 0; var kept = 0; var failed = 0
            for (i in 0 until minOf(s.slots, liveSets.size)) {
                val a = liveSets[i].devices[d.name]
                val own = if (a?.mode == "own") a.sound?.toRef()?.digest?.let { lib.store.get(it) } else null
                val bytes = own ?: if (d.selectsLiveSets && a?.mode != "select") s.silent else continue
                val slot = i + 1
                val key = slotKey(d, area, slot)
                val digest = Digest.of(bytes).hex
                if (prefs.getString(key, null) == digest) { slots[i] = slot; kept++; continue }
                if (s.write(bytes, area, slot).all(::send)) {
                    slots[i] = slot; written++; prefs.edit().putString(key, digest).apply()
                } else failed++
            }
            val more = liveSets.drop(s.slots).any { it.devices[d.name]?.mode == "own" }
            if (more) status = "${d.name}: only live sets 1-${s.slots} can own a sound (one ${s.area.label})"
            else if (written + failed > 0) status = if (failed == 0)
                "${d.name}: ${s.area.label} $area, $written slot(s) written, $kept unchanged"
                else "${d.name}: could not write $failed slot(s) -- is it in a direct USB mode?"
            if (written + failed > 0) note(status)
        }
    }

    /** Forget what the devices' reserved areas hold, so the next song rewrites them all. */
    fun forget() {
        val e = prefs.edit()
        for (d in devices) {
            val s = driver(d).sounds ?: continue
            val area = area(d) ?: continue
            for (n in 1..s.slots) e.remove(slotKey(d, area, n))
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
        for (d in devices) {
            val a = liveSet?.devices?.get(d.name)
            when (a?.mode) {
                "select" -> select(d, a.address)
                "own" -> ownSlot(d, index)?.let { select(d, it) }
                else -> if (d.selectsLiveSets) ownSlot(d, index)?.let { select(d, it) }
            }
        }
    }

    private fun ownSlot(d: ExternalSynth, index: Int): Map<String, Int>? {
        val slot = owned[d.name]?.get(index) ?: return null
        return driver(d).sounds?.slotAddress(area(d) ?: return null, slot)
    }

    private fun select(d: ExternalSynth, address: Map<String, Int>) {
        val drv = driver(d)
        val ok = send(drv.select(address, d.channel))
        lastSent[d.name] = address to System.currentTimeMillis()
        status = if (ok) "${d.name} -> ${drv.describe(address)}" else "${d.name} not reachable (MIDI out needs a direct USB mode)"
    }

    /** MIDI out has come up: maybe another unit than before, so every device is brought in line. */
    fun midiOutReady(liveSets: List<LiveSet>, index: Int) {
        forget()
        writeSong(liveSets)
        liveSetSelected(index, liveSets.getOrNull(index))
        if (devices.isNotEmpty()) note("MIDI out ready: the devices brought in line ($status)")
    }

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
            val area = area(d) ?: return -1
            // Its slot in the reserved area; failing that, the same slot of the area it is on
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
     * (V2) -- into the store. Blocking, a fraction of a second: off the UI
     * thread. Returns the sound and its name, or throws with the reason.
     */
    fun importCurrent(d: ExternalSynth): Pair<Digest, String> {
        val s: DeviceSounds = driver(d).sounds ?: error("${d.name} has no sounds of its own to read")
        while (NativeBridge.nativeUsbSysexDrain() != null) { /* stale replies */ }
        check(send(s.dumpRequest())) { "MIDI out failed: ${d.name} must be in a direct USB mode" }
        val got = ArrayList<ByteArray>()
        val end = System.currentTimeMillis() + 2000
        var sound: ByteArray? = null
        while (sound == null && System.currentTimeMillis() < end) {
            val m = NativeBridge.nativeUsbSysexDrain()
            if (m == null) Thread.sleep(5) else { got += m; sound = s.collect(got) }
        }
        val whole = sound ?: error("${d.name} answered with ${got.size} message(s), not a whole sound")
        return lib.store.put(whole) to s.name(whole)
    }

    /** [sound] into [d]'s current sound, to hear it; no stored slot is touched. */
    fun audition(d: ExternalSynth, sound: ByteArray): Boolean = driver(d).sounds?.audition(sound)?.all(::send) ?: false

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
        val area = area(d) ?: s.area.range.last
        return s.formats.firstNotNullOfOrNull { it.writeArea(area, sets) }
    }

    private fun slotKey(d: ExternalSynth, area: Int, slot: Int) = "device.${d.kind}.${d.name}.$area.$slot"

    companion object {
        private const val KEY = "devices"
        private const val TAG = "bos-devices"
        @Suppress("unused") private fun log(m: String) = Log.i(TAG, m)
    }
}
