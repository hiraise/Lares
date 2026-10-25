package app.lares.server.notifications

import app.lares.contract.api.ApiErrorBody
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.server.testing.jsonClient
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings › Notifications on the server (FND-052, CAL-016): muted types and the birthday reminder lead time. */
class NotificationSettingsTest {
    private val path = "/api/v1/me/notification-settings"

    /** CAL-016: default 3 days, stored per member, only the offered choices, muted types kept by a days-only change. */
    @Test
    fun cal016BirthdayDaysBefore() =
        testApp { _ ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken

            suspend fun get() = client.send(HttpMethod.Get, path, token).body<NotificationSettings>()

            assertEquals(NotificationSettings(), get())
            assertEquals(3, get().birthdayDaysBefore)

            val muted = setOf(NotificationType.SYSTEM_TEST)
            client.send(HttpMethod.Put, path, token, NotificationSettings(muted)).also { assertEquals(HttpStatusCode.OK, it.status) }
            val saved = client.send(HttpMethod.Put, path, token, NotificationSettings(muted, 7)).body<NotificationSettings>()
            assertEquals(NotificationSettings(muted, 7), saved)
            assertEquals(NotificationSettings(muted, 7), get())

            val rejected = client.send(HttpMethod.Put, path, token, NotificationSettings(emptySet(), 5))
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            val error = rejected.body<ApiErrorBody>().error
            assertEquals(ErrorCode.VALIDATION_FAILED, error.code)
            assertEquals(mapOf("birthdayDaysBefore" to FieldErrorCode.OUT_OF_RANGE), error.fields)
            // Nothing changed by the rejected request.
            assertEquals(NotificationSettings(muted, 7), get())

            client.send(HttpMethod.Put, path, token, NotificationSettings(muted, 0))
            assertEquals(NotificationSettings(muted, 0), get())
        }
}
