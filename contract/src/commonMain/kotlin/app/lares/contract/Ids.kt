package app.lares.contract

import kotlinx.datetime.LocalDate
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Identifier generation for syncable entities. Keeps the UUID API opt-in in one place. */
object Ids {
    /** Returns a new time-ordered UUIDv7 in the canonical lowercase string form. */
    @OptIn(ExperimentalUuidApi::class)
    fun newId(): String = Uuid.generateV7().toString()

    /**
     * The id of the `event_occurrence` row that overrides [occurrence] of [eventId] (ADR-0022): two phones changing the
     * same occurrence offline write the same row. Throws [IllegalArgumentException] for an invalid [eventId].
     */
    fun occurrenceRowId(
        eventId: String,
        occurrence: LocalDate,
    ): String = derive(eventId, occurrence.toEpochDays().toLong(), marker = false)

    /**
     * The id of [memberId]'s `event_reminder` row for [eventId] (ADR-0023). Throws [IllegalArgumentException] for an
     * invalid [eventId] or [memberId].
     */
    @OptIn(ExperimentalUuidApi::class)
    fun reminderRowId(
        eventId: String,
        memberId: String,
    ): String {
        val member = Uuid.parse(memberId).toByteArray()
        var salt = 0L
        for (i in SALT_FROM until UUID_BYTES) salt = (salt shl Byte.SIZE_BITS) or (member[i].toLong() and BYTE_MASK)
        return derive(eventId, salt, marker = true)
    }

    /** architecture/calendar.md § Derived ids; the same algorithm is in `tools/dev/seed.py`. */
    @OptIn(ExperimentalUuidApi::class)
    private fun derive(
        eventId: String,
        salt: Long,
        marker: Boolean,
    ): String {
        val b = Uuid.parse(eventId).toByteArray()
        for (i in SALT_FROM until UUID_BYTES) {
            val shift = (UUID_BYTES - 1 - i) * Byte.SIZE_BITS
            b[i] = (b[i].toInt() xor (salt ushr shift).toInt()).toByte()
        }
        if (marker) b[7] = (b[7].toInt() xor REMINDER_MARKER).toByte()
        b[6] = ((b[6].toInt() and 0x0F) or VERSION_8).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or VARIANT_RFC).toByte()
        return Uuid.fromByteArray(b).toString()
    }

    private const val UUID_BYTES = 16
    private const val SALT_FROM = 8
    private const val BYTE_MASK = 0xFFL
    private const val REMINDER_MARKER = 0x5A
    private const val VERSION_8 = 0x80
    private const val VARIANT_RFC = 0x80
}
