package app.familyhub.server.platform

import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** Current time for `timestamptz` columns. PostgreSQL keeps microseconds, so finer digits are dropped up front. */
fun Clock.nowForDb(): java.time.Instant = now().toJavaInstant().truncatedTo(ChronoUnit.MICROS)

fun Instant.toDb(): java.time.Instant = toJavaInstant().truncatedTo(ChronoUnit.MICROS)

fun java.time.Instant.toContract(): Instant = toKotlinInstant()
