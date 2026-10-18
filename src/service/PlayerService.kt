package service

import adapter.BrandAdapter
import adapter.CacheAdapter
import exception.SessionExpiredException
import exception.SessionNotFoundException
import kotlinx.datetime.Clock
import model.Brand
import model.Player
import model.PlayerTable
import model.Session
import model.SessionTable
import org.jetbrains.exposed.sql.upsert
import util.Amount
import util.Currency
import util.Env
import util.runTransactionCatching

class PlayerService(
    private val brandAdapter: BrandAdapter,
    private val cacheAdapter: CacheAdapter
) {

    /**
     * Register (or extend) the operator-minted [token] as a session. The brand passes only the
     * token; we learn who it belongs to by calling the operator's `/player` webhook (identified by
     * that token), which returns the player's `id` (our [Player.externalId]) plus display info.
     * We upsert the player and bind the session. Idempotent per token: re-sending the same token
     * extends its expiry; a new token opens a new session (a player may hold several over time).
     */
    suspend fun createSession(brand: Brand, token: String): Result<Session> =
        runTransactionCatching {
            val data = brandAdapter.findPlayer(brand, token)
            val player = upsertPlayer(brand, data)

            val now = Clock.System.now()
            val existing = Session.find { SessionTable.token eq token }.firstOrNull()

            if (existing != null) {
                existing.expiredAt = now + Env.sessionExpireDuration
                existing
            } else {
                Session.new {
                    this.player = player
                    this.token = token
                    this.expiredAt = now + Env.sessionExpireDuration
                }
            }
        }

    private fun upsertPlayer(brand: Brand, data: BrandAdapter.PlayerData): Player {
        val row = PlayerTable.upsert(PlayerTable.externalId, PlayerTable.brand) {
            it[PlayerTable.externalId] = data.id
            it[PlayerTable.brand] = brand.id
            it[PlayerTable.username] = data.username
            it[PlayerTable.profilePic] = data.profilePic
        }.resultedValues!!.first()
        return Player.wrapRow(row)
    }

    /**
     * Hot path. Resolves a player from a session token scoped to the [operator] (a brand may mint a
     * token; we confirm the resolved player belongs to that brand). Cache-first: on a cache hit the
     * session row is skipped. On a miss the session is read, expiry checked, and the playerId cached
     * for the remaining session lifetime. A token that resolves to a different brand is treated as
     * not found.
     */
    suspend fun findByToken(sessionToken: String, operator: String): Result<Player> {
        val key = SESSION_PREFIX + sessionToken
        val cachedId = cacheAdapter.get(key)?.toLongOrNull()

        return runTransactionCatching {
            if (cachedId != null) {
                val player = Player.findById(cachedId) ?: throw SessionNotFoundException()
                if (player.brand.identity.value != operator) throw SessionNotFoundException()
                player
            } else {
                val session = Session.find { SessionTable.token eq sessionToken }.firstOrNull()
                    ?: throw SessionNotFoundException()

                val now = Clock.System.now()
                if (session.expiredAt <= now) throw SessionExpiredException()

                val player = session.player
                if (player.brand.identity.value != operator) throw SessionNotFoundException()
                cacheAdapter.set(key, player.id.value.toString(), session.expiredAt - now)
                player
            }
        }
    }

    /** Live operator balance. Pure adapter call (runs within the caller's transaction). */
    suspend fun findBalance(player: Player, sessionToken: String, currency: Currency): Result<Amount> =
        runCatching {
            Amount(brandAdapter.findBalance(player, sessionToken, currency).balance)
        }

    private companion object {
        const val SESSION_PREFIX = "session:"
    }
}
