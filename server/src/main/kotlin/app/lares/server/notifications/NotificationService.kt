package app.lares.server.notifications

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.api.notifications.PushPayload
import app.lares.contract.api.notifications.PushRegistrationRequest
import app.lares.contract.api.notifications.PushTestResult
import app.lares.server.identity.MemberPrincipal
import app.lares.server.identity.SessionTable
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.nowForDb
import com.interaso.webpush.WebPush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Push registrations, per-type muting and sending (FND-051, FND-052, plan M0-09).
 * Muted notifications are not sent at all; sync tickles are content-free and cannot be muted.
 */
class NotificationService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val guard: PushEndpointGuard,
    private val sender: WebPushSender,
) {
    suspend fun register(
        principal: MemberPrincipal,
        request: PushRegistrationRequest,
    ) {
        val fields = mutableMapOf<String, FieldErrorCode>()
        if (request.endpoint.length > MAX_ENDPOINT_LENGTH) {
            fields["endpoint"] = FieldErrorCode.TOO_LONG
        } else if (withContext(Dispatchers.IO) { guard.check(request.endpoint) } != null) {
            fields["endpoint"] = FieldErrorCode.INVALID_FORMAT
        }
        if (decodedSize(request.p256dh) != P256DH_BYTES) fields["p256dh"] = FieldErrorCode.INVALID_FORMAT
        if (decodedSize(request.auth) != AUTH_BYTES) fields["auth"] = FieldErrorCode.INVALID_FORMAT
        if (fields.isNotEmpty()) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Invalid push registration: ${fields.keys.joinToString()}", fields)
        }
        db.dbQuery {
            val now = clock.nowForDb()
            PushRegistrationTable.upsert(onUpdateExclude = listOf(PushRegistrationTable.createdAt)) {
                it[sessionId] = principal.sessionId
                it[householdId] = principal.householdId
                it[memberId] = principal.memberId
                it[endpoint] = request.endpoint
                it[p256dh] = request.p256dh.trim()
                it[auth] = request.auth.trim()
                it[language] = NotificationTexts.language(request.language)
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    suspend fun unregister(principal: MemberPrincipal) {
        db.dbQuery { PushRegistrationTable.deleteWhere { sessionId eq principal.sessionId } }
    }

    suspend fun settings(principal: MemberPrincipal): NotificationSettings = db.dbQuery { storedSettings(principal.memberId) }

    /** Replaces the member's muted types and stores the birthday lead time (one of `BIRTHDAY_DAYS_CHOICES`, CAL-016). */
    suspend fun setSettings(
        principal: MemberPrincipal,
        settings: NotificationSettings,
    ): NotificationSettings {
        if (settings.birthdayDaysBefore !in NotificationSettings.BIRTHDAY_DAYS_CHOICES) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Invalid fields: birthdayDaysBefore",
                mapOf("birthdayDaysBefore" to FieldErrorCode.OUT_OF_RANGE),
            )
        }
        return db.dbQuery {
            NotificationSettingTable.deleteWhere { memberId eq principal.memberId }
            NotificationSettingTable.batchInsert(settings.muted) { type ->
                this[NotificationSettingTable.memberId] = principal.memberId
                this[NotificationSettingTable.type] = type.key
                this[NotificationSettingTable.muted] = true
            }
            NotificationPreferenceTable.upsert {
                it[memberId] = principal.memberId
                it[birthdayDaysBefore] = settings.birthdayDaysBefore
            }
            storedSettings(principal.memberId)
        }
    }

    private fun storedSettings(memberId: UUID): NotificationSettings =
        NotificationSettings(
            muted = mutedTypes(memberId),
            birthdayDaysBefore =
                NotificationPreferenceTable
                    .select(NotificationPreferenceTable.birthdayDaysBefore)
                    .where { NotificationPreferenceTable.memberId eq memberId }
                    .singleOrNull()
                    ?.get(NotificationPreferenceTable.birthdayDaysBefore)
                    ?: NotificationSettings.DEFAULT_BIRTHDAY_DAYS_BEFORE,
        )

    /** `POST /me/push/test`: a [NotificationType.SYSTEM_TEST] notification to the caller's own session. */
    suspend fun sendTest(principal: MemberPrincipal): PushTestResult {
        val type = NotificationType.SYSTEM_TEST
        val target =
            db.dbQuery { activeTargets(PushRegistrationTable.sessionId eq principal.sessionId).singleOrNull() }
                ?: return PushTestResult.NOT_REGISTERED
        if (db.dbQuery { type in mutedTypes(principal.memberId) }) return PushTestResult.MUTED
        return when (sendNotification(target, type) { NotificationTexts.systemTest(it).let { t -> t.title to t.body } }) {
            PushSendResult.SENT -> PushTestResult.SENT
            PushSendResult.EXPIRED -> PushTestResult.EXPIRED
            PushSendResult.FAILED, PushSendResult.REFUSED -> PushTestResult.FAILED
        }
    }

    /**
     * Sends a [type] notification to every registered session of [memberIds] in the household, except members who
     * muted the type. [render] returns title and body for a language (`en`, `ru`). Returns the result per session.
     */
    suspend fun notify(
        householdId: UUID,
        memberIds: Collection<UUID>,
        type: NotificationType,
        entityType: String? = null,
        entityId: String? = null,
        render: (language: String) -> Pair<String, String>,
    ): Map<UUID, PushSendResult> {
        if (memberIds.isEmpty()) return emptyMap()
        val targets =
            db.dbQuery {
                val muting =
                    NotificationSettingTable
                        .selectAll()
                        .where {
                            (NotificationSettingTable.memberId inList memberIds) and (NotificationSettingTable.type eq type.key) and
                                (NotificationSettingTable.muted eq true)
                        }.map { it[NotificationSettingTable.memberId] }
                        .toSet()
                activeTargets(
                    (PushRegistrationTable.householdId eq householdId) and (PushRegistrationTable.memberId inList memberIds),
                ).filter { it.memberId !in muting }
            }
        return sendAll(targets) { sendNotification(it, type, entityType, entityId, render) }
    }

    /** A content-free `sync` push to every registered session of the household, the originating one included. */
    suspend fun tickle(
        householdId: UUID,
        seq: Long,
    ): Map<UUID, PushSendResult> {
        val targets = db.dbQuery { activeTargets(PushRegistrationTable.householdId eq householdId) }
        val payload = PushPayload(PushPayload.KIND_SYNC, seq = seq)
        return sendAll(targets) { sender.send(it, payload, SYNC_TTL, WebPush.Urgency.Normal) }
    }

    private suspend fun sendNotification(
        target: PushTarget,
        type: NotificationType,
        entityType: String? = null,
        entityId: String? = null,
        render: (language: String) -> Pair<String, String>,
    ): PushSendResult {
        val (title, body) = render(target.language)
        val payload =
            PushPayload(
                PushPayload.KIND_NOTIFICATION,
                type = type,
                title = title,
                body = body,
                entityType = entityType,
                entityId = entityId,
            )
        return sender.send(target, payload, NOTIFICATION_TTL, WebPush.Urgency.High)
    }

    private suspend fun sendAll(
        targets: List<PushTarget>,
        send: suspend (PushTarget) -> PushSendResult,
    ): Map<UUID, PushSendResult> =
        coroutineScope {
            targets.map { target -> async { target.sessionId to send(target) } }.awaitAll().toMap()
        }

    /** Registrations of sessions that are neither revoked nor past their refresh expiry. */
    private fun activeTargets(where: Op<Boolean>): List<PushTarget> =
        PushRegistrationTable
            .join(SessionTable, JoinType.INNER, PushRegistrationTable.sessionId, SessionTable.id)
            .selectAll()
            .where { where and SessionTable.revokedAt.isNull() and (SessionTable.refreshExpiresAt greater clock.nowForDb()) }
            .map(::toTarget)

    private fun mutedTypes(memberId: UUID): Set<NotificationType> =
        NotificationSettingTable
            .selectAll()
            .where { (NotificationSettingTable.memberId eq memberId) and (NotificationSettingTable.muted eq true) }
            .mapNotNull { NotificationType.fromKey(it[NotificationSettingTable.type]) }
            .toSet()

    private fun toTarget(row: ResultRow) =
        PushTarget(
            sessionId = row[PushRegistrationTable.sessionId],
            memberId = row[PushRegistrationTable.memberId],
            endpoint = row[PushRegistrationTable.endpoint],
            p256dh = row[PushRegistrationTable.p256dh],
            auth = row[PushRegistrationTable.auth],
            language = row[PushRegistrationTable.language],
        )

    private fun decodedSize(text: String): Int? = runCatching { WebPushSender.decodeKey(text).size }.getOrNull()

    companion object {
        private const val MAX_ENDPOINT_LENGTH = 2048
        private const val P256DH_BYTES = 65
        private const val AUTH_BYTES = 16
        val NOTIFICATION_TTL = 1.days
        val SYNC_TTL = 10.minutes
    }
}
