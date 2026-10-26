package app.lares.contract.entities

import app.lares.contract.serialization.LenientEnumSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Sync data of the location module (architecture/location.md § Sync data). Positions are not sync data (ADR-0024).

/** A family place (LOC-020): a circle on the map, family data visible with `location.view`. */
@Serializable
data class PlaceData(
    /** Trimmed, 1..60. */
    val name: String,
    val icon: PlaceIcon = PlaceIcon.PLACE,
    /** -90..90. */
    val latitude: Double,
    /** -180..180. */
    val longitude: Double,
    /** 50..1000. */
    val radiusM: Int = 100,
    /** Display only (from the address search); trimmed, blank -> null, ≤ 200. */
    val address: String? = null,
    /** Member id; set on create, never changes. */
    val createdBy: String,
)

/** Unknown icons from a newer version decode to [PLACE]. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = PlaceIconSerializer::class)
enum class PlaceIcon {
    @SerialName("home")
    HOME,

    @SerialName("school")
    SCHOOL,

    @SerialName("work")
    WORK,

    @SerialName("sport")
    SPORT,

    @SerialName("shop")
    SHOP,

    @SerialName("place")
    PLACE,
    ;

    /** The wire name, e.g. `school`. */
    val key: String get() = PlaceIconSerializer.serialName(this)

    companion object {
        /** Returns the icon with this wire name, or `null` if this version does not know it. */
        fun fromKey(key: String): PlaceIcon? = PlaceIconSerializer.decodeOrNull(key)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object PlaceIconSerializer :
    LenientEnumSerializer<PlaceIcon>(PlaceIcon.generatedSerializer(), PlaceIcon.entries, PlaceIcon.PLACE)

/**
 * The subscriber's arrive / leave alerts for one member (LOC-021): a row seen only by its subscriber (ADR-0023 pattern).
 * The id is `Ids.placeAlertRowId(memberId, subscriberId)`. Clients never delete these rows; empty [placeIds] = no alerts.
 */
@Serializable
data class PlaceAlertData(
    /** The watched member; never changes. */
    val memberId: String,
    /** The writer; never changes; differs from [memberId]. */
    val subscriberId: String,
    /** ≤ 50; arrive and leave together. */
    val placeIds: Set<String> = emptySet(),
)
