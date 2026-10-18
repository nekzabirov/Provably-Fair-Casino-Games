package api

import kotlinx.serialization.Serializable

/** A game in the catalog. [identity] is the slug the client/operator references. */
@Serializable
data class GameDto(val identity: String)
