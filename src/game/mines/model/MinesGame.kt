package game.mines.model

import game.mines.service.MinesMultiplier
import model.Round
import model.RoundTable
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import util.Amount
import util.Identity

/**
 * Game-specific state for a single Mines round.
 *
 * Provably-fair: the mine layout is NEVER stored. It is always derived from
 * (serverSeed + clientSeed + roundId) via [game.mines.service.MinesRng]. The only
 * mutable state is [opened] — the diamonds the player has revealed, in order.
 *
 * 1:1 with the shared [Round]. The Round owns lifecycle (createdAt/closedAt);
 * this owns the game payload.
 */
object MinesGameTable : LongIdTable("mines_games") {
    val round = reference("round_id", RoundTable).uniqueIndex()
    val mineCount = integer("mine_count")
    val placeAmount = long("place_amount")              // stake, minor units
    val balance = long("balance")                       // operator balance captured at start
    val opened = varchar("opened", 128).default("")     // CSV of revealed indices, e.g. "5,12"
    val serverSeed = varchar("server_seed", 64)
    val clientSeed = varchar("client_seed", 64)         // the player's seed, supplied on auth
    val tonSeed = varchar("ton_seed", 64).default("")   // public TON block hash captured at start
}

class MinesGame(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<MinesGame>(MinesGameTable) {
        val GAME = Identity("mines")

        /** The Mines payload for a round, or null if none exists. */
        fun forRound(round: Round): MinesGame? =
            find { MinesGameTable.round eq round.id }.firstOrNull()
    }

    var round by Round referencedOn MinesGameTable.round
    var mineCount by MinesGameTable.mineCount
    var placeAmount by MinesGameTable.placeAmount.transform({ it.value }, { Amount(it) })
    var balance by MinesGameTable.balance.transform({ it.value }, { Amount(it) })
    var opened by MinesGameTable.opened
    var serverSeed by MinesGameTable.serverSeed
    var clientSeed by MinesGameTable.clientSeed
    var tonSeed by MinesGameTable.tonSeed

    /** Revealed diamond indices, in reveal order. Empty when nothing opened yet. */
    fun openedIndices(): List<Int> =
        if (opened.isBlank()) emptyList() else opened.split(",").map { it.toInt() }

    /** Append a newly revealed diamond index. */
    fun addOpened(index: Int) {
        opened = (openedIndices() + index).joinToString(",")
    }

    /** Current payout = stake × cumulative multiplier after the diamonds revealed so far. */
    fun settleAmount(): Long =
        (placeAmount.value * MinesMultiplier.calculate(mineCount, openedIndices().size)).toLong()

    /** Revealed diamonds, each with the cumulative multiplier at the moment it was revealed. */
    fun revealedCells(): List<MinesState.Cell> =
        openedIndices().mapIndexed { i, cellIndex ->
            MinesState.Cell(
                index = cellIndex,
                type = MinesState.Cell.Type.DIAMOND,
                multiplier = MinesMultiplier.calculate(mineCount, i + 1)
            )
        }
}
