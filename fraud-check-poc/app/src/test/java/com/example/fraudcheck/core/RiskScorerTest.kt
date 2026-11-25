package com.example.fraudcheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskScorerTest {
    private fun s(id: String, flagged: Boolean, w: Int, hard: Boolean = false) =
        Signal(id, id, flagged, w, "e", hardStop = hard)

    @Test fun cleanDeviceIsLow() {
        val r = RiskScorer.score(listOf(s("a", false, 40), s("b", false, 30)))
        assertEquals(0, r.score); assertEquals(Band.LOW, r.band)
    }

    @Test fun weightsAddUpToMedium() {
        val r = RiskScorer.score(listOf(s("vpn", true, 10), s("paste", true, 15), s("battery", true, 10), s("x", false, 50)))
        assertEquals(35, r.score); assertEquals(Band.MEDIUM, r.band)
    }

    @Test fun scoreIsCappedAt100() {
        val r = RiskScorer.score(listOf(s("a", true, 80), s("b", true, 80)))
        assertEquals(100, r.score); assertEquals(Band.HIGH, r.band)
    }

    @Test fun hardStopForcesHigh() {
        val r = RiskScorer.score(listOf(s("cloner", true, 5, hard = true)))
        assertEquals(100, r.score); assertEquals(1, r.hardStops.size)
    }

    @Test fun jsonEscapesEvidence() {
        val sig = Signal("id", "t", true, 5, "path \"x\"\nnext")
        val json = RiskScorer.toJson(RiskScorer.score(listOf(sig)), "sess")
        assertTrue(json.contains("path \\\"x\\\"\\nnext"))
    }
}
