package model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlinx.datetime.Clock

/**
 * Access grant from a brand to one player. The [token] is the credential the game
 * client carries. Sessions are never force-revoked; they simply expire.
 */
object SessionTable : LongIdTable("sessions") {
    val player = reference("player_id", PlayerTable)
    val token = varchar("token", 64).uniqueIndex()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val expiredAt = timestamp("expired_at")
}

class Session(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Session>(SessionTable)

    var player by Player referencedOn SessionTable.player

    var token by SessionTable.token

    var createdAt by SessionTable.createdAt

    var expiredAt by SessionTable.expiredAt
}
