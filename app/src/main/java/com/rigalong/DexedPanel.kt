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
 * The DX7's front panel — which on the original was famously one membrane
 * keypad and a two-line display, so almost anything is an improvement.
 *
 * What a player actually reaches for on a DX7 is the voice, and there are 1056
 * of them in the box. So the voice browser is the panel: previous, next, and the
 * full list by bank. Editing six operators' envelopes is what made the original
 * notorious and is not something to attempt on a touchscreen between songs.
 *
 * The one genuinely musical switch here is the **FM core**. Dexed carries three,
 * and they are not subtle: MARK I models the original hardware's quirks and is
 * the one that sounds like a DX7; MODERN is cleaner arithmetic; OPL is the
 * cheaper Yamaha voicing. It defaults to MARK I.
 */
object Dexed {
    const val LEVEL = 0
    const val ENGINE = 1
    const val TRANSPOSE = 2
    const val BEND_RANGE = 3
    const val MOD_DEPTH = 4

    const val FADER_TOP = 1.5f
    const val DEFAULT_LEVEL = 1.0f
}

class DexedPanelBuilder(private val context: Context) {

    private val refreshers = ArrayList<() -> Unit>()
    private var voiceLabel: TextView? = null
    private var statusLabel: TextView? = null
    private var engineButton: Button? = null
    private var levelFader: SeekBar? = null

    // The row the level fader sits in, kept so it can be hidden: the mix
    // faders only exist for balancing a layer, so they are shown only in ALL.
    // See setMixVisible.
    private var levelRow: View? = null
    private var modFader: SeekBar? = null

    private var names: List<String> = emptyList()
    private var engineNames: Array<String> = arrayOf("MODERN", "MARK I", "OPL")

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
            setBackgroundColor(Color.parseColor("#1B1820"))
        }

        root.addView(TextView(context).apply {
            text = "DEXED — DX7"
            setTextColor(Color.parseColor("#E0A0FF"))
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

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(bigButton("◀") { step(-1) })

        voiceLabel = TextView(context).apply {
            text = "—"
            setTextColor(Color.parseColor("#E6EAF2"))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 20)
            setBackgroundColor(Color.parseColor("#2A2F38"))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { leftMargin = 12; rightMargin = 12 }
            setOnClickListener { showPicker() }
        }
        row.addView(voiceLabel)
        row.addView(bigButton("▶") { step(1) })
        root.addView(row)

        // --- the switches that matter live ---------------------------------
        val switches = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        engineButton = smallButton("ENGINE") {
            val next = (NativeBridge.nativeGetDexedParam(Dexed.ENGINE).toInt() + 1) %
                engineNames.size
            NativeBridge.nativeSetDexedParam(Dexed.ENGINE, next.toFloat())
            paintEngine()
        }
        switches.addView(engineButton)
        switches.addView(smallButton("OCT −") { transposeBy(-12) })
        switches.addView(smallButton("OCT +") { transposeBy(12) })
        switches.addView(smallButton("RESET") {
            NativeBridge.nativeSetDexedParam(Dexed.TRANSPOSE, 0f)
        })
        root.addView(switches)

        root.addView(slider("Mod wheel", 0.99f, keep = { modFader = it }) {
            NativeBridge.nativeSetDexedParam(Dexed.MOD_DEPTH, it)
        })
        levelRow = slider(Part.Dexed.label, 0f, keep = { levelFader = it }) {
            NativeBridge.nativeSetDexedParam(Dexed.LEVEL, it * Dexed.FADER_TOP)
        }
        root.addView(levelRow)

        refreshers.add {
            val ready = NativeBridge.nativeDexedReady()
            val count = if (ready) NativeBridge.nativeDexedVoiceCount() else 0
            statusLabel?.text = when {
                !ready -> "loading…"
                // Two causes now, not one. The 33 builtin cartridges are
                // unpacked from the dexed submodule at build time, so an APK
                // built without submodules has none either -- and blaming the
                // dx7 folder for that would send someone looking in the wrong
                // place. So state the fact rather than the remedy.
                count == 0 -> "no cartridges — none bundled, none in the dx7 folder"
                else -> "$count voices in ${count / 32} cartridges"
            }
            if (ready && names.size != count) {
                names = (0 until count).map { NativeBridge.nativeDexedVoiceName(it) }
            }
            val current = NativeBridge.nativeDexedCurrentVoiceName()
            voiceLabel?.text = if (current.isEmpty()) "INIT VOICE" else current

            levelFader?.progress =
                (NativeBridge.nativeGetDexedParam(Dexed.LEVEL) / Dexed.FADER_TOP * 100f).toInt()
            modFader?.progress =
                (NativeBridge.nativeGetDexedParam(Dexed.MOD_DEPTH) * 100f).toInt()
            paintEngine()
        }

        runCatching { engineNames = NativeBridge.nativeDexedEngineNames() }
        return root
    }

    /** Mirrors [Part] onto the level, as the other panels do. */
    fun setPart(part: Part) {
        val level = if (part == Part.Dexed || part == Part.All) Dexed.DEFAULT_LEVEL else 0f
        NativeBridge.nativeSetDexedParam(Dexed.LEVEL, level)
        levelFader?.progress = (level / Dexed.FADER_TOP * 100f).toInt()
    }

    // ------------------------------------------------------------------- voices

    private fun paintEngine() {
        val i = NativeBridge.nativeGetDexedParam(Dexed.ENGINE).toInt()
            .coerceIn(0, engineNames.size - 1)
        engineButton?.text = engineNames[i]
        // Mark I is the authentic one, so it is the one that gets the highlight:
        // the button should say at a glance whether you are on the real thing.
        val authentic = engineNames[i] == "MARK I"
        engineButton?.setTextColor(
            if (authentic) Color.parseColor("#14161A") else Color.parseColor("#C9D1E0")
        )
        engineButton?.setBackgroundColor(
            if (authentic) Color.parseColor("#C9A227") else Color.parseColor("#2A2F38")
        )
    }

    private fun transposeBy(semis: Int) {
        val now = NativeBridge.nativeGetDexedParam(Dexed.TRANSPOSE)
        NativeBridge.nativeSetDexedParam(
            Dexed.TRANSPOSE, (now + semis).coerceIn(-24f, 24f)
        )
    }

    private fun step(delta: Int) {
        val count = NativeBridge.nativeDexedVoiceCount()
        if (count <= 0) return
        val current = NativeBridge.nativeDexedCurrentVoice()
        val next = if (current < 0) {
            if (delta > 0) 0 else count - 1
        } else {
            ((current + delta) % count + count) % count
        }
        NativeBridge.nativeDexedSelectVoice(next)
        voiceLabel?.text = if (next < names.size) names[next] else "…"
    }

    private fun showPicker() {
        val count = NativeBridge.nativeDexedVoiceCount()
        if (count <= 0) return
        if (names.size != count) {
            names = (0 until count).map { NativeBridge.nativeDexedVoiceName(it) }
        }
        // 1056 voices in one list is unusable, so pick the cartridge first. That
        // is also how a DX7 works: you load a cartridge, then choose from 32.
        val banks = names.map { it.substringBefore('/') }.distinct()
        AlertDialog.Builder(context)
            .setTitle("Cartridge")
            .setItems(banks.toTypedArray()) { _, b ->
                val bank = banks[b]
                val slots = names.withIndex().filter { it.value.startsWith("$bank/") }
                AlertDialog.Builder(context)
                    .setTitle(bank)
                    .setItems(slots.map { it.value.substringAfter('/') }.toTypedArray()) { _, i ->
                        val index = slots[i].index
                        NativeBridge.nativeDexedSelectVoice(index)
                        voiceLabel?.text = names[index]
                    }
                    .setNegativeButton("Back") { _, _ -> showPicker() }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
