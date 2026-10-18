package game.plinko.network

import exception.GameException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory

/**
 * Type → handler dispatch for incoming frames. No interfaces: handlers are
 * registered as typed lambdas that deserialize the payload and invoke a
 * Koin-wired command handler. Domain and transport errors become an error frame;
 * unexpected failures are logged and reported generically.
 */
class CommandBus(@PublishedApi internal val json: Json) {

    @PublishedApi
    internal val handlers = HashMap<String, suspend (Connection, JsonObject) -> Unit>()

    inline fun <reified T> register(type: String, crossinline handler: suspend (Connection, T) -> Unit) {
        handlers[type] = { connection, payload -> handler(connection, json.decodeFromJsonElement<T>(payload)) }
    }

    suspend fun dispatch(connection: Connection, frame: String) {
        try {
            val (type, payload) = Envelope.parse(json, frame)
            val handler = handlers[type] ?: throw UnknownCommandException(type)
            handler(connection, payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GameException) {
            connection.send(ErrorFrame(error = e.message ?: "error"))
        } catch (e: WsException) {
            connection.send(ErrorFrame(error = e.message ?: "bad request"))
        } catch (e: SerializationException) {
            connection.send(ErrorFrame(error = "Invalid payload"))
        } catch (e: IllegalArgumentException) {
            connection.send(ErrorFrame(error = e.message ?: "invalid request"))
        } catch (e: Exception) {
            log.error("Command dispatch failed", e)
            connection.send(ErrorFrame(error = "Internal error"))
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(CommandBus::class.java)
    }
}
