package model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * The operator's user, lazily materialized on first contact. [externalId] is the
 * operator's own id for the player; unique per (externalId, brand).
 */
object PlayerTable : LongIdTable("players") {
    val externalId = varchar("external_id", 128)
    val brand = reference("brand_id", BrandTable)
    val username = varchar("username", 255)
    val profilePic = varchar("profile_pic", 512).nullable()

    init {
        uniqueIndex(externalId, brand)
    }
}

class Player(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Player>(PlayerTable)

    var externalId by PlayerTable.externalId

    var brand by Brand referencedOn PlayerTable.brand

    var username by PlayerTable.username

    var profilePic by PlayerTable.profilePic
}
