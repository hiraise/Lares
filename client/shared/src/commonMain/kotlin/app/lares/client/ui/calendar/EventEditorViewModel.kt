package app.lares.client.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.Tag
import app.lares.client.data.TagsRepository
import app.lares.client.data.TagsWriter
import app.lares.client.data.calendar.CalendarInputs
import app.lares.client.data.calendar.CalendarRepository
import app.lares.client.data.calendar.CalendarViews.isEmpty
import app.lares.client.data.calendar.CalendarWriter
import app.lares.client.sync.LocalWriteException
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.RepeatPreset
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.client.ui.family.TagEditor
import app.lares.client.ui.family.TagSheet
import app.lares.contract.api.ErrorCode
import app.lares.contract.calendar.EventSchedule
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.Recurrence
import app.lares.contract.permissions.EventRules
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime

/** Where the editor goes after saving. */
sealed interface EventEditorResult {
    /** A new event: its page replaces the editor. */
    data class Created(
        val id: String,
    ) : EventEditorResult

    /** Saved; [splitId] is the new series «Это и следующие» created (its page replaces the old one). */
    data class Saved(
        val splitId: String? = null,
    ) : EventEditorResult

    /** The event or the occurrence is gone (deleted or cancelled meanwhile). */
    data object Gone : EventEditorResult

    /** The member may not change this event: the editor closes with «Нет прав». */
    data object Forbidden : EventEditorResult
}

/** A choice of the participants sheet: [member] persons first; [relation] is the supporting line if set. */
data class ParticipantChoice(
    val id: String,
    val name: String,
    val color: PaletteColor,
    val relation: String?,
    val member: Boolean,
)

data class EventEditorUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val scope: RepeatScope = RepeatScope.ALL,
    /** The scheduled date of the occurrence «Только это» / «Это и следующие» start from. */
    val occurrence: LocalDate? = null,
    val form: EventForm = EventForm(),
    /** The form as opened, for "discard changes?". */
    val initial: EventForm = EventForm(),
    val fieldErrors: Map<String, FieldError> = emptyMap(),
    val error: UiError? = null,
    val saving: Boolean = false,
    val today: LocalDate = EventForm.EPOCH,
    val participants: List<ParticipantChoice> = emptyList(),
    val allNames: List<String> = emptyList(),
    /** All tags, archived ones included (shown only when already on the event). */
    val tags: List<Tag> = emptyList(),
    val canCreate: Boolean = false,
    val canEdit: Boolean = false,
    val canManageTags: Boolean = false,
    /** Changed or cancelled occurrences of the series («Все» warns before resetting them). */
    val changedCount: Int = 0,
    /** «Изменённые отдельные повторения (N) будут сброшены» is shown. */
    val confirmReset: Boolean = false,
    val result: EventEditorResult? = null,
) {
    val allowed: Boolean get() = if (isNew) canCreate else canEdit

    val changed: Boolean get() = form != initial

    val canSave: Boolean get() = allowed && form.title.isNotBlank() && !saving

    /** «Только это»: no repeat, «До», reminder, tags, all-day switch or type. */
    val onlyThis: Boolean get() = scope == RepeatScope.THIS && !isNew

    val tagChoices: List<Tag>
        get() = tags.filter { !it.data.archived || it.id in form.tagIds }.sortedBy { it.data.name.lowercase() }
}

/**
 * The event editor (plan M3-06, CAL-001, CAL-002, CAL-003, CAL-005, CAL-006, CAL-013, CAL-015, TAG-002): one form for new
 * events and memorable dates and for changing one occurrence, this and following, or all, written through
 * [CalendarWriter] (offline, same rights and validation as the server).
 */
class EventEditorViewModel(
    repository: CalendarRepository,
    tagsRepository: TagsRepository,
    tagsWriter: TagsWriter,
    private val writer: CalendarWriter,
    private val args: EventEditorArgs,
) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val tagEditor = TagEditor(viewModelScope, tagsRepository, tagsWriter)
    val tagSheet: StateFlow<TagSheet?> = tagEditor.sheet

    private var inputs: CalendarInputs? = null

    val state: StateFlow<EventEditorUiState> =
        combine(local, repository.observeInputs()) { local, inputs ->
            this.inputs = inputs
            build(local, inputs)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, EventEditorUiState(isNew = args.id == null))

    init {
        viewModelScope.launch {
            val first = repository.observeInputs().first()
            val opened = open(first)
            local.update {
                when (opened) {
                    is Opened.Form -> it.copy(loaded = true, form = opened.form, initial = opened.form, scope = opened.scope)
                    is Opened.Closed -> it.copy(loaded = true, result = opened.result)
                }
            }
        }
    }

    fun onKind(kind: EventKind) = edit { it.withKind(kind) }

    fun onTitle(value: String) = edit("title") { it.withTitle(value) }

    fun onAllDay(on: Boolean) = edit("endDate") { it.withAllDay(on) }

    fun onStartDate(date: LocalDate) = edit("endDate", "repeatUntil") { it.withStartDate(date) }

    fun onStartTime(time: LocalTime) = edit("endDate") { it.withStartTime(time) }

    /** A span over 366 days is refused with «Слишком длинное событие». */
    fun onEndDate(date: LocalDate) {
        val next = local.value.form.withEndDate(date)
        if (next == null) {
            local.update { it.copy(fieldErrors = it.fieldErrors + ("endDate" to EventForm.TOO_LONG)) }
        } else {
            edit("endDate") { next }
        }
    }

    fun onEndTime(time: LocalTime) = edit("endDate") { it.withEndTime(time) }

    fun onPreset(preset: RepeatPreset) = edit("repeatUntil") { it.withPreset(preset) }

    fun onRecurrence(rule: Recurrence?) = edit("repeatUntil") { it.withRecurrence(rule) }

    fun onRepeatUntil(date: LocalDate?) = edit("repeatUntil") { it.withRepeatUntil(date) }

    fun onWholeFamily() = edit { it.withWholeFamily() }

    fun toggleParticipant(personId: String) = edit { it.toggleParticipant(personId) }

    fun onPlace(value: String) = edit { it.withPlace(value) }

    fun onNotes(value: String) = edit { it.withNotes(value) }

    fun toggleTag(tagId: String) = edit { it.toggleTag(tagId) }

    fun onReminder(minutesBefore: Int?) = edit { it.withReminder(minutesBefore) }

    fun onMemorableDate(
        day: Int,
        month: Int,
        year: Int?,
    ) = edit { it.withMemorableDate(day, month, year, state.value.today) }

    fun onCountYears(value: Boolean) = edit { it.withCountYears(value) }

    /** "+ New tag" in the tags sheet: the saved tag is selected. */
    fun newTag() =
        tagEditor.new(
            state.value.tags
                .filter { !it.data.archived }
                .map { it.data.color },
        ) { id -> edit { if (id in it.tagIds) it else it.toggleTag(id) } }

    /**
     * Saves for the scope. «Все» over changed occurrences with a change of the start date, all-day or the rule asks
     * first ([confirmReset]); [confirmed] is the answer.
     */
    fun save(confirmed: Boolean = false) {
        val current = state.value
        val held = local.value
        // The form is read from the local state: the combined state may not show the last edit yet.
        val form = held.form
        if (!current.allowed || form.title.isBlank() || held.saving || held.result != null) return
        val errors = form.validate()
        if (errors.isNotEmpty()) {
            local.update { it.copy(fieldErrors = EventForm.fieldErrors(errors), error = otherError(errors.keys), confirmReset = false) }
            return
        }
        val id = args.id
        val scope = held.scope
        if (!confirmed && id != null && scope == RepeatScope.ALL && current.changedCount > 0 && form.touchesSchedule()) {
            local.update { it.copy(confirmReset = true) }
            return
        }
        local.update { it.copy(saving = true, confirmReset = false) }
        viewModelScope.launch {
            val result = attempt { write(id, scope, form) }
            val failure = result.exceptionOrNull()
            local.update {
                when (failure) {
                    null -> {
                        it.copy(saving = false, result = result.getOrNull())
                    }

                    is LocalWriteException -> {
                        it.copy(
                            saving = false,
                            fieldErrors = EventForm.fieldErrors(failure.fields),
                            // Not about a shown field (rights, other fields): said under the form.
                            error =
                                if (failure.fields.isNotEmpty() && failure.fields.keys.all { f -> f in EventForm.FIELDS }) {
                                    null
                                } else {
                                    UiError.Api(failure.code)
                                },
                        )
                    }

                    else -> {
                        it.copy(saving = false, error = failure.toUiError())
                    }
                }
            }
        }
    }

    fun dismissReset() = local.update { it.copy(confirmReset = false) }

    private suspend fun write(
        id: String?,
        scope: RepeatScope,
        form: EventForm,
    ): EventEditorResult {
        if (id == null) return EventEditorResult.Created(writer.create(form.toData()))
        val series = inputs?.events?.firstOrNull { it.first == id }?.second ?: return EventEditorResult.Gone
        val occurrence = args.occurrence
        return when {
            scope == RepeatScope.THIS && occurrence != null -> {
                val row = inputs?.occurrenceRows?.firstOrNull { it.eventId == id && it.occurrence == occurrence }
                writer.editOccurrence(id, occurrence, form.occurrenceChanges(row))
                EventEditorResult.Saved()
            }

            scope == RepeatScope.FOLLOWING && occurrence != null -> {
                val newId = writer.editFollowing(id, occurrence, form.applyTo(EventForm.shifted(series, occurrence)))
                EventEditorResult.Saved(newId.takeIf { it != id })
            }

            else -> {
                writer.update(id, form.applyTo(series))
                EventEditorResult.Saved()
            }
        }
    }

    /** The form the editor opens with, or why it closes at once. */
    private fun open(inputs: CalendarInputs): Opened {
        val today = inputs.now.toLocalDateTime(inputs.zone).date
        val id = args.id
        if (id == null) {
            val hour = args.hour ?: EventForm.nextHour(inputs.now.toLocalDateTime(inputs.zone).time)
            val form = EventForm.new(args.date ?: today, hour, args.memorable)
            return Opened.Form(
                form.copy(
                    participantIds = setOfNotNull(args.personId?.takeIf { p -> inputs.persons.any { it.id == p } }),
                    tagIds = setOfNotNull(args.tagId?.takeIf { t -> inputs.tags.any { it.id == t } }),
                ),
                RepeatScope.ALL,
            )
        }
        val series = inputs.events.firstOrNull { it.first == id }?.second ?: return Opened.Closed(EventEditorResult.Gone)
        val viewer = inputs.viewer
        if (viewer == null || !EventRules.canEdit(viewer, series)) return Opened.Closed(EventEditorResult.Forbidden)
        val occurrence = args.occurrence
        // A one-off or a memorable date always changes as a whole.
        val scope =
            if (series.recurrence == null || series.kind == EventKind.MEMORABLE_DATE || occurrence == null) RepeatScope.ALL else args.scope
        return when (scope) {
            RepeatScope.THIS -> {
                val rows = inputs.occurrenceRows.filter { it.eventId == id }
                val occ = EventSchedule.occurrence(id, series, rows, occurrence!!) ?: return Opened.Closed(EventEditorResult.Gone)
                Opened.Form(EventForm.ofOccurrence(series, occ, today), scope)
            }

            RepeatScope.FOLLOWING -> {
                if (!EventSchedule.isScheduled(series, occurrence!!)) return Opened.Closed(EventEditorResult.Gone)
                Opened.Form(EventForm.of(EventForm.shifted(series, occurrence), today), scope)
            }

            RepeatScope.ALL -> {
                Opened.Form(EventForm.of(series, today), scope)
            }
        }
    }

    private fun otherError(fields: Set<String>): UiError? =
        if (fields.any { it !in EventForm.FIELDS }) UiError.Api(ErrorCode.VALIDATION_FAILED) else null

    private fun edit(
        vararg fields: String,
        change: (EventForm) -> EventForm,
    ) = local.update {
        it.copy(form = change(it.form), fieldErrors = it.fieldErrors - fields.toSet(), error = null)
    }

    private fun build(
        local: Local,
        inputs: CalendarInputs,
    ): EventEditorUiState {
        val viewer = inputs.viewer
        val id = args.id
        val series = id?.let { inputs.events.firstOrNull { e -> e.first == it }?.second }
        val memberPersons = inputs.members.map { it.data.personId }.toSet()
        val participants =
            inputs.persons
                .map { ParticipantChoice(it.id, it.data.name, it.color, it.data.relation, it.id in memberPersons) }
                .sortedWith(compareBy<ParticipantChoice> { !it.member }.thenBy { it.name.lowercase() })
        return EventEditorUiState(
            loaded = local.loaded,
            isNew = id == null,
            scope = local.scope,
            occurrence = args.occurrence,
            form = local.form,
            initial = local.initial,
            fieldErrors = local.fieldErrors,
            error = local.error,
            saving = local.saving,
            today = inputs.now.toLocalDateTime(inputs.zone).date,
            participants = participants,
            allNames = inputs.persons.map { it.data.name },
            tags = inputs.tags,
            canCreate = viewer != null && EventRules.canCreate(viewer, NEW.copy(createdBy = viewer.memberId)),
            canEdit = viewer != null && series != null && EventRules.canEdit(viewer, series),
            canManageTags = viewer?.permissions?.has(Permission.TAGS_MANAGE) == true,
            changedCount = if (id == null) 0 else inputs.occurrenceRows.count { it.eventId == id && !it.isEmpty() },
            confirmReset = local.confirmReset,
            result = local.result,
        )
    }

    private sealed interface Opened {
        data class Form(
            val form: EventForm,
            val scope: RepeatScope,
        ) : Opened

        data class Closed(
            val result: EventEditorResult,
        ) : Opened
    }

    /** What the editor holds besides the synced data. */
    private data class Local(
        val loaded: Boolean = false,
        val form: EventForm = EventForm(),
        val initial: EventForm = EventForm(),
        val fieldErrors: Map<String, FieldError> = emptyMap(),
        val error: UiError? = null,
        val saving: Boolean = false,
        val confirmReset: Boolean = false,
        val result: EventEditorResult? = null,
        /** The scope the editor really uses. */
        val scope: RepeatScope = RepeatScope.ALL,
    )

    private companion object {
        val NEW = EventData("", "", EventForm.EPOCH, EventForm.EPOCH, allDay = true)
    }
}
