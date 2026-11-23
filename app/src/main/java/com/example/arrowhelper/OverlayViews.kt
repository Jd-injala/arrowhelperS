package com.example.arrowhelper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Full-screen box you drag so it covers the puzzle grid. */
@SuppressLint("ViewConstructor")
class GridEditorView(
    ctx: Context,
    private val initialScreenRect: RectF?,
) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val loc = IntArray(2)
    private var rect: RectF? = null
    private var mode = 0 // 0 none, 1 corner, 2 move
    private var corner = -1
    private var lastX = 0f
    private var lastY = 0f

    private val dim = Paint().apply { color = 0x33000000 }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3 * d
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16 * d; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private fun ensureRect(): RectF {
        rect?.let { return it }
        getLocationOnScreen(loc)
        val r = initialScreenRect?.let { RectF(it).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()) } }
            ?: RectF(width * 0.08f, height * 0.3f, width * 0.92f, height * 0.65f)
        rect = r
        return r
    }

    /** Current box in screen coordinates. */
    fun screenRect(): RectF? {
        val r = rect ?: return null
        getLocationOnScreen(loc)
        return RectF(r).apply { offset(loc[0].toFloat(), loc[1].toFloat()) }
    }

    override fun onDraw(canvas: Canvas) {
        val r = ensureRect()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(r, frame)
        for ((x, y) in corners(r)) canvas.drawCircle(x, y, 12 * d, handle)
        canvas.drawText("Cover the whole puzzle, with a little margin", r.left, r.top - 14 * d, label)
    }

    private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = ensureRect()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                corner = corners(r).indexOfFirst { (x, y) -> hypot(e.x - x, e.y - y) < 40 * d }
                mode = when { corner >= 0 -> 1; r.contains(e.x, e.y) -> 2; else -> 0 }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                val old = RectF(r)
                when (mode) {
                    1 -> when (corner) {
                        0 -> { r.left += dx; r.top += dy }
                        1 -> { r.right += dx; r.top += dy }
                        2 -> { r.right += dx; r.bottom += dy }
                        3 -> { r.left += dx; r.bottom += dy }
                    }
                    2 -> r.offset(dx, dy)
                }
                if (r.width() < 40 * d || r.height() < 40 * d) r.set(old)
                lastX = e.x; lastY = e.y
                invalidate()
            }
        }
        return true
    }
}

/** Draws the tap order at each arrow's head. Touches pass straight through it. */
@SuppressLint("ViewConstructor")
class HintView(
    ctx: Context,
    private val board: Board,
    result: SolveResult,
) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val loc = IntArray(2)
    private val order = result.order
    private val stuck = result.stuck
    private var done = 0

    private val next = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }
    private val later = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD2E7D32.toInt() }
    private val stuckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDD32F2F.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    /** Steps before [n] are finished and no longer drawn. */
    fun setDone(n: Int) { done = n; invalidate() }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        val spacing = if (board.cols > 1) board.xs[1] - board.xs[0] else 40f
        val radius = min(spacing * 0.42f, 16 * d)
        text.textSize = radius * 1.05f

        fun mark(arrowIndex: Int, paint: Paint, label: String) {
            val h = board.arrows[arrowIndex].head
            val cx = board.xs[h.c] - loc[0]
            val cy = board.ys[h.r] - loc[1]
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
        }
        for (i in stuck) mark(i, stuckPaint, "!")
        // Draw later steps first so the next one ends up on top.
        for (step in order.indices.reversed()) {
            if (step < done) continue
            mark(order[step], if (step == done) next else later, "${step + 1}")
        }
    }
}
