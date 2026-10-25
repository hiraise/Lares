package app.lares.client.ui.calendar

import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.TimedBlock
import app.lares.client.data.calendar.WeekDay
import app.lares.client.resources.Res
import app.lares.client.resources.cal_block_description
import app.lares.client.resources.cal_range
import app.lares.client.resources.cal_week_same_month
import app.lares.client.resources.cal_week_same_month_year
import app.lares.client.resources.cal_whole_family
import app.lares.client.resources.date_day_month_short
import app.lares.client.resources.date_day_month_short_year
import app.lares.client.resources.months
import app.lares.client.resources.months_short
import app.lares.client.resources.weekdays_lower
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.client.ui.tasks.TasksLogic
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Where a block sits inside its day column; units are those of the arguments of [WeekGridLogic.blockBox]. */
data class BlockBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** An all-day chip of the week strip: columns [first]..[last] (0 = Monday) on [row]. */
data class StripChip(
    val key: String,
    val item: CalendarItem,
    val first: Int,
    val last: Int,
    val row: Int,
)

/** The all-day strip: chips on rows below [WeekGridLogic.STRIP_ROWS]; [more] counts hidden chips per column. */
data class WeekStrip(
    val chips: List<StripChip>,
    val more: List<Int>,
) {
    val rows: Int get() = (chips.maxOfOrNull { it.row } ?: -1) + 1

    val hasMore: Boolean get() = more.any { it > 0 }

    val isEmpty: Boolean get() = chips.isEmpty() && !hasMore
}

/** Pure layout and texts of the week grid (plan M3-07, CAL-008, CAL-013). */
object WeekGridLogic {
    const val DAYS = 7
    const val HOURS = 24
    const val STRIP_ROWS = 3
    const val DEFAULT_SCROLL_MINUTE = 7 * 60
    const val SCROLL_LEAD_MINUTES = 30
    private const val MINUTES_PER_HOUR = 60
    private const val MIDNIGHT = "0:00"
    private const val END_OF_DAY = "24:00"

    /**
     * The block's box in a day column [columnWidth] wide with [hourHeight] per hour: x and width from `column/columns`
     * with [gap] between neighbours, y and height from the minutes, at least [minHeight] high and kept inside the day.
     */
    fun blockBox(
        block: TimedBlock,
        columnWidth: Float,
        hourHeight: Float,
        gap: Float,
        minHeight: Float,
    ): BlockBox {
        val slot = columnWidth / block.columns
        val height = blockHeight(block, hourHeight, gap, minHeight)
        val y = minOf(block.startMinute * hourHeight / MINUTES_PER_HOUR, HOURS * hourHeight - height).coerceAtLeast(0f)
        return BlockBox(slot * block.column, y, (slot - gap).coerceAtLeast(0f), height)
    }

    /** The height alone (it does not depend on the column width): duration less [gap], at least [minHeight]. */
    fun blockHeight(
        block: TimedBlock,
        hourHeight: Float,
        gap: Float,
        minHeight: Float,
    ): Float = maxOf((block.endMinute - block.startMinute) * hourHeight / MINUTES_PER_HOUR - gap, minHeight)

    /**
     * Chips of the all-day strip: an item shown on several days of the week (a multi-day event) is one chip across
     * those columns. Chips are placed by first column, longer first, each on the lowest row free in all its columns;
     * chips on rows from [maxRows] on are counted in [WeekStrip.more] of every column they cover.
     */
    fun strip(
        days: List<WeekDay>,
        maxRows: Int = STRIP_ROWS,
    ): WeekStrip {
        val spans = LinkedHashMap<String, Triple<CalendarItem, Int, Int>>()
        days.forEachIndexed { column, day ->
            day.allDay.forEach { item ->
                val key = key(item)
                val span = spans[key]
                spans[key] = if (span == null) Triple(item, column, column) else span.copy(third = column)
            }
        }
        val order = spans.keys.withIndex().associate { it.value to it.index }
        val sorted =
            spans.entries.sortedWith(
                compareBy({ it.value.second }, { -(it.value.third - it.value.second) }, { order.getValue(it.key) }),
            )
        val taken = mutableListOf<BooleanArray>()
        val chips = mutableListOf<StripChip>()
        val more = IntArray(days.size)
        for ((key, span) in sorted) {
            val (item, first, last) = span
            var row = taken.indexOfFirst { used -> (first..last).none { used[it] } }
            if (row < 0) {
                row = taken.size
                taken += BooleanArray(days.size)
            }
            (first..last).forEach { taken[row][it] = true }
            if (row < maxRows) {
                chips += StripChip(key, item, first, last, row)
            } else {
                (first..last).forEach { more[it]++ }
            }
        }
        return WeekStrip(chips, more.toList())
    }

    /** The minute the grid opens at: 07:00, or 30 minutes before the week's earliest block when that is earlier. */
    fun initialScrollMinute(days: List<WeekDay>): Int {
        val earliest = days.flatMap { it.blocks }.minOfOrNull { it.startMinute } ?: return DEFAULT_SCROLL_MINUTE
        return minOf(DEFAULT_SCROLL_MINUTE, (earliest - SCROLL_LEAD_MINUTES).coerceAtLeast(0))
    }

    /**
     * The header title: "October 19–25" / «19–25 октября», across months «28 сент. – 4 окт.», across years
     * «29 дек. 2025 – 4 янв. 2026»; a week of another year than [today]'s carries its year.
     */
    fun weekTitle(
        monday: LocalDate,
        today: LocalDate,
    ): UiText {
        val sunday = monday.plus(DAYS - 1, DateTimeUnit.DAY)
        val month = UiText.Item(Res.array.months, monday.month.ordinal)
        val firstDay = raw(monday.day.toString())
        val lastDay = raw(sunday.day.toString())
        return when {
            monday.year != sunday.year -> {
                uiText(Res.string.cal_range, shortWithYear(monday), shortWithYear(sunday))
            }

            monday.month == sunday.month && monday.year == today.year -> {
                uiText(Res.string.cal_week_same_month, firstDay, lastDay, month)
            }

            monday.month == sunday.month -> {
                uiText(
                    Res.string.cal_week_same_month_year,
                    firstDay,
                    lastDay,
                    month,
                    raw(monday.year.toString()),
                )
            }

            monday.year == today.year -> {
                uiText(Res.string.cal_range, short(monday), short(sunday))
            }

            else -> {
                uiText(Res.string.cal_range, short(monday), shortWithYear(sunday))
            }
        }
    }

    /**
     * What a screen reader says for a block: «Танцы, понедельник 17:30–18:45, Аня». The times are those of the
     * occurrence on [date]: a day inside an overnight event runs from 0:00 or to 24:00.
     */
    fun blockDescription(
        block: TimedBlock,
        date: LocalDate,
    ): UiText {
        val occ = block.item.occ
        val start = if (date == occ.startDate) TasksLogic.time(occ.startTime!!) else MIDNIGHT
        val end = if (date == occ.endDate) TasksLogic.time(occ.endTime!!) else END_OF_DAY
        val participants = block.item.participants
        val who = if (participants.isEmpty()) uiText(Res.string.cal_whole_family) else raw(participants.joinToString { it.data.name })
        return uiText(
            Res.string.cal_block_description,
            raw(occ.title),
            UiText.Item(Res.array.weekdays_lower, date.dayOfWeek.ordinal),
            raw(start),
            raw(end),
            who,
        )
    }

    /** "07:00" for an hour label. */
    fun minutes(
        hour: Int,
        minute: Int = 0,
    ): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    /** A stable key of an all-day item: the occurrence of an event, the person and date of a birthday. */
    fun key(item: CalendarItem): String =
        when (item) {
            is CalendarItem.Event -> "e-${item.occ.eventId}-${item.occ.occurrence}"
            is CalendarItem.Birthday -> "b-${item.person.id}-${item.date}"
            is CalendarItem.Task -> "t-${item.view.id}"
        }

    private fun short(date: LocalDate): UiText =
        uiText(Res.string.date_day_month_short, raw(date.day.toString()), UiText.Item(Res.array.months_short, date.month.ordinal))

    private fun shortWithYear(date: LocalDate): UiText =
        uiText(
            Res.string.date_day_month_short_year,
            raw(date.day.toString()),
            UiText.Item(Res.array.months_short, date.month.ordinal),
            raw(date.year.toString()),
        )

    /** The start of a block as day rows show times: "7:30". */
    fun blockStart(block: TimedBlock): String =
        TasksLogic.time(
            LocalTime(
                block.startMinute / MINUTES_PER_HOUR,
                block.startMinute % MINUTES_PER_HOUR,
            ),
        )
}
