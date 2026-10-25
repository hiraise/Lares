package app.lares.contract.api.notifications

import app.lares.contract.serialization.LenientEnumSerializer
import app.lares.contract.serialization.LenientEnumSetSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Notifications (FND-050…052, plan M0-09): push payload, push registration, per-type muting.

/** Android notification channel a notification type is shown on. [id] is the stable channel id. */
enum class NotificationChannel(
    val id: String,
) {
    REMINDERS("reminders"),
    FAMILY_ACTIVITY("family_activity"),
    SYSTEM("system"),
}

/**
 * Kinds of notifications a member can receive and mute. The serial name is stable: it is stored in the database
 * (`notification_setting.type`) and sent in pushes. Types unknown to this version are ignored.
 */
@Serializable
enum class NotificationType(
    val channel: NotificationChannel,
) {
    /** "Send test notification" in settings (M0). */
    @SerialName("system.test")
    SYSTEM_TEST(NotificationChannel.SYSTEM),

    /** Declared for M1 (tasks). */
    @SerialName("task.assigned")
    TASK_ASSIGNED(NotificationChannel.FAMILY_ACTIVITY),

    @SerialName("task.overdue")
    TASK_OVERDUE(NotificationChannel.REMINDERS),

    @SerialName("task.completed")
    TASK_COMPLETED(NotificationChannel.FAMILY_ACTIVITY),

    /** Local task reminders (TSK-030). Stored like other types so members can mute them; the server never sends it. */
    @SerialName("task.reminder")
    TASK_REMINDER(NotificationChannel.REMINDERS),

    /** An event was added for, changed or cancelled for a participant (CAL-007). */
    @SerialName("event.changed")
    EVENT_CHANGED(NotificationChannel.FAMILY_ACTIVITY),

    /** Local event reminders (CAL-006); the server never sends it. */
    @SerialName("event.reminder")
    EVENT_REMINDER(NotificationChannel.REMINDERS),

    /** Local birthday and memorable-date reminders (CAL-016); the server never sends it. */
    @SerialName("birthday.reminder")
    BIRTHDAY_REMINDER(NotificationChannel.REMINDERS),
    ;

    /** The stable wire name, e.g. `system.test`. */
    val key: String get() = NotificationTypeElementSerializer.serialName(this)

    companion object {
        /** Returns the type with this wire name, or `null` if this version does not know it. */
        fun fromKey(key: String): NotificationType? = NotificationTypeElementSerializer.decodeOrNull(key)
    }
}

internal object NotificationTypeElementSerializer :
    LenientEnumSerializer<NotificationType>(NotificationType.serializer(), NotificationType.entries, fallback = null)

/** Serializer for every `Set<NotificationType>`: types unknown to this version are dropped. */
object NotificationTypeSetSerializer : LenientEnumSetSerializer<NotificationType>(NotificationTypeElementSerializer)

/** Serializer for an optional [NotificationType]: an unknown type decodes to `null`. */
object NullableNotificationTypeSerializer : KSerializer<NotificationType?> {
    override val descriptor: SerialDescriptor = NotificationTypeElementSerializer.descriptor.nullable

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: NotificationType?,
    ) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.key)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): NotificationType? =
        if (decoder.decodeNotNullMark()) NotificationType.fromKey(decoder.decodeString()) else decoder.decodeNull()
}

/**
 * The cleartext of a Web Push message (≤ 2 KB; encrypted with RFC 8291 `aes128gcm` on the way).
 * [kind] `sync`: data changed, the app syncs ([seq] is the household's newest change). [kind] `notification`: show
 * [title] and [body] (already in the member's language) on the channel of [type].
 */
@Serializable
data class PushPayload(
    val kind: String,
    val seq: Long? = null,
    @Serializable(with = NullableNotificationTypeSerializer::class) val type: NotificationType? = null,
    val title: String? = null,
    val body: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
) {
    companion object {
        const val KIND_SYNC = "sync"
        const val KIND_NOTIFICATION = "notification"

        /** Upper bound for the encoded payload; UnifiedPush allows at most 3993 bytes of cleartext. */
        const val MAX_BYTES = 2048
    }
}

/**
 * `PUT /api/v1/me/push` (FND-051): the UnifiedPush endpoint of this session. Encryption is mandatory, so [p256dh]
 * (the device's public key) and [auth] (its auth secret) are required, both base64url. [language] (`en`/`ru`, the
 * device UI language) selects the language of notification texts.
 */
@Serializable
data class PushRegistrationRequest(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val language: String,
)

/** Outcome of `POST /api/v1/me/push/test`. */
@Serializable
enum class PushTestResult {
    /** The push service accepted the message. */
    @SerialName("sent")
    SENT,

    /** The member muted test notifications; nothing was sent. */
    @SerialName("muted")
    MUTED,

    /** This session has no push endpoint. */
    @SerialName("not_registered")
    NOT_REGISTERED,

    /** The push service no longer knows the endpoint; the registration was deleted. */
    @SerialName("expired")
    EXPIRED,

    /** The push service could not be reached or refused the message. */
    @SerialName("failed")
    FAILED,
}

@Serializable
data class PushTestResponse(
    val result: PushTestResult,
)

/**
 * `GET` and `PUT /api/v1/me/notification-settings` (FND-052): notification types the member muted, and how many days
 * before a birthday or memorable date the reminders start (CAL-016; one of [BIRTHDAY_DAYS_CHOICES], 0 = on the day only;
 * "off" is muting [NotificationType.BIRTHDAY_REMINDER]).
 */
@Serializable
data class NotificationSettings(
    @Serializable(with = NotificationTypeSetSerializer::class) val muted: Set<NotificationType> = emptySet(),
    val birthdayDaysBefore: Int = DEFAULT_BIRTHDAY_DAYS_BEFORE,
) {
    companion object {
        const val DEFAULT_BIRTHDAY_DAYS_BEFORE = 3
        val BIRTHDAY_DAYS_CHOICES: List<Int> = listOf(0, 1, 3, 7, 14)
    }
}
