package api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import model.Brand
import org.koin.ktor.ext.inject
import service.BrandService
import service.PlayerService
import util.Identity

private const val HEADER_OPERATOR = "X-Operator"
private const val HEADER_SECRET = "X-Secret-Key"

/**
 * Operator-facing REST API. Every request authenticates with X-Operator (the
 * brand identity) and X-Secret-Key (the brand secret).
 */
fun Application.operatorApi() {
    val brandService by inject<BrandService>()
    val playerService by inject<PlayerService>()

    routing {
        route("/api/v1") {

            // List the available games.
            get("/games") {
                call.authenticateOperator(brandService) ?: return@get
                call.respond(GameCatalog.all)
            }

            // Create (or extend) a session for one of the operator's players.
            post("/session") {
                val brand = call.authenticateOperator(brandService) ?: return@post
                val body = call.receive<CreateSessionDto>()
                val session = playerService.createSession(brand, body.token).getOrThrow()
                call.respond(SessionResponse(session.token, session.expiredAt))
            }
        }
    }
}

/** Resolves the operator from the auth headers, or responds 401 and returns null. */
private suspend fun ApplicationCall.authenticateOperator(brandService: BrandService): Brand? {
    val identity = request.headers[HEADER_OPERATOR]?.let { runCatching { Identity(it) }.getOrNull() }
    val secret = request.headers[HEADER_SECRET]

    if (identity == null || secret.isNullOrBlank()) {
        respond(HttpStatusCode.Unauthorized, ApiError("Missing or malformed operator headers"))
        return null
    }

    val brand = brandService.authenticate(identity, secret).getOrThrow()
    if (brand == null) {
        respond(HttpStatusCode.Unauthorized, ApiError("Invalid operator credentials"))
        return null
    }
    return brand
}
