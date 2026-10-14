package com.rigalong

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A playable keyboard, so the instrument can be demonstrated with no CP-88
 * attached — through the tablet's own speaker if that is all there is.
 *
 * Drawn rather than assembled from child views, for three reasons that all come
 * from wanting it to behave like a keyboard rather than like a row of buttons:
 *
 *  * **Black keys overlap the white ones.** That is the actual geometry, and it
 *    cannot be had from a LinearLayout without a custom ViewGroup doing the
 *    same hit-testing this does anyway.
 *  * **Sliding across the keys has to sound.** A glissando is one finger
 *    crossing key boundaries, so the note under each pointer is tracked on every
 *    move and retriggered when it changes. Child views cannot see that: Android
 *    delivers the whole gesture to whichever view the finger first went down on.
 *  * **Several fingers at once**, each on its own note, which means tracking by
 *    pointer id rather than by a single pressed flag.
 *
 * Velocity comes from WHERE the key is struck — near the top is soft, near the
 * bottom is loud, as on a real keybed where the pivot leaves less leverage at
 * the back. Without it every note arrives mezzo-forte and a sampled piano with
 * sixteen velocity layers is wasted.
 */
class TouchKeyboardView(
    context: Context,
    private val lowNote: Int = 48,      // C3
    private val highNote: Int = 84,     // C6 — three octaves
    private val onNote: (Int, Int) -> Unit,
) : View(context) {

    private val whiteNotes = ArrayList<Int>()
    /** note -> index of the white key it sits after, for the black keys. */
    private val blackNotes = ArrayList<Pair<Int, Int>>()

    /** Which note each active finger is currently holding. */
    private val heldByPointer = HashMap<Int, Int>()
    /**
     * The fingers that went down on the keys. Once one finger is down here,
     * Android hands this view every further finger too -- one on a drawbar
     * above the keys included --, and the key under a finger is found by its
     * x alone: such a finger played the key below it without touching it.
     */
    private val ownPointers = HashSet<Int>()

    private fun inside(x: Float, y: Float) = x >= 0f && y >= 0f && x < width && y < height

    private val whitePaint = Paint().apply { color = Color.parseColor("#E8ECF2") }
    private val whiteDown = Paint().apply { color = Color.parseColor("#7FD1B9") }
    private val blackPaint = Paint().apply { color = Color.parseColor("#15181D") }
    private val blackDown = Paint().apply { color = Color.parseColor("#3E8C79") }
    private val edge = Paint().apply {
        color = Color.parseColor("#0A0C0F")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val label = Paint().apply {
        color = Color.parseColor("#8A94A6")
        textSize = 26f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    init {
        for (n in lowNote..highNote) {
            if (isBlack(n)) blackNotes.add(n to whiteNotes.size) else whiteNotes.add(n)
        }
        isFocusable = false
    }

    private fun isBlack(note: Int) = (note % 12) in setOf(1, 3, 6, 8, 10)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Reported rather than guessed from a screenshot. The number that
        // decides whether a chord is playable is the white-only strip, and
        // reading it off a scaled screenshot got it wrong twice.
        android.util.Log.i("rigalong-ui",
            "keyboard: ${w}x${h} px, white key ${"%.0f".format(whiteWidth)} wide, " +
                "black ${"%.0f".format(blackHeight)} long, " +
                "white-only strip ${"%.0f".format(h - blackHeight)} px")
    }

    private val whiteWidth: Float
        get() = if (whiteNotes.isEmpty()) 1f else width.toFloat() / whiteNotes.size
    private val blackWidth: Float get() = whiteWidth * 0.62f

    // A real piano's black keys are about 63 % of the white key's length, and
    // that ratio was the first thing tried. On glass it plays badly: what
    // matters for a chord is not the RATIO but the absolute size of the strip of
    // white key left reachable below the black ones, and at 320 px tall that
    // strip was 122 px -- about 1,3 cm, too little to hit reliably with three
    // fingers at once.
    //
    // Taken mostly out of the ratio rather than out of the screen: at 0,55 and
    // 360 px the black keys stay the same absolute length they already were
    // (198 px) while the white-only strip grows to 162 px, a third more, for
    // 40 px of extra height. Slightly stubby black keys, a keyboard that can be
    // played.
    private val blackHeight: Float get() = height * 0.55f

    override fun onDraw(canvas: Canvas) {
        val w = whiteWidth
        val r = RectF()
        for ((i, note) in whiteNotes.withIndex()) {
            r.set(i * w, 0f, (i + 1) * w, height.toFloat())
            canvas.drawRect(r, if (heldByPointer.containsValue(note)) whiteDown else whitePaint)
            canvas.drawRect(r, edge)
            if (note % 12 == 0) {
                canvas.drawText("C${note / 12 - 1}", (i + 0.5f) * w, height - 14f, label)
            }
        }
        for ((note, after) in blackNotes) {
            val cx = after * w
            r.set(cx - blackWidth / 2f, 0f, cx + blackWidth / 2f, blackHeight)
            canvas.drawRect(r, if (heldByPointer.containsValue(note)) blackDown else blackPaint)
        }
    }

    /** The note under a point, black keys taking precedence where they overlap. */
    private fun noteAt(x: Float, y: Float): Int? {
        if (whiteNotes.isEmpty()) return null
        val w = whiteWidth
        if (y <= blackHeight) {
            for ((note, after) in blackNotes) {
                val cx = after * w
                if (x >= cx - blackWidth / 2f && x <= cx + blackWidth / 2f) return note
            }
        }
        val i = (x / w).toInt()
        if (i < 0 || i >= whiteNotes.size) return null
        return whiteNotes[i]
    }

    private fun velocityAt(y: Float, note: Int): Int {
        val span = if (isBlack(note)) blackHeight else height.toFloat()
        val frac = (y / span).coerceIn(0f, 1f)
        return (40 + frac * 87).toInt().coerceIn(1, 127)
    }

    private fun press(pointerId: Int, x: Float, y: Float) {
        val note = noteAt(x, y) ?: return
        val already = heldByPointer[pointerId]
        if (already == note) return

        // Whether this note is ALREADY sounding under some other finger, asked
        // before this pointer's own entry is changed.
        val soundingElsewhere = heldByPointer.any { it.key != pointerId && it.value == note }

        heldByPointer[pointerId] = note

        // Release the note this finger was on -- but only if no OTHER finger is
        // still holding it. Without that test, a left hand sustaining a chord is
        // cut off the moment a right hand slides off the same note, which is
        // exactly what happens when two hands meet in the middle of the keyboard.
        if (already != null && !heldByPointer.containsValue(already)) onNote(already, 0)

        // And do not double-trigger a note two fingers happen to share: on a real
        // keybed one key makes one sound, and sfizz would otherwise start a
        // second voice and play it twice as loud.
        if (!soundingElsewhere) onNote(note, velocityAt(y, note))
    }

    private fun release(pointerId: Int) {
        val note = heldByPointer.remove(pointerId) ?: return
        // Only silence it if no OTHER finger is still on that same note, which
        // happens constantly during a two-handed slide.
        if (!heldByPointer.containsValue(note)) onNote(note, 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                if (inside(event.getX(i), event.getY(i))) {
                    ownPointers += id
                    press(id, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Every finger of ours, every move: this is what makes a slide
                // sound. One slid off the keys lets its key go.
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id !in ownPointers) continue
                    if (inside(event.getX(i), event.getY(i))) press(id, event.getX(i), event.getY(i)) else release(id)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                ownPointers -= id
                release(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                ownPointers.clear()
                for (id in heldByPointer.keys.toList()) release(id)
            }
            else -> return false
        }
        invalidate()
        return true
    }
}
