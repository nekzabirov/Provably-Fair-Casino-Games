package adapter

import exception.OperatorRejectedException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import model.Brand
import model.Player
import model.Round
import util.Amount
import util.Currency
import util.Env

/**
 * The operator webhook. One route per command, all POST, authenticated by the
 * brand's secret. Paths are appended to brand.webhookUrl. Every request carries
 * sessionToken = the operator-minted session credential, never our internal id; the
 * operator resolves its own session (and from it, the player) by that token.
 *
 * The client is configured with expectSuccess = true, so a non-2xx (e.g. the
 * operator declining a debit) throws and rolls back the surrounding transaction.
 */
class BrandAdapter(private val client: HttpClient) {

    suspend fun findPlayer(brand: Brand, sessionToken: String): PlayerData =
        post(brand, PLAYER, PlayerRequest(sessionToken)).body()

    suspend fun findBalance(player: Player, sessionToken: String, currency: Currency): BalanceResponse =
        post(player.brand, BALANCE, BalanceRequest(sessionToken, currency.value)).body()

    suspend fun openRound(player: Player, sessionToken: String, round: Round) {
        post(player.brand, ROUND_OPEN, round.toRequest(sessionToken))
    }

    suspend fun closeRound(player: Player, sessionToken: String, round: Round) {
        post(player.brand, ROUND_CLOSE, round.toRequest(sessionToken))
    }

    suspend fun debit(player: Player, sessionToken: String, round: Round, amount: Amount, transactionId: Long) {
        post(player.brand, DEBIT, round.toTxRequest(sessionToken, amount, transactionId))
    }

    suspend fun credit(player: Player, sessionToken: String, round: Round, amount: Amount, transactionId: Long) {
        post(player.brand, CREDIT, round.toTxRequest(sessionToken, amount, transactionId))
    }

    /** Centralizes URL, auth header, content-type and per-call timeout. */
    private suspend inline fun <reified B : Any> post(brand: Brand, path: String, body: B): HttpResponse =
        withTimeout(Env.brandTimeout) {
            try {
                client.post(brand.webhookUrl + path) {
                    header(SECRET_HEADER, brand.secret)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (e: ResponseException) {
                throw OperatorRejectedException("Operator rejected $path (${e.response.status})")
            }
        }

    private fun Round.toRequest(sessionToken: String) =
        RoundRequest(sessionToken, id.value, game.value, currency.value)

    private fun Round.toTxRequest(sessionToken: String, amount: Amount, transactionId: Long) =
        TxRequest(sessionToken, id.value, game.value, currency.value, amount.value, transactionId)

    @Serializable
    data class PlayerRequest(val sessionToken: String)

    @Serializable
    data class PlayerData(val id: String, val username: String, val profilePic: String? = null)

    @Serializable
    data class BalanceRequest(val sessionToken: String, val currency: String)

    @Serializable
    data class BalanceResponse(val balance: Long)

    @Serializable
    data class RoundRequest(
        val sessionToken: String,
        val roundId: Long,
        val game: String,
        val currency: String
    )

    @Serializable
    data class TxRequest(
        val sessionToken: String,
        val roundId: Long,
        val game: String,
        val currency: String,
        val amount: Long,
        /** Our Transaction id — a round may carry many debits/settles, the operator needs each tx unique. */
        val transactionId: Long
    )

    private companion object {
        const val SECRET_HEADER = "X-Secret-Key"
        const val PLAYER = "/player"
        const val BALANCE = "/balance"
        const val ROUND_OPEN = "/round/open"
        const val ROUND_CLOSE = "/round/close"
        const val DEBIT = "/debit"
        const val CREDIT = "/credit"
    }
}
