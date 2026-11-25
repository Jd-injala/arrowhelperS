package com.example.fraudcheck.core

enum class Band(val label: String) {
    LOW("Continue"),
    MEDIUM("Step-up: video KYC or manual review"),
    HIGH("Reject or manual review"),
}

data class RiskReport(
    val score: Int,
    val band: Band,
    val hardStops: List<Signal>,
    val signals: List<Signal>,
)

/**
 * Local PoC scorer. Production scoring runs on the backend after verifying the
 * Play Integrity token and key attestation; this only previews what it would see.
 */
object RiskScorer {
    const val MEDIUM_FROM = 35
    const val HIGH_FROM = 70

    fun score(signals: List<Signal>): RiskReport {
        val flagged = signals.filter { it.flagged }
        val raw = flagged.sumOf { it.weight.coerceAtLeast(0) }
        val hardStops = flagged.filter { it.hardStop }
        val score = if (hardStops.isNotEmpty()) 100 else raw.coerceIn(0, 100)
        val band = when {
            score >= HIGH_FROM -> Band.HIGH
            score >= MEDIUM_FROM -> Band.MEDIUM
            else -> Band.LOW
        }
        return RiskReport(score, band, hardStops, signals.sortedByDescending { if (it.flagged) it.weight else -1 })
    }

    /** Minimal JSON without Android deps so the report can be unit-tested and sent to a backend. */
    fun toJson(report: RiskReport, sessionId: String, deviceInfo: List<Pair<String, String>> = emptyList()): String {
        fun esc(s: String) = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
                '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        val items = report.signals.joinToString(",") { s ->
            """{"id":"${esc(s.id)}","flagged":${s.flagged},"weight":${s.weight},"trust":"${s.trust}","hardStop":${s.hardStop},"evidence":"${esc(s.evidence)}"}"""
        }
        val device = deviceInfo.joinToString(",") { (k, v) -> "\"${esc(k)}\":\"${esc(v)}\"" }
        return """{"sessionId":"${esc(sessionId)}","score":${report.score},"band":"${report.band}","device":{$device},"signals":[$items]}"""
    }
}
