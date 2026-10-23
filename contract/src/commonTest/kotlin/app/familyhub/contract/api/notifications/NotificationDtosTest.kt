package app.familyhub.contract.api.notifications

import app.familyhub.contract.api.FamilyHubJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationDtosTest {
    @Test
    fun payloadRoundTripsWithStableTypeNames() {
        val payload = PushPayload(PushPayload.KIND_NOTIFICATION, type = NotificationType.SYSTEM_TEST, title = "T", body = "B")
        val text = FamilyHubJson.encodeToString(PushPayload.serializer(), payload)
        assertTrue("\"type\":\"system.test\"" in text, text)
        assertEquals(payload, FamilyHubJson.decodeFromString(PushPayload.serializer(), text))
    }

    @Test
    fun unknownTypeInPayloadDecodesToNull() {
        val payload =
            FamilyHubJson.decodeFromString(
                PushPayload.serializer(),
                """{"kind":"notification","type":"future.thing","title":"x"}""",
            )
        assertNull(payload.type)
        assertEquals("x", payload.title)
    }

    @Test
    fun syncPayloadWithoutType() {
        val payload = FamilyHubJson.decodeFromString(PushPayload.serializer(), """{"kind":"sync","seq":5,"type":null}""")
        assertEquals(PushPayload(PushPayload.KIND_SYNC, seq = 5), payload)
    }

    @Test
    fun settingsDropUnknownTypes() {
        val settings = FamilyHubJson.decodeFromString(NotificationSettings.serializer(), """{"muted":["system.test","future.thing"]}""")
        assertEquals(setOf(NotificationType.SYSTEM_TEST), settings.muted)
    }

    @Test
    fun everyTypeHasAKey() {
        NotificationType.entries.forEach { assertEquals(it, NotificationType.fromKey(it.key)) }
    }
}
