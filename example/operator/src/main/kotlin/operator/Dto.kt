package operator

import kotlinx.serialization.Serializable

// ── Incoming webhook requests (engine -> operator) ──────────────────────────

@Serializable
data class PlayerRequest(val sessionToken: String)

@Serializable
data class BalanceRequest(val sessionToken: String, val currency: String)

@Serializable
data class RoundRequest(val sessionToken: String, val roundId: Long, val game: String, val currency: String)

@Serializable
data class TxRequest(
    val sessionToken: String,
    val roundId: Long,
    val game: String,
    val currency: String,
    val amount: Long,
    /** The engine's transaction id. A round carries several debits/credits, so the
     *  operator needs it to deduplicate a replayed webhook. */
    val transactionId: Long
)

// ── Webhook responses (operator -> engine) ──────────────────────────────────

@Serializable
data class PlayerData(val id: String, val username: String, val profilePic: String? = null)

@Serializable
data class BalanceResponse(val balance: Long)

// ── Session creation (operator -> engine, then echoed to the caller) ────────
// We mint the token ourselves and pass it to the engine; it stores and echoes it.

@Serializable
data class CreateSessionRequest(val token: String)

@Serializable
data class SessionResponse(val token: String, val expireAt: String)

@Serializable
data class ErrorBody(val error: String)
