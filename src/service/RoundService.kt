package service

import adapter.BrandAdapter
import exception.RoundNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import model.Player
import model.Round
import model.RoundTable
import model.Transaction
import model.TransactionTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import util.Amount
import util.Currency
import util.Identity
import util.runTransactionCatching

/**
 * Round lifecycle and the money ledger, built on the PENDING-transaction pattern:
 * a money row is created and COMMITTED (status PENDING) in its own small DB
 * transaction BEFORE the operator webhook, so the transactionId that crossed the
 * wire always has a durable local record. The webhook and the CONFIRMED mark share
 * a second transaction — a webhook failure rolls back only the mark, leaving the
 * PENDING row (and its stable id) behind for a safe replay: a retried settle sends
 * the SAME transactionId, so an operator that committed the lost call deduplicates
 * instead of paying twice. Methods take the Player entity the caller already holds.
 */
class RoundService(private val brandAdapter: BrandAdapter) {

    /** Idempotent: returns the active round if one exists, else opens (and registers) a new one. */
    suspend fun open(player: Player, sessionToken: String, game: Identity, currency: Currency): Result<Round> =
        runTransactionCatching {
            findActiveRound(player, game, currency)?.let { return@runTransactionCatching it }

            val round = Round.new {
                this.player = player
                this.game = game
                this.currency = currency
            }
            brandAdapter.openRound(player, sessionToken, round)
            round
        }

    /**
     * Always opens (and registers) a NEW round, never reusing an active one. Used by
     * the lottery, where each ticket is its own round and a player may hold several
     * open rounds at once (the `uniq_active_round` partial index excludes `lottery`).
     */
    suspend fun openNew(player: Player, sessionToken: String, game: Identity, currency: Currency): Result<Round> =
        runTransactionCatching {
            val round = Round.new {
                this.player = player
                this.game = game
                this.currency = currency
            }
            brandAdapter.openRound(player, sessionToken, round)
            round
        }

    /** Finalizes the active round and notifies the operator. */
    suspend fun close(player: Player, sessionToken: String, game: Identity, currency: Currency): Result<Round> =
        runTransactionCatching {
            val round = findActiveRound(player, game, currency) ?: throw RoundNotFoundException()
            round.closedAt = Clock.System.now()
            brandAdapter.closeRound(player, sessionToken, round)
            round
        }

    /** Close a SPECIFIC round by id (lottery: a player has many open rounds, so the
     *  active-round lookup is ambiguous). Idempotent: an already-closed round just
     *  re-notifies the operator close. */
    suspend fun close(roundId: Long, player: Player, sessionToken: String): Result<Round> =
        runTransactionCatching {
            val round = Round.findById(roundId) ?: throw RoundNotFoundException()
            if (round.closedAt == null) round.closedAt = Clock.System.now()
            brandAdapter.closeRound(player, sessionToken, round)
            round
        }

    /**
     * Stake taken from the player. The PENDING row commits first, so a stake the
     * operator accepted can never lose its local record to a later rollback; it is
     * CONFIRMED together with the webhook and voided (ABORTED) when the operator
     * rejects. Callers run their game-state work AFTER this returns and must call
     * [compensateDebit] if that work fails — the player must never stay silently
     * charged for a bet that left no game behind. The caller's round must already
     * be committed (this runs in its own transactions and cannot see uncommitted
     * rows).
     */
    suspend fun debit(player: Player, sessionToken: String, game: Identity, currency: Currency, amount: Amount): Result<Transaction> =
        runCatching {
            val txId = newSuspendedTransaction(Dispatchers.IO) {
                val round = findActiveRound(player, game, currency) ?: throw RoundNotFoundException()
                Transaction.new {
                    this.type = Transaction.Type.DEBIT
                    this.status = Transaction.Status.PENDING
                    this.amount = amount
                    this.round = round
                }.id.value
            }
            try {
                newSuspendedTransaction(Dispatchers.IO) {
                    val transaction = Transaction[txId]
                    brandAdapter.debit(player, sessionToken, transaction.round, amount, txId)
                    transaction.status = Transaction.Status.CONFIRMED
                    transaction
                }
            } catch (e: Exception) {
                // No confirmed charge: void the attempt but keep the row — its id
                // went over the wire, so support may need the trail.
                runCatching {
                    newSuspendedTransaction(Dispatchers.IO) { Transaction[txId].status = Transaction.Status.ABORTED }
                }.onFailure { log.error("failed to mark debit $txId ABORTED", it) }
                throw e
            }
        }

    /**
     * Debit a SPECIFIC round by id (lottery multi-ticket: one debit per ticket round,
     * resolved by id since the player holds several open rounds). Same PENDING-first
     * shape as [debit].
     */
    suspend fun debit(roundId: Long, player: Player, sessionToken: String, amount: Amount): Result<Transaction> =
        runCatching {
            val txId = newSuspendedTransaction(Dispatchers.IO) {
                val round = Round.findById(roundId) ?: throw RoundNotFoundException()
                Transaction.new {
                    this.type = Transaction.Type.DEBIT
                    this.status = Transaction.Status.PENDING
                    this.amount = amount
                    this.round = round
                }.id.value
            }
            try {
                newSuspendedTransaction(Dispatchers.IO) {
                    val transaction = Transaction[txId]
                    brandAdapter.debit(player, sessionToken, transaction.round, amount, txId)
                    transaction.status = Transaction.Status.CONFIRMED
                    transaction
                }
            } catch (e: Exception) {
                runCatching {
                    newSuspendedTransaction(Dispatchers.IO) { Transaction[txId].status = Transaction.Status.ABORTED }
                }.onFailure { log.error("failed to mark debit $txId ABORTED", it) }
                throw e
            }
        }

    /**
     * Credit a SPECIFIC round by id, with the same reuse-keyed idempotency as [credit]
     * but scoped to that round's CREDIT rows. Lottery settlement/recovery uses this so
     * a winner's payout targets their winning ticket's round, not an arbitrary active one.
     */
    suspend fun credit(
        roundId: Long,
        player: Player,
        sessionToken: String,
        amount: Amount,
        reusePending: Boolean = true
    ): Result<Transaction> =
        runCatching {
            val claim = newSuspendedTransaction(Dispatchers.IO) {
                val round = Round.findById(roundId) ?: throw RoundNotFoundException()
                val previous = if (reusePending) {
                    Transaction.find {
                        (TransactionTable.round eq round.id) and (TransactionTable.type eq Transaction.Type.CREDIT)
                    }.toList()
                } else {
                    emptyList()
                }
                previous.firstOrNull { it.status == Transaction.Status.CONFIRMED }?.let {
                    return@newSuspendedTransaction CreditClaim(it.id.value, alreadyConfirmed = true)
                }
                val transaction = previous.firstOrNull { it.status == Transaction.Status.PENDING }
                    ?.also { it.amount = amount }
                    ?: Transaction.new {
                        this.type = Transaction.Type.CREDIT
                        this.status = Transaction.Status.PENDING
                        this.amount = amount
                        this.round = round
                    }
                CreditClaim(transaction.id.value, alreadyConfirmed = false)
            }
            if (claim.alreadyConfirmed) {
                log.info("credit ${claim.txId} already confirmed for round $roundId; skipping operator call")
                return@runCatching newSuspendedTransaction(Dispatchers.IO) { Transaction[claim.txId] }
            }
            newSuspendedTransaction(Dispatchers.IO) {
                val transaction = Transaction[claim.txId]
                brandAdapter.credit(player, sessionToken, transaction.round, amount, claim.txId)
                transaction.status = Transaction.Status.CONFIRMED
                transaction
            }
        }

    /** Round-targeted [compensateDebit]: refund a CONFIRMED ticket debit whose recording
     *  failed, then retire that specific round. Never throws. */
    suspend fun compensateDebit(
        roundId: Long,
        player: Player,
        sessionToken: String,
        debit: Transaction,
        refund: Amount = debit.amount
    ) {
        runCatching {
            credit(roundId, player, sessionToken, refund, reusePending = false).getOrThrow()
            newSuspendedTransaction(Dispatchers.IO) { Transaction[debit.id.value].status = Transaction.Status.ABORTED }
            if (close(roundId, player, sessionToken).isFailure) retireRoundLocally(debit)
        }.onFailure { e ->
            runCatching { retireRoundLocally(debit) }
                .onFailure { log.error("failed to retire round $roundId after failed compensation", it) }
            log.error("FAILED to compensate debit ${debit.id.value} on round $roundId", e)
        }
    }

    /**
     * Winnings paid to the player. With [reusePending] (settle/cashout-style
     * credits — at most one credit per round lifecycle) the credit keeps a STABLE
     * identity across retries: an unconfirmed earlier attempt for the same round
     * is resent with the SAME transactionId (the operator deduplicates a replay),
     * and an already CONFIRMED one short-circuits without calling the operator
     * again. Plinko's per-drop credits and compensation refunds pass false — each
     * is its own fresh transaction.
     */
    suspend fun credit(
        player: Player,
        sessionToken: String,
        game: Identity,
        currency: Currency,
        amount: Amount,
        reusePending: Boolean = true
    ): Result<Transaction> =
        runCatching {
            val claim = newSuspendedTransaction(Dispatchers.IO) {
                val round = findActiveRound(player, game, currency) ?: throw RoundNotFoundException()
                val previous = if (reusePending) {
                    Transaction.find {
                        (TransactionTable.round eq round.id) and (TransactionTable.type eq Transaction.Type.CREDIT)
                    }.toList()
                } else {
                    emptyList()
                }
                previous.firstOrNull { it.status == Transaction.Status.CONFIRMED }?.let {
                    return@newSuspendedTransaction CreditClaim(it.id.value, alreadyConfirmed = true)
                }
                val transaction = previous.firstOrNull { it.status == Transaction.Status.PENDING }
                    ?.also {
                        if (it.amount != amount) {
                            // The lost first attempt may have committed at the operator
                            // with the old amount — same id keeps it single-pay, but the
                            // ledgers can disagree; flag for reconciliation.
                            log.warn(
                                "credit ${it.id.value} retried with amount ${amount.value} " +
                                    "(was ${it.amount.value}) for round ${round.id.value}"
                            )
                        }
                        it.amount = amount
                    }
                    ?: Transaction.new {
                        this.type = Transaction.Type.CREDIT
                        this.status = Transaction.Status.PENDING
                        this.amount = amount
                        this.round = round
                    }
                CreditClaim(transaction.id.value, alreadyConfirmed = false)
            }
            if (claim.alreadyConfirmed) {
                log.info("credit ${claim.txId} already confirmed for this round; skipping operator call")
                return@runCatching newSuspendedTransaction(Dispatchers.IO) { Transaction[claim.txId] }
            }
            newSuspendedTransaction(Dispatchers.IO) {
                val transaction = Transaction[claim.txId]
                brandAdapter.credit(player, sessionToken, transaction.round, amount, claim.txId)
                transaction.status = Transaction.Status.CONFIRMED
                transaction
            }
        }

    /**
     * Undo a CONFIRMED [debit] whose game flow failed afterwards: refund [refund]
     * (the voided position — usually the debit amount), void the debit and retire
     * the round so a later bet cannot adopt it (an adopted round carrying a
     * compensation credit would mask its real settle through the reuse path). The
     * round retires even when the refund itself FAILS — a poisoned round must
     * never be re-entered. Never throws — the original game error must surface to
     * the caller.
     */
    suspend fun compensateDebit(
        player: Player,
        sessionToken: String,
        game: Identity,
        currency: Currency,
        debit: Transaction,
        refund: Amount = debit.amount
    ) {
        runCatching {
            credit(player, sessionToken, game, currency, refund, reusePending = false).getOrThrow()
            newSuspendedTransaction(Dispatchers.IO) { Transaction[debit.id.value].status = Transaction.Status.ABORTED }
            if (close(player, sessionToken, game, currency).isFailure) {
                // Operator close failed; still retire the round locally so the next
                // bet starts from a clean one (same shape as engine reconcile).
                retireRoundLocally(debit)
            }
        }.fold(
            onSuccess = {
                log.error(
                    "compensated debit ${debit.id.value}: refunded ${refund.value} ${currency.value} " +
                        "to player ${player.id.value} after the game flow failed post-debit"
                )
            },
            onFailure = { e ->
                // The refund did not confirm, but the round must STILL retire: left
                // open it would be adopted by the player's next bet, and that bet's
                // settle would collide with the dangling PENDING compensation credit
                // through the reuse path — tangling two bets' money in one round.
                runCatching { retireRoundLocally(debit) }
                    .onFailure { log.error("failed to retire round of debit ${debit.id.value} after failed compensation", it) }
                log.error(
                    "FAILED to compensate debit ${debit.id.value}: player ${player.id.value} " +
                        "may remain charged ${refund.value} ${currency.value}",
                    e
                )
            }
        )
    }

    private suspend fun retireRoundLocally(debit: Transaction) {
        newSuspendedTransaction(Dispatchers.IO) {
            val round = Transaction[debit.id.value].round
            if (round.closedAt == null) round.closedAt = Clock.System.now()
        }
    }

    /**
     * The player's single active round for (game, currency), or null. Currency is
     * part of the round identity, so games resolve the round on reconnect using the
     * currency held on the connection.
     */
    fun findActiveRound(player: Player, game: Identity, currency: Currency): Round? =
        Round.find {
            (RoundTable.player eq player.id) and
                (RoundTable.game eq game.value) and
                (RoundTable.currency eq currency.value) and
                RoundTable.closedAt.isNull()
        }.firstOrNull()

    private class CreditClaim(val txId: Long, val alreadyConfirmed: Boolean)

    private companion object {
        private val log = LoggerFactory.getLogger(RoundService::class.java)
    }
}
