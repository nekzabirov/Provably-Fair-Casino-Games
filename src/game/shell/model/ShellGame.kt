package game.shell.model

import model.Round
import model.RoundTable
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import util.Amount
import util.Identity

/**
 * Game-specific state for a single Shell round.
 *
 * Provably-fair: the winning cup is NEVER stored. It is always derived from
 * (serverSeed + clientSeed + roundId) via [game.shell.service.ShellResult]. There
 * is no mutable per-round state — a round opens at `start` and resolves+closes at
 * the single `pick`.
 *
 * 1:1 with the shared [Round]. The Round owns lifecycle (createdAt/closedAt);
 * this owns the stake + seeds.
 */
object ShellGameTable : LongIdTable("shell_games") {
    val round = reference("round_id", RoundTable).uniqueIndex()
    val placeAmount = long("place_amount")              // stake, minor units
    val balance = long("balance")                       // operator balance captured at start
    val serverSeed = varchar("server_seed", 64)
    val clientSeed = varchar("client_seed", 64)         // the player's seed, supplied on auth
    val tonSeed = varchar("ton_seed", 64).default("")   // public TON block hash captured at start
}

class ShellGame(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<ShellGame>(ShellGameTable) {
        val GAME = Identity("luckycup")

        /** The Shell payload for a round, or null if none exists. */
        fun forRound(round: Round): ShellGame? =
            find { ShellGameTable.round eq round.id }.firstOrNull()
    }

    var round by Round referencedOn ShellGameTable.round
    var placeAmount by ShellGameTable.placeAmount.transform({ it.value }, { Amount(it) })
    var balance by ShellGameTable.balance.transform({ it.value }, { Amount(it) })
    var serverSeed by ShellGameTable.serverSeed
    var clientSeed by ShellGameTable.clientSeed
    var tonSeed by ShellGameTable.tonSeed
}
