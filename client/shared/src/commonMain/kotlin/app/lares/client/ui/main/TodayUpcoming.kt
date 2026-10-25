package app.lares.client.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.resources.Res
import app.lares.client.resources.today_tasks_more
import app.lares.client.resources.today_upcoming_calendar
import app.lares.client.resources.today_upcoming_title
import app.lares.client.ui.calendar.CalendarLogic
import app.lares.client.ui.calendar.DATED_ROW_INSET
import app.lares.client.ui.calendar.DatedItemRow
import app.lares.client.ui.common.text
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * «Скоро» (CAL-019, mockup `Main.dc.html`): events, birthdays and memorable dates of today and the next 7 days with a
 * date tile; «Календарь» opens the tab, «Ещё N» opens it in «Список».
 */
@Composable
fun TodayUpcoming(
    upcoming: TodayUpcomingUi,
    actions: TodayActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("today_upcoming"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(Res.string.today_upcoming_title))
            Spacer(Modifier.weight(1f))
            LinkButton(stringResource(Res.string.today_upcoming_calendar), actions.onCalendar, Modifier.testTag("today_calendar"))
        }
        ListGroup {
            upcoming.items.forEachIndexed { index, dated ->
                if (index > 0) ListDivider(inset = DATED_ROW_INSET)
                val item = dated.item
                DatedItemRow(
                    date = dated.date,
                    item = item,
                    supporting = CalendarLogic.upcomingWhen(dated, upcoming.today).text(),
                    today = upcoming.today,
                    allNames = upcoming.allNames,
                    onClick = {
                        when (item) {
                            is CalendarItem.Event -> actions.onEvent(item.occ.eventId, item.occ.occurrence)
                            is CalendarItem.Birthday -> actions.onPerson(item.person.id)
                            is CalendarItem.Task -> Unit
                        }
                    },
                    modifier = Modifier.testTag("today_upcoming_$index"),
                )
            }
            if (upcoming.more > 0) {
                ListDivider(inset = DATED_ROW_INSET)
                ListRow(
                    stringResource(Res.string.today_tasks_more, upcoming.more.toString()),
                    modifier = Modifier.testTag("today_upcoming_more"),
                    titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    showChevron = true,
                    onClick = actions.onCalendarList,
                )
            }
        }
    }
}
