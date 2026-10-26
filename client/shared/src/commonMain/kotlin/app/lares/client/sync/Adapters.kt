package app.lares.client.sync

import app.lares.client.data.location.PlaceAdapter
import app.lares.client.data.location.PlaceAlertAdapter
import app.lares.client.db.LaresDb
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.ContactData
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.GroupData
import app.lares.contract.entities.HouseholdData
import app.lares.contract.entities.LenientRecurrenceSerializer
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.entities.TagData
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.BuiltInGroup
import app.lares.contract.permissions.EventRules
import app.lares.contract.permissions.PermissionSetSerializer
import app.lares.contract.permissions.ShoppingRules
import app.lares.contract.permissions.TaskRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.shopping.ShoppingNames
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import kotlin.time.Instant
import app.lares.client.db.Checklist_item as ChecklistItemRow
import app.lares.client.db.Event as EventRow
import app.lares.client.db.Event_occurrence as EventOccurrenceRow
import app.lares.client.db.Event_reminder as EventReminderRow
import app.lares.client.db.Group_entity as GroupRow
import app.lares.client.db.Household as HouseholdRow
import app.lares.client.db.Member as MemberRow
import app.lares.client.db.Person as PersonRow
import app.lares.client.db.Shopping_item as ShoppingItemRow
import app.lares.client.db.Shopping_list as ShoppingListRow
import app.lares.client.db.Tag as TagRow
import app.lares.client.db.Task as TaskRow
import app.lares.client.db.Task_completion as TaskCompletionRow

// Typed-table adapters of all entity types and the row ↔ data class mapping shared with the repositories.

internal val stringList = ListSerializer(String.serializer())
private val contactList = ListSerializer(ContactData.serializer())

internal fun Boolean.toDb(): Long = if (this) 1L else 0L

internal fun paletteColor(key: String?): PaletteColor? = key?.let { PaletteColor.fromKey(it) ?: PaletteColor.GRAY }

internal fun HouseholdRow.toData() = HouseholdData(name, time_zone, currency, default_language)

internal fun MemberRow.toData() = MemberData(person_id, user_id, login, has_password != 0L)

internal fun GroupRow.toData() =
    GroupData(
        name = name,
        builtIn = built_in?.let { value -> BuiltInGroup.entries.firstOrNull { it.name == value } },
        permissions = LaresJson.decodeFromString(PermissionSetSerializer, permissions),
        memberIds = LaresJson.decodeFromString(stringList, member_ids).toSet(),
    )

internal fun PersonRow.toData() =
    PersonData(
        name = name,
        birthYear = birth_year?.toInt(),
        birthMonth = birth_month?.toInt(),
        birthDay = birth_day?.toInt(),
        relation = relation,
        notes = notes,
        contacts = LaresJson.decodeFromString(contactList, contacts),
        color = paletteColor(color),
    )

internal fun TagRow.toData() = TagData(name, paletteColor(color) ?: PaletteColor.GRAY, icon, archived != 0L)

class HouseholdAdapter : EntityAdapter<HouseholdData>(EntityTypes.HOUSEHOLD, HouseholdData.serializer(), writable = false) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.householdQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: HouseholdData,
    ) {
        db.householdQueries.upsert(id, data.name, data.timeZone, data.currency, data.defaultLanguage)
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.householdQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.householdQueries.deleteAll()
    }
}

class MemberAdapter : EntityAdapter<MemberData>(EntityTypes.MEMBER, MemberData.serializer(), writable = false) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.memberQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: MemberData,
    ) {
        db.memberQueries.upsert(id, data.personId, data.userId, data.login, data.hasPassword.toDb())
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.memberQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.memberQueries.deleteAll()
    }
}

class GroupAdapter : EntityAdapter<GroupData>(EntityTypes.GROUP, GroupData.serializer(), writable = false) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.group_entityQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: GroupData,
    ) {
        db.group_entityQueries.upsert(
            id = id,
            name = data.name,
            built_in = data.builtIn?.name,
            permissions = LaresJson.encodeToString(PermissionSetSerializer, data.permissions),
            member_ids = LaresJson.encodeToString(stringList, data.memberIds.sorted()),
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.group_entityQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.group_entityQueries.deleteAll()
    }
}

class PersonAdapter(
    private val clock: Clock,
) : EntityAdapter<PersonData>(EntityTypes.PERSON, PersonData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.personQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
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
            contacts = LaresJson.encodeToString(contactList, data.contacts),
            color = data.color?.key,
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.personQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.personQueries.deleteAll()
    }

    override fun normalize(data: PersonData) = data.copy(name = data.name.trim())

    override fun checkDelete(
        db: LaresDb,
        id: String,
        current: PersonData,
    ) {
        if (db.memberQueries.countByPerson(id).executeAsOne() > 0) throw LocalWriteException(ErrorCode.PERSON_IS_MEMBER)
    }

    // The server validates with the UTC year too.
    override fun validate(data: PersonData) = Validators.person(data, clock.now().toLocalDateTime(TimeZone.UTC).year)
}

class TagAdapter : EntityAdapter<TagData>(EntityTypes.TAG, TagData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.tagQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: TagData,
    ) {
        db.tagQueries.upsert(id, data.name, data.color.key, data.icon, data.archived.toDb())
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.tagQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.tagQueries.deleteAll()
    }

    override fun normalize(data: TagData) = data.copy(name = data.name.trim())

    override fun validate(data: TagData) = Validators.tag(data)

    /**
     * TAG-006: names are unique ignoring case; archived tags keep their name. Compared in Kotlin, because SQLite's
     * `lower()` folds only ASCII.
     */
    override fun localConflicts(
        db: LaresDb,
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

// Tasks (architecture/tasks.md § Client): the same TaskRules checks as the server's handlers, with the parent task
// read from the local `task` table.

internal fun TaskRow.toData() =
    TaskData(
        title = title,
        createdBy = created_by,
        notes = notes,
        dueDate = due_date?.let(LocalDate::parse),
        dueTime = due_time?.let(LocalTime::parse),
        assigneeIds = LaresJson.decodeFromString(stringList, assignee_ids).toSet(),
        tagIds = LaresJson.decodeFromString(stringList, tag_ids).toSet(),
        recurrence = recurrence?.let { LaresJson.decodeFromString(LenientRecurrenceSerializer, it) },
        reminderMinutesBefore = reminder_minutes_before?.toInt(),
        notifyCreatorOnDone = notify_creator_on_done != 0L,
    )

internal fun ChecklistItemRow.toData() =
    ChecklistItemData(task_id, text, position.toInt(), done != 0L, done_occurrence?.let(LocalDate::parse))

internal fun TaskCompletionRow.toData() =
    TaskCompletionData(task_id, completed_by, Instant.fromEpochMilliseconds(completed_at), occurrence?.let(LocalDate::parse), skipped != 0L)

/** The parent of a checklist item or completion; `ENTITY_NOT_FOUND` if it is not (or no longer) in the local table. */
private fun requireParentTask(
    db: LaresDb,
    taskId: String,
): TaskData =
    db.taskQueries
        .selectById(taskId)
        .executeAsOneOrNull()
        ?.toData() ?: throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Task not found")

/** Top-level field names whose JSON differs between [before] and [after]. */
private fun <T : Any> EntityAdapter<T>.changedFields(
    before: T,
    after: T,
): Set<String> {
    val a = encode(before)
    val b = encode(after)
    return (a.keys + b.keys).filterTo(mutableSetOf()) { a[it] != b[it] }
}

internal fun forbidden(
    op: MutationOp,
    what: String,
): Nothing = throw LocalForbiddenException("Not allowed to ${op.name.lowercase()} this $what")

class TaskAdapter : EntityAdapter<TaskData>(EntityTypes.TASK, TaskData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.taskQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: TaskData,
    ) {
        db.taskQueries.upsert(
            id = id,
            title = data.title,
            created_by = data.createdBy,
            notes = data.notes,
            due_date = data.dueDate?.toString(),
            due_time = data.dueTime?.toString(),
            assignee_ids = LaresJson.encodeToString(stringList, data.assigneeIds.sorted()),
            tag_ids = LaresJson.encodeToString(stringList, data.tagIds.sorted()),
            recurrence = data.recurrence?.let { LaresJson.encodeToString(Recurrence.serializer(), it) },
            reminder_minutes_before = data.reminderMinutesBefore?.toLong(),
            notify_creator_on_done = data.notifyCreatorOnDone.toDb(),
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.taskQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.taskQueries.deleteAll()
    }

    override fun normalize(data: TaskData) = Validators.normalizeTask(data)

    override fun validate(data: TaskData) = Validators.task(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: TaskData?,
        merged: TaskData?,
    ) {
        // Like the server: edits are checked against the stored task.
        val allowed =
            when {
                current != null -> TaskRules.canEdit(viewer, current)
                merged != null -> TaskRules.canCreate(viewer, merged)
                else -> false
            }
        if (!allowed) forbidden(op, "task")
        if (current != null && merged != null) {
            if (current.createdBy != merged.createdBy) throw LocalValidationException(mapOf("createdBy" to FieldErrorCode.INVALID_FORMAT))
            // ADR-0017: a routine never becomes a one-off.
            if (current.recurrence != null && merged.recurrence == null) {
                throw LocalValidationException(mapOf("recurrence" to FieldErrorCode.INVALID_FORMAT))
            }
        }
    }
}

class ChecklistItemAdapter : EntityAdapter<ChecklistItemData>(EntityTypes.CHECKLIST_ITEM, ChecklistItemData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.checklist_itemQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: ChecklistItemData,
    ) {
        db.checklist_itemQueries.upsert(
            id,
            data.taskId,
            data.text,
            data.position.toLong(),
            data.done.toDb(),
            data.doneOccurrence?.toString(),
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.checklist_itemQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.checklist_itemQueries.deleteAll()
    }

    override fun normalize(data: ChecklistItemData) = Validators.normalizeChecklistItem(data)

    override fun validate(data: ChecklistItemData) = Validators.checklistItem(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: ChecklistItemData?,
        merged: ChecklistItemData?,
    ) {
        val task = requireParentTask(db, (current ?: merged ?: return).taskId)
        val allowed =
            if (current != null && merged != null) {
                if (current.taskId != merged.taskId) throw LocalValidationException(mapOf("taskId" to FieldErrorCode.INVALID_FORMAT))
                TaskRules.canChangeChecklistItem(viewer, task, changedFields(current, merged))
            } else {
                TaskRules.canEdit(viewer, task)
            }
        if (!allowed) forbidden(op, "checklist item")
    }
}

class TaskCompletionAdapter :
    EntityAdapter<TaskCompletionData>(EntityTypes.TASK_COMPLETION, TaskCompletionData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.task_completionQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: TaskCompletionData,
    ) {
        db.task_completionQueries.upsert(
            id = id,
            task_id = data.taskId,
            completed_by = data.completedBy,
            completed_at = data.completedAt.toEpochMilliseconds(),
            occurrence = data.occurrence?.toString(),
            skipped = data.skipped.toDb(),
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.task_completionQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.task_completionQueries.deleteAll()
    }

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: TaskCompletionData?,
        merged: TaskCompletionData?,
    ) {
        when {
            current == null && merged != null -> {
                val task = requireParentTask(db, merged.taskId)
                if (merged.completedBy != viewer.memberId || !TaskRules.canComplete(viewer, task)) forbidden(op, "task")
            }

            current != null && merged != null -> {
                // Completions are immutable.
                val changed = changedFields(current, merged)
                if (changed.isNotEmpty()) throw LocalValidationException(changed.associateWith { FieldErrorCode.INVALID_FORMAT })
            }

            current != null -> {
                val task = requireParentTask(db, current.taskId)
                if (!TaskRules.canDeleteCompletion(viewer, task, current)) forbidden(op, "completion")
            }
        }
    }

    override fun localConflicts(
        db: LaresDb,
        id: String,
        data: TaskCompletionData,
    ): Map<String, FieldErrorCode> = Validators.taskCompletion(data, requireParentTask(db, data.taskId))
}

// Shopping (architecture/shopping.md § Adapters): the same ShoppingRules checks as the server's handlers, with the
// parent list read from the local `shopping_list` table.

internal fun ShoppingListRow.toData() =
    ShoppingListData(
        name = name,
        createdBy = created_by,
        tagIds = LaresJson.decodeFromString(stringList, tag_ids).toSet(),
        groupByCategory = group_by_category != 0L,
    )

internal fun ShoppingItemRow.toData() =
    ShoppingItemData(
        listId = list_id,
        name = name,
        addedBy = added_by,
        addedAt = Instant.fromEpochMilliseconds(added_at),
        status = ShoppingItemStatus.fromKey(status) ?: ShoppingItemStatus.CLEARED,
        category = ProductCategory.fromKey(category) ?: ProductCategory.OTHER,
        quantity = quantity,
        note = note,
        tagIds = LaresJson.decodeFromString(stringList, tag_ids).toSet(),
        boughtBy = bought_by,
        boughtAt = bought_at?.let(Instant::fromEpochMilliseconds),
        boughtCount = bought_count.toInt(),
    )

class ShoppingListAdapter : EntityAdapter<ShoppingListData>(EntityTypes.SHOPPING_LIST, ShoppingListData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.shopping_listQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: ShoppingListData,
    ) {
        db.shopping_listQueries.upsert(
            id = id,
            name = data.name,
            created_by = data.createdBy,
            tag_ids = LaresJson.encodeToString(stringList, data.tagIds.sorted()),
            group_by_category = data.groupByCategory.toDb(),
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.shopping_listQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.shopping_listQueries.deleteAll()
    }

    override fun normalize(data: ShoppingListData) = Validators.normalizeShoppingList(data)

    override fun validate(data: ShoppingListData) = Validators.shoppingList(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: ShoppingListData?,
        merged: ShoppingListData?,
    ) {
        val allowed =
            when {
                current != null -> ShoppingRules.canManageLists(viewer)
                merged != null -> ShoppingRules.canCreateList(viewer, merged)
                else -> false
            }
        if (!allowed) forbidden(op, "shopping list")
        if (current != null && merged != null && current.createdBy != merged.createdBy) {
            throw LocalValidationException(mapOf("createdBy" to FieldErrorCode.INVALID_FORMAT))
        }
    }
}

class ShoppingItemAdapter : EntityAdapter<ShoppingItemData>(EntityTypes.SHOPPING_ITEM, ShoppingItemData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.shopping_itemQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: ShoppingItemData,
    ) {
        db.shopping_itemQueries.upsert(
            id = id,
            list_id = data.listId,
            name = data.name,
            name_key = ShoppingNames.key(data.name),
            status = data.status.key,
            category = data.category.key,
            quantity = data.quantity,
            note = data.note,
            tag_ids = LaresJson.encodeToString(stringList, data.tagIds.sorted()),
            added_by = data.addedBy,
            added_at = data.addedAt.toEpochMilliseconds(),
            bought_by = data.boughtBy,
            bought_at = data.boughtAt?.toEpochMilliseconds(),
            bought_count = data.boughtCount.toLong(),
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.shopping_itemQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.shopping_itemQueries.deleteAll()
    }

    override fun normalize(data: ShoppingItemData) = Validators.normalizeShoppingItem(data)

    override fun validate(data: ShoppingItemData) = Validators.shoppingItem(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: ShoppingItemData?,
        merged: ShoppingItemData?,
    ) {
        val allowed =
            if (merged != null) {
                // Like the server: the stored parent decides; a changed listId is invalid (checked after the rights).
                requireParentList(db, (current ?: merged).listId)
                ShoppingRules.canEditItems(viewer) && ShoppingRules.foreignAuthorFields(viewer, current, merged).isEmpty()
            } else {
                ShoppingRules.canEditItems(viewer)
            }
        if (!allowed) forbidden(op, "shopping item")
        if (current != null && merged != null && current.listId != merged.listId) {
            throw LocalValidationException(mapOf("listId" to FieldErrorCode.INVALID_FORMAT))
        }
    }
}

/** The parent of a shopping item; `ENTITY_NOT_FOUND` if it is not (or no longer) in the local table. */
private fun requireParentList(
    db: LaresDb,
    listId: String,
): ShoppingListData =
    db.shopping_listQueries
        .selectById(listId)
        .executeAsOneOrNull()
        ?.toData() ?: throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Shopping list not found")

// Calendar (architecture/calendar.md § Adapters): the same EventRules checks, immutable fields and derived-id checks as
// the server's handlers, with the parent event read from the local `event` table.

internal fun EventRow.toData() =
    EventData(
        title = title,
        createdBy = created_by,
        startDate = LocalDate.parse(start_date),
        endDate = LocalDate.parse(end_date),
        allDay = all_day != 0L,
        startTime = start_time?.let(LocalTime::parse),
        endTime = end_time?.let(LocalTime::parse),
        place = place,
        notes = notes,
        participantIds = LaresJson.decodeFromString(stringList, participant_ids).toSet(),
        tagIds = LaresJson.decodeFromString(stringList, tag_ids).toSet(),
        recurrence = recurrence?.let { LaresJson.decodeFromString(LenientRecurrenceSerializer, it) },
        repeatUntil = repeat_until?.let(LocalDate::parse),
        reminderMinutesBefore = reminder_minutes_before?.toInt(),
        kind = EventKind.fromKey(kind) ?: EventKind.EVENT,
        countYears = count_years != 0L,
        splitFrom = split_from,
    )

internal fun EventOccurrenceRow.toData() =
    EventOccurrenceData(
        eventId = event_id,
        occurrence = LocalDate.parse(occurrence),
        cancelled = cancelled != 0L,
        startDate = start_date?.let(LocalDate::parse),
        endDate = end_date?.let(LocalDate::parse),
        startTime = start_time?.let(LocalTime::parse),
        endTime = end_time?.let(LocalTime::parse),
        title = title,
        place = place,
        notes = notes,
        participantIds = participant_ids?.let { LaresJson.decodeFromString(stringList, it).toSet() },
    )

internal fun EventReminderRow.toData() = EventReminderData(event_id, member_id, minutes_before?.toInt())

/** The parent of an occurrence or reminder row; `ENTITY_NOT_FOUND` if it is not (or no longer) in the local table. */
private fun requireParentEvent(
    db: LaresDb,
    eventId: String,
): EventData =
    db.eventQueries
        .selectById(eventId)
        .executeAsOneOrNull()
        ?.toData() ?: throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Event not found")

internal fun invalid(field: String): Nothing = throw LocalValidationException(mapOf(field to FieldErrorCode.INVALID_FORMAT))

/** A child row's id must be the one derived from its parent (ADR-0022, ADR-0023), else `id: INVALID_FORMAT`. */
internal fun requireDerivedId(
    id: String,
    expected: () -> String,
) {
    val derived = runCatching(expected).getOrNull()
    if (derived == null || id.lowercase() != derived.lowercase()) invalid("id")
}

class EventAdapter : EntityAdapter<EventData>(EntityTypes.EVENT, EventData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.eventQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: EventData,
    ) {
        db.eventQueries.upsert(
            id = id,
            title = data.title,
            created_by = data.createdBy,
            start_date = data.startDate.toString(),
            end_date = data.endDate.toString(),
            all_day = data.allDay.toDb(),
            start_time = data.startTime?.toString(),
            end_time = data.endTime?.toString(),
            place = data.place,
            notes = data.notes,
            participant_ids = LaresJson.encodeToString(stringList, data.participantIds.sorted()),
            tag_ids = LaresJson.encodeToString(stringList, data.tagIds.sorted()),
            recurrence = data.recurrence?.let { LaresJson.encodeToString(Recurrence.serializer(), it) },
            repeat_until = data.repeatUntil?.toString(),
            reminder_minutes_before = data.reminderMinutesBefore?.toLong(),
            kind = data.kind.key,
            count_years = data.countYears.toDb(),
            split_from = data.splitFrom,
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.eventQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.eventQueries.deleteAll()
    }

    override fun normalize(data: EventData) = Validators.normalizeEvent(data)

    override fun validate(data: EventData) = Validators.event(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: EventData?,
        merged: EventData?,
    ) {
        // Like the server: edits are checked against the stored event.
        val allowed =
            when {
                current != null -> EventRules.canEdit(viewer, current)
                merged != null -> EventRules.canCreate(viewer, merged)
                else -> false
            }
        if (!allowed) forbidden(op, "event")
        if (merged == null) return
        if (current != null) {
            if (current.createdBy != merged.createdBy) invalid("createdBy")
            if (current.kind != merged.kind) invalid("kind")
            if (current.splitFrom != merged.splitFrom) invalid("splitFrom")
        } else {
            // The origin of a split must be an event this phone knows (it may have been deleted since).
            val origin = merged.splitFrom
            if (origin != null) {
                val known =
                    db.eventQueries.selectById(origin).executeAsOneOrNull() != null ||
                        db.sync_shadowQueries.get(EntityTypes.EVENT, origin).executeAsOneOrNull() != null
                if (origin.lowercase() == id.lowercase() || !known) invalid("splitFrom")
            }
        }
    }
}

class EventOccurrenceAdapter :
    EntityAdapter<EventOccurrenceData>(EntityTypes.EVENT_OCCURRENCE, EventOccurrenceData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.event_occurrenceQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: EventOccurrenceData,
    ) {
        db.event_occurrenceQueries.upsert(
            id = id,
            event_id = data.eventId,
            occurrence = data.occurrence.toString(),
            cancelled = data.cancelled.toDb(),
            start_date = data.startDate?.toString(),
            end_date = data.endDate?.toString(),
            start_time = data.startTime?.toString(),
            end_time = data.endTime?.toString(),
            title = data.title,
            place = data.place,
            notes = data.notes,
            participant_ids = data.participantIds?.let { LaresJson.encodeToString(stringList, it.sorted()) },
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.event_occurrenceQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.event_occurrenceQueries.deleteAll()
    }

    override fun normalize(data: EventOccurrenceData) = Validators.normalizeEventOccurrence(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: EventOccurrenceData?,
        merged: EventOccurrenceData?,
    ) {
        // The stored parent decides.
        val event = requireParentEvent(db, (current ?: merged ?: return).eventId)
        if (!EventRules.canEdit(viewer, event)) forbidden(op, "occurrence")
        if (merged == null) return
        if (current != null) {
            if (current.eventId != merged.eventId) invalid("eventId")
            if (current.occurrence != merged.occurrence) invalid("occurrence")
        }
        requireDerivedId(id) { Ids.occurrenceRowId(merged.eventId, merged.occurrence) }
    }

    /** Never reached: `WriteRules` refuses deletes of occurrence rows; only the event's cascade removes them. */
    override fun checkDelete(
        db: LaresDb,
        id: String,
        current: EventOccurrenceData,
    ): Unit = throw LocalForbiddenException("Occurrence rows are removed only with their event")

    override fun localConflicts(
        db: LaresDb,
        id: String,
        data: EventOccurrenceData,
    ): Map<String, FieldErrorCode> = Validators.eventOccurrence(data, requireParentEvent(db, data.eventId))
}

class EventReminderAdapter :
    EntityAdapter<EventReminderData>(EntityTypes.EVENT_REMINDER, EventReminderData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.event_reminderQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: EventReminderData,
    ) {
        db.event_reminderQueries.upsert(id, data.eventId, data.memberId, data.minutesBefore?.toLong())
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.event_reminderQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.event_reminderQueries.deleteAll()
    }

    override fun validate(data: EventReminderData) = Validators.eventReminder(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: EventReminderData?,
        merged: EventReminderData?,
    ) {
        requireParentEvent(db, (current ?: merged ?: return).eventId)
        // Both the stored row and the result must be the writer's own.
        val allowed =
            (current == null || EventRules.canSetReminder(viewer, current)) &&
                (merged == null || EventRules.canSetReminder(viewer, merged))
        if (!allowed) forbidden(op, "reminder")
        if (merged == null) return
        if (current != null) {
            if (current.eventId != merged.eventId) invalid("eventId")
            if (current.memberId != merged.memberId) invalid("memberId")
        }
        requireDerivedId(id) { Ids.reminderRowId(merged.eventId, merged.memberId) }
    }

    /** Never reached: `WriteRules` refuses deletes of reminder rows; only the event's cascade removes them. */
    override fun checkDelete(
        db: LaresDb,
        id: String,
        current: EventReminderData,
    ): Unit = throw LocalForbiddenException("Reminder rows are removed only with their event")
}

/** All adapters of this client version. */
fun defaultEntityAdapters(clock: Clock) =
    EntityAdapters(
        listOf(
            HouseholdAdapter(),
            MemberAdapter(),
            GroupAdapter(),
            PersonAdapter(clock),
            TagAdapter(),
            TaskAdapter(),
            ChecklistItemAdapter(),
            TaskCompletionAdapter(),
            ShoppingListAdapter(),
            ShoppingItemAdapter(),
            EventAdapter(),
            EventOccurrenceAdapter(),
            EventReminderAdapter(),
            PlaceAdapter(),
            PlaceAlertAdapter(),
        ),
    )
