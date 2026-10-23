package app.familyhub.client.sync

import app.familyhub.client.db.FamilyHubDb
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.entities.ContactData
import app.familyhub.contract.entities.GroupData
import app.familyhub.contract.entities.HouseholdData
import app.familyhub.contract.entities.MemberData
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.entities.TagData
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.contract.permissions.PermissionSetSerializer
import app.familyhub.contract.sync.EntityTypes
import app.familyhub.contract.validation.Validators
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import app.familyhub.client.db.Group_entity as GroupRow
import app.familyhub.client.db.Household as HouseholdRow
import app.familyhub.client.db.Member as MemberRow
import app.familyhub.client.db.Person as PersonRow
import app.familyhub.client.db.Tag as TagRow

// Typed-table adapters of the M0 entity types and the row ↔ data class mapping shared with the repositories.

private val stringList = ListSerializer(String.serializer())
private val contactList = ListSerializer(ContactData.serializer())

internal fun Boolean.toDb(): Long = if (this) 1L else 0L

internal fun paletteColor(key: String?): PaletteColor? = key?.let { PaletteColor.fromKey(it) ?: PaletteColor.GRAY }

internal fun HouseholdRow.toData() = HouseholdData(name, time_zone, currency, default_language)

internal fun MemberRow.toData() = MemberData(person_id, user_id, login, has_password != 0L)

internal fun GroupRow.toData() =
    GroupData(
        name = name,
        builtIn = built_in?.let { value -> BuiltInGroup.entries.firstOrNull { it.name == value } },
        permissions = FamilyHubJson.decodeFromString(PermissionSetSerializer, permissions),
        memberIds = FamilyHubJson.decodeFromString(stringList, member_ids).toSet(),
    )

internal fun PersonRow.toData() =
    PersonData(
        name = name,
        birthYear = birth_year?.toInt(),
        birthMonth = birth_month?.toInt(),
        birthDay = birth_day?.toInt(),
        relation = relation,
        notes = notes,
        contacts = FamilyHubJson.decodeFromString(contactList, contacts),
        color = paletteColor(color),
    )

internal fun TagRow.toData() = TagData(name, paletteColor(color) ?: PaletteColor.GRAY, icon, archived != 0L)

class HouseholdAdapter : EntityAdapter<HouseholdData>(EntityTypes.HOUSEHOLD, HouseholdData.serializer(), writable = false) {
    override fun selectTyped(
        db: FamilyHubDb,
        id: String,
    ) = db.householdQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: FamilyHubDb,
        id: String,
        data: HouseholdData,
    ) {
        db.householdQueries.upsert(id, data.name, data.timeZone, data.currency, data.defaultLanguage)
    }

    override fun deleteTyped(
        db: FamilyHubDb,
        id: String,
    ) {
        db.householdQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: FamilyHubDb) {
        db.householdQueries.deleteAll()
    }
}

class MemberAdapter : EntityAdapter<MemberData>(EntityTypes.MEMBER, MemberData.serializer(), writable = false) {
    override fun selectTyped(
        db: FamilyHubDb,
        id: String,
    ) = db.memberQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: FamilyHubDb,
        id: String,
        data: MemberData,
    ) {
        db.memberQueries.upsert(id, data.personId, data.userId, data.login, data.hasPassword.toDb())
    }

    override fun deleteTyped(
        db: FamilyHubDb,
        id: String,
    ) {
        db.memberQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: FamilyHubDb) {
        db.memberQueries.deleteAll()
    }
}

class GroupAdapter : EntityAdapter<GroupData>(EntityTypes.GROUP, GroupData.serializer(), writable = false) {
    override fun selectTyped(
        db: FamilyHubDb,
        id: String,
    ) = db.group_entityQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: FamilyHubDb,
        id: String,
        data: GroupData,
    ) {
        db.group_entityQueries.upsert(
            id = id,
            name = data.name,
            built_in = data.builtIn?.name,
            permissions = FamilyHubJson.encodeToString(PermissionSetSerializer, data.permissions),
            member_ids = FamilyHubJson.encodeToString(stringList, data.memberIds.sorted()),
        )
    }

    override fun deleteTyped(
        db: FamilyHubDb,
        id: String,
    ) {
        db.group_entityQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: FamilyHubDb) {
        db.group_entityQueries.deleteAll()
    }
}

class PersonAdapter(
    private val clock: Clock,
) : EntityAdapter<PersonData>(EntityTypes.PERSON, PersonData.serializer(), writable = true) {
    override fun selectTyped(
        db: FamilyHubDb,
        id: String,
    ) = db.personQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: FamilyHubDb,
        id: String,
        data: PersonData,
    ) {
        db.personQueries.upsert(
            id = id,
            name = data.name,
            birth_year = data.birthYear?.toLong(),
            birth_month = data.birthMonth?.toLong(),
            birth_day = data.birthDay?.toLong(),
            relation = data.relation,
            notes = data.notes,
            contacts = FamilyHubJson.encodeToString(contactList, data.contacts),
            color = data.color?.key,
        )
    }

    override fun deleteTyped(
        db: FamilyHubDb,
        id: String,
    ) {
        db.personQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: FamilyHubDb) {
        db.personQueries.deleteAll()
    }

    override fun normalize(data: PersonData) = data.copy(name = data.name.trim())

    // The server validates with the UTC year too.
    override fun validate(data: PersonData) = Validators.person(data, clock.now().toLocalDateTime(TimeZone.UTC).year)
}

class TagAdapter : EntityAdapter<TagData>(EntityTypes.TAG, TagData.serializer(), writable = true) {
    override fun selectTyped(
        db: FamilyHubDb,
        id: String,
    ) = db.tagQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: FamilyHubDb,
        id: String,
        data: TagData,
    ) {
        db.tagQueries.upsert(id, data.name, data.color.key, data.icon, data.archived.toDb())
    }

    override fun deleteTyped(
        db: FamilyHubDb,
        id: String,
    ) {
        db.tagQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: FamilyHubDb) {
        db.tagQueries.deleteAll()
    }

    override fun normalize(data: TagData) = data.copy(name = data.name.trim())

    override fun validate(data: TagData) = Validators.tag(data)

    /**
     * TAG-006: names are unique ignoring case; archived tags keep their name. Compared in Kotlin, because SQLite's
     * `lower()` folds only ASCII.
     */
    override fun localConflicts(
        db: FamilyHubDb,
        id: String,
        data: TagData,
    ): Map<String, FieldErrorCode> {
        // Like the server: an unchanged name is never a conflict.
        if (selectTyped(db, id)?.name == data.name) return emptyMap()
        val name = data.name.lowercase()
        val taken =
            db.tagQueries
                .selectAll()
                .executeAsList()
                .any { it.id != id && it.name.lowercase() == name }
        return if (taken) mapOf("name" to FieldErrorCode.TAKEN) else emptyMap()
    }
}

/** All adapters of this client version. */
fun defaultEntityAdapters(clock: Clock) =
    EntityAdapters(listOf(HouseholdAdapter(), MemberAdapter(), GroupAdapter(), PersonAdapter(clock), TagAdapter()))
