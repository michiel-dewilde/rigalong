package com.rigalong

import android.content.Context
import android.os.Build

/**
 * What the audio path says about itself (S19.3): every figure for Setup, the
 * stage's one line, and the dense line the diagnostics log keeps.
 */
class AudioStatus(private val context: Context, private val router: AudioRouter, private val content: EngineContent) {

    /** Every status figure as Setup shows it, rebuilt by [update]. */
    @Volatile var lines: List<String> = listOf("starting…")
        private set

    /**
     * The tablet's heat (REQUIREMENTS S16.2): Android's thermal status, and from
     * API 30 its headroom forecast (1.0 = throttling begins). Hot from
     * "moderate" on, which is where sustained performance starts to give.
     */
    private fun thermal(): Pair<String, Boolean> {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val status = pm.currentThermalStatus
        val name = when (status) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "cool"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "warm"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "HOT"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "VERY HOT"
            else -> "THROTTLING"
        }
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10).takeIf { !it.isNaN() } else null
        val text = "heat $name" + (headroom?.let { " (%.0f %% of the limit in 10 s)".format(it * 100) } ?: "")
        return text to (status >= android.os.PowerManager.THERMAL_STATUS_MODERATE)
    }

    // --- the audio status (S19.3) -------------------------------------------
    //
    // What the latch hunt left behind was every counter it ever needed, on one
    // screen, for both paths at once. Reworked 2026-10-11 so that each line is
    // a reading that means something on the path in use, says since when, and
    // carries its unit:
    //   * direct USB has no AAudio stream: no device id, no HAL timestamp
    //     ("presented"), no xruns -- those lines are AAudio's;
    //   * the callback's cpu and worst time read ~0 on either path: it only
    //     copies from the ring (FINDINGS.md section 3), so they are gone; xruns
    //     count among the dropouts;
    //   * `deficit` (wall time minus wire time) drifts with the device's
    //     crystal and folds every hold in, so it explains nothing a player can
    //     act on; it stays in the diagnostics log;
    //   * dropouts are counted by cause, for the last minute and since a named
    //     moment (AudioHealth), not as raw counters with unknown origins.

    private val audioHealth = AudioHealth()
    private var xrunBaseline = 0
    private var lastWarn = ""
    private var lastFault = false

    private val holdReasons = listOf("proof", "gap", "lost", "bad ask", "slow burst", "starve")

    /** An alt cycle slower than this means something in the way: measured 6,7 ms direct, ~50 behind hubs. */
    private val SLOW_ALT_MS = 20f

    private fun ms(v: Float, digits: Int = 1) = "%.${digits}f ms".format(v)

    /** Dropouts as "2 render, 1 USB" (causes with a count only), or null for none. */
    private fun causes(counts: LongArray): String? {
        val names = listOf("render", "USB", "USB error", "xrun")
        return counts.indices.filter { counts[it] > 0 }.joinToString(", ") { "${counts[it]} ${names[it]}" }.ifEmpty { null }
    }

    /** Every status figure for Setup: grouped, and only what applies to the path in use. */
    private fun statusReport(s: NativeBridge.Stats, warn: String, fault: Boolean): List<String> {
        val out = ArrayList<String>()
        fun line(label: String, text: String) = out.add(label.padEnd(12) + text)
        val (aMode, mMode) = router.modesInForce()
        val running = if (fault) warn else if (s.running) "running" else "stopped"
        val reserve = s.ringFillMs
        if (s.usbActive) {
            val wire = s.bufferFrames * 1000f / maxOf(1, s.sampleRate)
            line("output", "${router.audioName()}  ·  direct USB (${aMode.label})  ·  $running")
            line("latency", "${ms(s.latencyMs)}  = ${ms(wire)} on the wire + ${ms(reserve)} render reserve " +
                "(target ${ms(s.ringTargetMs)})")
        } else {
            line("output", "${router.audioDeviceLabel}  ·  ${if (s.usesAAudio) "AAudio" else "OpenSL ES"} " +
                "${s.sharingModeText.lowercase()} ${s.performanceModeText.lowercase()}  ·  $running")
            line("presented", when {
                !s.timestampOk -> "no timestamp from the HAL"
                s.stalledSeconds > 1.0f -> "FROZEN for ${"%.1f".format(s.stalledSeconds)} s"
                else -> "advancing (the stream is alive -- not proof of sound)"
            })
            line("latency", "${ms(s.latencyMs + reserve)}  = ${ms(s.latencyMs)} stream + ${ms(reserve)} render reserve " +
                "(target ${ms(s.ringTargetMs)})")
            line("stream", "${s.sampleRate} Hz  ·  burst ${s.burstFrames} fr (${ms(s.burstFrames * 1000f / maxOf(1, s.sampleRate), 2)})  ·  " +
                "buffer ${s.bufferFrames} fr (${ms(s.bufferFrames * 1000f / maxOf(1, s.sampleRate), 2)})")
        }
        if (router.routingStatus.isNotEmpty()) line("routing", router.routingStatus)

        // Dropouts: what someone may have heard, by cause.
        val minute = audioHealth.lastMinute().map { it.toLong() }.toLongArray()
        val since = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(audioHealth.since))
        line("dropouts", "${causes(minute)?.let { "$it in the last minute" } ?: "none in the last minute"}  ·  " +
            "since $since: ${causes(audioHealth.total()) ?: "none"}")

        if (s.usbActive) {
            // A hold is a short silence by design: the transport empties the
            // CP-88's FIFO after anything it could not prove, so it never
            // latches. Alt errors are holds that could not empty it.
            val by = s.usbHoldsBy.mapIndexedNotNull { i, n -> if (n > 0) "$n ${holdReasons[i]}" else null }
            line("usb", "${s.usbHolds} holds${if (by.isEmpty()) "" else " (${by.joinToString(", ")})"}  ·  " +
                "${s.usbErrors} wire errors  ·  ${s.usbAltErrors} alt errors (must stay 0)  ·  since the transport started")
            // What a hold costs: the alt cycle that empties the FIFO. ~7 ms with
            // the CP-88 on the tablet's own port, ~50 ms behind the dock's hubs
            // (LOG.md, 2026-10-05 and 2026-10-11).
            if (s.usbMaxAltMs > 0f) line("hold cost", "alt cycle ${ms(s.usbLastAltMs)}, worst ${ms(s.usbMaxAltMs)}: " +
                "most of each hold's silence" + if (s.usbMaxAltMs > SLOW_ALT_MS) "  ·  SLOW (on its own port: ~7 ms)" else "")
            line("reap loop", "worst gap ${ms(s.usbMaxGapMs)} since the last reset (alt cycles left out)  ·  " +
                "device clock ${"%.0f".format(s.usbFeedbackRate)} Hz")
        }
        // The render thread fills the ring a 128-frame block at a time; a block
        // that takes longer than it plays eats the reserve, and an empty ring
        // is a render dropout (concealed on the direct path).
        val block = 128 * 1000f / maxOf(1, s.sampleRate)
        line("render", "worst block ${ms(s.renderMaxMs, 2)} (it plays ${ms(block, 2)})  ·  " +
            "sfizz voices ${s.activeVoices} / ${s.voiceLimit}" +
            NativeBridge.nativeRenderRescues().let { if (it > 0) "  ·  rescued $it blocks" else "" })
        val bars = (s.outputPeak.coerceIn(0f, 1f) * 24f).toInt()
        line("level", "${"#".repeat(bars).padEnd(24, '.')} ${"%.3f".format(s.outputPeak)}" +
            if (s.outputPeak >= 0.999f) "  CLIPPING" else "")
        val midiOpen = NativeBridge.nativeMidiOpen()
        line("midi", (if (midiOpen) "connected" else "NOT CONNECTED") + " (${mMode.label})  ·  " +
            (if (!midiOpen && router.midiStatus.isNotEmpty()) "${router.midiStatus}  ·  " else "") +
            "${s.midiMessages} messages in  ·  ${NativeBridge.nativeMidiClockTicks()} clock ticks out")
        line("heat", thermal().first.removePrefix("heat "))
        if (s.loading || content.warming) line("loading", if (content.warming) "${content.warmedMb} MB read into the page cache…" else "an instrument…")
        line("probe", if (s.usbActive && s.probeState == 0) "unavailable on the direct path (no input stream to listen on)"
            else s.probeText + if (s.probeState != 0)
            "  ·  input ${"%.4f".format(s.inputPeak)}, ${ms(s.inputLatencyMs)}" else "")
        return out
    }

    /** The stage's one line: the path and its latency when all is well, else what is wrong. */
    fun stageHealth(): String {
        val s = runCatching { NativeBridge.stats() }.getOrNull() ?: return ""
        val (heat, hot) = thermal()
        val warm = !heat.startsWith("heat cool")
        val minute = audioHealth.lastMinute()
        val drops = minute.sum()
        return when {
            lastFault -> "! $lastWarn"
            drops > 0 -> "! " + context.resources.getQuantityString(R.plurals.dropouts_recent, drops, drops) +
                " (" + causes(minute.map { it.toLong() }.toLongArray()) + ")"
            hot -> "! $heat"
            else -> {
                val total = if (s.usbActive) s.latencyMs else s.latencyMs + s.ringFillMs
                val path = if (s.usbActive) context.getString(R.string.health_direct_usb) else context.getString(R.string.health_aaudio)
                context.getString(R.string.health_ok, router.audioName(), path, "%.1f".format(total)) +
                    if (warm) "  ·  $heat" else ""
            }
        }
    }

    /** One dense line for the diagnostics log: every figure, including those the screen leaves out. */
    fun healthDetail(): String {
        val s = runCatching { NativeBridge.stats() }.getOrNull() ?: return ""
        val t = audioHealth.total()
        return "warn=$lastWarn running=${s.running} usb=${s.usbActive} latency=${"%.1f".format(s.latencyMs)} " +
            "reserve=${"%.1f".format(s.ringFillMs)} target=${"%.1f".format(s.ringTargetMs)} " +
            "drops[render,hold,usbErr,xrun]=${t.joinToString(",")} holds=${s.usbHolds} by=${s.usbHoldsBy} " +
            "altErr=${s.usbAltErrors} errors=${s.usbErrors} deficit=${"%.1f".format(s.usbDeficitMs)} " +
            "maxGap=${"%.1f".format(s.usbMaxGapMs)} alt=${"%.1f".format(s.usbLastAltMs)}/${"%.1f".format(s.usbMaxAltMs)} clock=${"%.1f".format(s.usbFeedbackRate)} " +
            "renderPeak=${"%.2f".format(s.renderMaxMs)} voices=${s.activeVoices} xruns=${s.xRuns} " +
            "stalled=${"%.1f".format(s.stalledSeconds)} peak=${"%.3f".format(s.outputPeak)} ${thermal().first}"
    }

    /** Counting starts again: the dropout totals, and the peaks the engine keeps (render, reap gap). */
    fun resetCounts() {
        NativeBridge.nativeResetPeaks()
        xrunBaseline = NativeBridge.stats().xRuns
        audioHealth.reset()
    }

    fun update() {
        val s = NativeBridge.stats()

        val xrunsNow = s.xRuns - xrunBaseline

        // Whether we are on the CP-88, not merely whether audio is running.
        // AAudio falls back to the built-in speaker without saying so, and that
        // failure is otherwise invisible: the meters move and it sounds fine in
        // a quiet room, while a stage hears nothing at all.
        val output = router.lastChosenOutputId
        val onChosen = output != NativeBridge.DEVICE_UNSPECIFIED && s.deviceId == output

        val warn = when {
            router.routingBusy -> "SWITCHING — ${router.routingStatus.ifEmpty { "routing" }}"
            s.usbActive && !s.usbRunning -> "DIRECT USB DOWN — re-routing"
            !s.running -> "STREAM DOWN"
            output == NativeBridge.DEVICE_UNSPECIFIED -> "NO USB OUTPUT — built-in speaker"
            !onChosen -> "WRONG DEVICE (${s.deviceId}) — recovering"
            // Above xruns deliberately: this one means NO SOUND AT ALL while
            // everything else on screen still looks alive, which is precisely
            // why it went undiagnosed for a whole session.
            s.audioStalled -> "AUDIO STALLED ${"%.0f".format(s.stalledSeconds)}s — replug"
            // Direct USB has no sharing mode; "not exclusive" would be a lie.
            s.usbActive -> "direct USB"
            s.sharingMode != 0 -> "not exclusive"
            xrunsNow > 0 -> "$xrunsNow xruns"
            else -> "clean"
        }
        // The states above "direct USB": something is wrong with the path itself.
        val fault = router.routingBusy || (s.usbActive && !s.usbRunning) || !s.running ||
            output == NativeBridge.DEVICE_UNSPECIFIED || !onChosen || s.audioStalled

        audioHealth.update(s)
        lastWarn = warn
        lastFault = fault
        lines = statusReport(s, warn, fault)
    }
}
