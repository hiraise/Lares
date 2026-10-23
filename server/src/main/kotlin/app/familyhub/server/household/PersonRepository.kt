package app.familyhub.server.household

import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.entities.ContactData
import app.familyhub.contract.entities.PersonData
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
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
            it[name] = data.name
            it[birthYear] = data.birthYear
            it[birthMonth] = data.birthMonth
            it[birthDay] = data.birthDay
            it[relation] = data.relation
            it[notes] = data.notes
            it[contacts] = FamilyHubJson.encodeToString(contactsSerializer, data.contacts)
            it[PersonTable.seq] = seq
            it[createdAt] = now
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

    fun get(
        householdId: UUID,
        id: UUID,
    ): PersonRow? =
        PersonTable
            .selectAll()
            .where { (PersonTable.householdId eq householdId) and (PersonTable.id eq id) }
            .singleOrNull()
            ?.let {
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
                        ),
                    seq = it[PersonTable.seq],
                    deleted = it[PersonTable.deleted],
                )
            }
}
