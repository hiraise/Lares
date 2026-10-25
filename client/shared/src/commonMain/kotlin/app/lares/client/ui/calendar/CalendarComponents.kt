package app.lares.client.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lares.client.data.Person
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.MonthCell
import app.lares.client.resources.Res
import app.lares.client.resources.action_add
import app.lares.client.resources.cal_nothing_planned
import app.lares.client.resources.cal_overdue
import app.lares.client.resources.ic_cake
import app.lares.client.resources.ic_celebration
import app.lares.client.resources.ic_repeat
import app.lares.client.resources.months_short
import app.lares.client.resources.task_mark
import app.lares.client.resources.task_unmark
import app.lares.client.resources.weekdays_short
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.tasks.TaskCheckbox
import app.lares.client.ui.tasks.TaskRowUi
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tones
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.PaletteColor
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** What a row of a day does on a tap; defaults do nothing (tests). */
class CalendarRowActions(
    /** An event or memorable date: its id and the scheduled date of the occurrence. */
    val onEvent: (String, LocalDate) -> Unit = { _, _ -> },
    val onPerson: (String) -> Unit = {},
    val onTask: (String) -> Unit = {},
    val onToggle: (TaskRowUi) -> Unit = {},
)

private val CELL_HEIGHT = 44.dp
private val CIRCLE = 32.dp
private val DOT = 5.dp
private val TIME_COLUMN = 52.dp
private val ROW_INSET = 80.dp

/** «Пн … Вс» and 6 rows of [DayCell]s (mockup `Calendar.dc.html`). */
@Composable
fun MonthGrid(
    cells: List<MonthCell>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekdays = stringArrayResource(Res.array.weekdays_short)
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { day ->
                Text(
                    weekdays.getOrElse(day.ordinal) { "" }.replaceFirstChar { it.uppercaseChar() },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (day.isWeekend()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = Spacing.xs),
                )
            }
        }
        cells.chunked(DayOfWeek.entries.size).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCell(cell, cell.date == selected, { onSelect(cell.date) }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 44 dp: the number in a 32 dp circle (today filled `primary`, selected outlined), up to 3 dots and «+» under it. */
@Composable
fun DayCell(
    cell: MonthCell,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val circle =
        when {
            cell.today -> Modifier.background(colors.primary, CircleShape)
            selected -> Modifier.border(1.5.dp, colors.primary, CircleShape)
            else -> Modifier
        }
    val numberColor =
        when {
            cell.today -> colors.onPrimary
            cell.date.dayOfWeek.isWeekend() -> colors.onSurfaceVariant
            else -> colors.onSurface
        }
    Column(
        modifier
            .height(CELL_HEIGHT)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("day_${cell.date}")
            .alpha(if (cell.inMonth) 1f else OTHER_MONTH_ALPHA),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(CIRCLE).then(circle).then(if (cell.today) Modifier.testTag("today_marker") else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                cell.date.day.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = numberColor,
            )
        }
        Row(Modifier.height(DOT), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            cell.dots.forEach { color -> Dot(color, Modifier.testTag("dot_${cell.date}")) }
            if (cell.more) {
                Text(
                    "+",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold),
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Dot(
    color: PaletteColor?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(DOT).background(color?.tones()?.base ?: MaterialTheme.colorScheme.primary, CircleShape))
}

/**
 * The selected day under the grid: header «Пятница, 23 октября» and its rows; nothing planned → a note and
 * «Добавить» (when [onAdd] is set).
 */
@Composable
fun DayList(
    header: String,
    rows: List<CalendarRow>,
    today: LocalDate,
    actions: CalendarRowActions,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        DayHeader(header, Modifier.testTag("day_header"))
        if (rows.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(Res.string.cal_nothing_planned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onAdd != null) LinkButton(stringResource(Res.string.action_add), onAdd, Modifier.testTag("day_add"))
            }
        } else {
            DayRows(rows, today, actions)
        }
    }
}

/** A day header: `titleSmall` bold, like the mockup. */
@Composable
fun DayHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        modifier = modifier.padding(horizontal = Spacing.xs),
    )
}

/** The rows of one day in a list group. */
@Composable
fun DayRows(
    rows: List<CalendarRow>,
    today: LocalDate,
    actions: CalendarRowActions,
) {
    val names = rows.flatMap { row -> (row.item as? CalendarItem.Event)?.participants.orEmpty().map { it.data.name } }.distinct()
    ListGroup {
        rows.forEachIndexed { index, row ->
            if (index > 0) ListDivider(inset = ROW_INSET)
            when (val item = row.item) {
                is CalendarItem.Event -> {
                    if (item.kind == EventKind.MEMORABLE_DATE) {
                        MemorableRow(item) { actions.onEvent(item.occ.eventId, item.occ.occurrence) }
                    } else {
                        EventRow(item, today, names) { actions.onEvent(item.occ.eventId, item.occ.occurrence) }
                    }
                }

                is CalendarItem.Birthday -> {
                    BirthdayRow(item) { actions.onPerson(item.person.id) }
                }

                is CalendarItem.Task -> {
                    val task = row.task
                    if (task != null) CalendarTaskRow(item, task, { actions.onToggle(task) }, { actions.onTask(item.view.id) })
                }
            }
        }
    }
}

/** An event: time column, a 3 dp bar in the first participant's color, title (+ `repeat`), meta, avatars. */
@Composable
fun EventRow(
    item: CalendarItem.Event,
    today: LocalDate,
    allNames: List<String> = item.participants.map { it.data.name },
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val time = CalendarLogic.timeColumn(item, today)
    RowFrame(onClick, Modifier.testTag("event_row_${item.occ.eventId}")) {
        Column(Modifier.width(TIME_COLUMN)) {
            Text(time.top.text(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            time.bottom?.let {
                Text(it.text(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2)
            }
        }
        val bar =
            item.participants
                .firstOrNull()
                ?.color
                ?.tones()
                ?.base ?: colors.primary
        Box(Modifier.width(3.dp).height(36.dp).background(bar, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.occ.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.repeating) AppIcon(Res.drawable.ic_repeat, null, size = 14.dp, tint = colors.onSurfaceVariant)
            }
            Meta(CalendarLogic.eventMeta(item.participants, item.occ.place).text())
        }
        PersonAvatars(item.participants, allNames)
    }
}

/** A birthday: `cake` in the person's color, «Бабушка Валя, 78 лет», the relation. A tap opens the person. */
@Composable
fun BirthdayRow(
    item: CalendarItem.Birthday,
    onClick: () -> Unit,
) {
    RowFrame(onClick, Modifier.testTag("birthday_row_${item.person.id}")) {
        LeadingIcon {
            AppIcon(
                Res.drawable.ic_cake,
                null,
                tint =
                    item.person.color
                        .tones()
                        .base,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Title(CalendarLogic.birthdayTitle(item.person.data.name, item.age).text())
            item.person.data.relation
                ?.takeIf { it.isNotBlank() }
                ?.let { Meta(it) }
        }
    }
}

/** A memorable date: `celebration` in the first participant's color, «Годовщина свадьбы · 16 лет», the names. */
@Composable
fun MemorableRow(
    item: CalendarItem.Event,
    onClick: () -> Unit,
) {
    val tint =
        item.participants
            .firstOrNull()
            ?.color
            ?.tones()
            ?.base ?: MaterialTheme.colorScheme.primary
    RowFrame(onClick, Modifier.testTag("memorable_row_${item.occ.eventId}")) {
        LeadingIcon { AppIcon(Res.drawable.ic_celebration, null, tint = tint) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Title(CalendarLogic.memorableTitle(item.occ.title, item.years).text())
            if (item.participants.isNotEmpty()) Meta(item.participants.joinToString { it.data.name })
        }
    }
}

/** A task with a date: its checkbox (ticks it off), title, «Задача · Дмитрий» and «Просрочено». */
@Composable
fun CalendarTaskRow(
    item: CalendarItem.Task,
    row: TaskRowUi,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val view = item.view
    val overdue = item.overdue && !row.done
    RowFrame(onClick, Modifier.testTag("cal_task_row_${row.key}"), startPadding = 6.dp) {
        // The 48 dp checkbox sits where the time column is; titles line up with event titles.
        Box(Modifier.width(TIME_COLUMN + 3.dp + Spacing.m + 10.dp)) {
            TaskCheckbox(
                checked = row.done,
                onToggle = onToggle,
                label = stringResource(if (row.done) Res.string.task_unmark else Res.string.task_mark, view.data.title),
                overdue = overdue,
                enabled = row.canToggle,
                modifier = Modifier.testTag("cal_task_check_${row.key}"),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Title(view.data.title, color = if (row.done) colors.onSurfaceVariant else colors.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Meta(CalendarLogic.taskMeta(view).text(), Modifier.weight(1f, fill = false))
                if (overdue) Meta(stringResource(Res.string.cal_overdue), color = colors.error)
            }
        }
    }
}

/** A calendar date tile (visual-style § Component patterns): 48 × 52, radius 14, `secondaryContainer`. */
@Composable
fun DateTile(
    date: LocalDate,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.size(width = 48.dp, height = 52.dp).background(colors.secondaryContainer, RoundedCornerShape(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.day.toString(),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = colors.onSecondaryContainer,
        )
        Text(
            stringArrayResource(Res.array.months_short).getOrElse(date.month.ordinal) { "" },
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSecondaryContainer,
        )
    }
}

/** Participant avatars: up to 3 overlapping (24 dp) and "+N"; none for the whole family. */
@Composable
fun PersonAvatars(
    persons: List<Person>,
    allNames: List<String> = persons.map { it.data.name },
    size: Dp = 24.dp,
    max: Int = 3,
) {
    if (persons.isEmpty()) return
    val shown = persons.take(max)
    val extra = persons.size - shown.size
    val description = persons.joinToString { it.data.name }
    Row(Modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, person ->
            Avatar(
                initialsFor(person.data.name, allNames),
                person.color,
                size = size,
                modifier =
                    Modifier
                        .offset(x = (-6 * index).dp)
                        .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
                        .padding(1.dp),
            )
        }
        if (extra > 0) {
            Text(
                "+$extra",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = (-6 * shown.size + 4).dp),
            )
        }
    }
}

@Composable
private fun RowFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = Spacing.l,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = startPadding, end = Spacing.l, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        content = content,
    )
}

/** The icon of a birthday or memorable row in the time column, so titles line up with events. */
@Composable
private fun LeadingIcon(icon: @Composable () -> Unit) {
    Row(Modifier.width(TIME_COLUMN + 3.dp + Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Title(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Meta(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun DayOfWeek.isWeekend() = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

private const val OTHER_MONTH_ALPHA = 0.5f
