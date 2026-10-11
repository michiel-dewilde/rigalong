@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package be.mdw.bagosynths.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.serializer

/*
 * The object types of schema version 1 (docs/FORMAT.md section 6).
 *
 * Every member that can be absent has a default, and defaults are never
 * written, so one content has exactly one encoding. Parameter values are
 * float32 stored as Double through [f32], so a value read back from an engine
 * hashes the same as the value that was stored (FORMAT.md section 2).
 *
 * Names of nodes, wires and parameters are the song's own vocabulary; a
 * parameter is addressed as "node.param", with the param part being the
 * engine's stable string id (PLAN.md D6).
 */

/** A float32 as the canonical Double: its shortest decimal, read back. */
fun f32(x: Float): Double = Canonical.shortestFloat32(x).toDouble()

/** A reference as a member of a typed object (FORMAT.md section 3). */
@Serializable
data class RefJ(
    @SerialName("@ref") val ref: String,
    @SerialName("@type") val type: String? = null,
) {
    fun toRef() = Ref(Digest.parse(ref), type)

    companion object {
        fun of(r: Ref) = RefJ(r.digest.toString(), r.type)
        fun of(d: Digest, type: String? = null) = RefJ(d.toString(), type)
    }
}

// ------------------------------------------------------------------ patches

@Serializable
data class Patch(
    val engine: String,
    val params: Map<String, Double> = emptyMap(),
    /** Engine-specific bytes: a Surge patch, a DX7 voice, an instrument pack. */
    val state: RefJ? = null,
    /** Engine-specific choice within [state], e.g. a SoundFont preset number. */
    val program: Int? = null,
    /**
     * Installed content by name, until packs are in the library (PLAN.md M9):
     * an SFZ instrument id, a Surge "Category/Name", a DX7 voice name, a
     * SoundFont file name, an organ registration, a Plaits model.
     */
    val preset: String? = null,
    val engineVersion: String? = null,
    @SerialName("@type") @EncodeDefault val type: String = TYPE,
) {
    companion object { const val TYPE = "bagosynths.patch/1" }
}

// --------------------------------------------------------------------- songs

/** One node of a song's graph: a synth instance, an effect, a MIDI processor or a port. */
@Serializable
data class Node(
    /** Factory type name: "sfizz", "hammond", "surge", "reverb", "midi.keyrange", "port.midiIn", ... */
    val kind: String,
    /** For a port node: the environment port it stands for. */
    val port: String? = null,
    val patch: RefJ? = null,
    /** Song values of this node's parameters, on top of the patch. */
    val params: Map<String, Double> = emptyMap(),
    /** Render with this port's island when the environment allows (I3.21). */
    val island: String? = null,
)

/** A wire, `node:pin` to `node:pin`. Carries MIDI or audio, never both. */
@Serializable
data class Wire(val from: String, val to: String)

@Serializable
enum class Recall {
    @SerialName("recall") RECALL,
    @SerialName("keep") KEEP,
    @SerialName("resume") RESUME,
}

@Serializable
data class MidiBinding(
    val port: String,
    val channel: Int,                 // 1-16
    val cc: Int? = null,
    val note: Int? = null,
    val takeover: String = "pickup",  // pickup | jump | scale (I6.6)
)

/** A control the stage can show and MIDI can drive (I6.1). */
@Serializable
data class Control(
    val id: String,
    val label: String,
    /** "node.param", or "cp88.<parameter>" for the CP-88's own. */
    val target: String,
    val widget: String = "fader",     // fader | knob | drawbar | toggle | button | selector
    val min: Double = 0.0,
    val max: Double = 1.0,
    val midi: MidiBinding? = null,
)

@Serializable
data class StageCell(val control: String, val x: Int, val y: Int, val w: Int = 1, val h: Int = 1)

@Serializable
data class StageLayout(val columns: Int = 8, val cells: List<StageCell> = emptyList())

@Serializable
data class Tempo(val bpm: Double, val beatsPerBar: Int = 4, val countInBars: Int? = null)

@Serializable
data class Song(
    val nodes: Map<String, Node> = emptyMap(),
    val wires: Map<String, Wire> = emptyMap(),
    /** Recall mode per "node.param"; absent means [Recall.RECALL]. */
    val recall: Map<String, Recall> = emptyMap(),
    val liveSets: List<RefJ> = emptyList(),
    val controls: List<Control> = emptyList(),
    val stage: StageLayout? = null,
    /** Incoming selection ("pc:<ch>:<n>", "cp88:<page>:<set>", "key:<name>") -> live set index (I10.4). */
    val switching: Map<String, Int> = emptyMap(),
    val tempo: Tempo? = null,
    val leadSheet: RefJ? = null,
    @SerialName("@type") @EncodeDefault val type: String = TYPE,
) {
    companion object { const val TYPE = "bagosynths.song/1" }
}

@Serializable
data class WireState(val on: Boolean = true, val gain: Double = 1.0)

@Serializable
data class MidiSend(val port: String, val hex: String)

/** What a live set does to the CP-88 (I4.7). */
@Serializable
data class Cp88Action(
    val mode: String,                 // leave | select | own
    val page: Int? = null,
    val set: Int? = null,
    val sound: RefJ? = null,
)

@Serializable
data class LiveSet(
    val name: String,
    val enabled: Map<String, Boolean> = emptyMap(),
    val wires: Map<String, WireState> = emptyMap(),
    /** "node.param" -> value, on top of the song value (I6.3). */
    val overrides: Map<String, Double> = emptyMap(),
    val recall: Map<String, Recall> = emptyMap(),
    val transitionMs: Map<String, Double> = emptyMap(),
    val onSelect: List<MidiSend> = emptyList(),
    val cp88: Cp88Action? = null,
    /** Stage cells shown in this live set; empty means all. */
    val visible: List<String> = emptyList(),
    val leadSheetPage: Int? = null,
    @SerialName("@type") @EncodeDefault val type: String = TYPE,
) {
    companion object { const val TYPE = "bagosynths.liveset/1" }
}

@Serializable
data class Setlist(
    val songs: List<RefJ> = emptyList(),
    @SerialName("@type") @EncodeDefault val type: String = TYPE,
) {
    companion object { const val TYPE = "bagosynths.setlist/1" }
}

// --------------------------------------------------------------- environment

@Serializable
enum class PortKind {
    @SerialName("audioIn") AUDIO_IN,
    @SerialName("audioOut") AUDIO_OUT,
    @SerialName("midiIn") MIDI_IN,
    @SerialName("midiOut") MIDI_OUT,
}

@Serializable
data class PortDef(val name: String, val kind: PortKind, val channels: Int = 2)

@Serializable
data class DeviceMatch(val vendor: Int? = null, val product: Int? = null, val name: String? = null)

@Serializable
data class Binding(
    val mode: String,                 // alsa | usbInterface | usbDevice | external
    val device: DeviceMatch,
    /** Device channels used, 0-based, in port channel order. Empty: the first N. */
    val channels: List<Int> = emptyList(),
    val rate: Int? = null,
    val bits: Int? = null,
    val bufferMs: Double? = null,
    val exclusive: Boolean? = null,
    val profile: RefJ? = null,
    /** MIDI: the USB-MIDI cable or ALSA port index. */
    val cable: Int? = null,
)

@Serializable
data class ExternalSynth(
    val name: String,
    val kind: String,                 // cp88 | midi
    val midiOut: String,
    val midiIn: String? = null,
    val channel: Int = 1,
    /** CP-88 pages (1-40) the app may write songs into (I3.4). */
    val reservedPages: List<Int> = emptyList(),
    /** Its own live set buttons select the app's live sets, its page kept silent elsewhere (S4.12). */
    val selectsLiveSets: Boolean = false,
    val midnam: RefJ? = null,
)

@Serializable
data class SwitchSource(
    val port: String,
    /** programChange | cc | key | cp88 */
    val kind: String,
    val channel: Int? = null,
    val number: Int? = null,
    /** select | next | previous | nextSong | previousSong | pageForward | pageBack | panic */
    val action: String = "select",
)

@Serializable
data class Environment(
    val ports: List<PortDef> = emptyList(),
    val bindings: Map<String, Binding> = emptyMap(),
    val externalSynths: List<ExternalSynth> = emptyList(),
    val switching: List<SwitchSource> = emptyList(),
    /** Page-turner keys -> action, e.g. "PAGE_DOWN" -> "pageForward". */
    val pageTurner: Map<String, String> = emptyMap(),
    val twoUp: Boolean = false,
    val halfTurn: Boolean = true,
    val clickPort: String? = null,
    val clickContinuous: Boolean = false,
    /** Share of the stage screen given to the lead sheet, 0-1. */
    val sheetShare: Double = 0.6,
    /** The page-turner keys turn lead-sheet pages (else live sets). */
    val keysTurnPages: Boolean = true,
    /** Do Not Disturb while the app is in front (S17.2). */
    val doNotDisturb: Boolean = false,
    /** The master volume: how loud the room needs it is a property of the room. */
    val masterDb: Double? = null,
    @SerialName("@type") @EncodeDefault val type: String = TYPE,
) {
    companion object { const val TYPE = "bagosynths.environment/1" }
}

// ---------------------------------------------------------------- profiles

/** The members of `struct fbx_profile` (tools/fbx/fbx.h), PLAN.md D10. */
@Serializable
data class FbxProfile(
    val outIface: Int, val outAlt: Int, val outEp: Int,
    val inIface: Int, val inAlt: Int, val inEp: Int,
    val frameBytes: Int, val maxFrames: Int, val nominal: Double,
    val startFill: Int, val refill: Int,
    val dangerLo: Double, val dangerHi: Double, val jitter: Double,
)

@Serializable
data class UsbProfile(
    val match: DeviceMatch,
    val fbx: FbxProfile,
    val measuredOn: String? = null,
    val notes: String? = null,
    @SerialName("@type") @EncodeDefault val type: String = TYPE,
) {
    companion object {
        const val TYPE = "bagosynths.usbprofile/1"

        /** FBX_PROFILE_CP88, measured on Michiel's unit (CP88-USB-AUDIO.md). */
        val CP88 = UsbProfile(
            match = DeviceMatch(vendor = 0x0499, name = "CP88/CP73"),
            fbx = FbxProfile(1, 1, 0x07, 2, 1, 0x86, 6, 45, 44.1, 176, 4, 132.7, 135.5, 1.0),
            measuredOn = "CP88, 2026-10-04, cp88bench",
        )
    }
}

// ------------------------------------------------------------------- codec

/** Typed objects to and from [Json], and into and out of a [Store]. */
object Types {
    private val kx = kotlinx.serialization.json.Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun <T> toJson(ser: KSerializer<T>, value: T): Json = fromKx(kx.encodeToJsonElement(ser, value))
    fun <T> fromJson(ser: KSerializer<T>, json: Json): T = kx.decodeFromJsonElement(ser, toKx(json))

    inline fun <reified T> toJson(value: T): Json = toJson(serializer<T>(), value)
    inline fun <reified T> fromJson(json: Json): T = fromJson(serializer<T>(), json)

    private fun fromKx(e: JsonElement): Json = when (e) {
        is JsonNull -> Json.Null
        is JsonPrimitive -> when {
            e.isString -> Json.Str(e.content)
            e.booleanOrNull != null -> Json.Bool(e.booleanOrNull!!)
            else -> Json.Num(e.content.toDouble())
        }
        is JsonArray -> Json.Arr(e.map(::fromKx))
        is JsonObject -> Json.Obj(e.mapValues { fromKx(it.value) })
    }

    private fun toKx(j: Json): JsonElement = when (j) {
        Json.Null -> JsonNull
        is Json.Bool -> JsonPrimitive(j.value)
        is Json.Num -> {
            val d = j.value
            // Integral values go back as integers, so Int members decode.
            if (d == Math.rint(d) && kotlin.math.abs(d) < 9.007199254740992E15) JsonPrimitive(d.toLong())
            else JsonPrimitive(d)
        }
        is Json.Str -> JsonPrimitive(j.value)
        is Json.Arr -> JsonArray(j.items.map(::toKx))
        is Json.Obj -> JsonObject(j.members.mapValues { toKx(it.value) })
    }
}

inline fun <reified T> Store.putTyped(value: T): Digest = put(Types.toJson(value))
inline fun <reified T> Store.getTyped(d: Digest): T = Types.fromJson(getJson(d))
