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
 * Surge XT's front panel — and deliberately *not* a Surge editor.
 *
 * Surge has around two thousand parameters, three oscillators a voice, two
 * filter units, dual FX chains and an MSEG editor. Rebuilding that on a tablet
 * is its own project, and a half-built one would be worse than none: the whole
 * point of shipping 641 factory patches is that somebody already did the sound
 * design.
 *
 * So what is on screen is what a player reaches for between songs:
 *
 *  * **the patch**, browsed by name — previous, next, and a full list;
 *  * **the eight macros**, which are the knobs the patch author themselves
 *    decided were worth exposing, so they are the right performance controls by
 *    construction rather than by our guess;
 *  * **a level**, because that is how parts are balanced here.
 *
 * Everything writes straight through to the native node, which queues the change
 * for the render thread. No state lives on this side beyond what is on screen.
 */
object Surge {
    const val LEVEL = 0
    const val MACRO1 = 1
    const val MACROS = 8

    /** The fader runs past unity so a quiet patch can be pushed without a rebuild. */
    const val FADER_TOP = 1.5f
    const val DEFAULT_LEVEL = 1.0f
}

class SurgePanelBuilder(private val context: Context) {

    private val refreshers = ArrayList<() -> Unit>()
    private var patchLabel: TextView? = null
    private var statusLabel: TextView? = null
    private var levelFader: SeekBar? = null

    // The row the level fader sits in, kept so it can be hidden: the mix
    // faders only exist for balancing a layer, so they are shown only in ALL.
    // See setMixVisible.
    private var levelRow: View? = null
    private val macroBars = ArrayList<SeekBar>(Surge.MACROS)
    private val macroLabels = ArrayList<TextView>(Surge.MACROS)

    /** Cached so the picker does not cross the JNI boundary 641 times per open. */
    private var names: List<String> = emptyList()

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
            setBackgroundColor(Color.parseColor("#181B21"))
        }

        root.addView(TextView(context).apply {
            text = "SURGE XT"
            setTextColor(Color.parseColor("#7FB3FF"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusLabel = TextView(context).apply {
            text = "starting…"
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 14f
            setPadding(0, 4, 0, 8)
        }
        root.addView(statusLabel)

        // --- the patch, on one row ------------------------------------------
        //
        // PREV and NEXT are big because they are what gets used while playing;
        // the name in the middle is the button that opens the full list, since
        // the name is the thing you are already looking at when you want it.
        val patchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        patchRow.addView(bigButton("◀") { step(-1) })

        patchLabel = TextView(context).apply {
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
        patchRow.addView(patchLabel)

        patchRow.addView(bigButton("▶") { step(1) })
        root.addView(patchRow)

        // --- macros ----------------------------------------------------------
        val macroHeader = TextView(context).apply {
            text = "Macros — what this patch put under your hand"
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        root.addView(macroHeader)

        // Two columns of four: eight full-width sliders would push the level
        // fader off a 14.6" screen, and the macros are coarse controls anyway.
        val macroGrid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = 24 }
        }
        for (i in 0 until Surge.MACROS) {
            val index = Surge.MACRO1 + i
            val row = slider(
                "M${i + 1}", 0.5f, captionWidth = 150,
                keep = { macroBars.add(it) },
                keepLabel = { macroLabels.add(it) },
            ) {
                NativeBridge.nativeSetSurgeParam(index, it)
            }
            (if (i < 4) left else right).addView(row)
        }
        macroGrid.addView(left)
        macroGrid.addView(right)
        root.addView(macroGrid)

        // --- level ------------------------------------------------------------
        // Starts at zero, like setBfree: the app opens on sfizz and each
        // other part is brought in deliberately. This mirrors what the node does.
        levelRow = slider(Part.Surge.label, 0f, keep = { levelFader = it }) {
            NativeBridge.nativeSetSurgeParam(Surge.LEVEL, it * Surge.FADER_TOP)
        }
        root.addView(levelRow)

        // Everything reads back from the node, so a restored patch shows what is
        // actually set rather than where the controls were left.
        refreshers.add {
            val ready = NativeBridge.nativeSurgeReady()
            val count = if (ready) NativeBridge.nativeSurgePatchCount() else 0

            statusLabel?.text = when {
                !ready -> "starting…"
                count == 0 ->
                    "no patch library — run tools/install_surge_data.sh (init patch only)"
                else -> "$count patches"
            }
            if (ready && names.size != count) {
                names = (0 until count).map { NativeBridge.nativeSurgePatchName(it) }
            }

            // Macro names come from the PATCH, so they change under us every time
            // one is loaded. Re-read rather than being told, for the same reason
            // the patch name is re-read: the load is asynchronous and there is
            // nothing to be told by.
            macroLabels.forEachIndexed { i, label ->
                val named = if (ready) NativeBridge.nativeSurgeMacroName(i) else ""
                label.text = if (named.isEmpty()) "M${i + 1}" else named
                // An author who bothered to name a macro is saying it matters,
                // so a named one reads brighter than a bare number.
                label.setTextColor(
                    if (named.isEmpty()) Color.parseColor("#6B7482")
                    else Color.parseColor("#C9D6E8")
                )
            }

            val current = NativeBridge.nativeSurgeCurrentPatchName()
            patchLabel?.text = if (current.isEmpty()) {
                if (ready && count > 0) "tap to choose" else "init patch"
            } else {
                current
            }

            levelFader?.progress =
                (NativeBridge.nativeGetSurgeParam(Surge.LEVEL) / Surge.FADER_TOP * 100f).toInt()
            macroBars.forEachIndexed { i, bar ->
                bar.progress =
                    (NativeBridge.nativeGetSurgeParam(Surge.MACRO1 + i) * 100f).toInt()
            }
        }

        return root
    }

    /**
     * Mirrors [Part] onto Surge XT's level, the same way the setBfree panel does.
     * The level is the single source of truth; the button is a view of it.
     */
    fun setPart(part: Part) {
        val level = if (part == Part.Surge || part == Part.All) Surge.DEFAULT_LEVEL else 0f
        NativeBridge.nativeSetSurgeParam(Surge.LEVEL, level)
        levelFader?.progress = (level / Surge.FADER_TOP * 100f).toInt()
    }

    // ------------------------------------------------------------------ patches

    private fun step(delta: Int) {
        val count = NativeBridge.nativeSurgePatchCount()
        if (count <= 0) return
        val current = NativeBridge.nativeSurgeCurrentPatch()
        // From "no patch yet", ◀ should land on the last one rather than on -1.
        val next = if (current < 0) {
            if (delta > 0) 0 else count - 1
        } else {
            ((current + delta) % count + count) % count
        }
        NativeBridge.nativeSurgeLoadPatch(next)
        // The load is asynchronous, so show the name we asked for straight away
        // rather than leaving the label stale until the next poll.
        patchLabel?.text = if (next < names.size) names[next] else "…"
    }

    /**
     * Category first, then patch.
     *
     * 641 patches in one alphabetical list is a scroll nobody wants between
     * songs, and it was the only browser here that worked that way — the DX7
     * picks a cartridge first and the SoundFont picks a bank first. A Surge
     * patch is already addressed as "Category/Name", so the split costs nothing
     * and brings about twenty-five names into view at a time.
     */
    private fun showPicker() {
        val count = NativeBridge.nativeSurgePatchCount()
        if (count <= 0) return
        if (names.size != count) {
            names = (0 until count).map { NativeBridge.nativeSurgePatchName(it) }
        }

        val categories = names.map { it.substringBefore('/') }.distinct()
        if (categories.size <= 1) {
            showPatchList(names.indices.toList())
            return
        }
        // The count per category is worth showing: it says at a glance whether
        // a category is worth opening.
        val labels = categories.map { cat ->
            "$cat  (${names.count { it.substringBefore('/') == cat }})"
        }
        AlertDialog.Builder(context)
            .setTitle("Category")
            .setItems(labels.toTypedArray()) { _, c ->
                val cat = categories[c]
                showPatchList(names.indices.filter { names[it].substringBefore('/') == cat })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPatchList(indices: List<Int>) {
        if (indices.isEmpty()) return
        AlertDialog.Builder(context)
            .setTitle("Patch")
            // The category is already known by this point, so show only the name.
            .setItems(indices.map { names[it].substringAfter('/') }.toTypedArray()) { _, i ->
                val index = indices[i]
                NativeBridge.nativeSurgeLoadPatch(index)
                patchLabel?.text = names[index]
            }
            .setNegativeButton("Back") { _, _ -> showPicker() }
            .show()
    }

    // ------------------------------------------------------------------ widgets

    private fun bigButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 22f
            setPadding(36, 20, 36, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun slider(
        label: String,
        initial: Float,
        captionWidth: Int = 260,
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
            layoutParams =
                LinearLayout.LayoutParams(captionWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
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
