package app.lares.client.ui.location

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lares.contract.location.Geo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** OpenFreeMap glyphs; the library's default fonts are not on its glyph server. */
private val LABEL_FONT = listOf("Noto Sans Bold")

/**
 * The map with maplibre-compose (ADR-0027, architecture/location.md § Map rendering): the server's style, places as
 * translucent circles with a dashed outline and a label under them, accuracy circles, pins with initials, a track and
 * stops. Everything is a GeoJSON layer of our own; the library's location indicator is not used.
 */
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
    onCenterChange: (Double, Double) -> Unit,
) {
    val styleUrl = state.styleUrl ?: return
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val secondary = scheme.secondary
    val grey = scheme.outline
    val memberClick by rememberUpdatedState(onMemberClick)
    val placeClick by rememberUpdatedState(onPlaceClick)
    val mapClick by rememberUpdatedState(onMapClick)
    val mapLongClick by rememberUpdatedState(onMapLongClick)
    val centerChange by rememberUpdatedState(onCenterChange)

    val placeAreas =
        remember(state.places, state.draftPlace, primary, secondary) {
            placeAreas(state.places + listOfNotNull(state.draftPlace), primary, secondary)
        }
    val placeLabels = remember(state.places, primary, secondary) { placeLabels(state.places, primary, secondary) }
    val accuracy = remember(state.pins, grey) { accuracyAreas(state.pins, grey) }
    val pins = remember(state.pins, grey) { pinPoints(state.pins, grey) }
    val track = remember(state.track) { trackLine(state.track) }
    val stops = remember(state.stops) { stopPoints(state.stops) }
    val trackColor = state.trackColor ?: primary

    val onPlace: FeaturesClickHandler = { features ->
        features.firstNotNullOfOrNull { it.properties.id() }?.let { placeClick(it) }
        ClickResult.Consume
    }

    val pinsNow by rememberUpdatedState(state.pins)
    val first = state.pins.firstOrNull()
    val mapState =
        rememberMapState(
            baseStyle = BaseStyle.Uri(styleUrl),
            initialCameraPosition =
                if (first != null) {
                    CameraPosition(center = Position(longitude = first.lon, latitude = first.lat), zoom = 13.0)
                } else {
                    CameraPosition()
                },
        ) {
            val placeAreaSource = rememberGeoJsonSource(GeoJsonData.JsonString(placeAreas))
            val placeLabelSource = rememberGeoJsonSource(GeoJsonData.JsonString(placeLabels))
            val accuracySource = rememberGeoJsonSource(GeoJsonData.JsonString(accuracy))
            val trackSource = rememberGeoJsonSource(GeoJsonData.JsonString(track))
            val stopSource = rememberGeoJsonSource(GeoJsonData.JsonString(stops))
            val pinSource = rememberGeoJsonSource(GeoJsonData.JsonString(pins))
            // Text gets its own source: when glyphs fail to load, MapLibre drops the whole tile of the source.
            val initialsSource = rememberGeoJsonSource(GeoJsonData.JsonString(pins))
            val color = feature["color"].convertToColor()

            FillLayer(id = "lares-place-fill", source = placeAreaSource, color = color, opacity = const(0.10f), onClick = onPlace)
            LineLayer(
                id = "lares-place-outline",
                source = placeAreaSource,
                color = color,
                opacity = const(0.6f),
                width = const(1.5.dp),
                dasharray = const(listOf(3, 2)),
            )
            SymbolLayer(
                id = "lares-place-label",
                source = placeLabelSource,
                textField = format(span(feature["name"].asString())),
                textFont = const(LABEL_FONT),
                textSize = const(12.sp),
                textColor = color,
                textHaloColor = const(Color.White.copy(alpha = 0.85f)),
                textHaloWidth = const(2.dp),
                textAnchor = const(SymbolAnchor.Top),
                onClick = onPlace,
            )
            FillLayer(id = "lares-accuracy", source = accuracySource, color = color, opacity = const(0.12f))
            LineLayer(
                id = "lares-track",
                source = trackSource,
                color = const(trackColor),
                width = const(4.dp),
                cap = const(org.maplibre.compose.expressions.value.LineCap.Round),
                join = const(org.maplibre.compose.expressions.value.LineJoin.Round),
            )
            CircleLayer(
                id = "lares-stops",
                source = stopSource,
                radius = const(6.dp),
                color = const(Color.White),
                strokeColor = const(trackColor),
                strokeWidth = const(2.dp),
            )
            CircleLayer(
                id = "lares-pins",
                source = pinSource,
                radius = const(21.dp),
                color = color,
                strokeColor = const(Color.White),
                strokeWidth = const(3.dp),
            )
            SymbolLayer(
                id = "lares-pin-initials",
                source = initialsSource,
                textField = format(span(feature["initials"].asString())),
                textFont = const(LABEL_FONT),
                textSize = const(16.sp),
                textColor = const(Color.White),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
            )
        }

    LaunchedEffect(camera) {
        val request = camera ?: return@LaunchedEffect
        val target = target(request.kind, state)
        when (target) {
            is CameraTarget.Center -> {
                mapState.animateCamera(CameraUpdate(center = Position(longitude = target.lon, latitude = target.lat), zoom = target.zoom))
            }

            is CameraTarget.Bounds -> {
                val pad = 48.dp
                mapState.animateCameraToBounds(
                    BoundingBox(west = target.west, south = target.south, east = target.east, north = target.north),
                    CameraFit(fitPadding = DpPadding(pad, pad, pad, pad)),
                )
            }

            null -> {}
        }
    }

    LaunchedEffect(mapState) {
        snapshotFlow { if (mapState.isCameraMoving) null else mapState.cameraPosition.center }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { centerChange(it.latitude, it.longitude) }
    }

    MaplibreMap(
        modifier = modifier,
        state = mapState,
        viewportInsets = contentPadding,
        interactions =
            MapInteractions {
                callbacks {
                    click {
                        // Pins are hit-tested here, on screen positions: more reliable than the rendered-feature query.
                        onEvent { event ->
                            val hit =
                                pinsNow
                                    .mapNotNull { pin ->
                                        val at =
                                            mapState.screenLocationFromPosition(Position(longitude = pin.lon, latitude = pin.lat))
                                                ?: return@mapNotNull null
                                        val dx = (at.x - event.screenOffset.x).value
                                        val dy = (at.y - event.screenOffset.y).value
                                        (dx * dx + dy * dy).takeIf { it <= PIN_HIT_DP * PIN_HIT_DP }?.let { pin.id to it }
                                    }.minByOrNull { it.second }
                            if (hit != null) {
                                memberClick(hit.first)
                                ClickResult.Consume
                            } else {
                                ClickResult.Pass
                            }
                        }
                        onUnhandled { event ->
                            event.position?.let { mapClick(it.latitude, it.longitude) }
                            ClickResult.Pass
                        }
                    }
                    longClick {
                        onEvent { event ->
                            val position = event.position ?: return@onEvent ClickResult.Pass
                            mapLongClick(position.latitude, position.longitude)
                            ClickResult.Consume
                        }
                    }
                }
            },
    )
}

private sealed interface CameraTarget {
    data class Center(
        val lat: Double,
        val lon: Double,
        val zoom: Double,
    ) : CameraTarget

    data class Bounds(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
    ) : CameraTarget
}

/** A single point is centred (a box of one point has no zoom); several are fitted. */
private fun target(
    kind: CameraKind,
    state: MapViewState,
): CameraTarget? =
    when (kind) {
        is CameraKind.Member -> {
            state.pins.firstOrNull { it.id == kind.id }?.let { CameraTarget.Center(it.lat, it.lon, MEMBER_ZOOM) }
        }

        is CameraKind.Place -> {
            state.places.firstOrNull { it.id == kind.id }?.let { CameraTarget.Center(it.lat, it.lon, MEMBER_ZOOM) }
        }

        is CameraKind.Point -> {
            CameraTarget.Center(kind.lat, kind.lon, kind.zoom)
        }

        is CameraKind.Bounds -> {
            CameraTarget.Bounds(west = kind.west, south = kind.south, east = kind.east, north = kind.north)
        }

        CameraKind.All -> {
            val points = state.pins.map { it.lat to it.lon }.ifEmpty { state.places.map { it.lat to it.lon } }
            when (points.size) {
                0 -> {
                    null
                }

                1 -> {
                    CameraTarget.Center(points[0].first, points[0].second, MEMBER_ZOOM - 1)
                }

                else -> {
                    CameraTarget.Bounds(
                        west = points.minOf { it.second },
                        south = points.minOf { it.first },
                        east = points.maxOf { it.second },
                        north = points.maxOf { it.first },
                    )
                }
            }
        }
    }

private fun JsonObject?.id(): String? = this?.get("id")?.jsonPrimitive?.contentOrNull

private fun Color.hex(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')

private fun placeColor(
    place: MapPlace,
    primary: Color,
    secondary: Color,
) = if (place.home) primary else secondary

private fun collection(features: List<JsonObject>): String =
    buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()

private fun geoFeature(
    geometry: JsonObject,
    vararg properties: Pair<String, String>,
): JsonObject =
    buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", JsonObject(properties.associate { (k, v) -> k to JsonPrimitive(v) }))
    }

private fun point(
    lat: Double,
    lon: Double,
): JsonObject =
    buildJsonObject {
        put("type", "Point")
        put(
            "coordinates",
            buildJsonArray {
                add(JsonPrimitive(lon))
                add(JsonPrimitive(lat))
            },
        )
    }

private fun polygon(ring: List<Pair<Double, Double>>): JsonObject =
    buildJsonObject {
        put("type", "Polygon")
        put(
            "coordinates",
            buildJsonArray {
                add(
                    buildJsonArray {
                        ring.forEach { (lat, lon) ->
                            add(
                                buildJsonArray {
                                    add(JsonPrimitive(lon))
                                    add(JsonPrimitive(lat))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

private fun placeAreas(
    places: List<MapPlace>,
    primary: Color,
    secondary: Color,
): String =
    collection(
        places.map {
            geoFeature(polygon(Geo.circle(it.lat, it.lon, it.radiusM)), "id" to it.id, "color" to placeColor(it, primary, secondary).hex())
        },
    )

/** The label sits under the circle, at its southern edge. */
private fun placeLabels(
    places: List<MapPlace>,
    primary: Color,
    secondary: Color,
): String =
    collection(
        places.map {
            val south = it.lat - it.radiusM / METERS_PER_DEGREE
            geoFeature(point(south, it.lon), "id" to it.id, "name" to it.name, "color" to placeColor(it, primary, secondary).hex())
        },
    )

private fun pinColor(
    pin: MapPin,
    grey: Color,
) = (if (pin.grey) grey else pin.color).hex()

private fun accuracyAreas(
    pins: List<MapPin>,
    grey: Color,
): String =
    collection(
        pins.mapNotNull { pin ->
            pin.accuracyM?.let { geoFeature(polygon(Geo.circle(pin.lat, pin.lon, it)), "id" to pin.id, "color" to pinColor(pin, grey)) }
        },
    )

private fun pinPoints(
    pins: List<MapPin>,
    grey: Color,
): String =
    collection(
        pins.map {
            geoFeature(point(it.lat, it.lon), "id" to it.id, "initials" to it.initials, "color" to pinColor(it, grey))
        },
    )

private fun trackLine(track: List<Pair<Double, Double>>): String =
    collection(
        if (track.size < 2) {
            emptyList()
        } else {
            listOf(
                geoFeature(
                    buildJsonObject {
                        put("type", "LineString")
                        put(
                            "coordinates",
                            buildJsonArray {
                                track.forEach { (lat, lon) ->
                                    add(
                                        buildJsonArray {
                                            add(JsonPrimitive(lon))
                                            add(JsonPrimitive(lat))
                                        },
                                    )
                                }
                            },
                        )
                    },
                ),
            )
        },
    )

private fun stopPoints(stops: List<MapStop>): String = collection(stops.map { geoFeature(point(it.lat, it.lon), "id" to it.id) })

private const val METERS_PER_DEGREE = 111_320.0

/** Pin radius 21 dp + border + a little slack. */
private const val PIN_HIT_DP = 28f
