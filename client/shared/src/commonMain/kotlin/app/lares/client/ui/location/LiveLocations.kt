package app.lares.client.ui.location

import app.lares.client.data.HouseholdRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.location.LocationRepository
import app.lares.client.data.location.LocationTexts
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.Place
import app.lares.client.data.location.PlaceAlert
import app.lares.client.data.location.PlacesRepository
import app.lares.client.data.location.PlacesWriter
import app.lares.client.data.observeViewer
import app.lares.client.data.tasks.householdZone
import app.lares.client.db.LocalDb
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** A member the viewer sees, with the status line («Школа №12 · с 08:52», «ул. Ленина, 12 · сейчас»). */
data class LocatedMember(
    val member: MemberOnMap,
    val status: String,
)

/** What Today and the person page need: the viewer, the members with status lines, places and own alerts. */
data class LiveLocationsState(
    val me: String? = null,
    /** Empty without `location.view`. */
    val members: List<LocatedMember> = emptyList(),
    val places: List<Place> = emptyList(),
    val alerts: List<PlaceAlert> = emptyList(),
)

/**
 * The live map data outside the Map (plan M4-10, LOC-016, LOC-017): status lines with street addresses asked through
 * the server (ADR-0026), the viewer's alerts and their writer. Opens the live socket only while collected and only
 * with `location.view`; view models share it with `WhileSubscribed`, so it closes with the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveLocations(
    private val location: LocationRepository,
    private val texts: LocationTexts,
    household: HouseholdRepository,
    localDb: LocalDb,
    permissions: PermissionsRepository,
    private val places: PlacesRepository,
    private val writer: PlacesWriter,
    private val clock: Clock,
) {
    private val viewer = observeViewer(localDb, permissions)
    private val zone = household.observe().map { householdZone(it?.data?.timeZone) }.distinctUntilChanged()

    fun observe(): Flow<LiveLocationsState> =
        viewer
            .map { v -> v?.memberId to (v?.permissions?.has(Permission.LOCATION_VIEW) == true) }
            .distinctUntilChanged()
            .flatMapLatest { (me, canView) -> if (canView) live(me) else flowOf(LiveLocationsState(me = me)) }

    /** Writes the viewer's alert rows (offline, through sync). */
    suspend fun saveAlerts(changes: Map<String, Set<String>>) = AlertsLogic.save(writer, changes)

    private fun live(me: String?): Flow<LiveLocationsState> =
        channelFlow {
            val addresses = MutableStateFlow<Map<Pair<Double, Double>, String?>>(emptyMap())
            val asked = mutableSetOf<Pair<Double, Double>>()
            combine(location.observeNow(), zone, addresses, places.observeMyAlerts()) { map, zone, known, alerts ->
                for (member in map.members) {
                    if (!MapLogic.needsAddress(member)) continue
                    val fix = member.last ?: continue
                    val key = MapLogic.addressKey(fix)
                    if (!asked.add(key)) continue
                    launch {
                        val label = location.address(fix.latitude, fix.longitude)
                        addresses.update { it + (key to label) }
                    }
                }
                val now = clock.now()
                LiveLocationsState(
                    me = me,
                    members =
                        map.members.map { member ->
                            val address = member.last?.let { known[MapLogic.addressKey(it)] }
                            LocatedMember(member, texts.status(member, address, now, zone, map.places))
                        },
                    places = map.places,
                    alerts = alerts,
                )
            }.collect { send(it) }
        }

    companion object {
        const val TODAY_ROWS = 4

        /** «Семья сейчас»: the others who share or are paused, by name, at most [TODAY_ROWS]. */
        fun todayRows(state: LiveLocationsState): List<LocatedMember> =
            state.members
                .filter { it.member.memberId != state.me }
                .filter { it.member.status == SharingStatus.SHARING || it.member.status == SharingStatus.PAUSED }
                .sortedBy { it.member.name.lowercase() }
                .take(TODAY_ROWS)
    }
}
