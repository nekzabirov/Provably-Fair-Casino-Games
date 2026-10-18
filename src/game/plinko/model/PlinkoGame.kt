package game.plinko.model

import game.plinko.service.PlinkoMultiplier
import model.Round
import model.RoundTable
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import util.Amount
import util.Identity

/**
 * The audit record for a single Plinko drop. A round stays open while the player
 * keeps throwing balls, so MANY drops (rows here) share one [Round]; each drop is
 * its own debit+credit pair on the operator side. This row preserves the full
 * provably-fair input (seeds + [nonce] = the drop's DEBIT transaction id) and
 * outcome (slot, multiplier) so any drop can be re-verified later. The path itself
 * is NEVER stored — like the Mines layout it is always derived from the seeds via
 * [game.plinko.service.PlinkoPath].
 */
object PlinkoGameTable : LongIdTable("plinko_games") {
    val round = reference("round_id", RoundTable).index()
    val rows = integer("rows")
    val risk = varchar("risk", 8)                       // LOW | MEDIUM | HIGH
    val placeAmount = long("place_amount")              // stake, minor units
    val balance = long("balance")                       // operator balance after settle
    val slot = integer("slot")                          // landing slot (0..rows)
    val multiplier = double("multiplier")               // paytable value for the slot
    val serverSeed = varchar("server_seed", 64)
    val clientSeed = varchar("client_seed", 64)         // the player's seed, supplied on auth
    val tonSeed = varchar("ton_seed", 64)               // public TON block hash at drop time
    val nonce = long("nonce").nullable()                // fairness nonce = DEBIT tx id; null on legacy rows (= roundId)
}

class PlinkoGame(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<PlinkoGame>(PlinkoGameTable) {
        val GAME = Identity("plinko")
    }

    var round by Round referencedOn PlinkoGameTable.round
    var rows by PlinkoGameTable.rows
    var risk by PlinkoGameTable.risk.transform({ it.name }, { PlinkoMultiplier.Risk.valueOf(it) })
    var placeAmount by PlinkoGameTable.placeAmount.transform({ it.value }, { Amount(it) })
    var balance by PlinkoGameTable.balance.transform({ it.value }, { Amount(it) })
    var slot by PlinkoGameTable.slot
    var multiplier by PlinkoGameTable.multiplier
    var serverSeed by PlinkoGameTable.serverSeed
    var clientSeed by PlinkoGameTable.clientSeed
    var tonSeed by PlinkoGameTable.tonSeed
    var nonce by PlinkoGameTable.nonce

    /** The fairness nonce: the drop's DEBIT tx id; legacy single-drop rows derived it from the round. */
    fun fairnessNonce(): Long = nonce ?: round.id.value

    /** Payout = stake × the landed slot's multiplier (0 when the slot pays nothing extra). */
    fun settleAmount(): Long = (placeAmount.value * multiplier).toLong()
}
