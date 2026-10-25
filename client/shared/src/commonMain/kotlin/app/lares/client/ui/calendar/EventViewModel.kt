package app.lares.client.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.calendar.CalendarRepository
import app.lares.client.data.calendar.CalendarWriter
import app.lares.client.data.calendar.EventDetail
import app.lares.client.resources.Res
import app.lares.client.resources.cal_occurrence_deleted
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.client.ui.common.uiText
import app.lares.contract.entities.EventKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

/** Parameters of [EventViewModel] (Koin `parametersOf`); [occurrence] is the scheduled date of the opened occurrence. */
data class EventArgs(
    val id: String,
    val occurrence: LocalDate? = null,
)

data class EventUiState(
    val loaded: Boolean = false,
    val detail: EventDetail? = null,
    val today: LocalDate = LocalDate(1970, 1, 1),
) {
    /** Loaded, but the event does not exist (deleted here or on another phone): the page closes. */
    val gone: Boolean get() = loaded && detail == null

    val repeating: Boolean get() = detail?.rule != null

    val memorable: Boolean get() = detail?.event?.kind == EventKind.MEMORABLE_DATE

    /** Edit and delete ask «Только это · Это и следующие · Все» (CAL-015); memorable dates change as a whole. */
    val asksScope: Boolean get() = repeating && !memorable && detail?.occ != null
}

/** One-shot results of the event page. */
sealed interface EventEvent {
    /** The page closes, with a snackbar [message] behind it if set. */
    data class Close(
        val message: UiText? = null,
    ) : EventEvent

    data class Failed(
        val error: UiError,
    ) : EventEvent
}

/** The event page (plan M3-05, CAL-014, CAL-006, CAL-015): facts, «Напомнить мне», delete one, following or all. */
class EventViewModel(
    repository: CalendarRepository,
    private val writer: CalendarWriter,
    args: EventArgs,
) : ViewModel() {
    private val id = args.id
    private val channel = Channel<EventEvent>(Channel.BUFFERED)

    val events: Flow<EventEvent> = channel.receiveAsFlow()

    val state: StateFlow<EventUiState> =
        combine(repository.observeDetail(id, args.occurrence), repository.observeInputs().map { it.now.toLocalDateTime(it.zone).date }) {
            detail,
            today,
            ->
            EventUiState(loaded = true, detail = detail, today = today)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, EventUiState())

    /** «Напомнить мне»: [minutes] before, or null for none. */
    fun setMyReminder(minutes: Int?) = write { writer.setMyReminder(id, minutes) }

    /** «Как в событии». */
    fun clearMyReminder() = write { writer.clearMyReminder(id) }

    /** Deletes [scope] of a repeating event from the shown occurrence, or the whole event. */
    fun delete(scope: RepeatScope) {
        val occurrence =
            state.value.detail
                ?.occ
                ?.occurrence
        write {
            when {
                scope == RepeatScope.ALL || occurrence == null || !state.value.asksScope -> {
                    writer.delete(id)
                    channel.send(EventEvent.Close())
                }

                scope == RepeatScope.THIS -> {
                    writer.cancelOccurrence(id, occurrence)
                    channel.send(EventEvent.Close(uiText(Res.string.cal_occurrence_deleted)))
                }

                else -> {
                    writer.deleteFollowing(id, occurrence)
                    channel.send(EventEvent.Close())
                }
            }
        }
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            attempt { block() }.exceptionOrNull()?.let { channel.send(EventEvent.Failed(it.toUiError())) }
        }
    }
}
