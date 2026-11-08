package app.lares.server.budget

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.permissions.BudgetRules
import app.lares.contract.sync.MutationOp
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.toContract
import app.lares.server.sync.MutationContext
import app.lares.server.sync.invalidField
import app.lares.server.tasks.TaskRepository
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// Server-only checks of the budget handlers (architecture/budget.md § Validation › Server-only checks).

/** `budget.manage` and, for rows that carry their writer, the writer's own id; else `FORBIDDEN`. */
internal fun requireBudgetWriter(
    ctx: MutationContext,
    op: MutationOp,
    type: String,
    foreign: Boolean,
) {
    if (!BudgetRules.canManage(ctx.viewer) || foreign) {
        throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} $type")
    }
}

/** A referenced id must be a lowercase UUID, as the server stores and returns it. */
internal fun requireUuid(
    field: String,
    value: String,
) {
    if (runCatching { UUID.fromString(value).toString() }.getOrNull() != value) throw invalidField(field)
}

/** An extra income's random id must be a UUIDv7 (derived ids are version 8). */
internal fun requireUuidV7(id: UUID) {
    if (id.version() != UUID_V7) throw invalidField("id")
}

/**
 * True when [month] is before the month of `now − 1 day` in the household zone: past plan versions are read-only
 * (BUD-004). The day of grace covers a phone that saved "from this month" just before midnight on the last day.
 */
internal fun isPastMonth(
    ctx: MutationContext,
    month: YearMonth,
): Boolean {
    val earliest = (ctx.now.toContract() - 1.days).toLocalDateTime(TaskRepository.householdTimeZone(ctx.householdId)).date.yearMonth
    return month < earliest
}

/** A time changed to later than `now + 1 day` (a phone clock far ahead): `OUT_OF_RANGE`. */
internal fun requireNotFuture(
    ctx: MutationContext,
    field: String,
    value: Instant?,
    stored: Instant?,
) {
    if (value != null && value != stored && value > ctx.now.toContract() + 1.days) {
        throw invalidField(field, FieldErrorCode.OUT_OF_RANGE)
    }
}

private const val UUID_V7 = 7
