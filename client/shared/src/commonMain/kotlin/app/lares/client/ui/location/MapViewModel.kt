package app.lares.client.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.api.ApiException
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.location.Connection
import app.lares.client.data.location.LocationRepository
import app.lares.client.data.location.LocationTexts
import app.lares.client.data.location.MapState
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.Place
import app.lares.client.data.location.PlacesWriter
import app.lares.client.data.observeViewer
import app.lares.client.data.tasks.householdZone
import app.lares.client.db.LocalDb
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.permissions.PlaceRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Where the Map opens: centred on a member or a place (notification taps, Today, person page). */
data class MapArgs(
    val memberId: String? = null,
    val placeId: String? = null,
)

/** A member row of the family sheet. */
data class MemberRow(
    val member: MemberOnMap,
    val status: String,
    val battery: BatteryInfo?,
)

/** «Обновить» (LOC-011): waiting for a newer fix, done, no answer in 90 s, paused, or the call failed. */
enum class RefreshPhase { WAITING, DONE, NO_ANSWER, PAUSED, FAILED }

data class RefreshUi(
    val memberId: String,
    val phase: RefreshPhase,
    /** The newest fix when «Обновить» was pressed; a newer one means done. */
    val baseline: Instant?,
    val error: UiError? = null,
)

/** The member card (sheet content after a tap). */
data class MemberCard(
    val row: MemberRow,
    /** Rounded meters, null without a position. */
    val accuracyM: Int?,
    val canRefresh: Boolean,
    val refresh: RefreshUi?,
    /** «История»: the viewer sees this member with history (LOC-004). */
    val canHistory: Boolean = false,
)

data class MapUiState(
    val loaded: Boolean = false,
    val me: String? = null,
    /** Everybody on the map, the viewer included. */
    val members: List<MemberOnMap> = emptyList(),
    val places: List<Place> = emptyList(),
    val rows: List<MemberRow> = emptyList(),
    val connection: Connection = Connection.POLLING,
    val config: LocationConfig? = null,
    val card: MemberCard? = null,
    val camera: MapCameraRequest? = null,
    val placesOpen: Boolean = false,
    /** The open place sheet. */
    val place: Place? = null,
    /** `location.manage_places`: «Добавить место», long press, edit and delete. */
    val canManagePlaces: Boolean = false,
) {
    val offline: Boolean get() = connection == Connection.OFFLINE

    /** What the `my_location` button does next. */
    val nextLocate: CameraKind get() = MapLogic.nextLocate(camera?.kind, me, members)
}

private data class Local(
    val selected: String? = null,
    val camera: MapCameraRequest? = null,
    val initialDone: Boolean = false,
    val placesOpen: Boolean = false,
    val placeId: String? = null,
    val refresh: RefreshUi? = null,
    val config: LocationConfig? = null,
)

/**
 * The Map screen (plan M4-08, LOC-003, LOC-009, LOC-011, LOC-013, LOC-014): the live map state with status lines,
 * street addresses for members outside places, the member card and «Обновить». Collected while visible only
 * (`WhileSubscribed`), so the live socket closes with the screen.
 */
class MapViewModel(
    private val location: LocationRepository,
    private val texts: LocationTexts,
    household: HouseholdRepository,
    localDb: LocalDb,
    permissions: PermissionsRepository,
    private val placesWriter: PlacesWriter,
    private val clock: Clock,
    private val args: MapArgs = MapArgs(),
    private val refreshTimeout: Duration = REFRESH_TIMEOUT,
) : ViewModel() {
    private val local = MutableStateFlow(Local())

    /** Street addresses by rounded position; a null value = asked, none (the distance to a place shows instead). */
    private val addresses = MutableStateFlow<Map<Pair<Double, Double>, String?>>(emptyMap())
    private val asked = mutableSetOf<Pair<Double, Double>>()
    private var latest: MapState? = null
    private var timeout: Job? = null
    private var serial = 0
    private var configLoading = false

    private val zone =
        household
            .observe()
            .map { householdZone(it?.data?.timeZone) }
            .distinctUntilChanged()
    private val viewer = observeViewer(localDb, permissions)
    private val me = viewer.map { it?.memberId }.distinctUntilChanged()
    private val canManage = viewer.map { it != null && PlaceRules.canManage(it) }.distinctUntilChanged()

    /** The camera centre the map last reported: «Добавить место» from the list starts there. */
    private var center: Pair<Double, Double>? = null

    val state: StateFlow<MapUiState> =
        combine(
            location.observeNow().onEach(::onMapState),
            combine(me, canManage, ::Pair),
            zone,
            addresses,
            local,
        ) { map, (me, canManage), zone, addresses, local ->
            val now = clock.now()
            val rows =
                MapLogic.rows(map.members, me).map { member ->
                    val address = member.last?.let { addresses[MapLogic.addressKey(it)] }
                    MemberRow(member, texts.status(member, address, now, zone, map.places), MapLogic.battery(member.last))
                }
            val selected = local.selected?.let { id -> map.members.firstOrNull { it.memberId == id } }
            val card =
                selected?.let { member ->
                    val row =
                        rows.firstOrNull { it.memberId == member.memberId }
                            ?: MemberRow(member, texts.status(member, null, now, zone, map.places), MapLogic.battery(member.last))
                    MemberCard(
                        row = row,
                        accuracyM = member.last?.accuracyM?.let { kotlin.math.round(it).toInt() },
                        canRefresh = MapLogic.canRefresh(member, me),
                        refresh = local.refresh?.takeIf { it.memberId == member.memberId }?.let { done(it, member) },
                        canHistory = member.level == VisibilityLevel.HISTORY,
                    )
                }
            MapUiState(
                loaded = true,
                me = me,
                members = map.members,
                places = map.places,
                rows = rows,
                connection = map.connection,
                config = local.config,
                card = card,
                camera = local.camera,
                placesOpen = local.placesOpen,
                place = local.placeId?.let { id -> map.places.firstOrNull { it.id == id } },
                canManagePlaces = canManage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    init {
        viewModelScope.launch { loadConfig() }
    }

    private val MemberRow.memberId: String get() = member.memberId

    /** A pin or a row: the card opens and the camera centres on the member. */
    fun select(memberId: String) {
        local.update { it.copy(selected = memberId, placesOpen = false, camera = request(CameraKind.Member(memberId))) }
    }

    /** Back from the card to the list. */
    fun closeCard() = local.update { it.copy(selected = null) }

    fun locate() {
        val next = state.value.nextLocate
        local.update { it.copy(camera = request(next)) }
    }

    fun openPlaces() = local.update { it.copy(placesOpen = true) }

    fun closePlaces() = local.update { it.copy(placesOpen = false) }

    /** A place row or a circle on the map: its sheet opens and the camera centres on it. */
    fun openPlace(placeId: String) =
        local.update { it.copy(placesOpen = false, placeId = placeId, selected = null, camera = request(CameraKind.Place(placeId))) }

    fun closePlace() = local.update { it.copy(placeId = null) }

    /** Deletes the place (after the confirmation in the sheet); works offline. */
    fun deletePlace(placeId: String) {
        local.update { it.copy(placeId = null) }
        viewModelScope.launch { attempt { placesWriter.delete(placeId) } }
    }

    fun onCenter(
        lat: Double,
        lon: Double,
    ) {
        center = lat to lon
    }

    /** Where a new place from «Добавить место» starts: the map centre, else the viewer, else the first place. */
    fun newPlaceCenter(): Pair<Double, Double>? {
        center?.let { return it }
        val map = latest ?: return null
        val mine = map.members.firstOrNull { it.memberId == state.value.me }?.last
        if (mine != null) return mine.latitude to mine.longitude
        return map.places.firstOrNull()?.let { it.data.latitude to it.data.longitude }
    }

    /** «Обновить»: asks the member's phone for a fresh fix; done when a newer one arrives, else after 90 s no answer. */
    fun refresh(memberId: String) {
        val member = latest?.members?.firstOrNull { it.memberId == memberId } ?: return
        if (local.value.refresh?.let { it.memberId == memberId && it.phase == RefreshPhase.WAITING } == true) return
        val baseline = member.last?.recordedAt
        local.update { it.copy(refresh = RefreshUi(memberId, RefreshPhase.WAITING, baseline)) }
        timeout?.cancel()
        timeout =
            viewModelScope.launch {
                val result = location.refresh(memberId)
                result.exceptionOrNull()?.let { e ->
                    val paused = (e as? ApiException)?.code == ErrorCode.CONFLICT
                    val failed =
                        if (paused) {
                            RefreshUi(memberId, RefreshPhase.PAUSED, baseline)
                        } else {
                            RefreshUi(memberId, RefreshPhase.FAILED, baseline, e.toUiError())
                        }
                    local.update { if (it.refresh?.memberId == memberId) it.copy(refresh = failed) else it }
                    return@launch
                }
                delay(refreshTimeout)
                local.update { state ->
                    val current = state.refresh ?: return@update state
                    if (current.memberId != memberId || current.phase != RefreshPhase.WAITING) return@update state
                    val fresh = latest?.members?.firstOrNull { it.memberId == memberId }?.let { newer(current, it) } == true
                    state.copy(refresh = current.copy(phase = if (fresh) RefreshPhase.DONE else RefreshPhase.NO_ANSWER))
                }
            }
    }

    private fun request(kind: CameraKind) = MapCameraRequest(kind, ++serial)

    /** The style comes from the server once; offline the stored one, else again when the network is back. */
    private suspend fun loadConfig() {
        if (configLoading) return
        configLoading = true
        try {
            val config = location.config()
            local.update { it.copy(config = config) }
        } finally {
            configLoading = false
        }
    }

    /** Every map update: the first camera, missing addresses, the style after the network came back. */
    private fun onMapState(map: MapState) {
        latest = map
        val current = local.value
        if (!current.initialDone && map.updatedAt != null) {
            val kind = MapLogic.initialCamera(args.memberId, args.placeId, map.members, map.places)
            local.update {
                it.copy(
                    initialDone = true,
                    camera = it.camera ?: request(kind),
                    selected = it.selected ?: args.memberId?.takeIf { id -> map.members.any { m -> m.memberId == id } },
                )
            }
        }
        if (current.config == null && map.connection != Connection.OFFLINE) viewModelScope.launch { loadConfig() }
        for (member in map.members) {
            if (!MapLogic.needsAddress(member)) continue
            val fix = member.last ?: continue
            val key = MapLogic.addressKey(fix)
            if (!asked.add(key)) continue
            viewModelScope.launch {
                val label = location.address(fix.latitude, fix.longitude)
                addresses.update { it + (key to label) }
            }
        }
    }

    private fun done(
        refresh: RefreshUi,
        member: MemberOnMap,
    ): RefreshUi = if (refresh.phase == RefreshPhase.WAITING && newer(refresh, member)) refresh.copy(phase = RefreshPhase.DONE) else refresh

    private fun newer(
        refresh: RefreshUi,
        member: MemberOnMap,
    ): Boolean {
        val at = member.last?.recordedAt ?: return false
        return refresh.baseline == null || at > refresh.baseline
    }

    companion object {
        val REFRESH_TIMEOUT: Duration = 90.seconds
    }
}
