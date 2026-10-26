package app.lares.contract.validation

import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.PartyKind
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.PutLocationRulesRequest
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.SetSharingRequest
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ValidatorsLocationTest {
    private val place = PlaceData("Школа", latitude = 55.7, longitude = 37.6, createdBy = "m1")
    private val now = Instant.parse("2026-10-25T10:00:00Z")
    private val fix = PositionFix(now, 55.7, 37.6, 10f)

    @Test
    fun `LOC-020 place fields`() {
        assertEquals(emptyMap(), Validators.place(place))
        assertEquals(mapOf("name" to FieldErrorCode.REQUIRED), Validators.place(place.copy(name = "  ")))
        assertEquals(mapOf("name" to FieldErrorCode.TOO_LONG), Validators.place(place.copy(name = "a".repeat(61))))
        assertEquals(emptyMap(), Validators.place(place.copy(name = "a".repeat(60), address = "b".repeat(200))))
        assertEquals(mapOf("address" to FieldErrorCode.TOO_LONG), Validators.place(place.copy(address = "b".repeat(201))))
        assertEquals(
            mapOf("latitude" to FieldErrorCode.OUT_OF_RANGE, "longitude" to FieldErrorCode.OUT_OF_RANGE),
            Validators.place(place.copy(latitude = 90.1, longitude = -180.1)),
        )
        assertEquals(mapOf("latitude" to FieldErrorCode.OUT_OF_RANGE), Validators.place(place.copy(latitude = Double.NaN)))
        for (r in listOf(50, 1000)) assertEquals(emptyMap(), Validators.place(place.copy(radiusM = r)))
        for (r in listOf(49, 1001)) assertEquals(mapOf("radiusM" to FieldErrorCode.OUT_OF_RANGE), Validators.place(place.copy(radiusM = r)))
    }

    @Test
    fun `LOC-020 normalize place trims name and address`() {
        assertEquals(
            place.copy(name = "Дом", address = "ул. Ленина"),
            Validators.normalizePlace(place.copy(name = " Дом ", address = " ул. Ленина ")),
        )
        assertNull(Validators.normalizePlace(place.copy(address = "   ")).address)
    }

    @Test
    fun `LOC-021 alert fields`() {
        assertEquals(emptyMap(), Validators.placeAlert(PlaceAlertData("m1", "m2", (1..50).map { "p$it" }.toSet())))
        assertEquals(
            mapOf("placeIds" to FieldErrorCode.OUT_OF_RANGE),
            Validators.placeAlert(
                PlaceAlertData(
                    "m1",
                    "m2",
                    (1..51)
                        .map {
                            "p$it"
                        }.toSet(),
                ),
            ),
        )
        assertEquals(mapOf("memberId" to FieldErrorCode.INVALID_FORMAT), Validators.placeAlert(PlaceAlertData("m1", "m1")))
    }

    @Test
    fun `LOC-003 position fix ranges`() {
        assertEquals(emptyMap(), Validators.positionFix(fix))
        assertEquals(emptyMap(), Validators.positionFix(fix.copy(accuracyM = 10_000f, speedMps = 100f, bearingDeg = 360f, battery = 0)))
        val bad = fix.copy(latitude = -91.0, longitude = 181.0, accuracyM = -1f, speedMps = 101f, bearingDeg = 361f, battery = 101)
        assertEquals(
            listOf("latitude", "longitude", "accuracyM", "speedMps", "bearingDeg", "battery").associateWith { FieldErrorCode.OUT_OF_RANGE },
            Validators.positionFix(bad),
        )
        assertEquals(mapOf("accuracyM" to FieldErrorCode.OUT_OF_RANGE), Validators.positionFix(fix.copy(accuracyM = 10_001f)))
    }

    @Test
    fun `LOC-012 set sharing request`() {
        assertEquals(emptyMap(), Validators.setSharing(SetSharingRequest(SharingStatus.SHARING), now))
        assertEquals(emptyMap(), Validators.setSharing(SetSharingRequest(SharingStatus.PAUSED), now))
        assertEquals(emptyMap(), Validators.setSharing(SetSharingRequest(SharingStatus.PAUSED, now + 7.days), now))
        assertEquals(emptyMap(), Validators.setSharing(SetSharingRequest(SharingStatus.OFF_ON_PHONE, reason = OffReason.STOPPED), now))
        assertEquals(mapOf("status" to FieldErrorCode.INVALID_FORMAT), Validators.setSharing(SetSharingRequest(SharingStatus.NEVER), now))
        for (until in listOf(now, now - 1.minutes, now + 7.days + 1.minutes)) {
            assertEquals(
                mapOf("pausedUntil" to FieldErrorCode.OUT_OF_RANGE),
                Validators.setSharing(SetSharingRequest(SharingStatus.PAUSED, until), now),
            )
        }
        assertEquals(
            mapOf("pausedUntil" to FieldErrorCode.INVALID_FORMAT),
            Validators.setSharing(SetSharingRequest(SharingStatus.SHARING, now + 1.days), now),
        )
        assertEquals(mapOf("reason" to FieldErrorCode.REQUIRED), Validators.setSharing(SetSharingRequest(SharingStatus.OFF_ON_PHONE), now))
        assertEquals(
            mapOf("reason" to FieldErrorCode.INVALID_FORMAT),
            Validators.setSharing(SetSharingRequest(SharingStatus.PAUSED, reason = OffReason.STOPPED), now),
        )
    }

    @Test
    fun `LOC-006 visibility rules shape`() {
        val good = LocationRuleInput(RuleParty(PartyKind.GROUP, "g1"), RuleParty(PartyKind.EVERYONE), VisibilityLevel.CURRENT)
        assertEquals(emptyMap(), Validators.locationRules(PutLocationRulesRequest(List(100) { good })))
        assertEquals(mapOf("rules" to FieldErrorCode.OUT_OF_RANGE), Validators.locationRules(PutLocationRulesRequest(List(101) { good })))
        val bad =
            listOf(
                good,
                good.copy(subject = RuleParty(PartyKind.EVERYONE, "g1")),
                good.copy(target = RuleParty(PartyKind.MEMBER)),
                good.copy(subject = RuleParty(PartyKind.UNKNOWN, "x"), target = RuleParty(PartyKind.GROUP, "")),
                good.copy(level = VisibilityLevel.NONE),
            )
        assertEquals(
            mapOf(
                "rules.1.subject" to FieldErrorCode.INVALID_FORMAT,
                "rules.2.target" to FieldErrorCode.INVALID_FORMAT,
                "rules.3.subject" to FieldErrorCode.INVALID_FORMAT,
                "rules.3.target" to FieldErrorCode.INVALID_FORMAT,
                "rules.4.level" to FieldErrorCode.INVALID_FORMAT,
            ),
            Validators.locationRules(PutLocationRulesRequest(bad)),
        )
    }

    @Test
    fun `LOC-005 history days are one of the choices`() {
        for (d in listOf(7, 14, 30, 60, 90)) assertNull(Validators.locationHistoryDays(d))
        for (d in listOf(0, 1, 31, 365)) assertEquals(FieldErrorCode.OUT_OF_RANGE, Validators.locationHistoryDays(d))
    }
}
