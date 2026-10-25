package app.lares.client.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.CalendarViews
import app.lares.client.data.calendar.TimedBlock
import app.lares.client.data.calendar.WeekDay
import app.lares.client.resources.Res
import app.lares.client.resources.cal_week_more
import app.lares.client.resources.ic_cake
import app.lares.client.resources.ic_celebration
import app.lares.client.resources.weekdays_short
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.PaletteTones
import app.lares.client.ui.theme.tabular
import app.lares.client.ui.theme.tones
import app.lares.contract.entities.EventKind
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

// The week view (plan M3-07, CAL-008, CAL-013, Q-024): a pager of weeks, each with the day header, the all-day strip
// and a 24-hour grid. Lines and hour labels are drawn; only blocks and chips are composables.

private val GUTTER = 40.dp
private val HOUR = 48.dp
private val BLOCK_GAP = 1.dp
private val BLOCK_MIN = 24.dp
private val BLOCK_TIME_MIN = 40.dp
private val BAR = 3.dp
private val TODAY_CIRCLE = 28.dp
private val CHIP_HEIGHT = 20.dp
private val CHIP_GAP = 2.dp
private val MORE_HEIGHT = 16.dp
private val NOW_LINE = 2.dp
private val GRID_BOTTOM = 96.dp
private val LABEL_LEAD = 8.dp
private val LABEL_PADDING = 4.dp
private val BLOCK_SHAPE = RoundedCornerShape(6.dp)
private const val TEXT_LINE_DP = 14
private const val PAGER_BASE = 6000
private const val PAGER_PAGES = 12_000
private const val MINUTES_PER_HOUR = 60f

/** Weeks in a pager (page [PAGER_BASE] = the week of today); the shown week is `state.nav.weekStart`. */
@Composable
internal fun WeekPager(
    state: CalendarUiState,
    actions: CalendarActions,
) {
    val base = remember { CalendarViews.mondayOnOrBefore(state.today) }
    val pager = rememberPagerState(initialPage = PAGER_BASE + weeksBetween(base, state.nav.weekStart)) { PAGER_PAGES }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            actions.onWeek(base.plus((page - PAGER_BASE) * WeekGridLogic.DAYS, DateTimeUnit.DAY))
        }
    }
    LaunchedEffect(state.nav.weekStart) {
        val target = PAGER_BASE + weeksBetween(base, state.nav.weekStart)
        if (pager.currentPage != target) pager.animateScrollToPage(target)
    }
    HorizontalPager(pager, Modifier.fillMaxSize().testTag("week_pager")) { page ->
        val monday = base.plus((page - PAGER_BASE) * WeekGridLogic.DAYS, DateTimeUnit.DAY)
        WeekPage(monday, state.weeks[monday], state, actions)
    }
}

@Composable
private fun WeekPage(
    monday: LocalDate,
    days: List<WeekDay>?,
    state: CalendarUiState,
    actions: CalendarActions,
) {
    val dates = remember(monday) { (0 until WeekGridLogic.DAYS).map { monday.plus(it, DateTimeUnit.DAY) } }
    val openDay = { date: LocalDate ->
        actions.onSelect(date)
        actions.onMode(CalendarMode.MONTH)
    }
    Column(Modifier.fillMaxSize()) {
        WeekHeader(dates, state.today, openDay)
        val strip = remember(days) { days?.let { WeekGridLogic.strip(it) } }
        if (strip != null && !strip.isEmpty) {
            AllDayStrip(strip, dates, openDay, actions.rows)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = GUTTER)
                .background(LaresTheme.colors.divider)
                .size(0.dp, 1.dp),
        )
        HoursGrid(
            dates = dates,
            days = days,
            today = state.today,
            nowMinute = state.nowMinute,
            onBlock = { block -> actions.rows.onEvent(block.item.occ.eventId, block.item.occ.occurrence) },
            onEmpty = if (state.canCreate) ({ date, hour -> actions.onCreate(EventEditorArgs(date = date, hour = hour)) }) else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Weekday short names over the day numbers (today in a [TODAY_CIRCLE] `primary` circle); a tap opens the day in «Месяц». */
@Composable
internal fun WeekHeader(
    dates: List<LocalDate>,
    today: LocalDate,
    onDay: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val weekdays = stringArrayResource(Res.array.weekdays_short)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Spacer(Modifier.width(GUTTER))
        dates.forEach { date ->
            val isToday = date == today
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { onDay(date) }
                    .padding(vertical = 2.dp)
                    .testTag("week_day_$date"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    weekdays.getOrElse(date.dayOfWeek.ordinal) { "" }.replaceFirstChar { it.uppercaseChar() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .size(TODAY_CIRCLE)
                        .then(if (isToday) Modifier.background(colors.primary, CircleShape).testTag("week_today") else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.day.toString(),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isToday) colors.onPrimary else colors.onSurface,
                    )
                }
            }
        }
    }
}

/** All-day events, birthdays and memorable dates as chips over the columns they cover; «+N» under a full column. */
@Composable
internal fun AllDayStrip(
    strip: WeekStrip,
    dates: List<LocalDate>,
    onDay: (LocalDate) -> Unit,
    actions: CalendarRowActions,
) {
    val moreColumns = strip.more.withIndex().filter { it.value > 0 }
    Layout(
        content = {
            strip.chips.forEach { chip ->
                key(chip.key) {
                    StripChipView(chip) {
                        when (val item = chip.item) {
                            is CalendarItem.Event -> actions.onEvent(item.occ.eventId, item.occ.occurrence)
                            is CalendarItem.Birthday -> actions.onPerson(item.person.id)
                            is CalendarItem.Task -> actions.onTask(item.view.id)
                        }
                    }
                }
            }
            moreColumns.forEach { (column, count) ->
                Text(
                    stringResource(Res.string.cal_week_more, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable { onDay(dates[column]) }
                            .testTag("week_more_${dates[column]}"),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("week_strip"),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gutter = GUTTER.roundToPx()
        val column = (width - gutter) / WeekGridLogic.DAYS.toFloat()
        val chipHeight = CHIP_HEIGHT.roundToPx()
        val gap = CHIP_GAP.roundToPx()
        val inset = 1.dp.roundToPx()
        val rowsHeight = if (strip.rows == 0) 0 else strip.rows * chipHeight + (strip.rows - 1) * gap
        val moreTop = if (strip.rows == 0) 0 else rowsHeight + gap
        val height = if (moreColumns.isEmpty()) rowsHeight else moreTop + MORE_HEIGHT.roundToPx()
        val placed =
            measurables.mapIndexed { index, measurable ->
                if (index < strip.chips.size) {
                    val chip = strip.chips[index]
                    val x = gutter + (column * chip.first).toInt() + inset
                    val w = ((column * (chip.last - chip.first + 1)).toInt() - 2 * inset).coerceAtLeast(0)
                    Triple(measurable.measure(Constraints.fixed(w, chipHeight)), x, chip.row * (chipHeight + gap))
                } else {
                    val (col, _) = moreColumns[index - strip.chips.size]
                    val placeable = measurable.measure(Constraints(maxWidth = column.toInt(), maxHeight = MORE_HEIGHT.roundToPx()))
                    Triple(placeable, gutter + (column * col).toInt() + (column.toInt() - placeable.width) / 2, moreTop)
                }
            }
        layout(width, height) { placed.forEach { (placeable, x, y) -> placeable.place(x, y) } }
    }
}

@Composable
private fun StripChipView(
    chip: StripChip,
    onClick: () -> Unit,
) {
    val item = chip.item
    val tones = chipTones(item)
    val (icon, label) =
        when (item) {
            is CalendarItem.Birthday -> {
                Res.drawable.ic_cake to CalendarLogic.birthdayTitle(item.person.data.name, item.age).text()
            }

            is CalendarItem.Event -> {
                if (item.kind == EventKind.MEMORABLE_DATE) {
                    Res.drawable.ic_celebration to CalendarLogic.memorableTitle(item.occ.title, item.years).text()
                } else {
                    null to item.occ.title
                }
            }

            is CalendarItem.Task -> {
                null to item.view.data.title
            }
        }
    Row(
        Modifier
            .clip(BLOCK_SHAPE)
            .background(tones?.container ?: MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
            .testTag("week_chip_${chip.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (icon != null) AppIcon(icon, null, size = 12.dp, tint = tones?.base ?: MaterialTheme.colorScheme.primary)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tones?.onContainer ?: MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 24 hours of [HOUR] each with drawn hour lines and labels, the days' blocks placed by [WeekGridLogic.blockBox] and a
 * "now" line on today's column. Opens at [WeekGridLogic.initialScrollMinute] once the days are known; a tap on an empty
 * spot calls [onEmpty] with the day and hour (null: the member cannot create events).
 */
@Composable
internal fun HoursGrid(
    dates: List<LocalDate>,
    days: List<WeekDay>?,
    today: LocalDate,
    nowMinute: Int,
    onBlock: (TimedBlock) -> Unit,
    onEmpty: ((LocalDate, Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Saved with the page, so coming back from an event page keeps the scroll position.
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(days != null) {
        if (days != null && !scrolled) {
            val minute = WeekGridLogic.initialScrollMinute(days)
            // A little above the hour, so its label is not cut by the header.
            scroll.scrollTo(with(density) { (HOUR * (minute / MINUTES_PER_HOUR) - LABEL_LEAD).roundToPx().coerceAtLeast(0) })
            scrolled = true
        }
    }
    val blocks =
        remember(days) {
            days.orEmpty().flatMapIndexed { index, day -> day.blocks.map { index to it } }
        }
    val colors = MaterialTheme.colorScheme
    val lineColor = LaresTheme.colors.divider
    val labelColor = colors.onSurfaceVariant
    val nowColor = colors.primary
    val labelStyle =
        MaterialTheme.typography.labelSmall
            .tabular()
            .copy(letterSpacing = 0.sp)
    val measurer = rememberTextMeasurer()
    val labels =
        remember(measurer, labelStyle, labelColor, density) {
            // A large font scale shrinks the labels to the gutter rather than cutting them.
            val style = labelStyle.copy(color = labelColor)
            val room = with(density) { (GUTTER - LABEL_PADDING * 2).toPx() }
            val widest = measurer.measure(WeekGridLogic.minutes(WeekGridLogic.HOURS - 1), style).size.width
            val fitted = if (widest > room) style.copy(fontSize = style.fontSize * (room / widest)) else style
            (1 until WeekGridLogic.HOURS).map { measurer.measure(WeekGridLogic.minutes(it), fitted) }
        }
    val todayColumn = dates.indexOf(today)
    val currentOnEmpty by rememberUpdatedState(onEmpty)
    Box(modifier.fillMaxWidth().verticalScroll(scroll).testTag("week_scroll")) {
        Layout(
            content = {
                blocks.forEach { (index, block) ->
                    key(block.item.occ.eventId, block.item.occ.occurrence, index) {
                        EventBlock(block, dates[index]) { onBlock(block) }
                    }
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val gutter = GUTTER.toPx()
                        val hour = HOUR.toPx()
                        val column = (size.width - gutter) / WeekGridLogic.DAYS
                        val stroke = 1.dp.toPx()
                        for (h in 0..WeekGridLogic.HOURS) {
                            val y = h * hour
                            drawLine(lineColor, Offset(gutter, y), Offset(size.width, y), stroke)
                        }
                        for (d in 0..WeekGridLogic.DAYS) {
                            val x = gutter + d * column
                            drawLine(lineColor, Offset(x, 0f), Offset(x, WeekGridLogic.HOURS * hour), stroke)
                        }
                        labels.forEachIndexed { i, label ->
                            val y = (i + 1) * hour - label.size.height / 2f
                            drawText(label, topLeft = Offset(gutter - LABEL_PADDING.toPx() - label.size.width, y))
                        }
                    }.drawWithContent {
                        drawContent()
                        if (todayColumn >= 0) {
                            val gutter = GUTTER.toPx()
                            val column = (size.width - gutter) / WeekGridLogic.DAYS
                            val y = nowMinute / MINUTES_PER_HOUR * HOUR.toPx()
                            val x = gutter + todayColumn * column
                            drawLine(nowColor, Offset(x, y), Offset(x + column, y), NOW_LINE.toPx())
                            drawCircle(nowColor, NOW_LINE.toPx() * 2, Offset(x, y))
                        }
                    }.pointerInput(dates) {
                        detectTapGestures { offset ->
                            val create = currentOnEmpty ?: return@detectTapGestures
                            val gutter = GUTTER.toPx()
                            if (offset.x < gutter) return@detectTapGestures
                            val column = ((offset.x - gutter) / ((size.width - gutter) / WeekGridLogic.DAYS)).toInt()
                            val hour = (offset.y / HOUR.toPx()).toInt()
                            if (column in dates.indices && hour in 0 until WeekGridLogic.HOURS) create(dates[column], hour)
                        }
                    }.testTag("week_grid"),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val gutter = GUTTER.toPx()
            val column = (width - gutter) / WeekGridLogic.DAYS
            val hour = HOUR.toPx()
            val placed =
                measurables.mapIndexed { i, measurable ->
                    val (index, block) = blocks[i]
                    val box = WeekGridLogic.blockBox(block, column, hour, BLOCK_GAP.toPx(), BLOCK_MIN.toPx())
                    val placeable = measurable.measure(Constraints.fixed(box.width.toInt(), box.height.toInt()))
                    Triple(placeable, (gutter + index * column + box.x).toInt(), box.y.toInt())
                }
            val height = (WeekGridLogic.HOURS * hour).toInt() + GRID_BOTTOM.roundToPx()
            layout(width, height) { placed.forEach { (placeable, x, y) -> placeable.place(x, y) } }
        }
    }
}

/**
 * A timed event: the first participant's container tone (whole family: `primaryContainer`) with a [BAR] in the base
 * tone; the title, and the start time on a second line when the block is at least [BLOCK_TIME_MIN] high.
 */
@Composable
internal fun EventBlock(
    block: TimedBlock,
    date: LocalDate,
    onClick: () -> Unit,
) {
    val tones = chipTones(block.item)
    val content = tones?.onContainer ?: MaterialTheme.colorScheme.onPrimaryContainer
    val description = WeekGridLogic.blockDescription(block, date).text()
    val height = WeekGridLogic.blockHeight(block, HOUR.value, BLOCK_GAP.value, BLOCK_MIN.value)
    val showTime = height >= BLOCK_TIME_MIN.value
    val titleLines = (((height - 4) / TEXT_LINE_DP).toInt() - if (showTime) 1 else 0).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxSize()
            .testTag("week_block_${block.item.occ.eventId}_$date")
            .clip(BLOCK_SHAPE)
            .background(tones?.container ?: MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(Modifier.width(BAR).fillMaxHeight().background(tones?.base ?: MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(horizontal = 3.dp, vertical = 2.dp).clearAndSetSemantics {}) {
            Text(
                block.item.occ.title,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = content,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (showTime) {
                Text(
                    WeekGridLogic.blockStart(block),
                    style =
                        MaterialTheme.typography.labelSmall
                            .tabular()
                            .copy(fontWeight = FontWeight.Medium),
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The first participant's tones; null for the whole family (drawn in `primary` roles). */
@Composable
private fun chipTones(item: CalendarItem): PaletteTones? =
    when (item) {
        is CalendarItem.Event -> {
            item.participants
                .firstOrNull()
                ?.color
                ?.tones()
        }

        is CalendarItem.Birthday -> {
            item.person.color.tones()
        }

        is CalendarItem.Task -> {
            null
        }
    }

private fun weeksBetween(
    from: LocalDate,
    to: LocalDate,
): Int = from.daysUntil(to).floorDiv(WeekGridLogic.DAYS)
