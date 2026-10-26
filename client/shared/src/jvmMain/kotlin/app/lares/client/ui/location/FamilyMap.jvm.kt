package app.lares.client.ui.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** JVM stand-in (ADR-0027): pins and places as clickable texts, so UI tests run without the native map. */
@Composable
actual fun FamilyMapView(
    state: MapViewState,
    camera: MapCameraRequest?,
    onMemberClick: (String) -> Unit,
    onPlaceClick: (String) -> Unit,
    onMapClick: (Double, Double) -> Unit,
    onMapLongClick: (Double, Double) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    Column(modifier.padding(contentPadding)) {
        state.pins.forEach { pin ->
            Text(
                "${pin.initials}${if (pin.grey) " (grey)" else ""}",
                Modifier.testTag("pin_${pin.id}").clickable { onMemberClick(pin.id) },
            )
        }
        state.places.forEach { place ->
            Text(place.name, Modifier.testTag("place_${place.id}").clickable { onPlaceClick(place.id) })
        }
        camera?.let { Text(it.kind.toString(), Modifier.testTag("camera")) }
    }
}
