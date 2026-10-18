package operator

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json

/** Calls the engine's operator-facing API as this operator. */
class EngineClient {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    /** POST /api/v1/session with our minted token, authenticated by X-Operator + X-Secret-Key. */
    suspend fun createSession(token: String): SessionResponse =
        client.post("${Config.engineUrl}/api/v1/session") {
            header("X-Operator", Config.identity)
            header("X-Secret-Key", Config.secret)
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(token))
        }.body()
}
