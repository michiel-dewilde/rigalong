package com.rigalong.play

/** Routing by the devices' own names; null name = automatic. Modes are IoMode names. */
data class RoutingSpec(val audioName: String?, val audioMode: String, val midiName: String?, val midiMode: String,
                       val bufferMs: Int)

/** What the UI needs from the activity that owns the engine and the routing. */
interface EngineHost {
    /** Installed SFZ instruments: id to display name. */
    fun instruments(): List<Pair<String, String>>
    fun selectInstrument(id: String)
    /** The SFZ file of installed instrument [id], for an added sfizz instance; null if not installed. */
    fun instrumentPath(id: String): String?
    fun currentInstrument(): String?
    /** The stage's one line: the path and its latency when all is well, else what is wrong ("! ..."). */
    fun healthLine(): String
    /** Every figure on one line, the ones the screen leaves out included: for the diagnostics log. */
    fun healthDetail(): String
    /** Every status figure, for the setup view (S19.3), grouped and for the path in use. */
    fun statusLines(): List<String>
    /** Dropout counts and the engine's peaks start again from now. */
    fun resetCounts()
    fun routing(): Routing
    fun cycleAudioDevice()
    fun cycleMidiDevice()
    /** A keyboard the app would rather drive directly over USB and has not asked about yet: its name; null when there is nothing to ask. */
    fun directOffer(): String?
    fun answerDirectOffer(use: Boolean)
    /** An IoMode name: ALSA, USB_INTERFACE, USB_DEVICE, NONE. */
    fun setAudioMode(mode: String)
    fun setMidiMode(mode: String)
    fun setUsbBufferMs(ms: Int)
    /**
     * Installs an SFZ instrument pack from a zip (I8.8: no adb), into the app's
     * instruments folder, and rescans. Blocking: call off the UI thread.
     * Returns the new instrument's id.
     */
    fun importInstrumentZip(input: java.io.InputStream, fileName: String): String
    /**
     * An engine's own file ([com.rigalong.model.EngineFiles]: a SoundFont,
     * a DX7 bank, a Surge XT patch) installed where its engine looks, and the
     * engines told to look again. Returns the name it got, or null if exactly
     * that file was installed already. Call off the UI thread.
     */
    fun installEngineFile(kind: com.rigalong.model.EngineFiles.Kind, fileName: String, bytes: ByteArray): String?
    /**
     * [patch] as its engine's own file ([com.rigalong.model.PatchExport]),
     * called [name]: the file name and its bytes, or null where the engine's
     * sound is not one file. Reads the engine's content: call off the UI thread.
     */
    fun exportPatch(patch: com.rigalong.model.Patch, name: String): Pair<String, ByteArray>?
    /** The routing in force, by the devices' own names (for environments). */
    fun routingSpec(): RoutingSpec
    fun applyRoutingSpec(spec: RoutingSpec)
    /** The names of every audio output and MIDI port present now (S3.10). */
    fun availableDevices(): Set<String>
    /** MEASURE (S20.3): a click through the device, heard on the tablet's mic; the result is in [statusLines]. */
    fun measureLatency()
    /** The master volume in dB, a global control: never recalled by a live set (I6.8). */
    fun masterDb(): Float
    fun setMasterDb(db: Float)
    /** Do Not Disturb while the app is in front (S17.2); asking for access first if needed. */
    fun stageDnd(): Boolean
    fun setStageDnd(on: Boolean)
}

/** The routing the activity has in force, for the setup view. */
data class Routing(
    val audioDevice: String, val audioMode: String,
    val midiDevice: String, val midiMode: String,
    val usbBufferMs: Int, val usbBufferChoices: List<Int>,
    /** A MIDI input is there to play from (a device, its use not switched off): no on-screen keys needed. */
    val midiIn: Boolean = false,
)
