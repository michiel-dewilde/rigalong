package com.rigalong

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * setBfree's front panel.
 *
 * This is the reason the instrument is a tablet and not a phone or a box: nine
 * drawbars want to be grabbed, and a 14.6" screen is wide enough to grab them
 * the way you would on the real thing. Everything here writes straight through
 * to [NativeBridge.nativeSetSetBfreeParam], which queues the change for the render
 * thread -- no state is kept on this side beyond what is on screen.
 *
 * Plain Views and custom drawing, matching the rest of the app: this screen sits
 * next to a real-time audio thread, and every frame it spends is a frame taken
 * from that.
 */

/** Parameter indices. These MUST match SetBfreeNode::Param in SetBfreeNode.h. */
object SetBfree {
    const val UPPER1 = 0
    const val LOWER1 = 9
    const val PEDAL1 = 18
    const val PERC_ENABLE = 27
    const val PERC_FAST = 28
    const val PERC_THIRD = 29
    const val PERC_SOFT = 30
    const val VIBRATO_KNOB = 31
    const val VIBRATO_UPPER = 32
    const val VIBRATO_LOWER = 33
    const val OVERDRIVE = 34
    const val OVERDRIVE_CHAR = 35
    const val OVERDRIVE_GAIN = 36
    const val ROTARY = 37
    const val REVERB_MIX = 38
    const val SWELL = 39
    const val SPLIT_PEDAL = 40
    const val SPLIT_LOWER = 41
    const val TRANSPOSE = 42
    const val LEVEL = 43

    /** Harmonic footages, in drawbar order. */
    val FOOTAGES = arrayOf("16'", "5⅓'", "8'", "4'", "2⅔'", "2'", "1⅗'", "1⅓'", "1'")

    /**
     * The colours are not decoration. On a B-3 the sub-fundamental pair is brown,
     * the octave-related bars are white and the mutations -- the ones that make a
     * chord out of a single key -- are black. Organists find a registration by
     * that pattern before they read any label, so reproducing it makes the panel
     * readable at a glance on stage.
     */
    val COLOURS = intArrayOf(
        0xFF6B4A32.toInt(), 0xFF6B4A32.toInt(),   // 16', 5 1/3'  brown
        0xFFE8E4DA.toInt(), 0xFFE8E4DA.toInt(),   // 8', 4'       white
        0xFF2A2A2E.toInt(),                        // 2 2/3'       black
        0xFFE8E4DA.toInt(),                        // 2'           white
        0xFF2A2A2E.toInt(), 0xFF2A2A2E.toInt(),   // 1 3/5', 1 1/3' black
        0xFFE8E4DA.toInt(),                        // 1'           white
    )

    val VIBRATO_NAMES = arrayOf("V1", "C1", "V2", "C2", "V3", "C3")

    /** Pedal is a manual too, in the sense that matters here: it has nine bars. */
    val MANUAL_NAMES = arrayOf("UPPER", "LOWER", "PEDAL")
    val ROTARY_NAMES = arrayOf("STOP", "SLOW", "FAST")
}

/**
 * One drawbar: nine detented positions, pulled down for louder.
 *
 * Drawn rather than assembled from a rotated SeekBar, because a drawbar is not a
 * slider -- it has discrete stops, it reads as a physical tab, and the number on
 * the tab is how a registration is communicated ("88 8000 000"). None of that
 * survives a stock widget, and drawing it is about thirty lines.
 */
class DrawbarView(
    context: Context,
    private val footage: String,
    private val colour: Int,
    private val onChange: (Int) -> Unit,
) : View(context) {

    var value: Int = 0
        set(v) {
            val clamped = v.coerceIn(0, 8)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val labelH = h * 0.10f
        val travelTop = labelH
        val travelH = h - labelH
        val tabH = travelH / 11f

        // The slot the bar slides in.
        paint.color = 0xFF0E1013.toInt()
        rect.set(w * 0.24f, travelTop, w * 0.76f, h)
        canvas.drawRoundRect(rect, w * 0.10f, w * 0.10f, paint)

        // The shaft above the tab, so a pulled-out bar looks pulled out.
        val tabTop = travelTop + (travelH - tabH) * (value / 8f)
        paint.color = 0xFF3A3F47.toInt()
        rect.set(w * 0.36f, travelTop, w * 0.64f, tabTop + tabH * 0.5f)
        canvas.drawRect(rect, paint)

        // The tab itself.
        paint.color = colour
        rect.set(w * 0.10f, tabTop, w * 0.90f, tabTop + tabH)
        canvas.drawRoundRect(rect, w * 0.08f, w * 0.08f, paint)

        // The number, in whatever reads against the tab.
        textPaint.color = if (colour == 0xFFE8E4DA.toInt()) 0xFF14161A.toInt() else 0xFFE8E4DA.toInt()
        textPaint.textSize = tabH * 0.62f
        canvas.drawText(
            value.toString(), w * 0.5f,
            tabTop + tabH * 0.5f + textPaint.textSize * 0.36f, textPaint
        )

        // The footage, above the slot.
        textPaint.color = 0xFF9AA4B2.toInt()
        textPaint.textSize = labelH * 0.72f
        canvas.drawText(footage, w * 0.5f, labelH * 0.78f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val labelH = height * 0.10f
                val travel = (height - labelH).coerceAtLeast(1f)
                val position = ((event.y - labelH) / travel * 8f)
                val next = Math.round(position).coerceIn(0, 8)
                if (next != value) {
                    value = next
                    onChange(next)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }
}

/**
 * Which instrument the keyboard is playing. Levels, not a switch -- see below.
 *
 * `All` rather than `Both` since Surge XT arrived: three parts that are mixed
 * rather than switched means "everything at once" is a real, useful position,
 * and it is where layering starts.
 *
 * `None` is the other end of the same idea and is leftmost on purpose: every
 * engine at zero, so nothing here makes a sound. That is what you want while
 * playing the CP-88's OWN voices -- the tablet stops competing for the ears
 * and, because the gate parks a silenced engine, stops competing for the CPU
 * as well. It is a position on the same axis, not a bypass switch.
 */
/** The engines as stage parts, each shown by its proper name. */
enum class Part(val label: String) {
    None(""), Sfizz("sfizz"), SetBfree("setBfree"), Surge("Surge XT"), Dexed("Dexed"), Plaits("Plaits"),
    Fluid("FluidSynth"), All("ALL")
}

/** Builds the whole setBfree panel, wired straight to the native node. */
class SetBfreePanelBuilder(private val context: Context) {

    // The setBfree fader runs to 1.5, so there is headroom above unity for a thin
    // registration. The one-tap switch uses 1.0, which is the level setBfree was
    // voiced against.
    private val kSetBfreeFaderTop = 1.5f
    private val kSetBfreeDefaultLevel = 1.0f

    private val bars = ArrayList<DrawbarView>(9)
    private var rotaryButton: Button? = null
    private var vibratoButton: Button? = null
    private var setBfreeFader: SeekBar? = null
    private var sfizzFader: SeekBar? = null

    // The rows those two faders sit in, kept so they can be hidden: see
    // setMixVisible. This panel carries TWO, because it is really the
    // keyboards panel -- setBfree and sfizz share it.
    private var setBfreeRow: View? = null
    private var sfizzRow: View? = null
    private var driveFader: SeekBar? = null
    private var reverbFader: SeekBar? = null

    /**
     * Which manual the nine drawbars are editing.
     *
     * One set of nine, switched, rather than twenty-seven on screen. A real
     * console has all of them at once because it has the room; a tablet does
     * not, and nine large drawbars you can actually grab beat twenty-seven you
     * cannot. The lower and pedal registrations only matter once a split is set,
     * and then they matter a great deal -- which is why they need to be
     * reachable at all.
     */
    private var manual = 0
    private var manualHeader: TextView? = null
    private val manualButtons = ArrayList<Button>(3)

    private fun base() = SetBfree.UPPER1 + manual * 9

    /**
     * Every control registers how to redraw itself from the node's own state.
     *
     * This exists because the panel is built in onCreate and the engine -- and
     * with it the setBfree node -- only comes up in onResume. Read the parameters
     * at build time and every drawbar shows 0 while the engine actually holds
     * 88 8000 000. The node is the single source of truth; the panel asks it.
     */
    private val refreshers = ArrayList<() -> Unit>()

    fun refresh() = refreshers.forEach { it() }

    /**
     * Shows or hides the two mix faders this panel carries.
     *
     * A per-instrument level only means something when several instruments
     * sound at once. In a solo part the level is not a choice -- the part is
     * either the one playing, at its own level, or it is silent -- so a fader
     * there is a control that either does nothing or quietly breaks the solo
     * it sits in. ALL is the only place these are a mixer, so it is the only
     * place they appear.
     *
     * Two of them here, and that is exactly where the old behaviour looked
     * arbitrary: this panel is shared by sfizz and setBfree, so soloing either
     * one left both faders on screen and no other part showed any.
     */
    fun setMixVisible(on: Boolean) {
        val how = if (on) View.VISIBLE else View.GONE
        setBfreeRow?.visibility = how
        sfizzRow?.visibility = how
    }

    private fun set(index: Int, value: Float) = NativeBridge.nativeSetSetBfreeParam(index, value)

    fun build(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 32, 0, 0)
        }

        val header = TextView(context).apply {
            setTextColor(Color.parseColor("#C9A227"))
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, 12)
        }
        manualHeader = header

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 40 })
        SetBfree.MANUAL_NAMES.forEachIndexed { index, name ->
            val b = smallButton(name) { selectManual(index) }
            manualButtons.add(b)
            headerRow.addView(b)
        }
        root.addView(headerRow)
        selectManual(0)

        // --- the nine bars
        val barRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 420)
        }
        for (i in 0 until 9) {
            val bar = DrawbarView(context, SetBfree.FOOTAGES[i], SetBfree.COLOURS[i]) { v ->
                set(base() + i, v.toFloat())
            }
            refreshers.add { bar.value = NativeBridge.nativeGetSetBfreeParam(base() + i).toInt() }
            bar.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { rightMargin = 10 }
            bars.add(bar)
            barRow.addView(bar)
        }
        root.addView(barRow)

        // --- presets: the registrations, by the names people use for them
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        NativeBridge.nativeSetBfreePresetNames().forEachIndexed { index, name ->
            presetRow.addView(smallButton(name) {
                NativeBridge.nativeSetBfreePreset(index, manual)
                refreshBars()
            })
        }
        root.addView(horizontalScroll(presetRow))

        // --- switches
        val switchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }

        switchRow.addView(toggle("PERC", SetBfree.PERC_ENABLE))
        switchRow.addView(toggle("FAST", SetBfree.PERC_FAST))
        switchRow.addView(toggle("3rd", SetBfree.PERC_THIRD))
        switchRow.addView(toggle("SOFT", SetBfree.PERC_SOFT))
        switchRow.addView(toggle("VIB UP", SetBfree.VIBRATO_UPPER))
        switchRow.addView(toggle("VIB LOW", SetBfree.VIBRATO_LOWER))
        switchRow.addView(toggle("DRIVE", SetBfree.OVERDRIVE))

        // The scanner has six positions rather than two, so it cycles.
        vibratoButton = smallButton(vibratoLabel()) {
            val next = (NativeBridge.nativeGetSetBfreeParam(SetBfree.VIBRATO_KNOB).toInt() + 1) % 6
            set(SetBfree.VIBRATO_KNOB, next.toFloat())
            vibratoButton?.text = vibratoLabel()
        }
        switchRow.addView(vibratoButton)

        // The Leslie gets the biggest control on the panel, because it is the one
        // thing reached for mid-phrase. The sustain pedal also toggles it -- that
        // is setBfree's default CC64 mapping and it is the classic wiring, so the
        // CP-88's own pedal works as a speed switch with nothing configured.
        rotaryButton = Button(context).apply {
            text = rotaryLabel()
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(48, 24, 48, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 32 }
            setOnClickListener {
                // Stop is a setting, not a performance gesture: tapping cycles
                // between the two speeds you actually play with.
                val current = NativeBridge.nativeGetSetBfreeParam(SetBfree.ROTARY).toInt()
                val next = if (current == 2) 1 else 2
                set(SetBfree.ROTARY, next.toFloat())
                rotaryButton?.text = rotaryLabel()
            }
            setOnLongClickListener {
                set(SetBfree.ROTARY, 0f)
                rotaryButton?.text = rotaryLabel()
                true
            }
        }
        switchRow.addView(rotaryButton)
        refreshers.add {
            rotaryButton?.text = rotaryLabel()
            vibratoButton?.text = vibratoLabel()
        }
        root.addView(horizontalScroll(switchRow))

        // --- levels. setBfree starts at zero: the app opens on sfizz and
        // setBfree is brought in deliberately, which is also what the node does.
        setBfreeRow = slider(Part.SetBfree.label, 0f, keep = { setBfreeFader = it }) {
            set(SetBfree.LEVEL, it * kSetBfreeFaderTop)
        }
        sfizzRow = slider(Part.Sfizz.label, 1f, keep = { sfizzFader = it }) {
            NativeBridge.nativeSetSfizzLevel(it)
        }
        root.addView(setBfreeRow)
        root.addView(sfizzRow)
        val drive = slider("Drive", 0.5f, keep = { driveFader = it }) {
            set(SetBfree.OVERDRIVE_GAIN, it)
        }
        val verb = slider("${Part.SetBfree.label} reverb", 0.15f, keep = { reverbFader = it }) {
            set(SetBfree.REVERB_MIX, it)
        }
        root.addView(drive)
        root.addView(verb)

        // Faders read back from the node like everything else, so a loaded patch
        // shows the levels it actually set rather than where they were left.
        refreshers.add {
            setBfreeFader?.progress =
                (NativeBridge.nativeGetSetBfreeParam(SetBfree.LEVEL) / kSetBfreeFaderTop * 100f).toInt()
            sfizzFader?.progress = (NativeBridge.nativeGetSfizzLevel() * 100f).toInt()
            driveFader?.progress =
                (NativeBridge.nativeGetSetBfreeParam(SetBfree.OVERDRIVE_GAIN) * 100f).toInt()
            reverbFader?.progress =
                (NativeBridge.nativeGetSetBfreeParam(SetBfree.REVERB_MIX) * 100f).toInt()
        }

        // --- one keyboard, three manuals
        val splitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        splitRow.addView(TextView(context).apply {
            text = "Split:"
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 16, 0)
        })
        // Off, then the two splits an organist actually uses on one keyboard.
        splitRow.addView(smallButton("OFF") { setSplit(0, 0) })
        splitRow.addView(smallButton("LOWER @ C3") { setSplit(0, 60) })
        splitRow.addView(smallButton("PEDALS + LOWER") { setSplit(48, 60) })
        root.addView(splitRow)

        return root
    }

    /**
     * Switch parts in one call, faders and all.
     *
     * The faders are the real control, but they live at the bottom of a panel
     * whose drawbars swallow a drag -- so reaching them mid-test means scrolling
     * past nine controls that each grab the touch. This exists so the top button
     * row, which is always on screen, can do it in one tap. The faders move with
     * it: two places showing different levels would be worse than the scroll.
     */
    fun setPart(part: Part) {
        val setBfreeLevel = if (part == Part.SetBfree || part == Part.All) kSetBfreeDefaultLevel else 0f
        val sfizzLevel = if (part == Part.Sfizz || part == Part.All) 1f else 0f

        set(SetBfree.LEVEL, setBfreeLevel)
        NativeBridge.nativeSetSfizzLevel(sfizzLevel)

        setBfreeFader?.progress = (setBfreeLevel / kSetBfreeFaderTop * 100f).toInt()
        sfizzFader?.progress = (sfizzLevel * 100f).toInt()
    }

    private fun selectManual(which: Int) {
        manual = which.coerceIn(0, 2)
        manualHeader?.text = "SETBFREE — ${SetBfree.MANUAL_NAMES[manual].lowercase()} manual"
        manualButtons.forEachIndexed { index, b ->
            val on = index == manual
            b.setTextColor(if (on) Color.parseColor("#14161A") else Color.parseColor("#C9D1E0"))
            b.setBackgroundColor(
                if (on) Color.parseColor("#C9A227") else Color.parseColor("#2A2F38")
            )
        }
        refreshBars()
    }

    private fun setSplit(pedal: Int, lower: Int) {
        set(SetBfree.SPLIT_PEDAL, pedal.toFloat())
        set(SetBfree.SPLIT_LOWER, lower.toFloat())
    }

    private fun refreshBars() {
        bars.forEachIndexed { i, bar ->
            bar.value = NativeBridge.nativeGetSetBfreeParam(base() + i).toInt()
        }
    }

    private fun rotaryLabel() =
        "LESLIE ${SetBfree.ROTARY_NAMES[NativeBridge.nativeGetSetBfreeParam(SetBfree.ROTARY).toInt().coerceIn(0, 2)]}"

    private fun vibratoLabel() =
        SetBfree.VIBRATO_NAMES[NativeBridge.nativeGetSetBfreeParam(SetBfree.VIBRATO_KNOB).toInt().coerceIn(0, 5)]

    private fun horizontalScroll(child: View): View =
        android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(child)
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

    private fun toggle(label: String, param: Int): Button {
        var on = NativeBridge.nativeGetSetBfreeParam(param) >= 0.5f
        val button = Button(context)
        fun paint() {
            button.text = label
            button.setTextColor(
                if (on) Color.parseColor("#14161A") else Color.parseColor("#C9D1E0")
            )
            button.setBackgroundColor(
                if (on) Color.parseColor("#C9A227") else Color.parseColor("#2A2F38")
            )
        }
        button.textSize = 15f
        button.setPadding(24, 16, 24, 16)
        button.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 16 }
        paint()
        button.setOnClickListener {
            on = !on
            NativeBridge.nativeSetSetBfreeParam(param, if (on) 1f else 0f)
            paint()
        }
        refreshers.add {
            on = NativeBridge.nativeGetSetBfreeParam(param) >= 0.5f
            paint()
        }
        return button
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
        val caption = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(260, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
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
        row.addView(caption)
        row.addView(bar)
        return row
    }
}
