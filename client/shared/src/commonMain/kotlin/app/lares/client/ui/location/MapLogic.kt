package app.lares.client.ui.location

import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.Place
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.theme.tones
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PlaceIcon
import kotlin.math.round

/** The battery icon of a member row and card. */
enum class BatteryIcon { CHARGING, BAR_1, BAR_3, BAR_5, BAR_6, FULL }

/** [low] = shown in `error` (≤ 15 % and not charging). */
data class BatteryInfo(
    val percent: Int,
    val icon: BatteryIcon,
    val low: Boolean,
)

/** Pure parts of the Map screen (plan M4-08, LOC-003, LOC-013, LOC-014). */
object MapLogic {
    /** Accuracy circles are drawn from this accuracy on (smaller ones hide under the pin). */
    const val MIN_ACCURACY_CIRCLE_M = 30.0

    private const val LOW_BATTERY = 15
    private const val BATTERY_3_BAR = 45
    private const val BATTERY_5_BAR = 75
    private const val BATTERY_6_BAR = 94
    private const val ROUNDING = 10_000.0

    /**
     * A pin for every member with a position, in the personal color; grey when paused, off, never sharing (but with an
     * old position) or stale. Members without a position get no pin (they still have a row).
     */
    fun pins(
        members: List<MemberOnMap>,
        dark: Boolean,
    ): List<MapPin> {
        val names = members.map { it.name }
        return members.mapNotNull { member ->
            val last = member.last ?: return@mapNotNull null
            MapPin(
                id = member.memberId,
                lat = last.latitude,
                lon = last.longitude,
                color = member.color.tones(dark).base,
                initials = initialsFor(member.name, names),
                grey = grey(member),
                accuracyM = last.accuracyM.toDouble().takeIf { it >= MIN_ACCURACY_CIRCLE_M },
            )
        }
    }

    fun grey(member: MemberOnMap): Boolean = member.status != SharingStatus.SHARING || member.stale

    fun places(places: List<Place>): List<MapPlace> =
        places.map {
            MapPlace(
                id = it.id,
                lat = it.data.latitude,
                lon = it.data.longitude,
                radiusM = it.data.radiusM.toDouble(),
                name = it.data.name,
                home = it.data.icon == PlaceIcon.HOME,
            )
        }

    /** The sheet's member rows: everybody but the viewer (the sharing row stands for them), by name as given. */
    fun rows(
        members: List<MemberOnMap>,
        me: String?,
    ): List<MemberOnMap> = members.filter { it.memberId != me }

    /** The status line shows a street address: sharing, a fresh position outside every place, not moving (LOC-014). */
    fun needsAddress(member: MemberOnMap): Boolean =
        member.status == SharingStatus.SHARING &&
            member.last != null &&
            !member.stale &&
            member.place == null &&
            !member.moving

    /** Addresses are asked once per position rounded to 4 decimals (about 10 m), as the server caches them. */
    fun addressKey(fix: PositionFix): Pair<Double, Double> = round4(fix.latitude) to round4(fix.longitude)

    fun battery(fix: PositionFix?): BatteryInfo? {
        val percent = fix?.battery ?: return null
        val charging = fix.charging == true
        val icon =
            when {
                charging -> BatteryIcon.CHARGING
                percent <= LOW_BATTERY -> BatteryIcon.BAR_1
                percent <= BATTERY_3_BAR -> BatteryIcon.BAR_3
                percent <= BATTERY_5_BAR -> BatteryIcon.BAR_5
                percent <= BATTERY_6_BAR -> BatteryIcon.BAR_6
                else -> BatteryIcon.FULL
            }
        return BatteryInfo(percent, icon, low = percent <= LOW_BATTERY && !charging)
    }

    /** «Обновить» (LOC-011): for others the viewer sees who share now. */
    fun canRefresh(
        member: MemberOnMap,
        me: String?,
    ): Boolean =
        member.memberId != me &&
            member.level != VisibilityLevel.NONE &&
            member.status == SharingStatus.SHARING

    /** Where the map opens: the asked member or place when it is on the map, else everybody. */
    fun initialCamera(
        memberId: String?,
        placeId: String?,
        members: List<MemberOnMap>,
        places: List<Place>,
    ): CameraKind =
        when {
            memberId != null && members.any { it.memberId == memberId && it.last != null } -> CameraKind.Member(memberId)
            placeId != null && places.any { it.id == placeId } -> CameraKind.Place(placeId)
            else -> CameraKind.All
        }

    /** The `my_location` button: «Показать всех» first; when everybody is shown, «Моё местоположение». */
    fun nextLocate(
        last: CameraKind?,
        me: String?,
        members: List<MemberOnMap>,
    ): CameraKind {
        val mine = me != null && members.any { it.memberId == me && it.last != null }
        return if (last == CameraKind.All && mine) CameraKind.Member(me) else CameraKind.All
    }

    private fun round4(value: Double): Double = round(value * ROUNDING) / ROUNDING
}
