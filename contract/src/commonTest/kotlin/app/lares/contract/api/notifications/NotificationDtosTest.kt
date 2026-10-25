package app.lares.contract.api.notifications

import app.lares.contract.api.LaresJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationDtosTest {
    @Test
    fun payloadRoundTripsWithStableTypeNames() {
        val payload = PushPayload(PushPayload.KIND_NOTIFICATION, type = NotificationType.SYSTEM_TEST, title = "T", body = "B")
        val text = LaresJson.encodeToString(PushPayload.serializer(), payload)
        assertTrue("\"type\":\"system.test\"" in text, text)
        assertEquals(payload, LaresJson.decodeFromString(PushPayload.serializer(), text))
    }

    @Test
    fun unknownTypeInPayloadDecodesToNull() {
        val payload =
            LaresJson.decodeFromString(
                PushPayload.serializer(),
                """{"kind":"notification","type":"future.thing","title":"x"}""",
            )
        assertNull(payload.type)
        assertEquals("x", payload.title)
    }

    @Test
    fun syncPayloadWithoutType() {
        val payload = LaresJson.decodeFromString(PushPayload.serializer(), """{"kind":"sync","seq":5,"type":null}""")
        assertEquals(PushPayload(PushPayload.KIND_SYNC, seq = 5), payload)
    }

    @Test
    fun settingsDropUnknownTypes() {
        val settings = LaresJson.decodeFromString(NotificationSettings.serializer(), """{"muted":["system.test","future.thing"]}""")
        assertEquals(setOf(NotificationType.SYSTEM_TEST), settings.muted)
    }

    @Test
    fun everyTypeHasAKey() {
        NotificationType.entries.forEach { assertEquals(it, NotificationType.fromKey(it.key)) }
    }

    @Test
    fun `CAL-016 missing birthday days take the default, the value round trips`() {
        val settings = LaresJson.decodeFromString(NotificationSettings.serializer(), """{"muted":[]}""")
        assertEquals(3, settings.birthdayDaysBefore)
        val week = NotificationSettings(setOf(NotificationType.BIRTHDAY_REMINDER), birthdayDaysBefore = 7)
        val text = LaresJson.encodeToString(NotificationSettings.serializer(), week)
        assertEquals(week, LaresJson.decodeFromString(NotificationSettings.serializer(), text))
        assertEquals(listOf(0, 1, 3, 7, 14), NotificationSettings.BIRTHDAY_DAYS_CHOICES)
        assertTrue(NotificationSettings.DEFAULT_BIRTHDAY_DAYS_BEFORE in NotificationSettings.BIRTHDAY_DAYS_CHOICES)
    }

    @Test
    fun `CAL-006 CAL-007 CAL-016 calendar notification types`() {
        assertEquals("event.changed", NotificationType.EVENT_CHANGED.key)
        assertEquals(NotificationChannel.FAMILY_ACTIVITY, NotificationType.EVENT_CHANGED.channel)
        assertEquals(NotificationType.EVENT_REMINDER, NotificationType.fromKey("event.reminder"))
        assertEquals(NotificationChannel.REMINDERS, NotificationType.EVENT_REMINDER.channel)
        assertEquals(NotificationType.BIRTHDAY_REMINDER, NotificationType.fromKey("birthday.reminder"))
        assertEquals(NotificationChannel.REMINDERS, NotificationType.BIRTHDAY_REMINDER.channel)
    }
}
