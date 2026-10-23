package app.familyhub.server.household

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.sync.EntityTypes
import app.familyhub.contract.validation.Validators
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.sync.ChangeRow
import app.familyhub.server.sync.MutationContext
import app.familyhub.server.sync.StoredEntity
import app.familyhub.server.sync.SyncColumns
import app.familyhub.server.sync.WritableSyncHandler
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * Persons (FND-030, FND-031, FND-034) through sync: relatives are created and deleted with `persons.manage`;
 * members may edit their own profile. A member's person cannot be deleted (`PERSON_IS_MEMBER`).
 */
class PersonSyncHandler : WritableSyncHandler<PersonData>(PersonData.serializer()) {
    override val type: String = EntityTypes.PERSON

    private val columns = SyncColumns(PersonTable, PersonTable.householdId, PersonTable.seq, PersonTable.deleted)

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        columns.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val person = PersonRepository.toRow(row)
            ChangeRow(type, person.id.toString(), person.seq, person.deleted, if (person.deleted) null else toJson(person.data))
        }

    override fun load(id: UUID): StoredEntity<PersonData>? =
        PersonTable.selectAll().where { PersonTable.id eq id }.singleOrNull()?.let(PersonRepository::toRow)?.let {
            StoredEntity(it.householdId, it.deleted, it.data)
        }

    override fun normalize(data: PersonData): PersonData = data.copy(name = data.name.trim())

    override fun validate(
        ctx: MutationContext,
        data: PersonData,
    ): Map<String, FieldErrorCode> = Validators.person(data, ctx.currentYear)

    override fun isOwnProfile(
        ctx: MutationContext,
        id: UUID,
    ): Boolean = id.toString() == ctx.viewer.personId

    override fun checkDelete(
        ctx: MutationContext,
        id: UUID,
    ) {
        if (MemberRepository.isPersonLinked(ctx.householdId, id)) {
            throw ApiException(ErrorCode.PERSON_IS_MEMBER, "The person is a member's profile")
        }
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: PersonData,
        seq: Long,
    ) = PersonRepository.create(ctx.householdId, id, data, seq, ctx.now)

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: PersonData,
        seq: Long,
    ) = PersonRepository.update(ctx.householdId, id, data, seq, ctx.now)

    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) = PersonRepository.tombstone(ctx.householdId, id, seq, ctx.now)
}
