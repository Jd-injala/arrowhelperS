package com.example.arrowhelper

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the detector + solver on a real screenshot of the game. */
class DetectorTest {

    @Test
    fun readsAndSolvesRealBoard() {
        // Crop of a real screenshot (raw RGB), the same box a user would draw around the puzzle.
        val l = 140; val t = 870; val w = 800; val h = 1080
        val raw = GZIPInputStream(javaClass.getResourceAsStream("/board1.rgb.gz")!!).readBytes()
        val px = IntArray(w * h) { i ->
            val r = raw[i * 3].toInt() and 0xFF
            val g = raw[i * 3 + 1].toInt() and 0xFF
            val b = raw[i * 3 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val board = ArrowDetector.detect(px, w, h, l.toFloat(), t.toFloat(), 90)!!

        assertEquals("dot columns", 19, board.cols)
        assertEquals("dot rows", 26, board.rows)
        assertEquals("unreadable shapes", 0, board.unreadable)
        assertEquals("arrows", 39, board.arrows.size)

        // Spot checks against the screenshot.
        val up = board.arrows.single { it.head == Node(2, 11) }        // short arrow near the top, pointing up
        assertEquals(-1, up.dr); assertEquals(0, up.dc)
        val longDown = board.arrows.single { it.head == Node(25, 0) }  // long arrow down the left edge
        assertEquals(1, longDown.dr)

        val result = Solver.solve(board)
        assertTrue("stuck: ${result.stuck}", result.stuck.isEmpty())
        assertEquals(39, result.order.size)
        // The red arrow pointing down at the left (head at row 14) is blocked by the long one below it.
        val red = board.arrows.indexOfFirst { it.head == Node(14, 0) }
        val blocker = board.arrows.indexOf(longDown)
        assertTrue(result.order.indexOf(blocker) < result.order.indexOf(red))
    }
}
