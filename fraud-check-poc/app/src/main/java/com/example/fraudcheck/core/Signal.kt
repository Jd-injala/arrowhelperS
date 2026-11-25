package com.example.fraudcheck.core

/** How much the backend may trust a signal. Only SERVER_VERIFIED signals may block on their own. */
enum class Trust { CLIENT_HINT, SERVER_VERIFIED }

/**
 * One check result. [weight] is added to the risk score when [flagged] is true.
 * [hardStop] marks signals that the backend should treat as a hard reject once it has verified them.
 */
data class Signal(
    val id: String,
    val title: String,
    val flagged: Boolean,
    val weight: Int,
    val evidence: String,
    val trust: Trust = Trust.CLIENT_HINT,
    val hardStop: Boolean = false,
) {
    companion object {
        fun error(id: String, title: String, e: Throwable) =
            Signal(id, title, flagged = false, weight = 0, evidence = "check failed: ${e.javaClass.simpleName}: ${e.message}")
    }
}
