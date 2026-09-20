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

    external fun nativeStart(deviceId: Int, bufferBursts: Int): Boolean
    external fun nativeStop()
    external fun nativeOpenMidi(device: MidiDevice, portIndex: Int): Boolean
    external fun nativeCloseMidi()
    /** Whether the native AMidi port is actually open. False after an unplug. */
    external fun nativeMidiOpen(): Boolean
    external fun nativePanic()
    external fun nativeResetPeaks()
    external fun nativeTestNote(note: Int, velocity: Int)
    external fun nativeGetStats(): FloatArray

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
