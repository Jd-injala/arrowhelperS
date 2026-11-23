package com.example.arrowhelper

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Dir(val dr: Int, val dc: Int, val glyph: String) {
    UP(-1, 0, "↑"), DOWN(1, 0, "↓"), LEFT(0, -1, "←"), RIGHT(0, 1, "→")
}

/** The puzzle grid: one arrow (or nothing) per cell. */
class Board(val rows: Int, val cols: Int, val cells: Array<Dir?>) {
    operator fun get(r: Int, c: Int): Dir? = cells[r * cols + c]
    val arrowCount: Int get() = cells.count { it != null }
}

data class Move(val row: Int, val col: Int, val dir: Dir)

/** moves = tap order; stuck = cells whose arrow can never leave (usually a detection mistake). */
class SolveResult(val moves: List<Move>, val stuck: List<Pair<Int, Int>>)

/**
 * "Arrow escape" rules: tapping an arrow makes it fly off the board in the direction it
 * points, but only if no other arrow is in its path. Removing an arrow never blocks
 * another one, so repeatedly removing any free arrow always finds a solution if one exists.
 */
object Solver {
    fun solve(board: Board): SolveResult {
        val g = board.cells.copyOf()
        val moves = mutableListOf<Move>()
        var progress = true
        while (progress) {
            progress = false
            for (r in 0 until board.rows) for (c in 0 until board.cols) {
                val d = g[r * board.cols + c] ?: continue
                if (pathClear(g, board.rows, board.cols, r, c, d)) {
                    moves += Move(r, c, d)
                    g[r * board.cols + c] = null
                    progress = true
                }
            }
        }
        val stuck = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until board.rows) for (c in 0 until board.cols)
            if (g[r * board.cols + c] != null) stuck += r to c
        return SolveResult(moves, stuck)
    }

    private fun pathClear(g: Array<Dir?>, rows: Int, cols: Int, r0: Int, c0: Int, d: Dir): Boolean {
        var r = r0 + d.dr
        var c = c0 + d.dc
        while (r in 0 until rows && c in 0 until cols) {
            if (g[r * cols + c] != null) return false
            r += d.dr; c += d.dc
        }
        return true
    }
}

/**
 * Reads the grid from a screenshot. For each cell it:
 *  1. takes the most common colour as the cell background,
 *  2. marks pixels that differ by more than [threshold] as "arrow",
 *  3. finds the arrow's long axis, then the arrowhead = the half that is widest.
 */
object ArrowDetector {

    fun detect(bmp: Bitmap, grid: RectF, rows: Int, cols: Int, threshold: Int): Board {
        val cells = arrayOfNulls<Dir>(rows * cols)
        val cw = grid.width() / cols
        val ch = grid.height() / rows
        val inset = 0.1f // skip grid lines / tile borders
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = (grid.left + (c + inset) * cw).toInt().coerceIn(0, bmp.width - 1)
            val x1 = (grid.left + (c + 1 - inset) * cw).toInt().coerceIn(x0 + 1, bmp.width)
            val y0 = (grid.top + (r + inset) * ch).toInt().coerceIn(0, bmp.height - 1)
            val y1 = (grid.top + (r + 1 - inset) * ch).toInt().coerceIn(y0 + 1, bmp.height)
            val w = x1 - x0
            val h = y1 - y0
            if (w < 6 || h < 6) continue
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, x0, y0, w, h)
            cells[r * cols + c] = classifyCell(px, w, h, threshold)
        }
        return Board(rows, cols, cells)
    }

    fun classifyCell(px: IntArray, w: Int, h: Int, threshold: Int): Dir? {
        val bg = dominantColor(px)
        val mask = BooleanArray(px.size)
        var n = 0
        for (i in px.indices) if (colorDistance(px[i], bg) > threshold) { mask[i] = true; n++ }
        if (n < px.size * 0.03 || n > px.size * 0.7) return null // empty cell / not an arrow
        return direction(mask, w, h)
    }

    fun direction(mask: BooleanArray, w: Int, h: Int): Dir {
        // Centroid and spread -> which axis the arrow lies along.
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) { sx += x; sy += y; n++ }
        val mx = sx / n
        val my = sy / n
        var vxx = 0.0; var vyy = 0.0
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            vxx += (x - mx) * (x - mx); vyy += (y - my) * (y - my)
        }
        val horizontal = vxx >= vyy

        // Thickness of the shape at each position along that axis.
        val len = if (horizontal) w else h
        val lo = IntArray(len) { Int.MAX_VALUE }
        val hi = IntArray(len) { -1 }
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            val a = if (horizontal) x else y
            val b = if (horizontal) y else x
            lo[a] = min(lo[a], b); hi[a] = max(hi[a], b)
        }
        var first = 0
        while (hi[first] < 0) first++
        var last = len - 1
        while (hi[last] < 0) last--

        // The tip tapers to a point; the tail end is as thick as the shaft.
        // So the end with the smaller average thickness is where the arrow points.
        val k = max(2, ((last - first + 1) * 0.06).toInt())
        fun span(a: Int) = if (hi[a] >= 0) hi[a] - lo[a] + 1 else 0
        var lowEnd = 0; var highEnd = 0
        for (i in 0 until k) {
            lowEnd += span(min(first + i, last))
            highEnd += span(max(last - i, first))
        }
        val towardHigh = highEnd < lowEnd
        return if (horizontal) { if (towardHigh) Dir.RIGHT else Dir.LEFT }
        else { if (towardHigh) Dir.DOWN else Dir.UP }
    }

    private fun dominantColor(px: IntArray): Int {
        val counts = IntArray(4096)
        for (p in px) counts[bin(p)]++
        var best = 0
        for (i in 1 until 4096) if (counts[i] > counts[best]) best = i
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (p in px) if (bin(p) == best) {
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun bin(p: Int) = ((p shr 12) and 0xF00) or ((p shr 8) and 0xF0) or ((p shr 4) and 0xF)

    private fun colorDistance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))
}
