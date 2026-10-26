package app.lares.client.ui.common

import app.lares.client.data.location.LocationTextResources
import app.lares.client.data.location.formatDistance
import app.lares.client.resources.Res
import app.lares.client.resources.date_day_month_short
import app.lares.client.resources.loc_sharing_moved
import app.lares.client.resources.loc_status_at
import app.lares.client.resources.loc_status_at_home
import app.lares.client.resources.loc_status_date_at
import app.lares.client.resources.loc_status_decimal_separator
import app.lares.client.resources.loc_status_from_home
import app.lares.client.resources.loc_status_from_place
import app.lares.client.resources.loc_status_kilometers
import app.lares.client.resources.loc_status_last_place
import app.lares.client.resources.loc_status_meters
import app.lares.client.resources.loc_status_minutes_ago
import app.lares.client.resources.loc_status_moving
import app.lares.client.resources.loc_status_no_address
import app.lares.client.resources.loc_status_no_data
import app.lares.client.resources.loc_status_no_data_since
import app.lares.client.resources.loc_status_not_sharing
import app.lares.client.resources.loc_status_now
import app.lares.client.resources.loc_status_off_location_disabled
import app.lares.client.resources.loc_status_off_permission_revoked
import app.lares.client.resources.loc_status_off_signed_out
import app.lares.client.resources.loc_status_off_stopped
import app.lares.client.resources.loc_status_paused
import app.lares.client.resources.loc_status_paused_until
import app.lares.client.resources.loc_status_since
import app.lares.client.resources.loc_status_since_yesterday
import app.lares.client.resources.loc_status_yesterday_at
import app.lares.client.resources.months_short
import app.lares.contract.api.location.OffReason
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray

/** Location texts in the app language: «Дома · с 15:10», «Нет данных с 18:05», «2 км от дома · 3 мин назад». */
class LocationResourceTexts : LocationTextResources {
    override suspend fun notSharing() = getString(Res.string.loc_status_not_sharing)

    override suspend fun paused(until: String?) =
        if (until == null) getString(Res.string.loc_status_paused) else getString(Res.string.loc_status_paused_until, until)

    override suspend fun off(reason: OffReason) =
        getString(
            when (reason) {
                OffReason.LOCATION_DISABLED -> Res.string.loc_status_off_location_disabled
                OffReason.PERMISSION_REVOKED -> Res.string.loc_status_off_permission_revoked
                OffReason.STOPPED -> Res.string.loc_status_off_stopped
                OffReason.SIGNED_OUT -> Res.string.loc_status_off_signed_out
            },
        )

    override suspend fun lastPlace(at: String) = getString(Res.string.loc_status_last_place, at)

    override suspend fun noData(since: String?) =
        if (since == null) getString(Res.string.loc_status_no_data) else getString(Res.string.loc_status_no_data_since, since)

    override suspend fun atHome() = getString(Res.string.loc_status_at_home)

    override suspend fun since(time: String) = getString(Res.string.loc_status_since, time)

    override suspend fun sinceYesterday(time: String) = getString(Res.string.loc_status_since_yesterday, time)

    override suspend fun moving() = getString(Res.string.loc_status_moving)

    override suspend fun noAddress() = getString(Res.string.loc_status_no_address)

    override suspend fun fromHome(distance: String) = getString(Res.string.loc_status_from_home, distance)

    override suspend fun fromPlace(
        distance: String,
        place: String,
    ) = getString(Res.string.loc_status_from_place, distance, place)

    override suspend fun distance(meters: Double): String {
        // The unit texts carry the number; split them around the placeholder for formatDistance.
        val m = getString(Res.string.loc_status_meters, "").trim()
        val km = getString(Res.string.loc_status_kilometers, "").trim()
        return formatDistance(meters, getString(Res.string.loc_status_decimal_separator), m, km)
    }

    override suspend fun justNow() = getString(Res.string.loc_status_now)

    override suspend fun minutesAgo(minutes: Int) = getPluralString(Res.plurals.loc_status_minutes_ago, minutes, minutes)

    override suspend fun at(time: String) = getString(Res.string.loc_status_at, time)

    override suspend fun yesterdayAt(time: String) = getString(Res.string.loc_status_yesterday_at, time)

    override suspend fun dateAt(
        day: Int,
        month: Int,
        time: String,
    ) = getString(Res.string.loc_status_date_at, date(day, month), time)

    override suspend fun date(
        day: Int,
        month: Int,
    ) = getString(Res.string.date_day_month_short, day.toString(), getStringArray(Res.array.months_short)[month - 1])

    override suspend fun sharingMovedToOtherPhone() = getString(Res.string.loc_sharing_moved)
}
