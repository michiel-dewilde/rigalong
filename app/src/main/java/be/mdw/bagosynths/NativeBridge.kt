package be.mdw.bagosynths

import android.media.midi.MidiDevice

/**
 * Thin JNI surface. Nothing here blocks, and nothing here is called from the
 * audio thread -- the audio thread lives entirely in C++.
 */
object NativeBridge {

    init {
        System.loadLibrary("bagosynths")
    }

    const val DEVICE_UNSPECIFIED = 0

    /** Reported as the device id while the direct-USB sink is live. */
    const val DEVICE_USB_DIRECT = -2

    external fun nativeStart(deviceId: Int, bufferBursts: Int): Boolean
    external fun nativeStop()
    external fun nativeOpenMidi(device: MidiDevice, portIndex: Int): Boolean
    external fun nativeCloseMidi()
    /** Whether the native AMidi port is actually open. False after an unplug. */
    external fun nativeMidiOpen(): Boolean
    external fun nativePanic()

    /** Underrun concealment on/off, for judging it by ear against its absence. */
    external fun nativeSetConcealment(on: Boolean)

    /** CLEAR_FEATURE(ENDPOINT_HALT) on both streaming endpoints. */
    external fun nativeUsbClearHalt(fd: Int, raw: ByteArray): Boolean

    /** TEST ONLY: emit N genuinely zero-length OUT packets, bypassing the invariant. */
    external fun nativeInjectRawZero(count: Int)

    /** The next [count] OUT packets carry exactly [frames] frames. TEST ONLY. */
    external fun nativeInjectSize(count: Int, frames: Int)

    /** The next [count] OUT packets carry what was asked plus [delta]. TEST ONLY. */
    external fun nativeInjectBias(count: Int, delta: Int)

    /** The next [count] OUT packets skip the feedback queue. TEST ONLY. */
    external fun nativeInjectStarve(count: Int)

    /** The size FIFO's geometry and its full-FIFO policy; -1 leaves a field alone. */
    external fun nativeSetFifo(len: Int, preRoll: Int, resync: Int, inPkts: Int,
                               outPkts: Int, midiUrbs: Int, gapUs: Int)

    /** Whether an OBSERVED-empty OUT queue also opens a resync gap. Live. */
    external fun nativeSetEmptyResync(on: Int)

    /** Replaces the USB payload with a tone, load and sizes unchanged. TEST ONLY. */
    external fun nativeSetUsbTone(hz: Float)

    /** Feedback capture depth in ms, applied at the next transport start. */
    external fun nativeSetInDepthMs(ms: Int)

    /** Writes the per-packet wire trace to [path]. TEST ONLY. */
    external fun nativeDumpUsbTrace(path: String)

    /** Delays OUT resubmission only, [count] times, [us] each. TEST ONLY. */
    external fun nativeHoldOut(us: Int, count: Int, every: Int)

    external fun nativeResetPeaks()
    external fun nativeTestNote(note: Int, velocity: Int)
    external fun nativeGetStats(): FloatArray

    /**
     * EXCLUSIVE (true, the default) or SHARED for the NEXT stream open.
     *
     * Exists to make the sharing mode testable on the rig rather than
     * arguable: EXCLUSIVE + MMAP is the one unusual thing this project asks
     * of the audio stack, and it is a suspect whenever the USB path misbehaves.
     */
    external fun nativeSetExclusive(exclusive: Boolean)

    /** The rate the chosen device declares, from AudioDeviceInfo. 0 = let AAudio pick. */
    /** Render-ring depth in ms; 0 restores the burst-derived default. */
    external fun nativeSetFillMs(ms: Int)

    external fun nativeSetPreferredRate(rate: Int)

    /** Oboe AudioFormat: 1 = I16, 2 = Float, 3 = I24, 4 = I32. 0 = let Oboe pick. */
    external fun nativeSetPreferredFormat(format: Int)

    /** Opens an input stream on the CP-88 for the loopback probe. Needs RECORD_AUDIO. */
    external fun nativeEnableInput(deviceId: Int): Boolean
    external fun nativeStartLatencyProbe()
    external fun nativeStartNoteProbe()
    external fun nativeStressChord(count: Int)
    external fun nativeLogProfile()

    /** Records the mic plus note-on markers, for offline analysis. */
    external fun nativeStartRecording(seconds: Int): Boolean
    external fun nativeWriteRecording(wavPath: String, marksPath: String): Boolean
    external fun nativeRecordedSeconds(): Float
    external fun nativeIsRecording(): Boolean

    /** Loads an SFZ file on the background loader thread. Returns immediately. */
    external fun nativeLoadInstrument(id: String, path: String)
    external fun nativeLoadedInstrument(): String

    /**
     * The tonewheel organ. Indices are [Organ]'s constants and values are in
     * natural units -- drawbars 0..8, switches 0/1, the Leslie 0/1/2, the rest
     * 0..1. Setting is safe from the UI thread: the node queues the change and
     * applies it at the top of the next audio block.
     */
    external fun nativeSetOrganParam(index: Int, value: Float)
    external fun nativeGetOrganParam(index: Int): Float
    external fun nativeOrganParamName(index: Int): String
    external fun nativeOrganPreset(index: Int, manual: Int)
    external fun nativeOrganPresetNames(): Array<String>

    /** The sampler's level, so organ and piano can be balanced against each other. */
    external fun nativeSetSamplerLevel(value: Float)
    external fun nativeGetSamplerLevel(): Float

    // ------------------------------------------------------------- surge xt
    //
    // Bringing Surge up scans 641 patches, so it happens on a loader thread and
    // `nativeSurgeReady` says when the library can be asked about itself. Until
    // then the node renders silence and the rest of the rig is unaffected.
    external fun nativeInitSurge(dataPath: String, homePath: String)
    external fun nativeSurgeReady(): Boolean
    external fun nativeSurgeBusy(): Boolean

    external fun nativeSurgePatchCount(): Int
    /** "Category/Name" — the stable address a saved patch stores. */
    external fun nativeSurgePatchName(index: Int): String
    external fun nativeSurgePatchShortName(index: Int): String
    external fun nativeSurgeCurrentPatch(): Int
    external fun nativeSurgeCurrentPatchName(): String

    /** Both are asynchronous: loading a patch opens and parses a file. */
    external fun nativeSurgeLoadPatch(index: Int)
    external fun nativeSurgeLoadPatchNamed(name: String)

    /** What the patch author called this macro, or "" if they left it unnamed. */
    external fun nativeSurgeMacroName(index: Int): String

    external fun nativeSetSurgeParam(index: Int, value: Float)
    external fun nativeGetSurgeParam(index: Int): Float

    // --------------------------------------------------------------- dexed
    //
    // A DX7 voice is addressed by "Bank/NAME". Bank loading is file IO and runs
    // on the loader thread; selecting a voice is a 128-byte unpack and is cheap.
    external fun nativeLoadDexedBanks(dirs: Array<String>)
    external fun nativeDexedReady(): Boolean
    external fun nativeDexedVoiceCount(): Int
    external fun nativeDexedVoiceName(index: Int): String
    external fun nativeDexedCurrentVoice(): Int
    external fun nativeDexedCurrentVoiceName(): String
    external fun nativeDexedSelectVoice(index: Int)
    external fun nativeDexedSelectVoiceNamed(name: String)
    external fun nativeSetDexedParam(index: Int, value: Float)
    external fun nativeGetDexedParam(index: Int): Float
    external fun nativeDexedEngineNames(): Array<String>

    // --- Plaits -------------------------------------------------------------
    // Sixteen synthesis models, already compiled into the binary because Surge
    // vendors them. No library to scan, so there is nothing async here.
    external fun nativeSetPlaitsParam(index: Int, value: Float)
    external fun nativeGetPlaitsParam(index: Int): Float
    external fun nativePlaitsEngineNames(): Array<String>
    /** What HARMONICS, TIMBRE, MORPH and AUX do in this model, in that order. */
    external fun nativePlaitsParamLabels(engine: Int): Array<String>
    external fun nativePlaitsActiveVoices(): Int

    // ----------------------------------------------------------- fluidsynth
    //
    // SF2 and SF3 SoundFonts. Fonts live beside the instrument library, not in
    // the APK -- they are content with their own licences. Both the font and
    // the preset are addressed by NAME, which is what a saved patch stores.
    external fun nativeInitFluid(dirs: Array<String>)
    external fun nativeFluidReady(): Boolean
    external fun nativeFluidBusy(): Boolean

    external fun nativeFluidFontCount(): Int
    external fun nativeFluidFontName(index: Int): String
    external fun nativeFluidCurrentFont(): String
    /** Both are asynchronous: a font is megabytes of samples, decoded for SF3. */
    external fun nativeFluidLoadFont(index: Int)
    external fun nativeFluidLoadFontNamed(name: String)

    external fun nativeFluidPresetCount(): Int
    /** "bank:prog NAME" — the stable address a saved patch stores. */
    external fun nativeFluidPresetName(index: Int): String
    external fun nativeFluidCurrentPreset(): Int
    external fun nativeFluidCurrentPresetName(): String
    external fun nativeFluidSelectPreset(index: Int)
    external fun nativeFluidSelectPresetNamed(name: String)

    external fun nativeSetFluidParam(index: Int, value: Float)
    external fun nativeGetFluidParam(index: Int): Float

    /**
     * The graph, generically: enough to walk it without knowing what is in it.
     *
     * Saving a patch needs exactly this, and so will the editor — which is why
     * it is a general surface rather than a save-the-organ function. Parameters
     * are addressed by index here and by NAME in the file: an index is what
     * changes when someone inserts an enum value, so a file must not contain one.
     */
    external fun nativeNodeCount(): Int
    external fun nativeNodeType(index: Int): String
    external fun nativeNodeParamCount(index: Int): Int
    external fun nativeNodeParamName(index: Int, param: Int): String
    external fun nativeGetNodeParam(index: Int, param: Int): Float
    external fun nativeSetNodeParam(index: Int, param: Int, value: Float)

    /** Four ints per edge: fromNode, fromPin, toNode, toPin — nodes as ordinals. */
    external fun nativeEdges(): IntArray

    // ------------------------------------------------------------ direct USB
    //
    // The two direct modes, where this process -- not snd-usb-audio -- drives
    // the device. Kotlin owns the connection (permission, claim, release);
    // native owns everything that needs the descriptors, so the parser that
    // plans the claim is the one that opens the endpoints.

    /** Parses getRawDescriptors(); see [UsbPlan] for the layout. */
    external fun nativeUsbPlan(raw: ByteArray, preferUmp: Boolean): IntArray

    /**
     * Rate, then altsetting, then URBs. The interfaces in the plan must already
     * be claimed on [fd]. Null on success, otherwise why not.
     */
    external fun nativeStartUsbAudio(fd: Int, raw: ByteArray, bufferMs: Int, preferredRate: Int): String?

    /** Stops the direct output and returns the streaming interfaces to alt 0. */
    external fun nativeStopUsbAudio(fd: Int, raw: ByteArray)

    /** `port` is the cable (MIDI 1.0) or group (MIDI 2.0). Null on success. */
    external fun nativeStartUsbMidi(fd: Int, raw: ByteArray, preferUmp: Boolean, port: Int): String?

    /** Builds the graph if no sink has, so the instrument loaders have nodes. */
    external fun nativePrepareGraph()

    /**
     * The master volume in dB, applied at the very end of the chain -- after the
     * limiter, before the single conversion to the device's format. 0 dB is
     * unity; the range is [MASTER_MIN_DB, MASTER_MAX_DB] and the bottom is
     * silence. Not part of a patch: it belongs to the room, not the sound.
     */
    external fun nativeSetMasterDb(db: Float)
    external fun nativeGetMasterDb(): Float

    const val MASTER_MIN_DB = -40f
    const val MASTER_MAX_DB = 12f

    /** GET_INTERFACE: what the DEVICE says is active; -1 if it will not answer. */
    external fun nativeUsbAltSetting(fd: Int, iface: Int): Int

    /** USBDEVFS_CONNECT: give a released interface back to its kernel driver. */
    external fun nativeUsbReconnectKernel(fd: Int, iface: Int): Boolean

    /** USBDEVFS_RESET: the fallback when reconnecting does not bring ALSA back. */
    external fun nativeUsbReset(fd: Int): Boolean

    /**
     * Asks the device itself how it is: [halted, clockValid, rate, alt].
     * -1 means the control transfer failed. See the native side for why this
     * exists at all -- nothing in the data path can see THE LATCH.
     */
    external fun nativeUsbHealthProbe(fd: Int, raw: ByteArray): IntArray

    /** snd-usb-audio's card short name, by the rule tested in engine/. */
    external fun nativeAlsaCardName(product: String?, vid: Int, pid: Int): String

    /** snd-usb-audio's MIDI port name: "card jack", or "card MIDI n" without one. */
    external fun nativeAlsaMidiPortName(card: String, jack: String?, index: Int): String

    /** Decoded [nativeUsbPlan]. Indices must match native_bridge.cpp. */
    class UsbPlan(v: IntArray) {
        val audioOk = v.isNotEmpty() && (v[0] and 1) != 0
        val midiOk = v.isNotEmpty() && (v[0] and 2) != 0
        val midiUmp = v.isNotEmpty() && (v[0] and 4) != 0
        val configValue = v.getOrElse(1) { -1 }
        val audioInterface = v.getOrElse(2) { -1 }
        val audioAlt = v.getOrElse(3) { -1 }
        /** 0 none, 1 implicit (the CP-88), 2 explicit. */
        val feedback = v.getOrElse(4) { 0 }
        val channels = v.getOrElse(7) { 0 }
        val subslotBytes = v.getOrElse(8) { 0 }
        val bitResolution = v.getOrElse(9) { 0 }
        val midiInterface = v.getOrElse(10) { -1 }
        val midiPortCount = v.getOrElse(12) { 0 }.coerceAtLeast(0)

        /**
         * The AudioControl interface, or -1.
         *
         * On UAC2 it carries the clock, so audio has to claim it too — see
         * planAudioOut. It is named separately because it is the one claimed
         * interface that is NOT streaming: waiting for the kernel to stop
         * streaming it would wait for something that never happens.
         */
        val controlInterface = v.getOrElse(13) { -1 }

        val portStringIndex: IntArray
        /** What "USB interface" audio claims: the audio function's interfaces. */
        val audioInterfaces: List<Int>
        /** What "USB interface" MIDI claims: the MIDI streaming interface. */
        val midiInterfaces: List<Int>
        /** What "USB device" claims: every interface of the configuration. */
        val allInterfaces: List<Int>

        /** The audio interfaces that actually carry samples. */
        val streamingInterfaces: List<Int> get() = audioInterfaces.filter { it != controlInterface }

        init {
            var i = 14
            portStringIndex = IntArray(midiPortCount) { v.getOrElse(i + it) { 0 } }
            i += midiPortCount
            fun list(): List<Int> {
                val n = v.getOrElse(i) { 0 }.coerceAtLeast(0)
                val out = (0 until n).map { v.getOrElse(i + 1 + it) { -1 } }
                i += 1 + n
                return out
            }
            audioInterfaces = list()
            midiInterfaces = list()
            allInterfaces = list()
        }
    }

    /** Decoded view of the stats array. Indices must match native_bridge.cpp. */
    class Stats(private val v: FloatArray) {
        val latencyMs get() = v[0]
        val cpuLoadPercent get() = v[1]
        val callbackMaxMs get() = v[2]
        val xRuns get() = v[3].toInt()
        val burstFrames get() = v[4].toInt()
        val bufferFrames get() = v[5].toInt()
        val sampleRate get() = v[6].toInt()
        val activeVoices get() = v[7].toInt()
        val sharingMode get() = v[8].toInt()
        val performanceMode get() = v[9].toInt()
        val usesAAudio get() = v[10].toInt() == 1
        val midiMessages get() = v[11].toLong()
        val running get() = v[12].toInt() == 1
        val ringUnderruns get() = v[18].toInt()
        val ringFillMs get() = v[19]
        val renderMaxMs get() = v[20]
        val ringTargetMs get() = v[21]

        /** Peak of what is actually leaving the box, 0..1, decayed over ~60 ms. */
        val outputPeak get() = v[22]

        /**
         * The device the stream is REALLY on.
         *
         * Not the one that was asked for: AAudio silently falls back to the
         * built-in speaker when a requested device has gone away, so this is the
         * only way to notice that a recovery landed somewhere useless.
         */
        val deviceId get() = v[23].toInt()

        /**
         * How long the HARDWARE has presented nothing, in seconds. 0 when
         * healthy.
         *
         * This is the number that separates "the app stopped making audio" from
         * "the audio stopped leaving the box", because it comes from the HAL's
         * own timestamp rather than from anything this process does. Read it
         * together with [outputPeak]: signal present AND this counting up is
         * the dropout that needs a replug, and nothing else looks like that.
         *
         * Seconds rather than a frame position on purpose -- an int64 of frames
         * stops being exact as a float after about six minutes at 44100 Hz,
         * which is exactly the timescale the fault lives on.
         */
        val stalledSeconds get() = v[24]

        /** 0 when the HAL gave no timestamp, so absent cannot pass for healthy. */
        val timestampOk get() = v[25].toInt() == 1

        /** oboe::StreamState. 2 = Open, 4 = Started, 6 = Paused, 8 = Stopped. */
        val streamState get() = v[26].toInt()

        // --- direct USB -------------------------------------------------------
        val usbActive get() = v.getOrElse(27) { 0f }.toInt() == 1
        val usbRunning get() = v.getOrElse(28) { 0f }.toInt() == 1
        /**
         * Wall time minus the audio the controller says it put on the wire. It
         * settles at a constant and stays; GROWTH is a hole the device heard as
         * silence, however healthy everything else reads.
         */
        val usbDeficitMs get() = v.getOrElse(29) { 0f }
        val usbLate get() = v.getOrElse(30) { 0f }.toInt()
        val usbStarved get() = v.getOrElse(31) { 0f }.toInt()
        val usbErrors get() = v.getOrElse(32) { 0f }.toInt()
        val usbFeedbackRate get() = v.getOrElse(33) { 0f }
        val usbMaxGapMs get() = v.getOrElse(34) { 0f }

        /** The unambiguous combination: making signal, and nothing coming out. */
        val audioStalled get() = timestampOk && stalledSeconds > 1.0f && outputPeak > 0.001f
        val probeState get() = v[13].toInt()
        val roundTripMs get() = v[14]
        val inputPeak get() = v[15]
        val inputLatencyMs get() = v[16]
        val loading get() = v[17].toInt() == 1
        /** Kept in step with SfizzNode::prepare(). */
        val voiceLimit get() = 64

        val probeText get() = when (probeState) {
            0 -> "unavailable (no input stream)"
            1 -> "ready"
            2 -> "arming…"
            3 -> "listening…"
            4 -> "ROUND TRIP ${"%.2f".format(roundTripMs)} ms"
            5 -> "FAILED — nothing came back"
            6 -> "ARMED — play a note on the piano"
            else -> "?"
        }

        // Oboe's SharingMode: 0 = Exclusive, 1 = Shared.
        val sharingModeText get() = when (sharingMode) {
            0 -> "EXCLUSIVE"
            1 -> "SHARED"
            else -> "?"
        }

        // Oboe's PerformanceMode: 10 = None, 11 = PowerSaving, 12 = LowLatency.
        val performanceModeText get() = when (performanceMode) {
            10 -> "NONE"
            11 -> "POWER_SAVING"
            12 -> "LOW_LATENCY"
            else -> "?"
        }
    }

    fun stats() = Stats(nativeGetStats())
}
