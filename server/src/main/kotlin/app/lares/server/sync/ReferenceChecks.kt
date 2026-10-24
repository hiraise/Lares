package app.lares.server.sync

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.server.household.PersonTable
import app.lares.server.platform.http.ApiException
import app.lares.server.tags.TagTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

// Server-only checks shared by the sync handlers of several modules (tasks, shopping, calendar). Run inside `dbQuery`.

/** `VALIDATION_FAILED` with one field error. */
internal fun invalidField(
    field: String,
    code: FieldErrorCode = FieldErrorCode.INVALID_FORMAT,
) = ApiException(ErrorCode.VALIDATION_FAILED, "Invalid fields: $field", mapOf(field to code))

/** Ids not yet stored must be non-deleted tags of the household (archived tags count). */
internal fun requireAddedTags(
    ctx: MutationContext,
    added: Set<String>,
) {
    if (added.isEmpty()) return
    val ids = parseAll(added) ?: throw invalidField("tagIds")
    val found =
        TagTable
            .select(TagTable.id)
            .where { (TagTable.householdId eq ctx.householdId) and (TagTable.id inList ids) and (TagTable.deleted eq false) }
            .count()
    if (found != ids.size.toLong()) throw invalidField("tagIds")
}

/** Ids not yet stored must be non-deleted persons of the household; stored ones stay valid (clients drop unknown ids). */
internal fun requireAddedPersons(
    ctx: MutationContext,
    added: Set<String>,
) {
    if (added.isEmpty()) return
    val ids = parseAll(added) ?: throw invalidField("participantIds")
    val found =
        PersonTable
            .select(PersonTable.id)
            .where { (PersonTable.householdId eq ctx.householdId) and (PersonTable.id inList ids) and (PersonTable.deleted eq false) }
            .count()
    if (found != ids.size.toLong()) throw invalidField("participantIds")
}

/** The ids as UUIDs, or null if any of them is malformed. */
internal fun parseAll(ids: Set<String>): Set<UUID>? =
    ids.mapTo(mutableSetOf()) { runCatching { UUID.fromString(it) }.getOrNull() ?: return null }
