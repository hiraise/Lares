package app.lares.server.calendar

import app.lares.contract.calendar.EventOccurrence
import kotlinx.datetime.LocalTime

/**
 * The `{when}` part of event notifications (architecture/calendar.md § Notifications): the start day of the occurrence in
 * household time, plus the start time for timed ones. Fixed abbreviations keep the server independent of JVM locale data.
 */
object EventWhenText {
    private val weekdaysRu = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val monthsRu = listOf("янв.", "февр.", "марта", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек.")
    private val weekdaysEn = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val monthsEn = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** ru «вт, 28 окт., 16:00» / «вт, 28 окт.»; en «Tue, Oct 28, 16:00» / «Tue, Oct 28». */
    fun format(
        language: String,
        occ: EventOccurrence,
    ): String {
        val date = occ.startDate
        val weekday = date.dayOfWeek.ordinal
        val month = date.month.ordinal
        val day =
            when (language) {
                "ru" -> "${weekdaysRu[weekday]}, ${date.day} ${monthsRu[month]}"
                else -> "${weekdaysEn[weekday]}, ${monthsEn[month]} ${date.day}"
            }
        val time = occ.startTime ?: return day
        return "$day, ${hhmm(time)}"
    }

    private fun hhmm(time: LocalTime): String = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
