package app.lares.client.ui.location

import app.lares.client.ui.common.FieldError
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.AddressResult
import app.lares.contract.api.location.PositionFix
import app.lares.contract.entities.PlaceData
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.location.LocationLimits
import app.lares.contract.validation.Validators
import kotlin.math.roundToInt

/**
 * The place editor's form (plan M4-09, LOC-020, LOC-024). [original] is the stored place (null = new). The address is
 * set only by a search result and cleared when the centre moves another way, so it never names a different spot.
 */
data class PlaceForm(
    val name: String = "",
    val icon: PlaceIcon = PlaceIcon.PLACE,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusM: Int = LocationLimits.DEFAULT_RADIUS_M,
    val address: String? = null,
    val original: PlaceData? = null,
) {
    fun withName(value: String) = copy(name = value.take(LocationLimits.PLACE_NAME_MAX))

    fun withIcon(value: PlaceIcon) = copy(icon = value)

    /** A tap on the map or «Я здесь». */
    fun withCentre(
        lat: Double,
        lon: Double,
    ) = if (lat == latitude && lon == longitude) this else copy(latitude = lat, longitude = lon, address = null)

    /** «Я здесь»: the newest own fix. */
    fun withFix(fix: PositionFix) = withCentre(fix.latitude, fix.longitude)

    /** A picked search result: the centre and the address. */
    fun withResult(result: AddressResult) =
        copy(latitude = result.latitude, longitude = result.longitude, address = result.label.take(LocationLimits.PLACE_ADDRESS_MAX))

    /** The slider: 50–1000 m in steps of 10. */
    fun withRadius(value: Float) = copy(radiusM = radius(value))

    fun toData(createdBy: String = original?.createdBy.orEmpty()) =
        PlaceData(
            name = name.trim(),
            icon = icon,
            latitude = latitude,
            longitude = longitude,
            radiusM = radiusM,
            address = address?.trim()?.ifEmpty { null },
            createdBy = createdBy,
        )

    /**
     * [base] (the place as stored now) with only the fields this form changed compared to [original], so a change made
     * meanwhile on another phone survives. The centre (latitude, longitude, address) moves together.
     */
    fun applyTo(base: PlaceData): PlaceData {
        val before = original ?: return toData(base.createdBy)
        val after = toData(before.createdBy)

        fun <T> pick(field: (PlaceData) -> T): T = if (field(after) != field(before)) field(after) else field(base)
        val moved = after.latitude != before.latitude || after.longitude != before.longitude || after.address != before.address
        return base.copy(
            name = pick { it.name },
            icon = pick { it.icon },
            radiusM = pick { it.radiusM },
            latitude = if (moved) after.latitude else base.latitude,
            longitude = if (moved) after.longitude else base.longitude,
            address = if (moved) after.address else base.address,
        )
    }

    /** The same rules as the server (`Validators.place`). */
    fun validate(): Map<String, FieldErrorCode> = Validators.place(toData("x"))

    val canSave: Boolean get() = name.isNotBlank()

    companion object {
        const val RADIUS_STEP = 10

        /** Fields shown next to their inputs; other errors appear under the form. */
        val FIELDS = setOf("name")

        /** The icon chips in the editor, in order. */
        val ICONS = listOf(PlaceIcon.HOME, PlaceIcon.SCHOOL, PlaceIcon.WORK, PlaceIcon.SPORT, PlaceIcon.SHOP, PlaceIcon.PLACE)

        fun radius(value: Float): Int =
            ((value / RADIUS_STEP).roundToInt() * RADIUS_STEP).coerceIn(LocationLimits.MIN_RADIUS_M, LocationLimits.MAX_RADIUS_M)

        fun of(data: PlaceData) = PlaceForm(data.name, data.icon, data.latitude, data.longitude, data.radiusM, data.address, data)

        fun new(
            lat: Double,
            lon: Double,
        ) = PlaceForm(latitude = lat, longitude = lon)

        fun fieldErrors(errors: Map<String, FieldErrorCode>): Map<String, FieldError> =
            errors.filterKeys { it in FIELDS }.mapNotNull { (field, code) -> FieldError.of(code)?.let { field to it } }.toMap()
    }
}
