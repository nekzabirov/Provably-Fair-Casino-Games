package game.pvp.model

import model.Round
import model.RoundTable
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import util.Amount
import util.Identity

/**
 * Per-player record for one PvP jackpot round, 1:1 with the shared [Round]
 * (mirrors [game.telefly.model.TeleFlyGame]). The Round owns the money lifecycle
 * (createdAt/closedAt); this owns the provably-fair payload + this player's result.
 *
 * The "global round" (the shared wheel — its seeds and the pooled pot) lives only
 * in the engine's in-memory state. Every participant's row carries that round's
 * identity ([roundNonce] + seeds), so a single player's stake and the round's
 * winning number can be verified end-to-end. [sessionToken] is kept so startup
 * reconciliation can refund orphaned rounds.
 *
 * [placeAmount] is the player's cumulative stake (it grows with each top-up before
 * lock). [winningNumber] / [won] are null until the round is decided; they are
 * persisted (with the winner's [payout]) BEFORE the first credit webhook, so a
 * still-open round with them set means an unfinished settle that boot recovery
 * must complete.
 */
object PvpGameTable : LongIdTable("pvp_games") {
    val round = reference("round_id", RoundTable).uniqueIndex()
    val roundNonce = long("round_nonce")
    val sessionToken = varchar("session_token", 64)
    val placeAmount = long("place_amount")              // cumulative stake, minor units
    val balance = long("balance")                       // operator balance captured after settle
    val serverSeed = varchar("server_seed", 64)
    val clientSeed = varchar("client_seed", 64)         // the public TON block hash
    val winningNumber = double("winning_number").nullable()   // the round's 0..100 number (revealed at lock)
    val won = bool("won").nullable()                    // did this player win the pot
    val payout = long("payout").nullable()              // the winner's pot, minor units (null for losers)
}

class PvpGame(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<PvpGame>(PvpGameTable) {
        val GAME = Identity("pvp")

        /** The PvP payload for a round, or null if none exists. */
        fun forRound(round: Round): PvpGame? =
            find { PvpGameTable.round eq round.id }.firstOrNull()
    }

    var round by Round referencedOn PvpGameTable.round
    var roundNonce by PvpGameTable.roundNonce
    var sessionToken by PvpGameTable.sessionToken
    var placeAmount by PvpGameTable.placeAmount.transform({ it.value }, { Amount(it) })
    var balance by PvpGameTable.balance.transform({ it.value }, { Amount(it) })
    var serverSeed by PvpGameTable.serverSeed
    var clientSeed by PvpGameTable.clientSeed
    var winningNumber by PvpGameTable.winningNumber
    var won by PvpGameTable.won
    var payout by PvpGameTable.payout
}
