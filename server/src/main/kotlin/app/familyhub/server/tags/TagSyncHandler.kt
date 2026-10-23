package app.familyhub.server.tags

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.TagData
import app.familyhub.contract.sync.EntityTypes
import app.familyhub.contract.validation.Validators
import app.familyhub.server.platform.db.HouseholdTable
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.sync.ChangeRow
import app.familyhub.server.sync.MutationContext
import app.familyhub.server.sync.StoredEntity
import app.familyhub.server.sync.SyncColumns
import app.familyhub.server.sync.WritableSyncHandler
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** `tag` (TAG-001). Name uniqueness (TAG-006) is enforced by a partial unique index and checked here for a clean error. */
object TagTable : Table("tag") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val name = text("name")

    /** `PaletteColor` wire name. */
    val color = text("color")
    val icon = text("icon").nullable()
    val archived = bool("archived").default(false)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** Tags are created, renamed, recolored, archived and deleted by clients through sync (`tags.manage`). */
class TagSyncHandler : WritableSyncHandler<TagData>(TagData.serializer()) {
    override val type: String = EntityTypes.TAG

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        TagTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[TagTable.deleted]
            ChangeRow(type, row[TagTable.id].toString(), row[TagTable.seq], deleted, if (deleted) null else toJson(toData(row)))
        }

    override fun load(id: UUID): StoredEntity<TagData>? =
        TagTable.selectAll().where { TagTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[TagTable.householdId], it[TagTable.deleted], toData(it))
        }

    override fun normalize(data: TagData): TagData = data.copy(name = data.name.trim())

    override fun validate(
        ctx: MutationContext,
        data: TagData,
    ): Map<String, FieldErrorCode> = Validators.tag(data)

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: TagData?,
        data: TagData,
    ) {
        if (existing != null && existing.name == data.name) return
        // Archived tags keep their name; deleted ones free it (TAG-006). Same lower() as the unique index.
        val taken =
            TagTable
                .selectAll()
                .where {
                    (TagTable.householdId eq ctx.householdId) and
                        (TagTable.deleted eq false) and
                        (TagTable.id neq id) and
                        (TagTable.name.lowerCase() eq stringParam(data.name).lowerCase())
                }.limit(1)
                .any()
        if (taken) throw ApiException(ErrorCode.TAG_NAME_TAKEN, "Tag name is taken", mapOf("name" to FieldErrorCode.TAKEN))
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: TagData,
        seq: Long,
    ) {
        TagTable.insert {
            it[TagTable.id] = id
            it[householdId] = ctx.householdId
            write(it, data)
            it[TagTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: TagData,
        seq: Long,
    ) {
        TagTable.update({ (TagTable.householdId eq ctx.householdId) and (TagTable.id eq id) }) {
            write(it, data)
            it[TagTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        TagTable.update({ (TagTable.householdId eq ctx.householdId) and (TagTable.id eq id) }) {
            it[deleted] = true
            it[TagTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    private fun write(
        it: UpdateBuilder<*>,
        data: TagData,
    ) {
        it[TagTable.name] = data.name
        it[TagTable.color] = data.color.key
        it[TagTable.icon] = data.icon
        it[TagTable.archived] = data.archived
    }

    private fun toData(row: ResultRow) =
        TagData(
            name = row[TagTable.name],
            color = PaletteColor.fromKey(row[TagTable.color]) ?: PaletteColor.GRAY,
            icon = row[TagTable.icon],
            archived = row[TagTable.archived],
        )
}
