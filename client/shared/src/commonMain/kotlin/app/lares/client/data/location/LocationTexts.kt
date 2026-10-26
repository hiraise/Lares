package app.lares.client.data.location

import app.lares.client.data.calendar.clockTime
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.location.Geo
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The phrases of the location texts in the app language. A suspend interface like `CalendarReminderTexts`:
 * [EnglishLocationTexts] in the core, `LocationResourceTexts` (string resources, en / ru) in the app UI.
 * Times arrive formatted ("9:05"), dates as day and month (1..12).
 */
interface LocationTextResources {
    /** «Не делится геопозицией». */
    suspend fun notSharing(): String

    /** «На паузе до 15:30» ([until] formatted), «На паузе» when [until] is null. */
    suspend fun paused(until: String?): String

    /** «Геопозиция выключена на телефоне», «Нет доступа к геопозиции», «Передача остановлена», «Выход из приложения». */
    suspend fun off(reason: OffReason): String

    /** «последнее место в 14:20» ([at] from [at] / [yesterdayAt] / [dateAt]). */
    suspend fun lastPlace(at: String): String

    /** «Нет данных с 18:05» ([since] from [since] / [sinceYesterday]); «Нет данных» when null. */
    suspend fun noData(since: String?): String

    /** «Дома»: a member in a place with the home icon. */
    suspend fun atHome(): String

    /** «с 15:10» / «с 12 окт.». */
    suspend fun since(time: String): String

    /** «со вчера, 15:10». */
    suspend fun sinceYesterday(time: String): String

    /** «В пути». */
    suspend fun moving(): String

    /** «Без адреса». */
    suspend fun noAddress(): String

    /** «2 км от дома». */
    suspend fun fromHome(distance: String): String

    /** «2 км от места «Школа №12»». */
    suspend fun fromPlace(
        distance: String,
        place: String,
    ): String

    /** «350 м», «2,5 км», «12 км» for a distance in meters. */
    suspend fun distance(meters: Double): String

    /** «сейчас». */
    suspend fun justNow(): String

    /** «3 мин назад». */
    suspend fun minutesAgo(minutes: Int): String

    /** «в 14:20». */
    suspend fun at(time: String): String

    /** «вчера в 14:20». */
    suspend fun yesterdayAt(time: String): String

    /** «12 окт. в 14:20». */
    suspend fun dateAt(
        day: Int,
        month: Int,
        time: String,
    ): String

    /** «12 окт.». */
    suspend fun date(
        day: Int,
        month: Int,
    ): String

    /** «Геопозицией теперь делится другой телефон» (LOC-019): the notice on the phone that stopped. */
    suspend fun sharingMovedToOtherPhone(): String
}

/** Battery level classes of the member rows: 15 % or lower is red (spec assumption). */
enum class BatteryLevel { LOW, MEDIUM, HIGH }

/**
 * The location texts of the spec (LOC-003, LOC-010, LOC-012…LOC-014): the status line of a member and how long ago
 * a position is. Times are shown in the household [TimeZone].
 */
class LocationTexts(
    private val resources: LocationTextResources,
) {
    /**
     * The status line under a member's name. Checks in this order: never shared; paused; off on the phone (with the
     * last place time); stale; in a place («Дома · с 15:10»); moving («В пути · сейчас»); else the [address], the
     * distance to the nearest of [places] (when there is no address) or «Без адреса», with the age.
     */
    suspend fun status(
        member: MemberOnMap,
        address: String?,
        now: Instant,
        zone: TimeZone,
        places: List<Place> = emptyList(),
    ): String {
        val last = member.last
        return when {
            member.status == SharingStatus.NEVER -> {
                resources.notSharing()
            }

            member.status == SharingStatus.PAUSED -> {
                resources.paused(member.pausedUntil?.let { until(it, now, zone) })
            }

            member.status == SharingStatus.OFF_ON_PHONE -> {
                val reason = resources.off(member.offReason ?: OffReason.STOPPED)
                if (last == null) reason else "$reason · ${resources.lastPlace(moment(last.recordedAt, now, zone))}"
            }

            last == null -> {
                resources.noData(null)
            }

            member.stale -> {
                resources.noData(since(last.recordedAt, now, zone))
            }

            member.place != null -> {
                val name = if (member.place.data.icon == PlaceIcon.HOME) resources.atHome() else member.place.data.name
                val since = member.placeSince?.let { since(it, now, zone) }
                if (since == null) name else "$name · $since"
            }

            member.moving -> {
                "${resources.moving()} · ${age(last.recordedAt, now, zone)}"
            }

            else -> {
                "${where(last.latitude, last.longitude, address, places)} · ${age(last.recordedAt, now, zone)}"
            }
        }
    }

    /** «сейчас» under 2 minutes, «N мин назад» under an hour, then «в 14:20», «вчера в 14:20», «12 окт. в 14:20». */
    suspend fun age(
        at: Instant,
        now: Instant,
        zone: TimeZone,
    ): String {
        val elapsed = now - at
        return when {
            elapsed < 2.minutes -> resources.justNow()
            elapsed < 1.hours -> resources.minutesAgo(elapsed.inWholeMinutes.toInt())
            else -> moment(at, now, zone)
        }
    }

    fun battery(level: Int): BatteryLevel =
        when {
            level <= LOW_BATTERY -> BatteryLevel.LOW
            level <= MEDIUM_BATTERY -> BatteryLevel.MEDIUM
            else -> BatteryLevel.HIGH
        }

    private suspend fun where(
        latitude: Double,
        longitude: Double,
        address: String?,
        places: List<Place>,
    ): String {
        if (address != null) return address
        val nearest =
            places.minByOrNull { Geo.distanceM(latitude, longitude, it.data.latitude, it.data.longitude) }
                ?: return resources.noAddress()
        val distance = resources.distance(Geo.distanceM(latitude, longitude, nearest.data.latitude, nearest.data.longitude))
        return if (nearest.data.icon == PlaceIcon.HOME) resources.fromHome(distance) else resources.fromPlace(distance, nearest.data.name)
    }

    /** «в 14:20» today, «вчера в 14:20», «12 окт. в 14:20». */
    private suspend fun moment(
        at: Instant,
        now: Instant,
        zone: TimeZone,
    ): String {
        val local = at.toLocalDateTime(zone)
        val time = clockTime(local.time)
        return when (dayOffset(local, now, zone)) {
            0 -> resources.at(time)
            1 -> resources.yesterdayAt(time)
            else -> resources.dateAt(local.date.day, local.date.month.ordinal + 1, time)
        }
    }

    /** «с 15:10» today, «со вчера, 15:10», «с 12 окт.». */
    private suspend fun since(
        at: Instant,
        now: Instant,
        zone: TimeZone,
    ): String {
        val local = at.toLocalDateTime(zone)
        return when (dayOffset(local, now, zone)) {
            0 -> resources.since(clockTime(local.time))
            1 -> resources.sinceYesterday(clockTime(local.time))
            else -> resources.since(resources.date(local.date.day, local.date.month.ordinal + 1))
        }
    }

    /** A pause end: «15:30» within a day, else «12 окт. 15:30». */
    private suspend fun until(
        at: Instant,
        now: Instant,
        zone: TimeZone,
    ): String {
        val local = at.toLocalDateTime(zone)
        val time = clockTime(local.time)
        return if (at - now < 24.hours) time else "${resources.date(local.date.day, local.date.month.ordinal + 1)} $time"
    }

    private fun dayOffset(
        local: LocalDateTime,
        now: Instant,
        zone: TimeZone,
    ): Int {
        val today = now.toLocalDateTime(zone).date
        return when (local.date) {
            today -> 0
            today.minus(1, DateTimeUnit.DAY) -> 1
            else -> 2
        }
    }

    private companion object {
        const val LOW_BATTERY = 15
        const val MEDIUM_BATTERY = 50
    }
}

/** Fallback without UI resources (integration tests, a core without the app UI). */
class EnglishLocationTexts : LocationTextResources {
    override suspend fun notSharing() = "Not sharing location"

    override suspend fun paused(until: String?) = if (until == null) "Paused" else "Paused until $until"

    override suspend fun off(reason: OffReason) =
        when (reason) {
            OffReason.LOCATION_DISABLED -> "Location is off on the phone"
            OffReason.PERMISSION_REVOKED -> "No location access"
            OffReason.STOPPED -> "Sharing stopped"
            OffReason.SIGNED_OUT -> "Signed out of the app"
        }

    override suspend fun lastPlace(at: String) = "last seen $at"

    override suspend fun noData(since: String?) = if (since == null) "No data" else "No data $since"

    override suspend fun atHome() = "At home"

    override suspend fun since(time: String) = "since $time"

    override suspend fun sinceYesterday(time: String) = "since yesterday $time"

    override suspend fun moving() = "On the way"

    override suspend fun noAddress() = "No address"

    override suspend fun fromHome(distance: String) = "$distance from home"

    override suspend fun fromPlace(
        distance: String,
        place: String,
    ) = "$distance from $place"

    override suspend fun distance(meters: Double) = formatDistance(meters, ".", "m", "km")

    override suspend fun justNow() = "now"

    override suspend fun minutesAgo(minutes: Int) = "$minutes min ago"

    override suspend fun at(time: String) = "at $time"

    override suspend fun yesterdayAt(time: String) = "yesterday at $time"

    override suspend fun dateAt(
        day: Int,
        month: Int,
        time: String,
    ) = "${date(day, month)} at $time"

    override suspend fun date(
        day: Int,
        month: Int,
    ) = "${MONTHS[month - 1]} $day"

    override suspend fun sharingMovedToOtherPhone() = "Another phone now shares your location"

    private companion object {
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }
}

/** «350 м» (rounded to 10 m) under 1 km, «2,5 км» under 10 km, «12 км» above. */
fun formatDistance(
    meters: Double,
    decimalSeparator: String,
    m: String,
    km: String,
): String {
    if (meters < METERS_PER_KM) return "${(meters / TEN_METERS + HALF).toLong().coerceAtLeast(1) * TEN_METERS} $m"
    val tenths = (meters / (METERS_PER_KM / TENTHS) + HALF).toLong()
    if (tenths >= TEN_KM_TENTHS) return "${(meters / METERS_PER_KM + HALF).toLong()} $km"
    val whole = tenths / TENTHS
    val fraction = tenths % TENTHS
    return if (fraction == 0L) "$whole $km" else "$whole$decimalSeparator$fraction $km"
}

private const val METERS_PER_KM = 1000.0
private const val TEN_METERS = 10
private const val TENTHS = 10
private const val TEN_KM_TENTHS = 100
private const val HALF = 0.5
