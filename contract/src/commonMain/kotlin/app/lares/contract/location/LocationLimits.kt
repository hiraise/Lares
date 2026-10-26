package app.lares.contract.location

/** Limits and thresholds of the location module (architecture/location.md § Pure logic). */
object LocationLimits {
    /** Positions per upload. */
    const val MAX_UPLOAD = 200

    /** Older fixes are dropped by the server. */
    const val MAX_FIX_AGE_HOURS = 24

    /** Fixes further in the future than this are dropped. */
    const val MAX_FUTURE_SKEW_MINUTES = 5

    /** A position older than this is shown as stale (LOC-003). */
    const val STALE_MINUTES = 30

    /** A member faster than this is "moving". */
    const val MOVING_SPEED_MPS = 2f

    /** A "moving" fix counts only while it is this fresh. */
    const val MOVING_MAX_AGE_MINUTES = 5

    /** «Обновить» for the same member at most this often (LOC-010). */
    const val REFRESH_COOLDOWN_SECONDS = 60
    const val MAX_RULES = 100
    const val MAX_HISTORY_POINTS = 3000

    /** Retention choices (LOC-005), in days. */
    val HISTORY_DAYS_CHOICES: List<Int> = listOf(7, 14, 30, 60, 90)
    const val DEFAULT_HISTORY_DAYS = 30

    const val MIN_RADIUS_M = 50
    const val MAX_RADIUS_M = 1000
    const val DEFAULT_RADIUS_M = 100

    const val PLACE_NAME_MAX = 60
    const val PLACE_ADDRESS_MAX = 200
    const val MAX_ALERT_PLACES = 50
    const val MAX_ACCURACY_M = 10_000f
    const val MAX_SPEED_MPS = 100f
    const val MAX_BEARING_DEG = 360f

    /** A pause lasts at most this long. */
    const val MAX_PAUSE_DAYS = 7
}
