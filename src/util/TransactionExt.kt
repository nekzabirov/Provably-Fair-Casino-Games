package util

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Runs a DB-touching block as a transaction and captures the outcome as a [Result].
 *
 * If a transaction is already active (this call is nested inside another
 * runTransactionCatching), the block JOINS it instead of opening an independent
 * one. That makes a composed flow — e.g. open round → debit → persist — a single
 * atomic unit: a thrown [exception.GameException] (operator rejection, etc.) rolls
 * the whole thing back, including work done by inner service calls, and surfaces
 * as [Result.failure].
 */
suspend fun <T> runTransactionCatching(
    block: suspend () -> T
): Result<T> = runCatching {
    if (TransactionManager.currentOrNull() != null) {
        block()
    } else {
        newSuspendedTransaction(Dispatchers.IO) { block() }
    }
}
