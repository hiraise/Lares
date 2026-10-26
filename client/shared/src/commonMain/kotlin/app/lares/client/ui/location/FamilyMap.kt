package app.lares.client.ui.location

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// The map seam (ADR-0027, architecture/location.md § Map rendering): all map library code is in the platform actuals.

/** A member's pin: [color] is the personal color, drawn grey when [grey]; [accuracyM] only when the circle is shown. */
@Immutable
data class MapPin(
    val id: String,
    val lat: Double,
    val lon: Double,
    val color: Color,
    val initials: String,
    val grey: Boolean,
    val accuracyM: Double?,
)

@Immutable
data class MapPlace(
    val id: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val name: String,
    val home: Boolean,
)

@Immutable
data class MapStop(
    val id: String,
    val lat: Double,
    val lon: Double,
)

/** What the map draws; [styleUrl] null = no style known yet (offline first run). */
@Immutable
data class MapViewState(
    val styleUrl: String?,
    val pins: List<MapPin> = emptyList(),
    val places: List<MapPlace> = emptyList(),
    val track: List<Pair<Double, Double>> = emptyList(),
    val trackColor: Color? = null,
    val stops: List<MapStop> = emptyList(),
    val draftPlace: MapPlace? = null,
)

/** Where the camera should go. */
sealed interface CameraKind {
    /** Fit all pins (the places when there are no pins). */
    data object All : CameraKind

    data class Member(
        val id: String,
    ) : CameraKind

    data class Place(
        val id: String,
    ) : CameraKind

    data class Point(
        val lat: Double,
        val lon: Double,
        val zoom: Double,
    ) : CameraKind
}

/** A camera move; a new [serial] repeats the same move (the button pressed twice). */
@Immutable
data class MapCameraRequest(
    val kind: CameraKind,
    val serial: Int,
)

/** Zoom for a member or a place. */
const val MEMBER_ZOOM = 16.0

/**
 * The family map. [contentPadding] is the part of the map covered by other UI (the sheet, the top row): camera moves
 * keep their targets out of it.
 */
@Composable
expect fun FamilyMapView(
    state: MapViewState,
    camera: MapCameraRequest?,
    onMemberClick: (String) -> Unit,
    onPlaceClick: (String) -> Unit,
    onMapClick: (Double, Double) -> Unit,
    onMapLongClick: (Double, Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
)
