package operator

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * A minimal casino operator: it asks the engine for player sessions and serves the
 * webhook the engine calls for player info and money movement.
 *
 * For this to work end-to-end the engine must have a brand row whose identity and
 * secret match [Config], with webhookUrl pointing at http://<host>:<port>/webhook
 * (see README).
 */
fun main() {
    val engine = EngineClient()

    embeddedServer(CIO, port = Config.port, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }

        routing {
            // Operator side: mint a session token for a player and register it with the engine.
            post("/session/{playerId}") {
                val playerId = call.parameters["playerId"]
                if (playerId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("playerId required"))
                    return@post
                }
                val token = Wallets.openSession(playerId)
                call.respond(engine.createSession(token))
            }

            // Engine side: the webhook the engine drives. brand.webhookUrl = .../webhook
            // Every call identifies the player by sessionToken (the token we minted above).
            route("/webhook") {
                post("/player") {
                    if (!call.verifySecret()) return@post
                    val req = call.receive<PlayerRequest>()
                    val playerId = Wallets.playerOf(req.sessionToken)
                    call.respond(PlayerData(id = playerId, username = Wallets.username(playerId)))
                }

                post("/balance") {
                    if (!call.verifySecret()) return@post
                    val req = call.receive<BalanceRequest>()
                    call.respond(BalanceResponse(Wallets.balance(Wallets.playerOf(req.sessionToken))))
                }

                post("/round/open") {
                    if (!call.verifySecret()) return@post
                    call.receive<RoundRequest>()
                    call.respond(HttpStatusCode.OK)
                }

                post("/round/close") {
                    if (!call.verifySecret()) return@post
                    call.receive<RoundRequest>()
                    call.respond(HttpStatusCode.OK)
                }

                post("/debit") {
                    if (!call.verifySecret()) return@post
                    val req = call.receive<TxRequest>()
                    if (Wallets.debit(Wallets.playerOf(req.sessionToken), req.amount)) {
                        call.respond(HttpStatusCode.OK)
                    } else {
                        call.respond(HttpStatusCode.PaymentRequired, ErrorBody("insufficient funds"))
                    }
                }

                post("/credit") {
                    if (!call.verifySecret()) return@post
                    val req = call.receive<TxRequest>()
                    Wallets.credit(Wallets.playerOf(req.sessionToken), req.amount)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }.start(wait = true)
}

/** Rejects the call with 401 unless it carries the agreed X-Secret-Key. */
private suspend fun ApplicationCall.verifySecret(): Boolean {
    if (request.headers["X-Secret-Key"] != Config.secret) {
        respond(HttpStatusCode.Unauthorized, ErrorBody("invalid secret"))
        return false
    }
    return true
}
