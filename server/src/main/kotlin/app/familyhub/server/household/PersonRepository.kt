package app.familyhub.server.household

import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.entities.ContactData
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.PaletteColors
import app.familyhub.contract.entities.PersonData
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class PersonRow(
    val id: UUID,
    val householdId: UUID,
    val data: PersonData,
    val seq: Long,
    val deleted: Boolean,
)

object PersonRepository {
    private val contactsSerializer = ListSerializer(ContactData.serializer())

    fun create(
        householdId: UUID,
        id: UUID,
        data: PersonData,
        seq: Long,
        now: Instant,
    ) {
        PersonTable.insert {
            it[PersonTable.id] = id
            it[PersonTable.householdId] = householdId
            writeData(it, data)
            it[PersonTable.seq] = seq
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    /** Writes all data columns with the caller's [seq]. */
    fun update(
        householdId: UUID,
        id: UUID,
        data: PersonData,
        seq: Long,
        now: Instant,
    ) {
        PersonTable.update({ (PersonTable.householdId eq householdId) and (PersonTable.id eq id) }) {
            writeData(it, data)
            it[PersonTable.seq] = seq
            it[updatedAt] = now
        }
    }

    fun rename(
        householdId: UUID,
        id: UUID,
        name: String,
        seq: Long,
        now: Instant,
    ) {
        PersonTable.update({ (PersonTable.householdId eq householdId) and (PersonTable.id eq id) }) {
            it[PersonTable.name] = name
            it[PersonTable.seq] = seq
            it[updatedAt] = now
        }
    }

    /** Marks the person deleted with the caller's [seq]; the tombstone travels through sync. */
    fun tombstone(
        householdId: UUID,
        id: UUID,
        seq: Long,
        now: Instant,
    ) {
        PersonTable.update({ (PersonTable.householdId eq householdId) and (PersonTable.id eq id) }) {
            it[deleted] = true
            it[PersonTable.seq] = seq
            it[updatedAt] = now
        }
    }

    fun get(
        householdId: UUID,
        id: UUID,
    ): PersonRow? =
        PersonTable
            .selectAll()
            .where { (PersonTable.householdId eq householdId) and (PersonTable.id eq id) }
            .singleOrNull()
            ?.let(::toRow)

    /** The personal color for a person the server creates (FND-034): least used among the household's persons. */
    fun leastUsedColor(householdId: UUID): PaletteColor {
        val used =
            PersonTable
                .select(PersonTable.color)
                .where { (PersonTable.householdId eq householdId) and (PersonTable.deleted eq false) }
                .mapNotNull { row -> row[PersonTable.color]?.let(PaletteColor::fromKey) }
        return PaletteColors.pickLeastUsed(used)
    }

    fun toRow(it: ResultRow) =
        PersonRow(
            id = it[PersonTable.id],
            householdId = it[PersonTable.householdId],
            data =
                PersonData(
                    name = it[PersonTable.name],
                    birthYear = it[PersonTable.birthYear],
                    birthMonth = it[PersonTable.birthMonth],
                    birthDay = it[PersonTable.birthDay],
                    relation = it[PersonTable.relation],
                    notes = it[PersonTable.notes],
                    contacts = FamilyHubJson.decodeFromString(contactsSerializer, it[PersonTable.contacts]),
                    // A color unknown to this version (after a downgrade) reads as gray, like on clients.
                    color = it[PersonTable.color]?.let { key -> PaletteColor.fromKey(key) ?: PaletteColor.GRAY },
                ),
            seq = it[PersonTable.seq],
            deleted = it[PersonTable.deleted],
        )

    private fun writeData(
        it: UpdateBuilder<*>,
        data: PersonData,
    ) {
        it[PersonTable.name] = data.name
        it[PersonTable.birthYear] = data.birthYear
        it[PersonTable.birthMonth] = data.birthMonth
        it[PersonTable.birthDay] = data.birthDay
        it[PersonTable.relation] = data.relation
        it[PersonTable.notes] = data.notes
        it[PersonTable.contacts] = FamilyHubJson.encodeToString(contactsSerializer, data.contacts)
        it[PersonTable.color] = data.color?.key
    }
}
