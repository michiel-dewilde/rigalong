package com.rigalong

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.io.File

/**
 * The scriptable test hooks (PROVISIONAL.md): broadcasts that drive the
 * engine, the routing and the stage by name, so an unattended run needs no
 * taps -- a tap coordinate read from a screenshot goes stale silently.
 *
 *     adb shell am broadcast -a com.rigalong.NOTE --ei note 48 --ei vel 90
 *     adb shell am broadcast -a com.rigalong.ROUTE \
 *         --es audio USB_DEVICE --es midi USB_DEVICE --ei buffer 8
 *
 * Each hook's line below says what it takes.
 */
class TestHooks(
    private val context: Context,
    private val router: AudioRouter,
    private val content: EngineContent,
    private val player: com.rigalong.play.SongPlayer,
    /** MEASURE: the latency probe, as Setup's button starts it. */
    private val measure: () -> Unit,
    private val setMasterDb: (Float) -> Unit,
) {
    private companion object {
        const val TAG = "rigalong-ui"
        const val PREFIX = "com.rigalong."
        val ACTIONS = listOf(
            "NOTE", "CC", "PANIC", "STRESS", "PROFILE", "DUMP", "PARAM", "INSTANCE", "INSTRUMENT", "RESCAN",
            "MASTER", "CONCEAL", "RESETPEAKS", "FILL", "RENDERPRIO", "USBSTALL",
            "ROUTE", "OUTPUT", "SHARING", "RATE", "RESET", "REINIT", "DEVHEALTH", "SYSEX", "MEASURE",
            "STAGE", "DEVICESELFTEST", "DEVICEPRELOADTEST", "DEVICEWRITESELECT", "RESET_LIBRARY")
    }

    fun register() {
        val filter = IntentFilter()
        for (a in ACTIONS) filter.addAction(PREFIX + a)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() { runCatching { context.unregisterReceiver(receiver) } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action?.removePrefix(PREFIX) ?: return
            runCatching { handle(action, intent) }.onFailure { Log.e(TAG, "hook $action: $it") }
        }
    }

    private fun handle(action: String, intent: Intent) {
        when (action) {
            // ------------------------------------------------------ the engine
            // A note, so that an unattended soak makes the rig do WORK:  --ei note 48 --ei vel 90
            "NOTE" -> NativeBridge.nativeTestNote(intent.getIntExtra("note", 48), intent.getIntExtra("vel", 90))
            // A controller as if from the keys (controller maps, MIDI learn):  --ei cc 21 --ei value 127
            "CC" -> NativeBridge.nativeTestControl(intent.getIntExtra("cc", 1), intent.getIntExtra("value", 0))
            // A stress test that ends leaves 64 voices ringing.
            "PANIC" -> NativeBridge.nativePanic()
            // A dense cluster in one block:  --ei count 30
            "STRESS" -> NativeBridge.nativeStressChord(intent.getIntExtra("count", 30))
            // The profile, including the per-engine held peaks, to the log.
            "PROFILE" -> NativeBridge.nativeLogProfile()
            // The whole graph, to the log: which node holds a level down.
            "DUMP" -> for (n in 0 until NativeBridge.nativeNodeCount()) {
                val params = (0 until NativeBridge.nativeNodeParamCount(n)).joinToString(" ") { p ->
                    "${NativeBridge.nativeNodeParamName(n, p)}=" + "%.3f".format(NativeBridge.nativeGetNodeParam(n, p))
                }
                Log.i(TAG, "node $n ${NativeBridge.nativeNodeType(n)}: $params")
            }
            // One parameter by (part of) its node's and its own name:  --es node sfizz --es param level --ef value 0.9
            "PARAM" -> {
                val wantNode = intent.getStringExtra("node").orEmpty()
                val wantParam = intent.getStringExtra("param").orEmpty()
                val value = intent.getFloatExtra("value", 1.0f)
                for (n in 0 until NativeBridge.nativeNodeCount()) {
                    if (!NativeBridge.nativeNodeType(n).contains(wantNode, ignoreCase = true)) continue
                    for (p in 0 until NativeBridge.nativeNodeParamCount(n)) {
                        if (!NativeBridge.nativeNodeParamName(n, p).contains(wantParam, ignoreCase = true)) continue
                        NativeBridge.nativeSetNodeParam(n, p, value)
                        Log.i(TAG, "set ${NativeBridge.nativeNodeType(n)}.${NativeBridge.nativeNodeParamName(n, p)} = $value")
                    }
                }
            }
            // Engine instances without a song:  --ei add 2  (a new Surge XT);  --ei i 6 --es preset NAME --ef level 0.8
            "INSTANCE" -> {
                if (intent.hasExtra("add")) {
                    val i = NativeBridge.nativeAddInstance(intent.getIntExtra("add", 0))
                    Log.i(TAG, "instance added: $i of ${NativeBridge.nativeInstanceCount()}")
                }
                val i = intent.getIntExtra("i", -1)
                if (i >= 0) {
                    intent.getStringExtra("preset")?.let { NativeBridge.nativeInstanceLoadPreset(i, it, -1, null) }
                    if (intent.hasExtra("level")) {
                        val node = NativeBridge.nativeInstanceNode(i)
                        val p = (0 until NativeBridge.nativeNodeParamCount(node))
                            .firstOrNull { NativeBridge.nativeNodeParamName(node, it) == "Level" }
                        if (p != null) NativeBridge.nativeSetNodeParam(node, p, intent.getFloatExtra("level", 0f))
                    }
                    Log.i(TAG, "instance $i: type ${NativeBridge.nativeInstanceType(i)}, node " +
                        "${NativeBridge.nativeInstanceNode(i)}, busy ${NativeBridge.nativeInstanceBusy(i)}, " +
                        "preset '${NativeBridge.nativeInstancePreset(i)}'")
                }
            }
            // The first sfizz's instrument by (part of) its name:  --es name tight
            "INSTRUMENT" -> {
                val want = intent.getStringExtra("name").orEmpty()
                val hit = content.library.firstOrNull { it.id.contains(want, true) || it.name.contains(want, true) }
                if (hit == null) Log.i(TAG, "instruments: " + content.library.joinToString { "${it.id} (${it.name})" })
                else content.select(hit)
            }
            // The engines look at their folders again, after a flow took out what it imported
            // (a bare file name, from the import folders):  --es remove FlowSurge.fxp
            "RESCAN" -> {
                intent.getStringExtra("remove")?.takeIf { it.isNotEmpty() && '/' !in it && it != ".." }?.let { name ->
                    val dirs = listOf(File(context.getExternalFilesDir(null), "soundfonts"),
                        File(context.getExternalFilesDir(null), "dx7")) +
                        listOfNotNull(NativeBridge.nativeSurgeUserPatchesPath().takeIf { it.isNotEmpty() }?.let { File(it, "Imported") })
                    for (d in dirs) File(d, name).takeIf { it.isFile }?.let { Log.i(TAG, "removed ${it.path}: ${it.delete()}") }
                }
                NativeBridge.nativeRescanContent()
            }

            // -------------------------------------------------- the audio path
            //   --ef db -12
            "MASTER" -> setMasterDb(intent.getFloatExtra("db", 0f))
            // Underrun concealment, to judge by ear against its absence:  --ei on 0
            "CONCEAL" -> {
                val on = intent.getIntExtra("on", 1) != 0
                NativeBridge.nativeSetConcealment(on)
                Log.i(TAG, "underrun concealment: " + if (on) "ON" else "OFF")
            }
            "RESETPEAKS" -> NativeBridge.nativeResetPeaks()
            // How much slack the render thread gets:  --ei ms 12  (0: back to the burst-derived default)
            "FILL" -> {
                val ms = intent.getIntExtra("ms", 0)
                NativeBridge.nativeSetFillMs(ms)
                Log.i(TAG, "ring fill: " + if (ms > 0) "$ms ms" else "burst-derived")
            }
            // The render thread's scheduling, to experiment with:
            //   --ei nice -19 --es cpus 3-7 --ei fifo 1 --ei rescue 1 --ei rescueat 50 --es reapcpus 0-4
            // nice and cpus at once; fifo (the loop on a borrowed SCHED_FIFO AAudio thread) and rescue restart the stream.
            "RENDERPRIO" -> {
                val fifo = intent.getIntExtra("fifo", -1)
                val nice = intent.getIntExtra("nice", 0)
                val cpus = intent.getStringExtra("cpus").orEmpty()
                NativeBridge.nativeSetRenderScheduling(nice, cpus, fifo)
                Log.i(TAG, "render scheduling: nice $nice cpus '$cpus' fifo $fifo")
                val rescue = intent.getIntExtra("rescue", -1)
                if (rescue >= 0) NativeBridge.nativeSetRenderRescue(rescue > 0)
                if (intent.hasExtra("rescueat")) NativeBridge.nativeSetRenderRescueAt(intent.getIntExtra("rescueat", 50))
                intent.getStringExtra("reapcpus")?.let {
                    NativeBridge.nativeSetReapCpus(it)
                    Log.i(TAG, "usb reap thread cpus: '$it'")
                }
                if (fifo >= 0 || rescue >= 0) router.applyRouting("render thread")
            }
            // One stall of the USB reap loop (a starved IN queue):  --ei ms 600
            "USBSTALL" -> NativeBridge.nativeUsbTestStall(intent.getIntExtra("ms", 600))

            // ------------------------------------------------------ the routing
            //   --es audio USB_DEVICE --es midi USB_DEVICE --ei aaudio 0|1 --ei buffer 6 --ei bursts 2
            "ROUTE" -> router.debugRoute(
                audio = intent.getStringExtra("audio"), midi = intent.getStringExtra("midi"),
                aaudio = if (intent.hasExtra("aaudio")) intent.getIntExtra("aaudio", 1) != 0 else null,
                buffer = if (intent.hasExtra("buffer")) intent.getIntExtra("buffer", UsbBuffers.DEFAULT_MS) else null,
                bursts = if (intent.hasExtra("bursts")) intent.getIntExtra("bursts", 2) else null)
            // The output device by name; empty is automatic:  --es name Speaker
            "OUTPUT" -> router.debugOutput(intent.getStringExtra("name").orEmpty())
            //   --ez exclusive false
            "SHARING" -> router.debugSharing(intent.getBooleanExtra("exclusive", true))
            //   --ei hz 48000 --ei fmt 1   (0 and 0: the device's own)
            "RATE" -> router.debugRate(intent.getIntExtra("hz", 0), intent.getIntExtra("fmt", 0))
            // The audio path reset: a USB reset in a direct mode, a recycle on ALSA.
            "RESET" -> router.resetAudioPath()
            //   --ei level 1|2|3
            "REINIT" -> router.debugReinit(intent.getIntExtra("level", 1))
            "DEVHEALTH" -> router.debugDeviceHealth()
            // MIDI out over direct USB, and the SysEx that comes back; several messages
            // separated by '|' go out `gap` ms apart:  --es hex "F0 43 20 7F 1C 08 0E 7F 00 F7" --ei wait 1500
            "SYSEX" -> sysexExchange(intent.getStringExtra("hex").orEmpty(), intent.getIntExtra("gap", 20),
                intent.getIntExtra("wait", 1500))
            // The acoustic oracle: a click through the device, heard on the tablet's microphone.
            "MEASURE" -> measure()

            // -------------------------------------------------------- the stage
            // The stage, without the screen (1-based):  --ei song 2 --ei liveset 3
            "STAGE" -> stage(intent)
            // A device's own-sound path end to end (SongPlayer.deviceSelfTest):  --ei liveset 1
            "DEVICESELFTEST" -> player.deviceSelfTest(intent.getIntExtra("liveset", 1))
            "DEVICEPRELOADTEST" -> player.devicePreloadTest()
            //   --es delays 0,0,10,10,50,50
            "DEVICEWRITESELECT" -> player.deviceWriteSelectTest(
                (intent.getStringExtra("delays") ?: "0,0,0,0").split(',').mapNotNull { it.trim().toIntOrNull() })
            // The library goes, nothing else (instruments, SoundFonts and banks stay), and the
            // app ends; started again it seeds a fresh library.
            "RESET_LIBRARY" -> {
                File(context.filesDir, "library").deleteRecursively()
                // What the prototype screen kept its patches in, before 2026-11-11.
                File(context.filesDir, "patches").deleteRecursively()
                Log.i(TAG, "library reset; ending")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun stage(intent: Intent) {
        val song = intent.getIntExtra("song", 0)
        val ls = intent.getIntExtra("liveset", 0)
        if (song > 0) player.selectSong(song - 1)
        if (ls > 0) player.selectLiveSet(ls - 1)
        // A file in the app's own folder as the song's lead sheet:
        //   --es sheet leadsheet-test.pdf   --ei page +1 / -1 (a page-turner key)
        intent.getStringExtra("sheet")?.let { name ->
            runCatching { player.attachSheetForTest(File(context.getExternalFilesDir(null), name)) }
                .onFailure { Log.e("rigalong-stage", "sheet: $it") }
        }
        when (intent.getIntExtra("page", 0)) { 1 -> player.forwardKey(); -1 -> player.backKey() }
        // A controller event, as from a footswitch (I10.4): --ei cc 67 --ei value 127;
        // --es learn next|previous first to learn it.
        intent.getStringExtra("learn")?.let { player.learn(it) }
        intent.getStringExtra("clearcc")?.let { player.clearController(it) }
        if (intent.hasExtra("cc")) player.onController(intent.getIntExtra("cc", 0), intent.getIntExtra("value", 127))
        // A zip in the app's own folder, installed as an instrument (I8.8):  --es instrument test-sine.zip
        intent.getStringExtra("instrument")?.let { name ->
            Thread {
                val r = runCatching {
                    File(context.getExternalFilesDir(null), name).inputStream().use { content.importInstrumentZip(it, name) }
                }
                Log.i("rigalong-stage", "instrument import $name: $r; library ${content.library.map { it.id }}")
            }.start()
        }
    }

    /**
     * Sends MIDI over direct USB and logs every SysEx that comes back, on its
     * own thread: a bulk dump answer takes some 0,4 s and must not hold up the
     * UI. Replies are logged under `rigalong-sysex` with their time since the first
     * byte went out.
     */
    private fun sysexExchange(hex: String, gapMs: Int, waitMs: Int) {
        Thread({
            while (NativeBridge.nativeUsbSysexDrain() != null) { /* discard stale replies */ }
            val messages = hex.split('|').map { part ->
                part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    .map { it.toInt(16).toByte() }.toByteArray()
            }.filter { it.isNotEmpty() }
            val t0 = System.nanoTime()
            var bytes = 0
            for ((i, m) in messages.withIndex()) {
                val r = NativeBridge.nativeUsbMidiSend(m)
                if (r < 0) {
                    Log.e("rigalong-sysex", "send ${i + 1}/${messages.size} failed: $r")
                    return@Thread
                }
                bytes += r
                if (gapMs > 0 && i < messages.size - 1) Thread.sleep(gapMs.toLong())
            }
            val sentMs = (System.nanoTime() - t0) / 1e6
            Log.i("rigalong-sysex", "sent ${messages.size} msg(s), $bytes B in ${"%.1f".format(sentMs)} ms")
            val end = t0 + (sentMs * 1e6).toLong() + waitMs * 1_000_000L
            var count = 0
            while (System.nanoTime() < end) {
                val rx = NativeBridge.nativeUsbSysexDrain()
                if (rx == null) { Thread.sleep(2); continue }
                count++
                val at = (System.nanoTime() - t0) / 1e6
                Log.i("rigalong-sysex", "rx ${"%.1f".format(at)} ms ${rx.size} B " +
                    rx.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
            }
            Log.i("rigalong-sysex", "done: $count repl${if (count == 1) "y" else "ies"}")
        }, "rigalong-sysex").start()
    }
}
