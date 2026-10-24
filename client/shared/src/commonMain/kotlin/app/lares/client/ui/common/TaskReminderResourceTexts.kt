package app.lares.client.ui.common

import app.lares.client.data.tasks.ReminderDay
import app.lares.client.data.tasks.TaskReminderTexts
import app.lares.client.data.tasks.reminderDay
import app.lares.client.data.tasks.reminderTime
import app.lares.client.resources.Res
import app.lares.client.resources.date_day_month
import app.lares.client.resources.months
import app.lares.client.resources.task_reminder_date
import app.lares.client.resources.task_reminder_date_at
import app.lares.client.resources.task_reminder_today
import app.lares.client.resources.task_reminder_today_at
import app.lares.client.resources.task_reminder_tomorrow
import app.lares.client.resources.task_reminder_tomorrow_at
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import kotlin.time.Clock
import kotlin.time.Instant

/** Task reminder bodies in the app language: "Due today at 20:00" / "Срок: сегодня в 20:00". */
class TaskReminderResourceTexts(
    private val clock: Clock,
) : TaskReminderTexts {
    override suspend fun body(
        dueMoment: Instant?,
        occurrence: LocalDate,
    ): String {
        val time = dueMoment?.let { reminderTime(it) }
        return when (reminderDay(occurrence, clock)) {
            ReminderDay.TODAY -> {
                if (time == null) getString(Res.string.task_reminder_today) else getString(Res.string.task_reminder_today_at, time)
            }

            ReminderDay.TOMORROW -> {
                if (time == null) getString(Res.string.task_reminder_tomorrow) else getString(Res.string.task_reminder_tomorrow_at, time)
            }

            ReminderDay.OTHER -> {
                val month = getStringArray(Res.array.months)[occurrence.month.ordinal]
                val date = getString(Res.string.date_day_month, occurrence.day.toString(), month)
                if (time ==
                    null
                ) {
                    getString(Res.string.task_reminder_date, date)
                } else {
                    getString(Res.string.task_reminder_date_at, date, time)
                }
            }
        }
    }
}
