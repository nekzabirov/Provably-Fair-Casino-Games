package game.lottery.model

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import util.Currency

/**
 * One lottery draw (a 2-hour round) — a single shared aggregate for ALL brands
 * (mirrors the "one pot for everyone" model of PvP, but persisted). Unlike the
 * in-memory PvP/TeleFly rounds, this row IS the source of truth: the engine loads
 * the active lottery on boot and resumes it, so a restart never loses the round.
 *
 * Pool accounting (minor units, TON nano):
 *   prizePool = seedPool + Σ ticket.amount         (sales derived from the tickets)
 *   seedPool  = the guaranteed/rolled-over base. 1000 TON at genesis; after a
 *               jackpot it reseeds the 1000 TON floor; with no winner the FULL pool
 *               rolls into the next round's seedPool (the jackpot grows).
 *
 * Provably-fair: [serverSeed] is committed via [serverSeedHash] for the whole round;
 * [tonSeed] (a public TON block hash) is captured once when DRAWING begins; the six
 * [winningNumbers] are derived deterministically from both and revealed one per
 * interval ([drawnCount] tracks reveal progress). The full serverSeed is revealed
 * once SETTLED so anyone can recompute the draw.
 */
object LotteryTable : LongIdTable("lotteries") {
    val currency = varchar("currency", 10)
    val status = enumerationByName("status", 16, Lottery.Status::class)
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")
    val seedPool = long("seed_pool")                          // guaranteed/rolled base, minor units
    val serverSeed = varchar("server_seed", 64)
    val serverSeedHash = varchar("server_seed_hash", 64)
    val tonSeed = varchar("ton_seed", 64).nullable()          // public TON seed, captured at draw start
    val winningNumbers = varchar("winning_numbers", 64).default("")   // CSV of the full drawn set
    val drawnCount = integer("drawn_count").default(0)        // how many of the winning numbers are revealed
    val netPool = long("net_pool").nullable()                 // distributed pool after commission (set at settle)
    val winnerCount = integer("winner_count").nullable()      // number of tickets matching all numbers
    val settledAt = timestamp("settled_at").nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
}

class Lottery(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Lottery>(LotteryTable)

    var currency by LotteryTable.currency.transform({ it.value }, { Currency(it) })
    var status by LotteryTable.status
    var startsAt by LotteryTable.startsAt
    var endsAt by LotteryTable.endsAt
    var seedPool by LotteryTable.seedPool
    var serverSeed by LotteryTable.serverSeed
    var serverSeedHash by LotteryTable.serverSeedHash
    var tonSeed by LotteryTable.tonSeed
    var winningNumbers by LotteryTable.winningNumbers
    var drawnCount by LotteryTable.drawnCount
    var netPool by LotteryTable.netPool
    var winnerCount by LotteryTable.winnerCount
    var settledAt by LotteryTable.settledAt
    var createdAt by LotteryTable.createdAt

    /** The full drawn set as ints (empty before the draw). */
    fun winningList(): List<Int> =
        winningNumbers.split(",").filter { it.isNotBlank() }.map { it.toInt() }

    /** Lifecycle of a draw. */
    enum class Status {
        /** Ticket sales open until [endsAt]. */
        SELLING,

        /** Sales closed; the six numbers are committed and revealed one per interval. */
        DRAWING,

        /** All numbers revealed; winners are being decided and paid. */
        SETTLING,

        /** Paid out; a brief pause before the next round opens. */
        SETTLED
    }
}
