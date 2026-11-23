package com.example.arrowhelper

import kotlin.math.roundToInt

/** A lattice node: row and column on the dot grid. */
data class Node(val r: Int, val c: Int)

/**
 * One arrow: a bent line along the dot grid. [nodes] runs tail → head,
 * so the arrow points from nodes[size-2] to nodes[size-1].
 */
class Arrow(val nodes: List<Node>) {
    val head: Node get() = nodes.last()
    val dr: Int get() = nodes.last().r - nodes[nodes.size - 2].r
    val dc: Int get() = nodes.last().c - nodes[nodes.size - 2].c
    /** A point on the arrow to tap: its middle node. */
    val tapNode: Node get() = nodes[nodes.size / 2]
}

/** Board read from the screen. xs/ys are the screen positions of the dot columns/rows. */
class Board(val xs: FloatArray, val ys: FloatArray, val arrows: List<Arrow>, val unreadable: Int) {
    val rows get() = ys.size
    val cols get() = xs.size
}

/** order = arrow indices in tap order; stuck = arrows that can never leave. */
class SolveResult(val order: List<Int>, val stuck: List<Int>)

/**
 * Tapping an arrow sends it forward: the head travels straight on in the direction it points
 * and the body follows. It escapes only if nothing is in the head's way all the way to the edge.
 * Removing an arrow never blocks another, so "remove any free arrow, repeat" finds a full
 * solution whenever one exists.
 */
object Solver {
    fun solve(board: Board): SolveResult {
        val owner = HashMap<Node, Pair<Int, Int>>() // node -> (arrow, index along it, 0 = tail)
        board.arrows.forEachIndexed { i, a -> a.nodes.forEachIndexed { j, n -> owner[n] = i to j } }
        val alive = board.arrows.indices.toMutableSet()
        val order = mutableListOf<Int>()

        fun free(i: Int): Boolean {
            val a = board.arrows[i]
            var r = a.head.r
            var c = a.head.c
            var steps = 0
            while (true) {
                r += a.dr; c += a.dc; steps++
                if (r !in 0 until board.rows || c !in 0 until board.cols) return true
                val o = owner[Node(r, c)] ?: continue
                if (o.first !in alive) continue
                if (o.first != i) return false
                // Its own body: the tail end moves out of the way as the arrow advances.
                if (steps <= o.second) return false
            }
        }

        var progress = true
        while (progress) {
            progress = false
            for (i in alive.sorted()) {
                if (free(i)) { order += i; alive.remove(i); progress = true }
            }
        }
        return SolveResult(order, alive.sorted())
    }
}

/**
 * Reads the board from a screenshot region (ARGB pixels, row-major).
 *  1. Bright pixels (much brighter than the background) are arrow lines; the dim dots are ignored.
 *  2. Lines only run along the dot grid, so the grid spacing is found from where the lines bunch up.
 *  3. Each grid node is checked for a line, and each pair of neighbouring nodes for a joining line.
 *  4. Joined nodes form one arrow; its head is the end with the arrowhead (extra pixels around it).
 */
object ArrowDetector {

    /**
     * @param px pixels of the region, [w]×[h], row-major ARGB
     * @param left/top screen position of the region, added to the returned coordinates
     */
    fun detect(px: IntArray, w: Int, h: Int, left: Float, top: Float, threshold: Int): Board? {
        val bgMax = backgroundMax(px)
        val fg = BooleanArray(w * h) { maxChannel(px[it]) - bgMax > threshold }

        val hx = IntArray(w)
        val hy = IntArray(h)
        for (y in 0 until h) for (x in 0 until w) if (fg[y * w + x]) { hx[x]++; hy[y]++ }
        val (pX, oX) = lattice(hx) ?: return null
        val (pY, oY) = lattice(hy) ?: return null

        val xs = positions(oX, pX, w)
        val ys = positions(oY, pY, h)
        val nr = ys.size
        val nc = xs.size
        if (nr < 2 || nc < 2) return null

        fun on(x: Float, y: Float, rad: Int): Boolean {
            val cx = x.roundToInt()
            val cy = y.roundToInt()
            for (dy in -rad..rad) for (dx in -rad..rad) {
                val xx = cx + dx
                val yy = cy + dy
                if (xx in 0 until w && yy in 0 until h && fg[yy * w + xx]) return true
            }
            return false
        }

        val occ = Array(nr) { r -> BooleanArray(nc) { c -> on(xs[c], ys[r], 3) } }
        val adj = HashMap<Node, MutableList<Node>>()
        for (r in 0 until nr) for (c in 0 until nc) {
            if (!occ[r][c]) continue
            val n = Node(r, c)
            adj.getOrPut(n) { mutableListOf() }
            if (c + 1 < nc && occ[r][c + 1] && on((xs[c] + xs[c + 1]) / 2, ys[r], 2)) link(adj, n, Node(r, c + 1))
            if (r + 1 < nr && occ[r + 1][c] && on(xs[c], (ys[r] + ys[r + 1]) / 2, 2)) link(adj, n, Node(r + 1, c))
        }

        fun headScore(end: Node, before: Node): Int {
            val dr = end.r - before.r
            val dc = end.c - before.c
            val x = xs[end.c]
            val y = ys[end.r]
            var s = 0
            for (t in floatArrayOf(0.25f, 0.35f)) if (on(x + dc * pX * t, y + dr * pY * t, 1)) s++
            for (t in floatArrayOf(0.22f, 0.3f)) for (sg in intArrayOf(1, -1)) {
                if (on(x + dr * pX * t * sg, y + dc * pY * t * sg, 1)) s++
            }
            return s
        }

        val seen = HashSet<Node>()
        val arrows = mutableListOf<Arrow>()
        var unreadable = 0
        for (start in adj.keys) {
            if (start in seen) continue
            val comp = mutableListOf<Node>()
            val stack = ArrayDeque<Node>().apply { add(start) }
            seen += start
            while (stack.isNotEmpty()) {
                val u = stack.removeLast()
                comp += u
                for (v in adj.getValue(u)) if (seen.add(v)) stack.add(v)
            }
            if (comp.size < 2) continue // stray pixel
            val ends = comp.filter { adj.getValue(it).size == 1 }
            if (ends.size != 2 || comp.any { adj.getValue(it).size > 2 }) { unreadable++; continue }

            val path = mutableListOf(ends[0])
            var prev: Node? = null
            while (true) {
                val u = path.last()
                val next = adj.getValue(u).firstOrNull { it != prev } ?: break
                prev = u
                path += next
            }
            val a = headScore(path.last(), path[path.size - 2])
            val b = headScore(path[0], path[1])
            if (b > a) path.reverse()
            arrows += Arrow(path)
        }

        return Board(
            FloatArray(nc) { left + xs[it] },
            FloatArray(nr) { top + ys[it] },
            arrows,
            unreadable,
        )
    }

    private fun link(adj: HashMap<Node, MutableList<Node>>, a: Node, b: Node) {
        adj.getOrPut(a) { mutableListOf() }.add(b)
        adj.getOrPut(b) { mutableListOf() }.add(a)
    }

    private fun positions(o: Float, p: Float, n: Int): FloatArray {
        val out = mutableListOf<Float>()
        var k = 0
        while ((o + k * p).roundToInt() < n) { out += o + k * p; k++ }
        return out.toFloatArray()
    }

    private data class Fit(val score: Double, val p: Double, val o: Double)

    /** Finds grid spacing and offset (px) from a projection of line pixels. */
    private fun lattice(h: IntArray): Pair<Float, Float>? {
        val n = h.size
        val perPeriod = mutableListOf<Fit>()
        var best: Fit? = null
        var p10 = 150 // spacing 15.0 .. 150.0 px, in 0.2 px steps
        while (p10 < 1500 && p10 / 10.0 * 3 < n) {
            val p = p10 / 10.0
            var here: Fit? = null
            var o10 = 0
            while (o10 < p10) {
                val o = o10 / 10.0
                var s = 0L; var m = 0L; var k = 0
                while (true) {
                    val i = Math.round(o + k * p).toInt()
                    if (i >= n) break
                    s += h[i]
                    val mi = Math.round(o + k * p + p / 2).toInt()
                    if (mi < n) m += h[mi]
                    k++
                }
                if (k >= 3) {
                    // On-grid minus half-way-between: rewards the true spacing, not its multiples.
                    val sc = (s - m).toDouble() / k
                    if (here == null || sc > here.score) here = Fit(sc, p, o)
                }
                o10 += 5
            }
            here?.let { perPeriod += it; if (best == null || it.score > best!!.score) best = it }
            p10 += 2
        }
        val b = best ?: return null
        if (b.score <= 0) return null
        // Smallest spacing that scores nearly as well as the best (avoids picking 2× or 3× the spacing).
        val coarse = perPeriod.first { it.score >= 0.6 * b.score }

        var r = Fit(Double.NEGATIVE_INFINITY, coarse.p, coarse.o)
        for (i in -50..50) {
            val p = coarse.p + i * 0.02
            if (p < 10) continue
            for (j in -8..8) {
                val o = coarse.o + j * 0.25
                if (o < 0) continue
                var s = 0L; var k = 0
                while (true) {
                    val idx = Math.round(o + k * p).toInt()
                    if (idx >= n) break
                    s += h[idx]; k++
                }
                if (k == 0) continue
                val sc = s.toDouble() / k
                if (sc > r.score) r = Fit(sc, p, o)
            }
        }
        return r.p.toFloat() to r.o.toFloat()
    }

    /** Brightest channel of the most common colour (the board background). */
    private fun backgroundMax(px: IntArray): Int {
        val counts = IntArray(4096)
        var i = 0
        while (i < px.size) { counts[bin(px[i])]++; i += 3 }
        var best = 0
        for (k in 1 until 4096) if (counts[k] > counts[best]) best = k
        val r = (best shr 8) and 0xF
        val g = (best shr 4) and 0xF
        val b = best and 0xF
        return maxOf(r, g, b) * 16 + 8
    }

    private fun bin(p: Int) = ((p shr 12) and 0xF00) or ((p shr 8) and 0xF0) or ((p shr 4) and 0xF)

    private fun maxChannel(p: Int) = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
}
