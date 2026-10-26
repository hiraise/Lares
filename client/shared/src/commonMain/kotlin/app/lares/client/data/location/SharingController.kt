package app.lares.client.data.location

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.lares.client.api.ApiException
import app.lares.client.api.LaresApi
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.minuteTicks
import app.lares.client.data.tasks.householdZone
import app.lares.client.db.LocalDb
import app.lares.client.notifications.NotificationPoster
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.SetSharingRequest
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.UploadPositionsResponse
import app.lares.contract.api.notifications.NotificationChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** «На 1 час», «До утра» (the next 08:00 in the household zone), «Пока не включу» (LOC-010). */
enum class PauseChoice { HOUR, MORNING, FOREVER }

/** Sharing needs the network to start (the server must learn which phone shares). */
class NeedsNetworkException(
    cause: Throwable,
) : Exception("Sharing needs a network connection", cause)

/**
 * Sharing on this phone (architecture/location.md § Sharing on this phone, LOC-010, LOC-012, LOC-019): enables,
 * pauses, resumes and reports it off, and reacts to upload responses. The state lives in `location_pref`, so it
 * survives restarts; a sharing request that could not be sent (offline) is kept as `pending` and sent by
 * [PositionUploader.flush] before positions. [onRefreshRequest] is the only way to [LocationTracking.freshFix].
 */
class SharingController(
    private val api: LaresApi,
    private val localDb: LocalDb,
    private val tracking: LocationTracking,
    private val device: LocationDevice,
    private val queue: PositionQueue,
    private val poster: NotificationPoster,
    private val texts: LocationTextResources,
    private val household: HouseholdRepository,
    private val clock: Clock,
    @Suppress("unused") private val scope: CoroutineScope,
    private val ticks: Flow<Instant> = minuteTicks(clock),
) {
    /** One change of the stored state at a time (UI, service and uploader call in parallel). */
    private val lock = Mutex()

    /** Turns sharing on here: `PUT SHARING` first (fails with [NeedsNetworkException] offline), then the service. */
    suspend fun enable(): Result<Unit> =
        lock.withLock {
            try {
                api.setSharing(SetSharingRequest(SharingStatus.SHARING))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withLock Result.failure(NeedsNetworkException(e))
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }
            localDb.transaction {
                location_prefQueries.put(ENABLED, "true")
                listOf(PAUSED_UNTIL, PAUSED_FOREVER, PENDING, REPORTED_OFF).forEach(location_prefQueries::remove)
            }
            tracking.start()
            Result.success(Unit)
        }

    /** Pauses at once on the phone, then tells the server (kept as pending when offline). */
    suspend fun pause(choice: PauseChoice): Result<Unit> {
        val until =
            when (choice) {
                PauseChoice.HOUR -> clock.now() + 1.hours
                PauseChoice.MORNING -> nextMorning(clock.now(), zone())
                PauseChoice.FOREVER -> null
            }
        lock.withLock {
            localDb.transaction {
                if (until == null) {
                    location_prefQueries.remove(PAUSED_UNTIL)
                    location_prefQueries.put(PAUSED_FOREVER, "true")
                } else {
                    location_prefQueries.remove(PAUSED_FOREVER)
                    location_prefQueries.put(PAUSED_UNTIL, until.toString())
                }
                putPending(SetSharingRequest(SharingStatus.PAUSED, pausedUntil = until))
            }
        }
        tracking.pauseChanged()
        val sent = send()
        if (sent is Sent.Refused) {
            // The server keeps the member sharing (e.g. no `location.pause_own`), so the phone must not stay silent.
            lock.withLock {
                localDb.transaction {
                    location_prefQueries.remove(PAUSED_UNTIL)
                    location_prefQueries.remove(PAUSED_FOREVER)
                }
            }
            tracking.pauseChanged()
            return Result.failure(sent.error)
        }
        return Result.success(Unit)
    }

    /** Ends a pause: at once on the phone, then `PUT SHARING` (pending when offline). */
    suspend fun resume(): Result<Unit> {
        lock.withLock {
            localDb.transaction {
                location_prefQueries.remove(PAUSED_UNTIL)
                location_prefQueries.remove(PAUSED_FOREVER)
                putPending(SetSharingRequest(SharingStatus.SHARING))
            }
        }
        tracking.pauseChanged()
        send()
        return Result.success(Unit)
    }

    /**
     * Called every minute by the service: an expired pause ends by itself. The server already counts it as sharing,
     * so nothing is sent; a pause request that never left the phone is dropped.
     */
    suspend fun checkPause() {
        val expired =
            lock.withLock {
                localDb.transaction {
                    val until = location_prefQueries.get(PAUSED_UNTIL).executeAsOneOrNull()?.let(Instant::parse)
                    if (until == null || until > clock.now()) return@transaction false
                    location_prefQueries.remove(PAUSED_UNTIL)
                    if (pending()?.status == SharingStatus.PAUSED) location_prefQueries.remove(PENDING)
                    true
                }
            }
        if (expired) tracking.pauseChanged()
    }

    /**
     * The phone cannot share (LOC-012): told once per reason until sharing works again; pending when offline. True
     * when this reason is new (the caller posts its phone notice once).
     */
    suspend fun reportOff(reason: OffReason): Boolean {
        val changed =
            lock.withLock {
                localDb.transaction {
                    if (location_prefQueries.get(ENABLED).executeAsOneOrNull() != "true") return@transaction false
                    if (location_prefQueries.get(REPORTED_OFF).executeAsOneOrNull() == reason.name) return@transaction false
                    location_prefQueries.put(REPORTED_OFF, reason.name)
                    putPending(SetSharingRequest(SharingStatus.OFF_ON_PHONE, reason = reason))
                    true
                }
            }
        if (changed) send()
        return changed
    }

    /** Stops sharing here without telling the server: sign-out (the server ends it with the session) or another phone took over. */
    suspend fun disable() {
        lock.withLock {
            localDb.transaction {
                listOf(ENABLED, PAUSED_UNTIL, PAUSED_FOREVER, PENDING, REPORTED_OFF, LAST_UPLOAD_OK, LAST_FIX_AT)
                    .forEach(location_prefQueries::remove)
            }
            queue.clear()
        }
        tracking.stop()
    }

    /** What an upload answered (ADR-0024): another phone shares, the member is paused, «Обновить» was pressed. */
    suspend fun onUpload(response: UploadPositionsResponse) {
        if (!response.sharingHere) {
            val wasEnabled = isEnabled()
            disable()
            if (wasEnabled) {
                poster.show(SHARING_MOVED_ID, NotificationChannel.SYSTEM, texts.sharingMovedToOtherPhone(), "", null)
            }
            return
        }
        lock.withLock {
            localDb.transaction {
                location_prefQueries.put(LAST_UPLOAD_OK, clock.now().toString())
                if (response.accepted > 0) location_prefQueries.remove(REPORTED_OFF)
                if (response.paused) {
                    val until = response.pausedUntil
                    if (until == null) {
                        location_prefQueries.remove(PAUSED_UNTIL)
                        location_prefQueries.put(PAUSED_FOREVER, "true")
                    } else {
                        location_prefQueries.remove(PAUSED_FOREVER)
                        location_prefQueries.put(PAUSED_UNTIL, until.toString())
                    }
                }
            }
        }
        if (response.paused) tracking.pauseChanged()
        if (response.refreshRequested) onRefreshRequest()
    }

    /** «Обновить» from a push or an upload response: a fresh fix only while sharing here and not paused here. */
    suspend fun onRefreshRequest() {
        if (isEnabled() && !isPausedLocally()) tracking.freshFix()
    }

    /** The service recorded a fix (for the «no fixes» checks of the Android plan). */
    suspend fun onFixRecorded(at: Instant) = localDb.transaction { location_prefQueries.put(LAST_FIX_AT, at.toString()) }

    suspend fun isEnabled(): Boolean = localDb.read { location_prefQueries.get(ENABLED).executeAsOneOrNull() } == "true"

    suspend fun isPausedLocally(): Boolean = localDb.read { prefs() }.pausedAt(clock.now())

    /**
     * Sends the pending sharing request. True when nothing is pending afterwards: sent, or refused by the server for
     * good (dropped); false when the server could not be reached (kept).
     */
    suspend fun sendPending(): Boolean = send() !is Sent.Kept

    private sealed interface Sent {
        data object Done : Sent

        data object Kept : Sent

        data class Refused(
            val error: ApiException,
        ) : Sent
    }

    private suspend fun send(): Sent =
        lock.withLock {
            val request = localDb.read { pending() } ?: return@withLock Sent.Done
            try {
                api.setSharing(request)
                clearPendingIf(request)
                Sent.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Refused (e.g. a pause end already in the past, no `location.pause_own`): retrying cannot help.
                clearPendingIf(request)
                Sent.Refused(e)
            } catch (_: Exception) {
                Sent.Kept
            }
        }

    /** This phone's sharing for the Map row and Settings › Геопозиция. */
    fun observe(): Flow<MySharing> =
        combine(
            localDb.db.location_prefQueries
                .all()
                .asFlow()
                .mapToList(localDb.dispatcher),
            queue.observeSize(),
            ticks,
        ) { rows, queued, now ->
            val prefs = Prefs(rows.associate { it.key to it.value_ })
            when {
                !prefs.enabled -> MySharing.Off
                prefs.pausedAt(now) -> MySharing.Paused(prefs.pausedUntil)
                !device.status().canShare -> MySharing.NeedsPermission
                queued > 0 && prefs.lastUploadOk?.let { now - it >= STALE_UPLOADS } ?: false -> MySharing.StaleUploads
                else -> MySharing.Sharing
            }
        }.distinctUntilChanged()

    private suspend fun clearPendingIf(request: SetSharingRequest) =
        localDb.transaction { if (pending() == request) location_prefQueries.remove(PENDING) }

    private fun app.lares.client.db.LaresDb.pending(): SetSharingRequest? =
        location_prefQueries
            .get(PENDING)
            .executeAsOneOrNull()
            ?.let { runCatching { LaresJson.decodeFromString(SetSharingRequest.serializer(), it) }.getOrNull() }

    private fun app.lares.client.db.LaresDb.putPending(request: SetSharingRequest) =
        location_prefQueries.put(PENDING, LaresJson.encodeToString(SetSharingRequest.serializer(), request))

    private fun app.lares.client.db.LaresDb.prefs(): Prefs =
        Prefs(location_prefQueries.all().executeAsList().associate { it.key to it.value_ })

    private suspend fun zone(): TimeZone =
        householdZone(
            household
                .observe()
                .first()
                ?.data
                ?.timeZone,
        )

    private class Prefs(
        values: Map<String, String>,
    ) {
        val enabled = values[ENABLED] == "true"
        val pausedUntil = values[PAUSED_UNTIL]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val pausedForever = values[PAUSED_FOREVER] == "true"
        val lastUploadOk = values[LAST_UPLOAD_OK]?.let { runCatching { Instant.parse(it) }.getOrNull() }

        fun pausedAt(now: Instant) = pausedForever || pausedUntil?.let { it > now } == true
    }

    companion object {
        /** The next 08:00 in [zone] after [now] (spec assumption: «До утра» ends at 08:00). */
        fun nextMorning(
            now: Instant,
            zone: TimeZone,
        ): Instant {
            val local = now.toLocalDateTime(zone)
            val today = local.date.atTime(MORNING).toInstant(zone)
            return if (today > now) {
                today
            } else {
                local.date
                    .plus(1, DateTimeUnit.DAY)
                    .atTime(MORNING)
                    .toInstant(zone)
            }
        }

        private val MORNING = LocalTime(8, 0)
        private val STALE_UPLOADS = 60.minutes
        private const val SHARING_MOVED_ID = "location.sharing_moved"
        private const val ENABLED = "enabled"
        private const val PAUSED_UNTIL = "paused_until"
        private const val PAUSED_FOREVER = "paused_forever"
        private const val PENDING = "pending"
        private const val LAST_UPLOAD_OK = "last_upload_ok"
        private const val LAST_FIX_AT = "last_fix_at"
        private const val REPORTED_OFF = "reported_off"
    }
}
