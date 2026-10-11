package be.mdw.bagosynths

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Plaits' front panel, which is the easiest one to reproduce honestly because
 * the hardware has almost nothing on it: a model knob and three parameters
 * called HARMONICS, TIMBRE and MORPH that mean something different in every one
 * of the sixteen models. That vagueness is the instrument, not a shortcoming —
 * you turn them and listen.
 *
 * So the panel is the model list plus those three, and then the two that decide
 * how it behaves under a key: DECAY, which is the release, and COLOUR, which is
 * how much high end survives the tail. AUX blends Plaits' second output, which
 * on most models is a related but different voice — a sub-oscillator, the
 * unfiltered version, the noisy part.
 */
object Plaits {
    const val LEVEL = 0
    const val ENGINE = 1
    const val HARMONICS = 2
    const val TIMBRE = 3
    const val MORPH = 4
    const val DECAY = 5
    const val COLOUR = 6
    const val AUX_MIX = 7
    const val TRANSPOSE = 8
    const val BEND_RANGE = 9
    const val MOD_DEPTH = 10

    const val FADER_TOP = 1.5f
    const val DEFAULT_LEVEL = 1.0f
}

class PlaitsPanelBuilder(private val context: Context) {

    private val refreshers = ArrayList<() -> Unit>()
    private var modelLabel: TextView? = null
    private var levelFader: SeekBar? = null

    // The row the level fader sits in, kept so it can be hidden: the mix
    // faders only exist for balancing a layer, so they are shown only in ALL.
    // See setMixVisible.
    private var levelRow: View? = null
    private val knobs = HashMap<Int, SeekBar>()
    private val knobLabels = HashMap<Int, TextView>()

    /**
     * The four controls whose meaning changes with the model, in the order the
     * label table uses: HARMONICS, TIMBRE, MORPH, AUX.
     */
    private val labelledParams =
        listOf(Plaits.HARMONICS, Plaits.TIMBRE, Plaits.MORPH, Plaits.AUX_MIX)

    private var engineNames: Array<String> = arrayOf("VIRTUAL ANALOG")

    fun refresh() = refreshers.forEach { it() }

    /**
     * Shows or hides the mix fader.
     *
     * A per-instrument level only means something when several instruments
     * sound at once. In a solo part the level is not a choice -- the part is
     * either the one playing, at its own level, or it is silent -- so a fader
     * there is a control that either does nothing or quietly breaks the solo
     * it sits in. ALL is the only place these are a mixer, so it is the only
     * place they appear.
     */
    fun setMixVisible(on: Boolean) {
        levelRow?.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun build(): View {
        runCatching { engineNames = NativeBridge.nativePlaitsEngineNames() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#141C1A"))
        }

        root.addView(TextView(context).apply {
            text = "PLAITS — macro oscillator"
            setTextColor(Color.parseColor("#7FE0C0"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        // --- the model ------------------------------------------------------
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }
        row.addView(bigButton("◀") { step(-1) })

        modelLabel = TextView(context).apply {
            text = engineNames.firstOrNull() ?: "—"
            setTextColor(Color.parseColor("#E6EAF2"))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 20)
            setBackgroundColor(Color.parseColor("#22302C"))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = 12; rightMargin = 12 }
            setOnClickListener { showPicker() }
        }
        row.addView(modelLabel)
        row.addView(bigButton("▶") { step(1) })
        root.addView(row)

        val switches = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        switches.addView(smallButton("OCT −") { transposeBy(-12) })
        switches.addView(smallButton("OCT +") { transposeBy(12) })
        switches.addView(smallButton("RESET") {
            NativeBridge.nativeSetPlaitsParam(Plaits.TRANSPOSE, 0f)
        })
        root.addView(switches)

        // --- the three that mean something different every time --------------
        root.addView(knob("Harmonics", Plaits.HARMONICS))
        root.addView(knob("Timbre", Plaits.TIMBRE))
        root.addView(knob("Morph", Plaits.MORPH))

        // --- how it behaves under a key --------------------------------------
        root.addView(knob("Decay", Plaits.DECAY))
        root.addView(knob("Colour", Plaits.COLOUR))
        root.addView(knob("Aux", Plaits.AUX_MIX))
        root.addView(knob("Mod wheel", Plaits.MOD_DEPTH))

        levelRow = slider(Part.Plaits.label, 0f, keep = { levelFader = it }) {
            NativeBridge.nativeSetPlaitsParam(Plaits.LEVEL, it * Plaits.FADER_TOP)
        }
        root.addView(levelRow)

        refreshers.add {
            modelLabel?.text = currentName()
            relabelKnobs()
            levelFader?.progress =
                (NativeBridge.nativeGetPlaitsParam(Plaits.LEVEL) / Plaits.FADER_TOP * 100f).toInt()
            knobs.forEach { (param, bar) ->
                bar.progress = (NativeBridge.nativeGetPlaitsParam(param) * 100f).toInt()
            }
        }

        return root
    }

    /** Mirrors [Part] onto the level, as the other panels do. */
    fun setPart(part: Part) {
        val level = if (part == Part.Plaits || part == Part.All) Plaits.DEFAULT_LEVEL else 0f
        NativeBridge.nativeSetPlaitsParam(Plaits.LEVEL, level)
        levelFader?.progress = (level / Plaits.FADER_TOP * 100f).toInt()
    }

    // -------------------------------------------------------------- the model

    private fun currentIndex(): Int =
        NativeBridge.nativeGetPlaitsParam(Plaits.ENGINE).toInt()
            .coerceIn(0, engineNames.size - 1)

    private fun currentName(): String = engineNames.getOrElse(currentIndex()) { "—" }

    private fun step(delta: Int) {
        val n = engineNames.size
        if (n <= 0) return
        val next = ((currentIndex() + delta) % n + n) % n
        NativeBridge.nativeSetPlaitsParam(Plaits.ENGINE, next.toFloat())
        modelLabel?.text = engineNames[next]
        relabelKnobs()
    }

    private fun showPicker() {
        AlertDialog.Builder(context)
            .setTitle("Model")
            .setItems(engineNames) { _, i ->
                NativeBridge.nativeSetPlaitsParam(Plaits.ENGINE, i.toFloat())
                modelLabel?.text = engineNames[i]
                relabelKnobs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun transposeBy(semis: Int) {
        val now = NativeBridge.nativeGetPlaitsParam(Plaits.TRANSPOSE)
        NativeBridge.nativeSetPlaitsParam(
            Plaits.TRANSPOSE, (now + semis).coerceIn(-24f, 24f)
        )
    }

    // ------------------------------------------------------------------ widgets

    private fun knob(label: String, param: Int): View {
        val initial = NativeBridge.nativeGetPlaitsParam(param)
        return slider(
            label, initial,
            keep = { knobs[param] = it },
            keepLabel = { knobLabels[param] = it },
        ) {
            NativeBridge.nativeSetPlaitsParam(param, it)
        }
    }

    /**
     * Relabels the three knobs plus AUX for the current model.
     *
     * On the hardware these are deliberately vague, and that vagueness is the
     * instrument — but "Inharmonicity" and "Grain Density" are the difference
     * between reaching for the right control and hunting for it, and hunting is
     * not a thing to do between songs. The generic name stays in brackets so the
     * manual is still followable.
     */
    private fun relabelKnobs() {
        val labels = runCatching {
            NativeBridge.nativePlaitsParamLabels(currentIndex())
        }.getOrNull() ?: return
        val generic = listOf("Harmonics", "Timbre", "Morph", "Aux")
        labelledParams.forEachIndexed { i, param ->
            val specific = labels.getOrNull(i).orEmpty()
            knobLabels[param]?.text =
                if (specific.isEmpty()) generic[i] else "$specific"
        }
    }

    private fun bigButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 22f
            setPadding(36, 20, 36, 20)
            setOnClickListener { onClick() }
        }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 15f
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 16 }
            setOnClickListener { onClick() }
        }

    private fun slider(
        label: String,
        initial: Float,
        keep: (SeekBar) -> Unit = {},
        keepLabel: (TextView) -> Unit = {},
        onChange: (Float) -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        val caption = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(300, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        keepLabel(caption)
        row.addView(caption)
        val bar = SeekBar(context).apply {
            max = 100
            progress = (initial * 100).toInt()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) onChange(value / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        keep(bar)
        row.addView(bar)
        return row
    }
}
