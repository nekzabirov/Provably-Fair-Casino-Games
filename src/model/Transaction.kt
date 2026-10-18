package model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlinx.datetime.Clock
import util.Amount

/**
 * Money-movement record tied to a round. Rows are committed as PENDING before the
 * operator webhook is called, so the transactionId that crossed the wire always
 * has a durable local record — a lost response can be replayed with the SAME id
 * and the operator deduplicates instead of moving money twice.
 */
object TransactionTable : LongIdTable("transactions") {
    val type = enumerationByName("type", 8, Transaction.Type::class)
    val status = enumerationByName("status", 16, Transaction.Status::class)
    val amount = long("amount")
    val round = reference("round_id", RoundTable)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
}

class Transaction(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Transaction>(TransactionTable)

    var type by TransactionTable.type

    var status by TransactionTable.status

    var amount by TransactionTable.amount.transform({ it.value }, { Amount(it) })

    var round by Round referencedOn TransactionTable.round

    var createdAt by TransactionTable.createdAt

    /** DEBIT = stake taken from player; CREDIT = winnings paid. */
    enum class Type { DEBIT, CREDIT }

    /**
     * PENDING = committed locally, operator outcome unknown; CONFIRMED = operator
     * accepted; ABORTED = voided (rejected debit, or one compensated by a refund).
     */
    enum class Status { PENDING, CONFIRMED, ABORTED }
}
