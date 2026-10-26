package app.lares.client.ui.location

import app.lares.client.data.location.OLGA
import app.lares.client.data.location.SCHOOL
import app.lares.client.data.location.fixAt
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.AddressResult
import app.lares.contract.entities.PlaceIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The place editor's form (plan M4-09, LOC-020, LOC-024). */
class PlaceFormTest {
    @Test
    fun `LOC-020 name is required and limited, radius stays 50-1000 in steps of 10`() {
        val form = PlaceForm.new(55.8, 37.5)
        assertEquals(PlaceIcon.PLACE, form.icon)
        assertEquals(100, form.radiusM)
        assertFalse(form.canSave)
        assertEquals(FieldErrorCode.REQUIRED, form.withName("   ").validate()["name"])
        assertEquals(60, form.withName("x".repeat(80)).name.length)
        assertTrue(form.withName("Бассейн").validate().isEmpty())
        assertEquals(150, form.withRadius(147f).radiusM)
        assertEquals(50, form.withRadius(10f).radiusM)
        assertEquals(1000, form.withRadius(1500f).radiusM)
        assertEquals(FieldErrorCode.OUT_OF_RANGE, form.withName("A").copy(radiusM = 20).validate()["radiusM"])
    }

    @Test
    fun `LOC-024 a search result moves the centre and fills the address, a tap clears it`() {
        val form = PlaceForm.new(55.8, 37.5).withResult(AddressResult("Ленинградский проспект, 39", 55.79, 37.54))
        assertEquals(55.79 to 37.54, form.latitude to form.longitude)
        assertEquals("Ленинградский проспект, 39", form.address)
        val tapped = form.withCentre(55.791, 37.541)
        assertNull(tapped.address)
        assertEquals(form, form.withCentre(55.79, 37.54))
    }

    @Test
    fun `LOC-020 I am here takes the own fix`() {
        val fix = fixAt(Instant.parse("2026-10-21T10:00:00Z"), lat = 55.77, lon = 37.61)
        val form = PlaceForm.new(55.8, 37.5).withFix(fix)
        assertEquals(55.77 to 37.61, form.latitude to form.longitude)
    }

    @Test
    fun `LOC-020 only changed fields are applied over the stored place`() {
        val form = PlaceForm.of(SCHOOL).withRadius(200f)
        // Meanwhile another phone renamed the place.
        val stored = SCHOOL.copy(name = "Гимназия №12")
        val result = form.applyTo(stored)
        assertEquals("Гимназия №12", result.name)
        assertEquals(200, result.radiusM)
        assertEquals(SCHOOL.latitude, result.latitude)
        assertEquals(OLGA, result.createdBy)
        val moved = PlaceForm.of(SCHOOL).withCentre(55.81, 37.53).applyTo(stored)
        assertEquals(55.81 to 37.53, moved.latitude to moved.longitude)
        assertEquals("Гимназия №12", moved.name)
        assertEquals(
            PlaceForm.new(1.0, 2.0).withName("A").toData(OLGA),
            PlaceForm
                .new(1.0, 2.0)
                .withName("A")
                .applyTo(SCHOOL)
                .copy(createdBy = OLGA),
        )
    }
}
