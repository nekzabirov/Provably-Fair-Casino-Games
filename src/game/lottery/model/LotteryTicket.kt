package game.lottery.model

import kotlinx.datetime.Clock
import model.Player
import model.PlayerTable
import model.Round
import model.RoundTable
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import util.Amount
import util.Identity

/**
 * One player's system ticket for one [Lottery] — 6..36 chosen numbers covering
 * C(n, 6) combinations, priced per combination (the [amount] charged, kept per-row
 * so the pool is exact even if the configured price changes between rounds; sales
 * are always derived from the tickets). 1:1 with a shared [Round] that owns the
 * money lifecycle: the round opens and a DEBIT moves the price on purchase, the
 * round stays open until the draw, then a winner gets a CREDIT and every round closes.
 *
 * A player may hold up to [util.Env.lotteryMaxTicketsPerPlayer] tickets per draw —
 * each ticket is its OWN round (the `uniq_active_round` partial index excludes
 * `lottery`), so the per-row [round] uniqueness is the only structural constraint.
 * [matched] / [payout] are null until the draw is decided; they are persisted BEFORE
 * the winner's credit webhook, so a still-open round on a decided ticket is an
 * unfinished settle that boot recovery completes through the credit reuse path.
 */
object LotteryTicketTable : LongIdTable("lottery_tickets") {
    val lottery = reference("lottery_id", LotteryTable)
    val round = reference("round_id", RoundTable).uniqueIndex()
    val player = reference("player_id", PlayerTable)
    val sessionToken = varchar("session_token", 64)
    val numbers = varchar("numbers", 64)                 // CSV of the chosen numbers
    val amount = long("amount")                          // price paid, minor units
    val matched = bool("matched").nullable()             // did all numbers match (decided at draw)
    val payout = long("payout").nullable()               // this ticket's winnings, minor units (null if not a winner)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
}

class LotteryTicket(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LotteryTicket>(LotteryTicketTable) {
        val GAME = Identity("lottery")

        /** The ticket bound to a round, or null. */
        fun forRound(round: Round): LotteryTicket? =
            find { LotteryTicketTable.round eq round.id }.firstOrNull()
    }

    var lottery by Lottery referencedOn LotteryTicketTable.lottery
    var round by Round referencedOn LotteryTicketTable.round
    var player by Player referencedOn LotteryTicketTable.player
    var sessionToken by LotteryTicketTable.sessionToken
    var numbers by LotteryTicketTable.numbers
    var amount by LotteryTicketTable.amount.transform({ it.value }, { Amount(it) })
    var matched by LotteryTicketTable.matched
    var payout by LotteryTicketTable.payout
    var createdAt by LotteryTicketTable.createdAt

    fun numbersList(): List<Int> =
        numbers.split(",").filter { it.isNotBlank() }.map { it.toInt() }
}
