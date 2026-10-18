package model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import util.Identity

/** The integrating operator/casino. Looked up by [identity]; id is for FK only. */
object BrandTable : LongIdTable("brands") {
    val identity = varchar("identity", 64).uniqueIndex()
    val secret = varchar("secret", 255)
    val webhookUrl = varchar("webhook_url", 512)
}

class Brand(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Brand>(BrandTable)

    var identity by BrandTable.identity.transform({ it.value }, { Identity(it) })

    var secret by BrandTable.secret

    var webhookUrl by BrandTable.webhookUrl
}
