package game.plinko.command

import game.plinko.model.PlinkoGame
import game.plinko.model.PlinkoState
import game.plinko.network.Connection
import game.plinko.service.PlinkoMultiplier
import game.plinko.service.PlinkoPath
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import service.PlayerService
import service.ProvablyFair
import service.RoundService
import util.Amount
import util.runTransactionCatching

/**
 * drop — one ball: debit + derive the provably-fair path + credit the payout.
 *
 * The shared round does NOT close here: the first drop opens it (open() is
 * idempotent and adopts a leftover open round) and every further drop joins it as
 * another debit/credit pair, so the player can keep throwing balls while the
 * round is open. The round closes when the socket disconnects (PlinkoSocket).
 * The fairness nonce is the drop's DEBIT transaction id — unique per ball, unlike
 * the round id which is now shared by many drops.
 *
 * Money safety: the stake's PENDING row commits before the operator /debit; if
 * anything after a successful debit fails before the payout is confirmed, the
 * drop is voided — the stake is refunded and the debit marked ABORTED, so the
 * player never stays silently charged for a ball that produced nothing.
 */
@Serializable
data class PlinkoDrop(val amount: Long, val rows: Int, val risk: String)

class PlinkoDropHandler(
    private val roundService: RoundService,
    private val provablyFair: ProvablyFair,
    private val playerService: PlayerService
) {
    suspend fun handle(connection: Connection, command: PlinkoDrop) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val sessionToken = connection.requireToken()

        require(command.amount > 0) { "amount must be positive" }
        require(command.rows in PlinkoMultiplier.MIN_ROWS..PlinkoMultiplier.MAX_ROWS) {
            "rows must be ${PlinkoMultiplier.MIN_ROWS}..${PlinkoMultiplier.MAX_ROWS}"
        }
        val risk = runCatching { PlinkoMultiplier.Risk.valueOf(command.risk) }
            .getOrElse { throw IllegalArgumentException("risk must be LOW, MEDIUM or HIGH") }

        // The round commits BEFORE the stake moves: the debit runs in its own
        // transactions and must reference a durable round.
        val round = roundService.open(player, sessionToken, PlinkoGame.GAME, currency).getOrThrow()

        val debit = roundService.debit(player, sessionToken, PlinkoGame.GAME, currency, Amount(command.amount)).getOrThrow()
        val nonce = debit.id.value

        var paid = false
        val state = runCatching {
            val serverSeed = provablyFair.generateServerSeed()
            val clientSeed = connection.requireClientSeed()
            val tonSeed = provablyFair.getTonSeed()
            val hash = provablyFair.computeHash(serverSeed, clientSeed, tonSeed, nonce.toString())

            val slot = PlinkoPath.slot(hash, command.rows)
            val multiplier = PlinkoMultiplier.multiplier(risk, command.rows, slot)
            val payout = Amount((command.amount * multiplier).toLong())

            if (payout.value > 0) {
                // Fresh per-drop credit: a retried drop is a NEW bet, never a replay
                // of another drop's credit, so the reuse path must not apply.
                roundService.credit(player, sessionToken, PlinkoGame.GAME, currency, payout, reusePending = false).getOrThrow()
                paid = true
            }

            runTransactionCatching {
                val balance = playerService.findBalance(player, sessionToken, currency).getOrThrow()

                val game = PlinkoGame.new {
                    this.round = round
                    this.rows = command.rows
                    this.risk = risk
                    this.placeAmount = Amount(command.amount)
                    this.balance = balance
                    this.slot = slot
                    this.multiplier = multiplier
                    this.serverSeed = serverSeed
                    this.clientSeed = clientSeed
                    this.tonSeed = tonSeed
                    this.nonce = nonce
                }

                PlinkoState.result(game, PlinkoState.PlayerInfo.from(player, balance), hash)
            }.getOrThrow()
        }.getOrElse { e ->
            if (paid) {
                // The payout is confirmed — the drop is settled at the operator and
                // voiding now would overpay. Money is correct; only the record failed.
                log.error("plinko drop record failed after confirmed payout: round=${round.id.value} debit=$nonce", e)
            } else {
                // The operator charged the stake but the drop produced nothing: refund + void.
                roundService.compensateDebit(player, sessionToken, PlinkoGame.GAME, currency, debit)
            }
            throw e
        }

        connection.send(state)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PlinkoDropHandler::class.java)
    }
}
