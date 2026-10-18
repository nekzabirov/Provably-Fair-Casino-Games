package api

import exception.BettingClosedException
import exception.OperatorRejectedException
import exception.SessionExpiredException
import exception.SessionNotFoundException
import exception.TicketAlreadyBoughtException
import exception.TicketLimitReachedException
import exception.WrongPhaseException
import game.lottery.service.LotteryEngine
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import service.PlayerService

/**
 * Player-facing REST API for the lottery iframe client (replaces the per-player WS for
 * our own client). The session token is the bearer: `token` + `operator` resolve the
 * player exactly like the WS `auth` frame — no operator secret, the player only holds
 * the token the operator minted. The client polls `state` for the live draw and posts
 * `buy` to purchase a ticket. The shared draw itself is driven by [LotteryEngine].
 */
fun Application.lotteryApi() {
    val playerService by inject<PlayerService>()
    val engine by inject<LotteryEngine>()

    routing {
        route("/api/v1/lottery") {

            // Current draw + this player's tickets and win chance.
            post("/state") {
                val body = call.receive<LotteryStateRequest>()
                call.handleLottery {
                    val player = playerService.findByToken(body.token, body.operator).getOrThrow()
                    engine.snapshotFor(player)
                }
            }

            // Buy this draw's single system ticket (6..36 distinct numbers).
            post("/buy") {
                val body = call.receive<LotteryBuyRequest>()
                call.handleLottery {
                    val player = playerService.findByToken(body.token, body.operator).getOrThrow()
                    engine.buy(player, body.token, LotteryEngine.CURRENCY, body.numbers)
                }
            }
        }
    }
}

/** Run a lottery action and map domain failures to HTTP, mirroring the WS error contract. */
private suspend inline fun ApplicationCall.handleLottery(block: () -> Any) {
    try {
        respond(block())
    } catch (e: SessionNotFoundException) {
        respond(HttpStatusCode.Unauthorized, ApiError(e.message ?: "Session not found"))
    } catch (e: SessionExpiredException) {
        respond(HttpStatusCode.Unauthorized, ApiError(e.message ?: "Session expired"))
    } catch (e: TicketLimitReachedException) {
        respond(HttpStatusCode.Conflict, ApiError(e.message ?: "Ticket limit reached"))
    } catch (e: TicketAlreadyBoughtException) {
        respond(HttpStatusCode.Conflict, ApiError(e.message ?: "Ticket purchase in progress"))
    } catch (e: BettingClosedException) {
        respond(HttpStatusCode.UnprocessableEntity, ApiError(e.message ?: "Betting is closed"))
    } catch (e: WrongPhaseException) {
        respond(HttpStatusCode.UnprocessableEntity, ApiError(e.message ?: "Not available now"))
    } catch (e: OperatorRejectedException) {
        respond(HttpStatusCode.PaymentRequired, ApiError(e.message ?: "Payment rejected"))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Invalid request"))
    } catch (e: Exception) {
        // Operator-declined debits (insufficient funds, etc.) surface here as a client
        // exception from the brand webhook; the buy left no charge behind (compensated).
        respond(HttpStatusCode.BadGateway, ApiError("Could not complete the request, try again"))
    }
}

@Serializable
data class LotteryStateRequest(val token: String, val operator: String)

@Serializable
data class LotteryBuyRequest(val token: String, val operator: String, val numbers: List<Int>)
