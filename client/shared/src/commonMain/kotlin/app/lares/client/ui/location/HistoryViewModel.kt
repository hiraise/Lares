package app.lares.client.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.api.ApiException
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.location.DayHistory
import app.lares.client.data.location.LocationRepository
import app.lares.client.data.location.Place
import app.lares.client.data.location.PlacesRepository
import app.lares.client.data.tasks.householdZone
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.toUiError
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.entities.PaletteColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Koin parameters of [HistoryViewModel]: [date] null = today. */
data class HistoryArgs(
    val memberId: String,
    val date: LocalDate? = null,
)

/** What the area under the map shows. */
enum class HistoryPhase { LOADING, READY, EMPTY, OFFLINE, FAILED }

data class HistoryUiState(
    val name: String = "",
    val color: PaletteColor? = null,
    val today: LocalDate? = null,
    val days: List<LocalDate> = emptyList(),
    val selected: LocalDate? = null,
    val phase: HistoryPhase = HistoryPhase.LOADING,
    val error: UiError? = null,
    val track: List<Pair<Double, Double>> = emptyList(),
    val rows: List<StopRow> = emptyList(),
    val places: List<Place> = emptyList(),
    val config: LocationConfig? = null,
    val camera: MapCameraRequest? = null,
    /** `FORBIDDEN` / `NOT_FOUND`: the screen closes with «Нет доступа к истории». */
    val denied: Boolean = false,
)

/**
 * A member's day (plan M4-09, LOC-004): the day strip of the retained days, the track and the stops with times and
 * places or addresses. Online only: the history is never stored on the phone.
 */
class HistoryViewModel(
    private val location: LocationRepository,
    members: MembersRepository,
    places: PlacesRepository,
    household: HouseholdRepository,
    private val clock: Clock,
    private val args: HistoryArgs,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val addresses = MutableStateFlow<Map<Pair<Double, Double>, String?>>(emptyMap())
    private val zones = household.observe().map { householdZone(it?.data?.timeZone) }.distinctUntilChanged()
    private var load: Job? = null
    private var serial = 0

    val state: StateFlow<HistoryUiState> =
        combine(local, members.observeAll(), places.observePlaces(), zones, addresses) { local, members, places, zone, addresses ->
            val member = members.firstOrNull { it.id == args.memberId }
            val history = local.history
            val rows = history?.let { HistoryLogic.rows(it.stops, places, addresses, zone) }.orEmpty()
            HistoryUiState(
                name = member?.name.orEmpty(),
                color = member?.color,
                today = local.today,
                days = local.days,
                selected = local.selected,
                phase = local.phase,
                error = local.error,
                track = history?.let { HistoryLogic.track(it.points) }.orEmpty(),
                rows = rows,
                places = places,
                config = local.config,
                camera = local.camera,
                denied = local.denied,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        viewModelScope.launch {
            val zone = zones.first()
            val today = clock.now().toLocalDateTime(zone).date
            val config = location.config()
            val days = HistoryLogic.days(today, config?.historyDays ?: HistoryLogic.DEFAULT_DAYS)
            val selected = args.date?.takeIf { it in days } ?: today
            local.update { it.copy(today = today, days = days, config = config) }
            load(selected)
        }
    }

    /** A day chip; the selected day again reloads it (after the network came back). */
    fun select(date: LocalDate) {
        if (date in local.value.days) load(date)
    }

    /** A stop row: the camera centres on it. */
    fun showStop(id: String) {
        val row = state.value.rows.firstOrNull { it.id == id } ?: return
        local.update { it.copy(camera = request(HistoryLogic.stopCamera(row.stop))) }
    }

    private fun load(date: LocalDate) {
        load?.cancel()
        local.update { it.copy(selected = date, phase = HistoryPhase.LOADING, error = null, history = null) }
        load =
            viewModelScope.launch {
                if (local.value.config == null) location.config()?.let { config -> local.update { it.copy(config = config) } }
                val result = location.history(args.memberId, date)
                val history = result.getOrNull()
                if (history == null) {
                    fail(result.exceptionOrNull())
                    return@launch
                }
                val camera = HistoryLogic.camera(history.points)
                local.update {
                    it.copy(
                        history = history,
                        phase = if (history.points.isEmpty()) HistoryPhase.EMPTY else HistoryPhase.READY,
                        camera = camera?.let(::request) ?: it.camera,
                    )
                }
                lookUpAddresses(history)
            }
    }

    private fun fail(error: Throwable?) {
        val code = (error as? ApiException)?.code
        local.update {
            when {
                code == ErrorCode.FORBIDDEN || code == ErrorCode.NOT_FOUND || code == ErrorCode.ENTITY_NOT_FOUND -> {
                    it.copy(denied = true)
                }

                error is ApiException -> {
                    it.copy(phase = HistoryPhase.FAILED, error = error.toUiError())
                }

                else -> {
                    it.copy(phase = HistoryPhase.OFFLINE)
                }
            }
        }
    }

    /** One lookup per stop outside places (ADR-0026: only for shown positions). */
    private fun lookUpAddresses(history: DayHistory) {
        for (stop in history.stops) {
            if (!HistoryLogic.needsAddress(stop)) continue
            val key = HistoryLogic.addressKey(stop)
            if (key in addresses.value) continue
            viewModelScope.launch {
                val label = location.address(stop.latitude, stop.longitude)
                addresses.update { it + (key to label) }
            }
        }
    }

    private fun request(kind: CameraKind) = MapCameraRequest(kind, ++serial)

    private data class Local(
        val today: LocalDate? = null,
        val days: List<LocalDate> = emptyList(),
        val selected: LocalDate? = null,
        val phase: HistoryPhase = HistoryPhase.LOADING,
        val error: UiError? = null,
        val history: DayHistory? = null,
        val config: LocationConfig? = null,
        val camera: MapCameraRequest? = null,
        val denied: Boolean = false,
    )
}
