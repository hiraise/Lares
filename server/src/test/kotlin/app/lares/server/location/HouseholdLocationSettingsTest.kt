package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.UpdateHouseholdRequest
import app.lares.contract.entities.HouseholdData
import app.lares.contract.sync.EntityTypes
import app.lares.server.testing.assertError
import app.lares.server.testing.dataAs
import app.lares.server.testing.jsonClient
import app.lares.server.testing.pullAll
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** LOC-005: days of location history kept, part of the synced household row. */
class HouseholdLocationSettingsTest {
    @Test
    fun loc005HistoryDaysAreSyncedAndLimitedToTheChoices() =
        testApp { _ ->
            val client = jsonClient()
            val admin = client.setup()

            suspend fun historyDays() =
                client
                    .pullAll(admin.accessToken)
                    .second
                    .last { it.type == EntityTypes.HOUSEHOLD }
                    .dataAs<HouseholdData>()
                    .locationHistoryDays
            assertEquals(30, historyDays())

            val ok = client.send(HttpMethod.Patch, "/api/v1/household", admin.accessToken, UpdateHouseholdRequest(locationHistoryDays = 14))
            assertEquals(HttpStatusCode.NoContent, ok.status)
            assertEquals(14, historyDays())

            val bad =
                client.send(
                    HttpMethod.Patch,
                    "/api/v1/household",
                    admin.accessToken,
                    UpdateHouseholdRequest(locationHistoryDays = 45),
                )
            assertEquals(
                mapOf("locationHistoryDays" to FieldErrorCode.OUT_OF_RANGE),
                bad.assertError(ErrorCode.VALIDATION_FAILED).error.fields,
            )
            assertEquals(14, historyDays())
        }
}
