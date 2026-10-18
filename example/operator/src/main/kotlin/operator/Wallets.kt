package operator

import java.util.concurrent.ConcurrentHashMap

/** In-memory player wallets + sessions. The operator owns balances and mints session tokens. */
object Wallets {

    private val balances = ConcurrentHashMap<String, Long>()

    private val sessions = ConcurrentHashMap<String, String>() // sessionToken -> playerId

    /** Mint a session token for [playerId] and remember it. The engine stores and echoes this
     *  token back as `sessionToken` on every webhook, where we resolve the player via [playerOf]. */
    fun openSession(playerId: String): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
        sessions[token] = playerId
        return token
    }

    /** The player a session token belongs to (how every webhook identifies the player now). */
    fun playerOf(sessionToken: String): String =
        sessions[sessionToken] ?: error("unknown session token")

    fun username(playerId: String): String = "player-$playerId"

    fun balance(playerId: String): Long = balances.getOrPut(playerId) { Config.startingBalance }

    /** Takes the stake. Returns false (and changes nothing) if funds are insufficient. */
    fun debit(playerId: String, amount: Long): Boolean {
        var accepted = false
        balances.compute(playerId) { _, current ->
            val balance = current ?: Config.startingBalance
            if (balance >= amount) {
                accepted = true
                balance - amount
            } else {
                balance
            }
        }
        return accepted
    }

    fun credit(playerId: String, amount: Long) {
        balances.compute(playerId) { _, current -> (current ?: Config.startingBalance) + amount }
    }
}
