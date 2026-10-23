package app.lares.server.household

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.UpdateHouseholdRequest
import app.lares.contract.api.auth.Me
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.testing.assertError
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.lastSeq
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals

/** FND-003: household settings. */
class HouseholdSettingsTest {
    @Test
    fun updatesGivenFieldsAndAllocatesSeq() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val seqBefore = deps.lastSeq(admin.householdUuid)
            val response =
                client.send(
                    HttpMethod.Patch,
                    "/api/v1/household",
                    admin.accessToken,
                    UpdateHouseholdRequest(name = "  Smith family ", currency = "EUR"),
                )
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("Smith family", client.send(HttpMethod.Get, "/api/v1/me", admin.accessToken).body<Me>().householdName)
            deps.db.dbQuery {
                val row = HouseholdTable.selectAll().single()
                assertEquals("EUR", row[HouseholdTable.currency])
                assertEquals("Europe/Moscow", row[HouseholdTable.timeZone])
                assertEquals(seqBefore + 1, row[HouseholdTable.seq])
            }
            // Nothing changed → nothing written.
            client.send(HttpMethod.Patch, "/api/v1/household", admin.accessToken, UpdateHouseholdRequest(currency = "EUR"))
            assertEquals(seqBefore + 1, deps.lastSeq(admin.householdUuid))
        }

    @Test
    fun invalidValuesAreRejectedWithFieldCodes() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val seqBefore = deps.lastSeq(admin.householdUuid)
            val response =
                client.send(
                    HttpMethod.Patch,
                    "/api/v1/household",
                    admin.accessToken,
                    UpdateHouseholdRequest(name = "", timeZone = "Mars/Olympus", currency = "rub", defaultLanguage = "de"),
                )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                mapOf(
                    "name" to FieldErrorCode.REQUIRED,
                    "timeZone" to FieldErrorCode.INVALID_FORMAT,
                    "currency" to FieldErrorCode.INVALID_FORMAT,
                    "defaultLanguage" to FieldErrorCode.INVALID_FORMAT,
                ),
                response.assertError(ErrorCode.VALIDATION_FAILED).error.fields,
            )
            assertEquals(seqBefore, deps.lastSeq(admin.householdUuid))
        }
}
