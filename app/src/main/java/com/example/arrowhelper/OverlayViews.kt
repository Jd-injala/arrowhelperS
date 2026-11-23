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
    private val rows: Int,
    private val cols: Int,
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
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA00FFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1 * d
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
        for (i in 1 until cols) {
            val x = r.left + r.width() * i / cols
            canvas.drawLine(x, r.top, x, r.bottom, line)
        }
        for (i in 1 until rows) {
            val y = r.top + r.height() * i / rows
            canvas.drawLine(r.left, y, r.right, y, line)
        }
        canvas.drawRect(r, frame)
        for ((x, y) in corners(r)) canvas.drawCircle(x, y, 12 * d, handle)
        canvas.drawText("$rows × $cols — drag corners to fit the grid", r.left, r.top - 14 * d, label)
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

/** Draws the tap order over the puzzle. Touches pass straight through it. */
@SuppressLint("ViewConstructor")
class HintView(
    ctx: Context,
    private val grid: RectF,
    private val board: Board,
    result: SolveResult,
) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val loc = IntArray(2)
    private val order = HashMap<Int, Int>()
    private var done = 0

    private val next = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }
    private val later = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD2E7D32.toInt() }
    private val stuck = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDD32F2F.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val dirText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; textAlign = Paint.Align.CENTER; setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8800FFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d
    }

    init {
        result.moves.forEachIndexed { i, m -> order[m.row * board.cols + m.col] = i }
    }

    /** Steps before [n] are finished and no longer drawn. */
    fun setDone(n: Int) { done = n; invalidate() }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        val ox = grid.left - loc[0]
        val oy = grid.top - loc[1]
        val cw = grid.width() / board.cols
        val ch = grid.height() / board.rows
        val radius = min(cw, ch) * 0.26f
        text.textSize = radius * 1.1f
        dirText.textSize = radius * 0.9f
        canvas.drawRect(ox, oy, ox + grid.width(), oy + grid.height(), outline)

        for (r in 0 until board.rows) for (c in 0 until board.cols) {
            val dir = board[r, c] ?: continue
            val cx = ox + (c + 0.5f) * cw
            val cy = oy + (r + 0.5f) * ch
            val step = order[r * board.cols + c]
            if (step != null && step < done) continue
            val paint = when {
                step == null -> stuck
                step == done -> next
                else -> later
            }
            canvas.drawCircle(cx, cy, radius, paint)
            val label = if (step == null) "!" else "${step + 1}"
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
            // What the detector thinks this arrow is — handy for spotting mistakes.
            canvas.drawText(dir.glyph, cx, oy + r * ch + ch * 0.18f, dirText)
        }
    }
}
