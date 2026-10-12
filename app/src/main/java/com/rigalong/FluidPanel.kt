package com.rigalong

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
 * The SoundFont panel: a font, a preset, and a level.
 *
 * There is nothing to edit here and that is the point — a SoundFont is a bank
 * of finished sounds, and what a player wants from it on stage is to find the
 * right one quickly. So the panel is two browsers: which font is loaded, and
 * which of its presets is playing.
 *
 * Presets are shown as "bank:prog NAME" because that is General MIDI's own
 * address for them, and a GM font lists 128 melodic presets in bank 0 followed
 * by the drum kits in bank 128. Picking the bank first keeps the list short,
 * the same two-step the DX7 cartridge browser uses.
 */
object Fluid {
    const val LEVEL = 0
    const val REVERB = 1
    const val CHORUS = 2
    const val TRANSPOSE = 3
    const val BEND_RANGE = 4

    const val FADER_TOP = 1.5f
    const val DEFAULT_LEVEL = 1.0f
}

class FluidPanelBuilder(private val context: Context) {

    private val refreshers = ArrayList<() -> Unit>()
    private var statusLabel: TextView? = null
    private var fontLabel: TextView? = null
    private var presetLabel: TextView? = null
    private var levelFader: SeekBar? = null

    // The row the level fader sits in, kept so it can be hidden: the mix
    // faders only exist for balancing a layer, so they are shown only in ALL.
    // See setMixVisible.
    private var levelRow: View? = null
    private var reverbFader: SeekBar? = null
    private var chorusFader: SeekBar? = null

    private var presetNames: List<String> = emptyList()

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
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#151A22"))
        }

        root.addView(TextView(context).apply {
            text = "FluidSynth — SF2 / SF3"
            setTextColor(Color.parseColor("#8FC8FF"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusLabel = TextView(context).apply {
            text = "loading…"
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 14f
            setPadding(0, 4, 0, 8)
        }
        root.addView(statusLabel)

        // --- which font ------------------------------------------------------
        val fontRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fontRow.addView(TextView(context).apply {
            text = "FONT"
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(140, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        fontLabel = TextView(context).apply {
            text = "—"
            setTextColor(Color.parseColor("#E6EAF2"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(16, 18, 16, 18)
            setBackgroundColor(Color.parseColor("#232B36"))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnClickListener { showFontPicker() }
        }
        fontRow.addView(fontLabel)
        root.addView(fontRow)

        // --- which preset ----------------------------------------------------
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 0)
        }
        row.addView(bigButton("◀") { step(-1) })
        presetLabel = TextView(context).apply {
            text = "—"
            setTextColor(Color.parseColor("#E6EAF2"))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 20)
            setBackgroundColor(Color.parseColor("#2A2F38"))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = 12; rightMargin = 12 }
            setOnClickListener { showPresetPicker() }
        }
        row.addView(presetLabel)
        row.addView(bigButton("▶") { step(1) })
        root.addView(row)

        val switches = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        switches.addView(smallButton("OCT −") { transposeBy(-12) })
        switches.addView(smallButton("OCT +") { transposeBy(12) })
        switches.addView(smallButton("RESET") {
            NativeBridge.nativeSetFluidParam(Fluid.TRANSPOSE, 0f)
        })
        root.addView(switches)

        // FluidSynth has its own reverb and chorus. They are off by default
        // because the graph already ends in a reverb — but a GM font is often
        // voiced expecting them, so they stay within reach.
        root.addView(slider("Reverb", 0f, keep = { reverbFader = it }) {
            NativeBridge.nativeSetFluidParam(Fluid.REVERB, it)
        })
        root.addView(slider("Chorus", 0f, keep = { chorusFader = it }) {
            NativeBridge.nativeSetFluidParam(Fluid.CHORUS, it)
        })
        levelRow = slider(Part.Fluid.label, 0f, keep = { levelFader = it }) {
            NativeBridge.nativeSetFluidParam(Fluid.LEVEL, it * Fluid.FADER_TOP)
        }
        root.addView(levelRow)

        refreshers.add {
            val ready = NativeBridge.nativeFluidReady()
            val busy = NativeBridge.nativeFluidBusy()
            val fonts = if (ready) NativeBridge.nativeFluidFontCount() else 0
            val presets = if (ready) NativeBridge.nativeFluidPresetCount() else 0

            statusLabel?.text = when {
                !ready -> "starting…"
                busy -> "loading SoundFont…"
                fonts == 0 ->
                    "no SoundFonts — put .sf2/.sf3 files in the soundfonts folder"
                presets == 0 -> "$fonts available — pick one"
                else -> "$presets presets in $fonts available"
            }

            val font = NativeBridge.nativeFluidCurrentFont()
            fontLabel?.text = if (font.isEmpty()) "tap to choose" else font

            if (ready && presetNames.size != presets) {
                presetNames = (0 until presets).map { NativeBridge.nativeFluidPresetName(it) }
            }
            val current = NativeBridge.nativeFluidCurrentPresetName()
            presetLabel?.text = if (current.isEmpty()) "—" else current

            levelFader?.progress =
                (NativeBridge.nativeGetFluidParam(Fluid.LEVEL) / Fluid.FADER_TOP * 100f).toInt()
            reverbFader?.progress =
                (NativeBridge.nativeGetFluidParam(Fluid.REVERB) * 100f).toInt()
            chorusFader?.progress =
                (NativeBridge.nativeGetFluidParam(Fluid.CHORUS) * 100f).toInt()
        }

        return root
    }

    /** Mirrors [Part] onto the level, as the other panels do. */
    fun setPart(part: Part) {
        val level = if (part == Part.Fluid || part == Part.All) Fluid.DEFAULT_LEVEL else 0f
        NativeBridge.nativeSetFluidParam(Fluid.LEVEL, level)
        levelFader?.progress = (level / Fluid.FADER_TOP * 100f).toInt()
    }

    // ------------------------------------------------------------------ fonts

    private fun showFontPicker() {
        val count = NativeBridge.nativeFluidFontCount()
        if (count <= 0) return
        val names = (0 until count).map { NativeBridge.nativeFluidFontName(it) }
        AlertDialog.Builder(context)
            .setTitle("SoundFont")
            .setItems(names.toTypedArray()) { _, i ->
                NativeBridge.nativeFluidLoadFont(i)
                fontLabel?.text = names[i]
                presetNames = emptyList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- presets

    private fun step(delta: Int) {
        val count = NativeBridge.nativeFluidPresetCount()
        if (count <= 0) return
        val current = NativeBridge.nativeFluidCurrentPreset()
        val next = if (current < 0) {
            if (delta > 0) 0 else count - 1
        } else {
            ((current + delta) % count + count) % count
        }
        NativeBridge.nativeFluidSelectPreset(next)
        presetLabel?.text =
            if (next < presetNames.size) presetNames[next] else "…"
    }

    private fun showPresetPicker() {
        val count = NativeBridge.nativeFluidPresetCount()
        if (count <= 0) return
        if (presetNames.size != count) {
            presetNames = (0 until count).map { NativeBridge.nativeFluidPresetName(it) }
        }
        // A GM font is 128 melodic presets plus the drum kits, which is a long
        // list. Bank first, as the DX7 browser picks a cartridge first.
        val banks = presetNames.map { it.substringBefore(':') }.distinct()
        if (banks.size <= 1) {
            showPresetList(presetNames.indices.toList())
            return
        }
        AlertDialog.Builder(context)
            .setTitle("Bank")
            .setItems(banks.map { bankLabel(it) }.toTypedArray()) { _, b ->
                val bank = banks[b]
                showPresetList(presetNames.indices.filter {
                    presetNames[it].substringBefore(':') == bank
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bankLabel(bank: String): String =
        // 128 is the drum bank in every General MIDI font, and calling it that
        // saves a wrong guess on stage.
        if (bank == "128") "128 — drums" else "bank $bank"

    private fun showPresetList(indices: List<Int>) {
        if (indices.isEmpty()) return
        AlertDialog.Builder(context)
            .setTitle("Preset")
            .setItems(indices.map { presetNames[it] }.toTypedArray()) { _, i ->
                val index = indices[i]
                NativeBridge.nativeFluidSelectPreset(index)
                presetLabel?.text = presetNames[index]
            }
            .setNegativeButton("Back") { _, _ -> showPresetPicker() }
            .show()
    }

    private fun transposeBy(semis: Int) {
        val now = NativeBridge.nativeGetFluidParam(Fluid.TRANSPOSE)
        NativeBridge.nativeSetFluidParam(
            Fluid.TRANSPOSE, (now + semis).coerceIn(-24f, 24f)
        )
    }

    // ------------------------------------------------------------------ widgets

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
        onChange: (Float) -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(260, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
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
