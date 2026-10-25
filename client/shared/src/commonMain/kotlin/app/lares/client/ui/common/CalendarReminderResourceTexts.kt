package app.lares.client.ui.common

import app.lares.client.data.calendar.CalendarReminderTexts
import app.lares.client.data.calendar.clockTime
import app.lares.client.data.tasks.ReminderDay
import app.lares.client.resources.Res
import app.lares.client.resources.cal_reminder_birthday_title
import app.lares.client.resources.cal_reminder_birthday_today_age
import app.lares.client.resources.cal_reminder_date_all_day
import app.lares.client.resources.cal_reminder_date_at
import app.lares.client.resources.cal_reminder_in_days
import app.lares.client.resources.cal_reminder_today
import app.lares.client.resources.cal_reminder_today_all_day
import app.lares.client.resources.cal_reminder_today_at
import app.lares.client.resources.cal_reminder_tomorrow
import app.lares.client.resources.cal_reminder_tomorrow_all_day
import app.lares.client.resources.cal_reminder_tomorrow_at
import app.lares.client.resources.cal_reminder_turns
import app.lares.client.resources.cal_reminder_with_place
import app.lares.client.resources.cal_reminder_years
import app.lares.client.resources.date_day_month
import app.lares.client.resources.months
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray

/** Calendar reminder texts in the app language: «Сегодня в 16:00 · Клиника на Ленина», «Через 3 дня, исполнится 78». */
class CalendarReminderResourceTexts : CalendarReminderTexts {
    override suspend fun eventBody(
        day: ReminderDay,
        date: LocalDate,
        time: LocalTime?,
        place: String?,
    ): String {
        val clock = time?.let(::clockTime)
        val whenText =
            when (day) {
                ReminderDay.TODAY -> {
                    if (clock ==
                        null
                    ) {
                        getString(Res.string.cal_reminder_today_all_day)
                    } else {
                        getString(Res.string.cal_reminder_today_at, clock)
                    }
                }

                ReminderDay.TOMORROW -> {
                    if (clock == null) {
                        getString(Res.string.cal_reminder_tomorrow_all_day)
                    } else {
                        getString(Res.string.cal_reminder_tomorrow_at, clock)
                    }
                }

                ReminderDay.OTHER -> {
                    val month = getStringArray(Res.array.months)[date.month.ordinal]
                    val dateText = getString(Res.string.date_day_month, date.day.toString(), month)
                    if (clock == null) {
                        getString(Res.string.cal_reminder_date_all_day, dateText)
                    } else {
                        getString(Res.string.cal_reminder_date_at, dateText, clock)
                    }
                }
            }
        return if (place == null) whenText else getString(Res.string.cal_reminder_with_place, whenText, place)
    }

    override suspend fun birthdayTitle(name: String): String = getString(Res.string.cal_reminder_birthday_title, name)

    override suspend fun birthdayBody(
        daysLeft: Int,
        age: Int?,
    ): String =
        when {
            age == null -> inDays(daysLeft)
            daysLeft == 0 -> getString(Res.string.cal_reminder_birthday_today_age, years(age))
            else -> getString(Res.string.cal_reminder_turns, inDays(daysLeft), age)
        }

    override suspend fun memorableBody(
        daysLeft: Int,
        years: Int?,
    ): String = if (years == null) inDays(daysLeft) else getString(Res.string.cal_reminder_with_place, inDays(daysLeft), years(years))

    private suspend fun years(count: Int) = getPluralString(Res.plurals.cal_reminder_years, count, count)

    private suspend fun inDays(days: Int): String =
        when (days) {
            0 -> getString(Res.string.cal_reminder_today)
            1 -> getString(Res.string.cal_reminder_tomorrow)
            else -> getPluralString(Res.plurals.cal_reminder_in_days, days, days)
        }
}
