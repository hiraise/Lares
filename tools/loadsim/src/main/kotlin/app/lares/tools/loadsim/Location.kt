package app.lares.tools.loadsim

import app.lares.contract.Ids
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.LiveMessage
import app.lares.contract.api.location.LocationHistory
import app.lares.contract.api.location.LocationNow
import app.lares.contract.api.location.MemberLocation
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.SetSharingRequest
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.UploadPositionsRequest
import app.lares.contract.api.location.UploadPositionsResponse
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.contract.sync.MutationOp
import app.lares.contract.sync.MutationStatus
import app.lares.contract.sync.PushRequest
import app.lares.contract.sync.PushResponse
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The synthetic route of every sharing phone (plan M4-05): a circle of [DIAMETER_M] in Moscow with the family places
 * evenly spaced on it. Phones walk within [WALK_ZONE_M] of a place (so a pass gives several fixes inside it: arrival and
 * departure) and drive between places. Angles are radians clockwise from north.
 */
class LoopRoute(
    places: Int,
) {
    /** Angles of the places on the circle. */
    val placeAngles: List<Double> = List(places) { 2 * PI * it / places }

    /** `(lat, lon)` of the point at [angle]. */
    fun point(angle: Double): Pair<Double, Double> {
        val lat = CENTER_LAT + RADIUS_M * cos(angle) / METRES_PER_DEGREE
        val lon = CENTER_LON + RADIUS_M * sin(angle) / (METRES_PER_DEGREE * cos(CENTER_LAT * PI / 180))
        return lat to lon
    }

    /** Metres along the circle from [angle] to the nearest place; infinite without places. */
    fun metresToPlace(angle: Double): Double =
        placeAngles.minOfOrNull { place ->
            val d = abs(normalize(angle) - place)
            min(d, 2 * PI - d) * RADIUS_M
        } ?: Double.POSITIVE_INFINITY

    /** Speed at [angle]: walking near places, driving elsewhere. */
    fun speedMps(angle: Double): Double = if (metresToPlace(angle) <= WALK_ZONE_M) WALK_MPS else DRIVE_MPS

    /** The angle after moving for [time] from [angle] at the speed of the starting point. */
    fun advance(
        angle: Double,
        time: Duration,
    ): Double = normalize(angle + speedMps(angle) * time.toDouble(DurationUnit.SECONDS) / RADIUS_M)

    /** Direction of travel at [angle], degrees clockwise from north (the circle is driven clockwise). */
    fun bearingDeg(angle: Double): Double = (normalize(angle + PI / 2) * 180 / PI) % 360

    companion object {
        const val CENTER_LAT = 55.7558
        const val CENTER_LON = 37.6173
        const val DIAMETER_M = 3_000.0
        const val RADIUS_M = DIAMETER_M / 2
        const val PLACE_RADIUS_M = 150
        const val WALK_ZONE_M = 500.0
        const val WALK_MPS = 1.4
        const val DRIVE_MPS = 11.0
        private const val METRES_PER_DEGREE = 111_320.0

        fun normalize(angle: Double): Double = ((angle % (2 * PI)) + 2 * PI) % (2 * PI)
    }
}

/**
 * A phone sharing its location: sets sharing on once, then uploads one fix every position interval while moving along
 * the [route]; an answer with `refreshRequested` gets one extra fix right away (as the app does).
 */
class SharingPhone(
    private val index: Int,
    private val api: DeviceApi,
    private val config: Config,
    private val recorder: Recorder,
    private val route: LoopRoute,
    startAngle: Double,
    private val random: Random,
) {
    private var angle = startAngle
    private val started = TimeSource.Monotonic.markNow()

    fun launchIn(scope: CoroutineScope) {
        scope.launch { run() }
    }

    private suspend fun run() {
        while (true) {
            try {
                api.send("PUT /location/me/sharing", HttpMethod.Put, "/location/me/sharing", SetSharingRequest(SharingStatus.SHARING))
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recorder.note("phone $index: sharing: ${e.message}")
                delay(5.seconds)
            }
        }
        // Spread the phones over the interval so they do not upload in step.
        delay(config.positionInterval * random.nextDouble())
        while (true) {
            guarded(recorder, "phone $index") {
                if (upload().refreshRequested) {
                    recorder.refreshesAnswered.incrementAndGet()
                    upload()
                }
            }
            delay(config.positionInterval)
            angle = route.advance(angle, config.positionInterval)
        }
    }

    private suspend fun upload(): UploadPositionsResponse {
        val response =
            api.call<UploadPositionsRequest, UploadPositionsResponse>(
                "POST /location/positions",
                HttpMethod.Post,
                "/location/positions",
                UploadPositionsRequest(listOf(fix())),
            )
        recorder.positionsSent.incrementAndGet()
        recorder.positionsAccepted.addAndGet(response.accepted.toLong())
        if (!response.sharingHere) recorder.uploadsNotSharingHere.incrementAndGet()
        return response
    }

    private fun fix(): PositionFix {
        val (lat, lon) = route.point(angle)
        val minutes = started.elapsedNow().inWholeMinutes
        return PositionFix(
            recordedAt = Clock.System.now(),
            latitude = lat,
            longitude = lon,
            accuracyM = random.nextDouble(10.0, 40.0).toFloat(),
            speedMps = (route.speedMps(angle) * random.nextDouble(0.9, 1.1)).toFloat(),
            bearingDeg = route.bearingDeg(angle).toFloat(),
            battery = batteryAt(minutes),
            charging = false,
        )
    }

    companion object {
        /** 1 % less every 10 minutes from 100; recharged to 100 after 20. */
        fun batteryAt(minutes: Long): Int = 100 - ((minutes / 10) % 81).toInt()
    }
}

/**
 * A device with the map open: keeps `/location/live` open (counting messages and keeping the visible members), reads
 * `GET /location/now` every 5 min and, every 10 min, the day's history of a visible member and «Обновить» for one.
 */
class MapViewer(
    private val index: Int,
    private val api: DeviceApi,
    private val memberId: String,
    private val recorder: Recorder,
    private val random: Random,
    private val zone: ZoneId,
) {
    private val visible = ConcurrentHashMap<String, MemberLocation>()

    fun launchIn(scope: CoroutineScope) {
        scope.launch { liveLoop() }
        scope.launch { pollLoop() }
    }

    private suspend fun liveLoop() {
        while (true) {
            try {
                api.live {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = LaresJson.decodeFromString(LiveMessage.serializer(), frame.readText())
                        recorder.liveMessage(message.type)
                        when (message.type) {
                            LiveMessage.SNAPSHOT -> message.now?.let(::replace)
                            LiveMessage.MEMBER -> message.member?.let(::changed)
                            LiveMessage.GONE -> message.memberId?.let { visible.remove(it) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recorder.note("viewer $index: live: ${e.message}")
            }
            recorder.liveClosed.incrementAndGet()
            delay(1.seconds)
        }
    }

    private suspend fun pollLoop() {
        var tick = 0
        while (true) {
            delay(POLL_INTERVAL * random.nextDouble(0.8, 1.2))
            guarded(recorder, "viewer $index") { replace(api.get<LocationNow>("GET /location/now", "/location/now")) }
            if (tick++ % 2 == 1) {
                guarded(recorder, "viewer $index") { history() }
                guarded(recorder, "viewer $index") { refresh() }
            }
        }
    }

    /** Keeps [member] and counts arrivals and departures seen live (the server's geofences at work). */
    private fun changed(member: MemberLocation) {
        val before = visible.put(member.memberId, member) ?: return
        if (before.placeId == member.placeId) return
        if (before.placeId != null) recorder.departuresSeen.incrementAndGet()
        if (member.placeId != null) recorder.arrivalsSeen.incrementAndGet()
    }

    private fun replace(now: LocationNow) {
        visible.keys.retainAll(now.members.map { it.memberId }.toSet())
        now.members.forEach { visible[it.memberId] = it }
    }

    private fun others(filter: (MemberLocation) -> Boolean): MemberLocation? =
        visible.values.filter { it.memberId != memberId && filter(it) }.randomOrNull(random)

    private suspend fun history() {
        val member = others { it.level == VisibilityLevel.HISTORY } ?: return
        api.get<LocationHistory>(
            "GET /location/history",
            "/location/history?memberId=${member.memberId}&date=${LocalDate.now(zone)}",
        )
    }

    private suspend fun refresh() {
        val member = others { it.status == SharingStatus.SHARING } ?: return
        api.exchange("POST /location/members/{id}/refresh", HttpMethod.Post, "/location/members/${member.memberId}/refresh", null, true)
        recorder.refreshesRequested.incrementAndGet()
    }

    private companion object {
        val POLL_INTERVAL = 5.minutes
    }
}

/** Location part of the family: who shares, who watches the map. */
class LocationFamily(
    val phones: List<SharingPhone>,
    val viewers: List<MapViewer>,
)

/**
 * Location setup: the administrator creates the places (sync push); every map viewer subscribes to arrive / leave
 * alerts of one sharing member ([alertTarget]) with its own `place_alert` row (the writer must be the subscriber).
 * [memberOf] gives the member of a device index; [apis] are the devices' sessions.
 */
suspend fun setUpLocation(
    config: Config,
    recorder: Recorder,
    runId: String,
    admin: DeviceApi,
    adminMember: String,
    memberOf: (Int) -> String,
    apis: List<DeviceApi>,
    zone: ZoneId,
): LocationFamily {
    val route = LoopRoute(config.places)
    val placeIds = List(config.places) { Ids.newId() }
    if (placeIds.isNotEmpty()) {
        pushAll(
            admin,
            route.placeAngles.mapIndexed { n, angle ->
                val (lat, lon) = route.point(angle)
                val place =
                    PlaceData(
                        "LT $runId place ${n + 1}",
                        latitude = lat,
                        longitude = lon,
                        radiusM = LoopRoute.PLACE_RADIUS_M,
                        createdBy = adminMember,
                    )
                upsert(EntityTypes.PLACE, placeIds[n], PlaceData.serializer(), place)
            },
        )
        val sharingMembers = config.sharingIndexes.map(memberOf)
        for (viewer in config.viewerIndexes) {
            val subscriber = memberOf(viewer)
            val target = alertTarget(sharingMembers, subscriber, viewer / 2) ?: continue
            val alert = PlaceAlertData(memberId = target, subscriberId = subscriber, placeIds = placeIds.toSet())
            pushAll(
                apis[viewer],
                listOf(upsert(EntityTypes.PLACE_ALERT, Ids.placeAlertRowId(target, subscriber), PlaceAlertData.serializer(), alert)),
            )
        }
    }
    val sharing = config.sharingIndexes
    return LocationFamily(
        phones =
            sharing.mapIndexed { n, index ->
                val start = 2 * PI * n / sharing.size + START_OFFSET
                SharingPhone(index, apis[index], config, recorder, route, start, Random(runId.hashCode() * 17 + index))
            },
        viewers =
            config.viewerIndexes.map { index ->
                MapViewer(index, apis[index], memberOf(index), recorder, Random(runId.hashCode() * 13 + index), zone)
            },
    )
}

/** The sharing member a viewer subscribes to: not itself, spread over the sharing members by [slot]. */
fun alertTarget(
    sharingMembers: List<String>,
    subscriber: String,
    slot: Int,
): String? = sharingMembers.filter { it != subscriber }.takeIf { it.isNotEmpty() }?.let { it[slot % it.size] }

/** Phones start a little before a place, so the first arrivals come early in the run. */
private const val START_OFFSET = -0.3

private fun <T> upsert(
    type: String,
    id: String,
    serializer: KSerializer<T>,
    data: T,
) = Mutation(Ids.newId(), type, id, MutationOp.UPSERT, LaresJson.encodeToJsonElement(serializer, data).jsonObject, Clock.System.now())

/** Pushes setup mutations; any rejection fails the setup. */
private suspend fun pushAll(
    api: DeviceApi,
    mutations: List<Mutation>,
) {
    val response = api.call<PushRequest, PushResponse>("POST /sync/mutations", HttpMethod.Post, "/sync/mutations", PushRequest(mutations))
    val rejected = response.results.filter { it.status == MutationStatus.REJECTED }
    check(rejected.isEmpty() && response.results.size == mutations.size) { "location setup rejected: ${rejected.map { it.error }}" }
}
