package app.lares.client.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.Person
import app.lares.client.data.Tag
import app.lares.client.data.calendar.CalendarFilter
import app.lares.client.data.calendar.CalendarInputs
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.CalendarRepository
import app.lares.client.data.calendar.CalendarViews
import app.lares.client.data.calendar.MonthCell
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.platform.AppPreferences
import app.lares.client.platform.PreferencesStore
import app.lares.client.ui.tasks.TaskEvent
import app.lares.client.ui.tasks.TaskRowUi
import app.lares.client.ui.tasks.TaskRows
import app.lares.client.ui.tasks.TaskTicker
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** The three views of the tab (CAL-008), stored in [AppPreferences.calendarView] by [key]. */
enum class CalendarMode(
    val key: String,
) {
    MONTH(AppPreferences.CALENDAR_VIEW_MONTH),
    WEEK(AppPreferences.CALENDAR_VIEW_WEEK),
    LIST(AppPreferences.CALENDAR_VIEW_LIST),
    ;

    companion object {
        fun of(key: String): CalendarMode = entries.firstOrNull { it.key == key } ?: MONTH
    }
}

/** One row of a day: the item and, for a task, its row state for the checkbox. */
data class CalendarRow(
    val item: CalendarItem,
    val task: TaskRowUi? = null,
) {
    val key: String =
        when (item) {
            is CalendarItem.Event -> "event-${item.occ.eventId}-${item.occ.occurrence}"
            is CalendarItem.Birthday -> "birthday-${item.person.id}"
            is CalendarItem.Task -> "task-${task?.key ?: item.view.id}"
        }
}

data class DayUi(
    val date: LocalDate,
    val rows: List<CalendarRow>,
)

/** Where the tab is: the shown month (its 1st), the selected day, the shown week (its Monday) and the list range. */
data class CalendarNav(
    val month: LocalDate,
    val selected: LocalDate,
    val weekStart: LocalDate,
    val listFrom: LocalDate,
    val listTo: LocalDate,
) {
    companion object {
        fun at(today: LocalDate) =
            CalendarNav(
                month = firstOfMonth(today),
                selected = today,
                weekStart = CalendarViews.mondayOnOrBefore(today),
                listFrom = today,
                listTo = today.plus(CalendarViewModel.LIST_STEP_DAYS, DateTimeUnit.DAY),
            )
    }
}

data class CalendarUiState(
    val loaded: Boolean = false,
    val mode: CalendarMode = CalendarMode.MONTH,
    val today: LocalDate = LocalDate(1970, 1, 1),
    val nav: CalendarNav = CalendarNav.at(LocalDate(1970, 1, 1)),
    val filter: CalendarFilter = CalendarFilter(),
    /** Grids of the shown month and its neighbours, keyed by the first day of the month. */
    val grids: Map<LocalDate, List<MonthCell>> = emptyMap(),
    /** The selected day's rows (month view). */
    val day: List<CalendarRow> = emptyList(),
    /** Non-empty days of the list range («Список»). */
    val listDays: List<DayUi> = emptyList(),
    val canCreate: Boolean = false,
    /** The member sees tasks, so «Показывать задачи» applies. */
    val canFilterTasks: Boolean = false,
    val persons: List<Person> = emptyList(),
    /** Tags on events or tasks, and the chosen one. */
    val tags: List<Tag> = emptyList(),
) {
    val filterPerson: Person? get() = persons.firstOrNull { it.id == filter.personId }

    val filterTag: Tag? get() = tags.firstOrNull { it.id == filter.tagId }

    val hidesTasks: Boolean get() = canFilterTasks && !filter.showTasks

    val filtered: Boolean get() = filter.personId != null || filter.tagId != null || hidesTasks

    /** The month view shows the current month with today selected; the week view the current week. */
    val atToday: Boolean
        get() =
            when (mode) {
                CalendarMode.MONTH -> nav.month == firstOfMonth(today) && nav.selected == today
                CalendarMode.WEEK -> nav.weekStart == CalendarViews.mondayOnOrBefore(today)
                CalendarMode.LIST -> nav.listFrom >= today
            }

    val personNames: List<String> get() = persons.map { it.data.name }
}

/**
 * The Calendar tab (plan M3-05, CAL-008, CAL-009, TAG-003): month grid and the selected day, «Список», filters, ticking
 * tasks off. The view is remembered in the preferences; the filter lives here.
 */
class CalendarViewModel(
    repository: CalendarRepository,
    tasks: TasksRepository,
    tasksWriter: TasksWriter,
    private val preferences: PreferencesStore,
    clock: Clock,
) : ViewModel() {
    private val ticker = TaskTicker(viewModelScope, tasksWriter)
    private val filter = MutableStateFlow(CalendarFilter())

    /** Null until the household's today is known. */
    private val nav = MutableStateFlow<CalendarNav?>(null)

    val events: Flow<TaskEvent> = ticker.events

    val state: StateFlow<CalendarUiState> =
        combine(
            repository.observeInputs(),
            tasks.observeInputs(),
            preferences.preferences.map { CalendarMode.of(it.calendarView) }.distinctUntilChanged(),
            nav,
            filter,
        ) { inputs, taskInputs, mode, nav, filter ->
            val today = inputs.now.toLocalDateTime(inputs.zone).date
            val current = nav ?: CalendarNav.at(today).also { this.nav.compareAndSet(null, it) }
            build(inputs, taskInputs, mode, current, filter)
        }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                clock
                    .now()
                    .toLocalDateTime(
                        TimeZone.currentSystemDefault(),
                    ).date
                    .let { CalendarUiState(today = it, nav = CalendarNav.at(it)) },
            )

    fun setMode(mode: CalendarMode) {
        viewModelScope.launch { preferences.setCalendarView(mode.key) }
    }

    /** Pages to the month starting on [first]; the selected day keeps its day of month when it exists, else the 1st. */
    fun showMonth(first: LocalDate) {
        // The pager reports its page as soon as it is composed; before the data is there it knows no real month.
        if (nav.value == null) return
        updateNav { nav ->
            val month = firstOfMonth(first)
            if (month == nav.month) return@updateNav nav
            val day = nav.selected.day
            val selected = if (day <= daysIn(month)) LocalDate(month.year, month.month, day) else month
            nav.copy(month = month, selected = selected)
        }
    }

    /** A tap on a day; a day of another month also pages there. */
    fun select(date: LocalDate) = updateNav { it.copy(month = firstOfMonth(date), selected = date) }

    /** ‹: the previous month or week. */
    fun previous() = step(-1)

    /** ›: the next month or week. */
    fun next() = step(1)

    fun goToToday() =
        updateNav { nav ->
            val today = state.value.today
            CalendarNav.at(today).copy(listFrom = minOf(nav.listFrom, today), listTo = nav.listTo)
        }

    /** The end of «Список» is near: show [LIST_STEP_DAYS] more days, up to [LIST_MAX_DAYS] ahead. */
    fun extendList() =
        updateNav { nav ->
            val limit = state.value.today.plus(LIST_MAX_DAYS, DateTimeUnit.DAY)
            if (nav.listTo >= limit) nav else nav.copy(listTo = minOf(nav.listTo.plus(LIST_STEP_DAYS, DateTimeUnit.DAY), limit))
        }

    /** «Показать прошлые»: [PAST_STEP_DAYS] more days before the list, up to [LIST_MAX_DAYS] back. */
    fun showPast() =
        updateNav { nav ->
            val limit = state.value.today.minus(LIST_MAX_DAYS, DateTimeUnit.DAY)
            nav.copy(listFrom = maxOf(nav.listFrom.minus(PAST_STEP_DAYS, DateTimeUnit.DAY), limit))
        }

    fun setPerson(personId: String?) = filter.update { it.copy(personId = personId) }

    fun setTag(tagId: String?) = filter.update { it.copy(tagId = tagId) }

    fun setShowTasks(show: Boolean) = filter.update { it.copy(showTasks = show) }

    fun resetFilter() {
        filter.value = CalendarFilter()
    }

    fun toggle(row: TaskRowUi) = ticker.toggle(row)

    fun undo(completionId: String) = ticker.undo(completionId)

    private fun step(direction: Int) =
        updateNav { nav ->
            when (state.value.mode) {
                CalendarMode.WEEK -> {
                    nav.copy(weekStart = nav.weekStart.plus(direction * DAYS_PER_WEEK, DateTimeUnit.DAY))
                }

                else -> {
                    val month = nav.month.plus(direction, DateTimeUnit.MONTH)
                    val day = nav.selected.day
                    nav.copy(month = month, selected = if (day <= daysIn(month)) LocalDate(month.year, month.month, day) else month)
                }
            }
        }

    private fun updateNav(change: (CalendarNav) -> CalendarNav) {
        nav.update { current -> change(current ?: CalendarNav.at(state.value.today)) }
    }

    companion object {
        const val LIST_STEP_DAYS = 90
        const val PAST_STEP_DAYS = 30
        const val LIST_MAX_DAYS = 730
        private const val DAYS_PER_WEEK = 7

        fun build(
            inputs: CalendarInputs,
            taskInputs: TaskInputs?,
            mode: CalendarMode,
            nav: CalendarNav,
            filter: CalendarFilter,
        ): CalendarUiState {
            val viewer = inputs.viewer
            val today = inputs.now.toLocalDateTime(inputs.zone).date
            val canFilterTasks = viewer?.permissions?.has(Permission.TASKS_VIEW) == true
            val effective = if (canFilterTasks) filter else filter.copy(showTasks = false)
            val taskRows = taskInputs?.let(::TaskRows)
            val rowsOf = { items: List<CalendarItem> ->
                items.map { item -> CalendarRow(item, (item as? CalendarItem.Task)?.let { taskRows?.row(it.view) }) }
            }
            val base =
                CalendarUiState(
                    loaded = true,
                    mode = mode,
                    today = today,
                    nav = nav,
                    filter = filter,
                    canCreate = viewer?.permissions?.has(Permission.CALENDAR_CREATE) == true,
                    canFilterTasks = canFilterTasks,
                    persons = inputs.persons.sortedBy { it.data.name.lowercase() },
                    tags = tagsInUse(inputs, filter.tagId),
                )
            return when (mode) {
                CalendarMode.MONTH -> {
                    val months = listOf(nav.month.minus(1, DateTimeUnit.MONTH), nav.month, nav.month.plus(1, DateTimeUnit.MONTH))
                    base.copy(
                        grids = months.associateWith { CalendarViews.month(inputs, it.year, it.month.ordinal + 1, effective) },
                        day = rowsOf(CalendarViews.days(inputs, nav.selected, nav.selected, effective).single().items),
                    )
                }

                CalendarMode.LIST -> {
                    val days = CalendarViews.days(inputs, nav.listFrom, nav.listTo, effective)
                    base.copy(listDays = days.filter { it.items.isNotEmpty() }.map { DayUi(it.date, rowsOf(it.items)) })
                }

                CalendarMode.WEEK -> {
                    base
                }
            }
        }

        /** The grid of a month without dots, for pages whose data is not computed yet. */
        fun plainGrid(
            first: LocalDate,
            today: LocalDate,
        ): List<MonthCell> {
            val start = CalendarViews.mondayOnOrBefore(first)
            return (0 until CalendarViews.MONTH_CELLS).map { offset ->
                val date = start.plus(offset, DateTimeUnit.DAY)
                MonthCell(date, inMonth = date.month == first.month, today = date == today, dots = emptyList(), more = false)
            }
        }

        private fun tagsInUse(
            inputs: CalendarInputs,
            selected: String?,
        ): List<Tag> {
            val used = mutableSetOf<String>()
            inputs.events.forEach { (_, event) -> used += event.tagIds }
            inputs.tasks.forEach { view -> view.tags.forEach { used += it.id } }
            selected?.let { used += it }
            return inputs.tags.filter { it.id in used }.sortedBy { it.data.name.lowercase() }
        }
    }
}

internal fun firstOfMonth(date: LocalDate): LocalDate = LocalDate(date.year, date.month, 1)

internal fun daysIn(first: LocalDate): Int = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
