package api

import kotlinx.serialization.Serializable

/** Error response body for operator-facing endpoints. */
@Serializable
data class ApiError(val error: String)
