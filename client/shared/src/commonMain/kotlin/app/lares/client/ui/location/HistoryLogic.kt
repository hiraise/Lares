package app.lares.client.ui.location

import app.lares.client.data.location.Place
import app.lares.client.resources.Res
import app.lares.client.resources.date_with_weekday
import app.lares.client.resources.day_today
import app.lares.client.resources.day_yesterday
import app.lares.client.resources.loc_history_range
import app.lares.client.resources.loc_status_at_home
import app.lares.client.resources.loc_status_no_address
import app.lares.client.resources.loc_status_since
import app.lares.client.resources.weekdays_short
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.client.ui.tasks.TasksLogic
import app.lares.contract.api.location.TrackPoint
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.location.LocationLimits
import app.lares.contract.location.Stop
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlin.math.round

/** One stop under the history map: «Школа №12» and «08:25–14:40», «Дома» and «с 16:50» (ongoing). */
data class StopRow(
    val id: String,
    val stop: Stop,
    val title: UiText,
    val time: UiText,
    val home: Boolean,
)

/** Pure parts of the history screen (plan M4-09, LOC-004). */
object HistoryLogic {
    private const val ROUNDING = 10_000.0

    /** The day strip: today first, back to the retention period ([historyDays] days, today included). */
    fun days(
        today: LocalDate,
        historyDays: Int,
    ): List<LocalDate> = (0 until historyDays.coerceAtLeast(1)).map { today.minus(DatePeriod(days = it)) }

    /** «Сегодня», «Вчера», «Пн, 20 окт.». */
    fun dayLabel(
        date: LocalDate,
        today: LocalDate,
    ): UiText =
        when (date.daysUntil(today)) {
            0 -> {
                uiText(Res.string.day_today)
            }

            1 -> {
                uiText(Res.string.day_yesterday)
            }

            else -> {
                UiText.Capitalized(
                    uiText(
                        Res.string.date_with_weekday,
                        UiText.Item(Res.array.weekdays_short, date.dayOfWeek.ordinal),
                        TasksLogic.shortDate(date),
                    ),
                )
            }
        }

    /**
     * Stop rows: the place («Дома» for the home), else the street address from [addresses] (by [addressKey]), else
     * «Без адреса»; times in the household [zone].
     */
    fun rows(
        stops: List<Stop>,
        places: List<Place>,
        addresses: Map<Pair<Double, Double>, String?>,
        zone: TimeZone,
    ): List<StopRow> {
        val byId = places.associateBy { it.id }
        return stops.mapIndexed { index, stop ->
            val place = stop.placeId?.let(byId::get)
            val title =
                when {
                    place != null && place.data.icon == PlaceIcon.HOME -> uiText(Res.string.loc_status_at_home)
                    place != null -> raw(place.data.name)
                    else -> addresses[addressKey(stop)]?.let(::raw) ?: uiText(Res.string.loc_status_no_address)
                }
            val start = TasksLogic.time(stop.start, zone)
            val time =
                if (stop.ongoing) {
                    uiText(Res.string.loc_status_since, raw(start))
                } else {
                    uiText(Res.string.loc_history_range, raw(start), raw(TasksLogic.time(stop.end, zone)))
                }
            StopRow(stopId(index), stop, title, time, home = place?.data?.icon == PlaceIcon.HOME)
        }
    }

    fun stopId(index: Int) = "stop_$index"

    /** Stops outside places get a street address (one lookup per stop, LOC-014). */
    fun needsAddress(stop: Stop): Boolean = stop.placeId == null

    fun addressKey(stop: Stop): Pair<Double, Double> = round4(stop.latitude) to round4(stop.longitude)

    /** The camera for a day: the track's bounds; one point (or all at one spot) at zoom 16; nothing for no points. */
    fun camera(points: List<TrackPoint>): CameraKind? {
        if (points.isEmpty()) return null
        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        val west = points.minOf { it.longitude }
        val east = points.maxOf { it.longitude }
        return if (south == north && west == east) {
            CameraKind.Point(south, west, MEMBER_ZOOM)
        } else {
            CameraKind.Bounds(south, west, north, east)
        }
    }

    fun stopCamera(stop: Stop): CameraKind = CameraKind.Point(stop.latitude, stop.longitude, MEMBER_ZOOM)

    fun track(points: List<TrackPoint>): List<Pair<Double, Double>> = points.map { it.latitude to it.longitude }

    fun mapStops(rows: List<StopRow>): List<MapStop> = rows.map { MapStop(it.id, it.stop.latitude, it.stop.longitude) }

    /** The retention period when the config is not known yet (offline first run). */
    val DEFAULT_DAYS: Int get() = LocationLimits.DEFAULT_HISTORY_DAYS

    private fun round4(value: Double): Double = round(value * ROUNDING) / ROUNDING
}
