package app.familyhub.server.platform.http

import app.familyhub.contract.api.InstanceInfo
import app.familyhub.contract.api.ProtocolRange
import app.familyhub.server.testing.insertHousehold
import app.familyhub.server.testing.jsonClient
import app.familyhub.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** FND-001: instance info is public and needs no protocol header. */
class InstanceTest {
    @Test
    fun emptyDatabaseIsNotInitialized() =
        testApp {
            val response = jsonClient(protocol = null).get("/api/v1/instance")
            assertEquals(HttpStatusCode.OK, response.status)
            val info = response.body<InstanceInfo>()
            assertEquals(false, info.initialized)
            assertEquals("0.1.0", info.serverVersion)
            assertEquals(ProtocolRange(min = 1, max = 1), info.protocol)
        }

    @Test
    fun existingHouseholdMeansInitialized() =
        testApp { deps ->
            deps.db.insertHousehold()
            val info = jsonClient().get("/api/v1/instance").body<InstanceInfo>()
            assertEquals(true, info.initialized)
        }
}
