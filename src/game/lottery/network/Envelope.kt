package game.lottery.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Incoming-frame parsing and the error-response shape. Every incoming frame is a
 * JSON object whose `type` selects the command; the remaining fields are the payload.
 *
 *   { "type": "buy", "numbers": [3, 11, 17, 22, 40, 49] }
 */
object Envelope {

    fun parse(json: Json, frame: String): Pair<String, JsonObject> {
        val obj = json.parseToJsonElement(frame).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: throw MalformedFrameException()
        return type to obj
    }
}

/** Error reply: { "type": "error", "error": "..." }. */
@Serializable
data class ErrorFrame(val type: String = "error", val error: String)
