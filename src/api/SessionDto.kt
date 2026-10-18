package api

import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/session body. [token] is the session credential **minted by the operator** — we
 * store it as-is and never generate our own. We learn the player (and its id) from the operator's
 * `/player` webhook, identified by this token. The game client carries this same token on the WS.
 */
@Serializable
data class CreateSessionDto(val token: String)

/** Session granted to a player; [token] is the credential the game client carries. */
@Serializable
data class SessionResponse(
    val token: String,
    @Serializable(with = InstantIso8601Serializer::class) val expireAt: Instant
)
