package app.lares.client.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.lares.client.resources.Res
import app.lares.client.resources.date_day_month
import app.lares.client.resources.date_day_month_year
import app.lares.client.resources.date_with_weekday
import app.lares.client.resources.months
import app.lares.client.resources.time_on_date
import app.lares.client.resources.weekdays
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

// Localized dates from string resources: common code has no locale-aware date formatting.

/** "Friday, October 23" / "Пятница, 23 октября" for the device's current day. */
@Composable
fun todayLine(clock: Clock = Clock.System): String {
    val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val weekday = stringArrayResource(Res.array.weekdays)[now.dayOfWeek.ordinal]
    return stringResource(Res.string.date_with_weekday, weekday, dayMonth(now))
}

/** Formats instants as "9:41" today and "October 23, 9:41" on other days, in the device time zone. */
@Composable
fun rememberTimeFormatter(clock: Clock = Clock.System): (Instant) -> String {
    val months = stringArrayResource(Res.array.months)
    val dayMonthPattern = stringResource(Res.string.date_day_month)
    val onDatePattern = stringResource(Res.string.time_on_date)
    return remember(months, dayMonthPattern, onDatePattern) {
        { instant ->
            val zone = TimeZone.currentSystemDefault()
            val at = instant.toLocalDateTime(zone)
            val today = clock.now().toLocalDateTime(zone).date
            val time = "${at.hour}:${at.minute.toString().padStart(2, '0')}"
            if (at.date == today) {
                time
            } else {
                val dayMonth = format(dayMonthPattern, at.day.toString(), months[at.month.ordinal])
                format(onDatePattern, dayMonth, time)
            }
        }
    }
}

@Composable
private fun dayMonth(at: LocalDateTime): String =
    stringResource(Res.string.date_day_month, at.day.toString(), stringArrayResource(Res.array.months)[at.month.ordinal])

/** Fills `%1$s`, `%2$s` placeholders outside composition (resources are read once in composition). */
private fun format(
    pattern: String,
    vararg args: String,
): String {
    var result = pattern
    args.forEachIndexed { index, arg -> result = result.replace("%${index + 1}\$s", arg) }
    return result
}

/** "March 8" or "March 8, 1950" / "8 марта 1950"; null when no birthday is set. */
@Composable
fun birthdayText(
    day: Int?,
    month: Int?,
    year: Int?,
): String? {
    if (day == null || month == null) return null
    val monthName = stringArrayResource(Res.array.months).getOrNull(month - 1) ?: return null
    return if (year == null) {
        stringResource(Res.string.date_day_month, day.toString(), monthName)
    } else {
        stringResource(Res.string.date_day_month_year, day.toString(), monthName, year.toString())
    }
}
