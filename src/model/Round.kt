package model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlinx.datetime.Clock
import util.Currency
import util.Identity

/**
 * A single play instance, created by a game (not by the session). One active round
 * per (player, game, currency), enforced by a partial unique index applied in the
 * bootstrap migration:
 *
 *   CREATE UNIQUE INDEX uniq_active_round
 *   ON rounds (player_id, game, currency) WHERE closed_at IS NULL;
 *
 * Deliberately does not reference Brand — within the engine, players are ours.
 */
object RoundTable : LongIdTable("rounds") {
    val game = varchar("game", 64)
    val player = reference("player_id", PlayerTable)
    val currency = varchar("currency", 10)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val closedAt = timestamp("closed_at").nullable()
}

class Round(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Round>(RoundTable)

    var game by RoundTable.game.transform({ it.value }, { Identity(it) })

    var player by Player referencedOn RoundTable.player

    var currency by RoundTable.currency.transform({ it.value }, { Currency(it) })

    var createdAt by RoundTable.createdAt

    var closedAt by RoundTable.closedAt
}
