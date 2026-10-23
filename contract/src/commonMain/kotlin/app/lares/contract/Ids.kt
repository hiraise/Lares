package app.lares.contract

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Identifier generation for syncable entities. Keeps the UUID API opt-in in one place. */
object Ids {
    /** Returns a new time-ordered UUIDv7 in the canonical lowercase string form. */
    @OptIn(ExperimentalUuidApi::class)
    fun newId(): String = Uuid.generateV7().toString()
}
