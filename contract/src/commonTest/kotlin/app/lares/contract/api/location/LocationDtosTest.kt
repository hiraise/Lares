package app.lares.contract.api.location

import app.lares.contract.api.LaresJson
import app.lares.contract.api.admin.UpdateHouseholdRequest
import app.lares.contract.entities.HouseholdData
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import app.lares.contract.entities.PlaceIcon
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LocationDtosTest {
    private fun <T> roundTrip(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val text = LaresJson.encodeToString(serializer, value)
        assertEquals(value, LaresJson.decodeFromString(serializer, text), text)
        return text
    }

    private val at = Instant.parse("2026-10-25T07:30:00Z")
    private val fix = PositionFix(at, 55.75, 37.61, 12.5f, 1.2f, 90f, 150.0, 80, charging = true)
    private val member =
        MemberLocation(
            memberId = "m1",
            level = VisibilityLevel.CURRENT,
            status = SharingStatus.PAUSED,
            pausedUntil = at,
            offReason = OffReason.LOCATION_DISABLED,
            statusSince = at,
            last = fix,
            placeId = "pl1",
            placeSince = at,
        )

    @Test
    fun `LOC-020 LOC-021 place and alert round trip with lowercase icon keys`() {
        val text = roundTrip(PlaceData.serializer(), PlaceData("Школа", PlaceIcon.SCHOOL, 55.7, 37.6, 150, "ул. Ленина, 1", "m1"))
        assertTrue("\"icon\":\"school\"" in text, text)
        roundTrip(PlaceAlertData.serializer(), PlaceAlertData("m1", "m2", setOf("pl1", "pl2")))
        val minimal =
            LaresJson.decodeFromString(
                PlaceData.serializer(),
                """{"name":"Дом","latitude":1.0,"longitude":2.0,"createdBy":"m1"}""",
            )
        assertEquals(PlaceIcon.PLACE, minimal.icon)
        assertEquals(100, minimal.radiusM)
        assertEquals(PlaceIcon.SPORT, PlaceIcon.fromKey("sport"))
    }

    @Test
    fun `LOC-003 LOC-012 every location DTO round trips`() {
        val fixText = roundTrip(PositionFix.serializer(), fix)
        assertTrue("\"recordedAt\":\"2026-10-25T07:30:00Z\"" in fixText, fixText)
        roundTrip(PositionFix.serializer(), PositionFix(at, 1.0, 2.0, 5f))
        roundTrip(UploadPositionsRequest.serializer(), UploadPositionsRequest(listOf(fix)))
        roundTrip(
            UploadPositionsResponse.serializer(),
            UploadPositionsResponse(3, sharingHere = true, paused = true, pausedUntil = at, refreshRequested = true),
        )
        val sharing =
            roundTrip(SetSharingRequest.serializer(), SetSharingRequest(SharingStatus.OFF_ON_PHONE, reason = OffReason.PERMISSION_REVOKED))
        assertTrue("\"status\":\"off_on_phone\"" in sharing && "\"reason\":\"permission_revoked\"" in sharing, sharing)
        val memberText = roundTrip(MemberLocation.serializer(), member)
        assertTrue("\"level\":\"current\"" in memberText && "\"offReason\":\"location_disabled\"" in memberText, memberText)
        roundTrip(LocationNow.serializer(), LocationNow(listOf(member), at))
        roundTrip(LocationHistory.serializer(), LocationHistory("m1", LocalDate(2026, 10, 25), listOf(TrackPoint(at, 1.0, 2.0, 10f))))
        roundTrip(LiveMessage.serializer(), LiveMessage(LiveMessage.SNAPSHOT, now = LocationNow(emptyList(), at)))
        roundTrip(LiveMessage.serializer(), LiveMessage(LiveMessage.MEMBER, member = member))
        roundTrip(LiveMessage.serializer(), LiveMessage(LiveMessage.GONE, memberId = "m1"))
        val rule = LocationRule("r1", RuleParty(PartyKind.GROUP, "g1"), RuleParty(PartyKind.EVERYONE), VisibilityLevel.HISTORY)
        val ruleText = roundTrip(LocationRules.serializer(), LocationRules(listOf(rule)))
        assertTrue("\"kind\":\"group\"" in ruleText && "\"kind\":\"everyone\"" in ruleText && "\"level\":\"history\"" in ruleText, ruleText)
        roundTrip(
            PutLocationRulesRequest.serializer(),
            PutLocationRulesRequest(listOf(LocationRuleInput(rule.subject, rule.target, rule.level))),
        )
        roundTrip(
            LocationWatchers.serializer(),
            LocationWatchers(listOf(Watcher("m2", VisibilityLevel.HISTORY)), listOf(AlertWatcher("m2", setOf("pl1")))),
        )
        roundTrip(LocationConfig.serializer(), LocationConfig("https://tiles/style.json", "https://tiles/dark.json", true, 30))
        roundTrip(AddressLabel.serializer(), AddressLabel("ул. Ленина, 1"))
        roundTrip(AddressLabel.serializer(), AddressLabel())
        roundTrip(AddressSearch.serializer(), AddressSearch(listOf(AddressResult("ул. Ленина, 1", 55.7, 37.6))))
    }

    @Test
    fun `LOC-006 LOC-012 unknown enum keys fall back`() {
        val member =
            LaresJson.decodeFromString(
                MemberLocation.serializer(),
                """{"memberId":"m1","level":"telepathy","status":"teleported","offReason":"aliens"}""",
            )
        assertEquals(VisibilityLevel.NONE, member.level)
        assertEquals(SharingStatus.NEVER, member.status)
        assertEquals(OffReason.STOPPED, member.offReason)
        val place =
            LaresJson.decodeFromString(
                PlaceData.serializer(),
                """{"name":"X","icon":"castle","latitude":1,"longitude":2,"createdBy":"m1"}""",
            )
        assertEquals(PlaceIcon.PLACE, place.icon)
        val party = LaresJson.decodeFromString(RuleParty.serializer(), """{"kind":"planet","id":"x"}""")
        assertEquals(PartyKind.UNKNOWN, party.kind)
    }

    @Test
    fun `LOC-005 household history days default to 30 and the update field is optional`() {
        val household =
            LaresJson.decodeFromString(
                HouseholdData.serializer(),
                """{"name":"H","timeZone":"UTC","currency":"RUB","defaultLanguage":"ru"}""",
            )
        assertEquals(30, household.locationHistoryDays)
        roundTrip(HouseholdData.serializer(), household.copy(locationHistoryDays = 7))
        assertEquals(null, LaresJson.decodeFromString(UpdateHouseholdRequest.serializer(), "{}").locationHistoryDays)
        roundTrip(UpdateHouseholdRequest.serializer(), UpdateHouseholdRequest(locationHistoryDays = 60))
    }
}
