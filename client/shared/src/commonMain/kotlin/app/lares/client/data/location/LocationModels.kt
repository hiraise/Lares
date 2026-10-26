package app.lares.client.data.location

import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.TrackPoint
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import app.lares.contract.location.Stop
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

// What the location core exposes to the UI (architecture/location.md § Core).

data class Place(
    val id: String,
    val data: PlaceData,
)

/** The logged-in member's own arrive / leave alerts about [PlaceAlertData.memberId]. */
data class PlaceAlert(
    val id: String,
    val data: PlaceAlertData,
)

/** How the map gets its data: the live socket, polling `GET /now` while the socket is down, or the cached snapshot. */
enum class Connection { LIVE, POLLING, OFFLINE }

/** This phone's sharing as the Map row and Settings › Геопозиция show it. */
sealed interface MySharing {
    data object Off : MySharing

    data object Sharing : MySharing

    /** [until] null = «Пока не включу». */
    data class Paused(
        val until: Instant?,
    ) : MySharing

    /** Sharing is enabled here but the phone lacks location access or has location turned off. */
    data object NeedsPermission : MySharing

    /** Sharing, but nothing could be uploaded for an hour while fixes are queued (LOC-008). */
    data object StaleUploads : MySharing
}

/** A member the viewer may see, joined with name, color and place. */
data class MemberOnMap(
    val memberId: String,
    val personId: String?,
    val name: String,
    val color: PaletteColor,
    val level: VisibilityLevel,
    val status: SharingStatus,
    val pausedUntil: Instant?,
    val offReason: OffReason?,
    val last: PositionFix?,
    val place: Place?,
    val placeSince: Instant?,
    /** Sharing, but the newest fix is older than 30 minutes (LOC-013). */
    val stale: Boolean,
    /** The newest fix is fresher than 5 minutes and reports at least 2 m/s. */
    val moving: Boolean,
)

data class MapState(
    val members: List<MemberOnMap>,
    val places: List<Place>,
    val me: MySharing,
    val connection: Connection,
    /** Server time of the data (or when the cached snapshot was fetched); null before anything arrived. */
    val updatedAt: Instant?,
)

/** One member's day: the thinned track and the stops found in it (LOC-004). */
data class DayHistory(
    val memberId: String,
    val date: LocalDate,
    val points: List<TrackPoint>,
    val stops: List<Stop>,
)
