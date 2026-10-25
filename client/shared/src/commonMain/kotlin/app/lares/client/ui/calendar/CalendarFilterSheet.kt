package app.lares.client.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.cal_filter
import app.lares.client.resources.cal_filter_any_tag
import app.lares.client.resources.cal_filter_everyone
import app.lares.client.resources.cal_filter_reset
import app.lares.client.resources.cal_filter_show_tasks
import app.lares.client.resources.cal_filter_tag
import app.lares.client.resources.cal_filter_whose
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.CheckRow
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.tasks.EditorSheet
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tones
import org.jetbrains.compose.resources.stringResource

/** «Фильтр» (CAL-008, CAL-009, TAG-003): whose calendar, tag, «Показывать задачи»; every choice applies at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarFilterSheet(
    state: CalendarUiState,
    actions: CalendarActions,
    onDismiss: () -> Unit,
) {
    val filter = state.filter
    EditorSheet(stringResource(Res.string.cal_filter), onDismiss) {
        Text(stringResource(Res.string.cal_filter_whose), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AppFilterChip(
                filter.personId == null,
                stringResource(Res.string.cal_filter_everyone),
                { actions.onPerson(null) },
                Modifier.testTag("filter_person_all"),
            )
            state.persons.forEach { person ->
                AppFilterChip(
                    filter.personId == person.id,
                    person.data.name,
                    { actions.onPerson(person.id) },
                    Modifier.testTag("filter_person_${person.id}"),
                    leading = { Avatar(initialsFor(person.data.name, state.personNames), person.color, size = 20.dp) },
                )
            }
        }
        if (state.tags.isNotEmpty()) {
            Text(stringResource(Res.string.cal_filter_tag), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AppFilterChip(
                    filter.tagId == null,
                    stringResource(Res.string.cal_filter_any_tag),
                    { actions.onTag(null) },
                    Modifier.testTag("filter_tag_any"),
                )
                state.tags.forEach { tag ->
                    AppFilterChip(
                        filter.tagId == tag.id,
                        tag.data.name,
                        { actions.onTag(tag.id) },
                        Modifier.testTag("filter_tag_${tag.id}"),
                        leading = {
                            Box(
                                Modifier.size(10.dp).background(
                                    tag.data.color
                                        .tones()
                                        .base,
                                    CircleShape,
                                ),
                            )
                        },
                    )
                }
            }
        }
        if (state.canFilterTasks) {
            ListGroup {
                CheckRow(
                    stringResource(Res.string.cal_filter_show_tasks),
                    filter.showTasks,
                    actions.onShowTasks,
                    Modifier.testTag("filter_show_tasks"),
                )
            }
        }
        if (state.filtered) LinkButton(stringResource(Res.string.cal_filter_reset), actions.onResetFilter, Modifier.testTag("filter_reset"))
    }
}
