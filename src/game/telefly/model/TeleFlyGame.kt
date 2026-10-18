package game.telefly.model

import model.Round
import model.RoundTable
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import util.Amount
import util.Identity

/**
 * Per-bet record for one TeleFly (crash) round, 1:1 with the shared [Round]
 * (mirrors [game.mines.model.MinesGame]). The Round owns money lifecycle
 * (createdAt/closedAt); this owns the provably-fair payload + the bet result.
 *
 * The "global round" (the shared multiplier curve) lives only in the engine's
 * in-memory state. Every bettor's row carries that round's identity ([roundNonce]
 * + seeds + [crashMultiplier]), so a single player's bet can be verified
 * end-to-end without a separate round registry. [sessionToken] is kept so startup
 * reconciliation can notify the operator for orphaned rounds.
 *
 * [cashoutMultiplier] is null until the player cashes out; a closed round with a
 * null cashoutMultiplier is a loss.
 */
object TeleFlyGameTable : LongIdTable("telefly_games") {
    val round = reference("round_id", RoundTable).uniqueIndex()
    val roundNonce = long("round_nonce")
    val sessionToken = varchar("session_token", 64)
    val placeAmount = long("place_amount")              // stake, minor units
    val balance = long("balance")                       // operator balance captured at bet time
    val serverSeed = varchar("server_seed", 64)
    val clientSeed = varchar("client_seed", 64)
    val crashMultiplier = double("crash_multiplier")    // the round's crash point (revealed at crash)
    val cashoutMultiplier = double("cashout_multiplier").nullable()
}

class TeleFlyGame(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TeleFlyGame>(TeleFlyGameTable) {
        val GAME = Identity("telefly")

        /** The TeleFly payload for a round, or null if none exists. */
        fun forRound(round: Round): TeleFlyGame? =
            find { TeleFlyGameTable.round eq round.id }.firstOrNull()
    }

    var round by Round referencedOn TeleFlyGameTable.round
    var roundNonce by TeleFlyGameTable.roundNonce
    var sessionToken by TeleFlyGameTable.sessionToken
    var placeAmount by TeleFlyGameTable.placeAmount.transform({ it.value }, { Amount(it) })
    var balance by TeleFlyGameTable.balance.transform({ it.value }, { Amount(it) })
    var serverSeed by TeleFlyGameTable.serverSeed
    var clientSeed by TeleFlyGameTable.clientSeed
    var crashMultiplier by TeleFlyGameTable.crashMultiplier
    var cashoutMultiplier by TeleFlyGameTable.cashoutMultiplier

    /** Payout for a cashed-out bet (stake x cashoutMultiplier); 0 if not cashed. */
    fun settleAmount(): Long =
        cashoutMultiplier?.let { (placeAmount.value * it).toLong() } ?: 0L
}
