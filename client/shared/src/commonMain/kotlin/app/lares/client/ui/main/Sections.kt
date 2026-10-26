package app.lares.client.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.ic_account_balance_wallet
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_calendar_month
import app.lares.client.resources.ic_home
import app.lares.client.resources.ic_location_on
import app.lares.client.resources.ic_shopping_cart
import app.lares.client.resources.ic_task_alt
import app.lares.client.resources.nav_calendar
import app.lares.client.resources.nav_shopping
import app.lares.client.resources.nav_tasks
import app.lares.client.resources.nav_today
import app.lares.client.resources.placeholder_coming
import app.lares.client.resources.profile_open
import app.lares.client.resources.section_budget
import app.lares.client.resources.section_map
import app.lares.client.resources.today_add
import app.lares.client.resources.today_empty
import app.lares.client.resources.today_tasks_all
import app.lares.client.resources.today_tasks_more
import app.lares.client.resources.today_tasks_none
import app.lares.client.resources.today_tasks_title
import app.lares.client.ui.common.todayLine
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.GradientFab
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.ScreenHeader
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.tasks.TaskRow
import app.lares.client.ui.tasks.TaskRowUi
import app.lares.client.ui.theme.Spacing
import app.lares.contract.permissions.Section
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What the Today screen's tasks block does. */
class TodayActions(
    val onToggle: (TaskRowUi) -> Unit = {},
    val onTask: (String) -> Unit = {},
    /** «Все» and «Ещё N»: the Tasks tab, scope Mine. */
    val onAllTasks: () -> Unit = {},
    val onCreate: () -> Unit = {},
    /** «Открыть» on the shopping card: the list page. */
    val onShoppingList: (String) -> Unit = {},
    /** «Ещё N списка»: the Shopping tab. */
    val onAllShopping: () -> Unit = {},
    /** A «Скоро» event or memorable date: its id and the scheduled date of the occurrence. */
    val onEvent: (String, LocalDate) -> Unit = { _, _ -> },
    /** A «Скоро» birthday: the person page. */
    val onPerson: (String) -> Unit = {},
    /** «Календарь»: the Calendar tab. */
    val onCalendar: () -> Unit = {},
    /** «Ещё N» under «Скоро»: the Calendar tab in «Список». */
    val onCalendarList: () -> Unit = {},
    /** «Семья сейчас»: the Map, centred on the member when given. */
    val onMap: (String?) -> Unit = {},
)

/**
 * FND-063: date line and title, sync indicator and own avatar (→ More), the active shopping list (SHP-013), the tasks
 * block (TSK-023), «Скоро» (CAL-019) and the FAB.
 */
@Composable
fun TodayScreen(
    state: MainUiState,
    onOpenSync: () -> Unit,
    onOpenMore: () -> Unit,
    today: TodayUiState = TodayUiState(),
    actions: TodayActions = TodayActions(),
    familyNow: TodayFamilyNowUi? = null,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(
                title = stringResource(Res.string.nav_today),
                contextLine = todayLine(),
            ) {
                SyncIndicator(state.sync, onOpenSync)
                val description = stringResource(Res.string.profile_open)
                IconButton(onClick = onOpenMore, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
                    Avatar(initialsFor(state.name), state.color, size = 32.dp, ring = true)
                }
            }
            if (today.empty && familyNow == null) {
                EmptyState(Res.drawable.ic_home, stringResource(Res.string.today_empty))
            } else if (today.loaded) {
                Column(
                    Modifier.padding(horizontal = Spacing.screen).padding(bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                ) {
                    today.shopping?.let { card ->
                        TodayShoppingBlock(card, onOpen = { actions.onShoppingList(card.listId) }, onOthers = actions.onAllShopping)
                    }
                    if (today.showTasks) TodayTasks(today, actions)
                    today.upcoming?.let { TodayUpcoming(it, actions) }
                    familyNow?.let { TodayFamilyNow(it, actions) }
                }
            }
        }
        if (today.canCreate) {
            GradientFab(
                Res.drawable.ic_add,
                stringResource(Res.string.today_add),
                actions.onCreate,
                Modifier.align(Alignment.BottomEnd).padding(Spacing.l).testTag("today_fab"),
            )
        }
    }
}

/** «Задачи на сегодня» with a count badge and «Все», then the rows (mockup `Main.dc.html`). */
@Composable
fun TodayTasks(
    today: TodayUiState,
    actions: TodayActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("today_tasks"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SectionHeader(stringResource(Res.string.today_tasks_title))
            if (today.count > 0) CountBadge(today.count)
            Spacer(Modifier.weight(1f))
            LinkButton(stringResource(Res.string.today_tasks_all), actions.onAllTasks, Modifier.testTag("today_all"))
        }
        ListGroup {
            if (today.rows.isEmpty()) {
                ListRow(stringResource(Res.string.today_tasks_none), icon = Res.drawable.ic_task_alt)
            }
            today.rows.forEachIndexed { index, row ->
                if (index > 0) ListDivider(inset = 52.dp)
                TaskRow(row, onToggle = { actions.onToggle(row) }, onClick = { actions.onTask(row.view.id) }, allNames = today.allNames)
            }
            if (today.more > 0) {
                ListDivider(inset = 52.dp)
                ListRow(
                    stringResource(Res.string.today_tasks_more, today.more.toString()),
                    modifier = Modifier.testTag("today_more"),
                    titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    showChevron = true,
                    onClick = actions.onAllTasks,
                )
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Text(
        count.toString(),
        modifier =
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = Spacing.s, vertical = 1.dp)
                .testTag("today_count"),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/** Large title + icon + "Coming in the next versions" until the module exists. */
@Composable
fun SectionPlaceholder(section: Section) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(section.title()))
        EmptyState(section.icon(), stringResource(Res.string.placeholder_coming))
    }
}

/** Map and Budget open from More as detail screens until their modules exist. */
@Composable
fun SectionPlaceholderPage(
    section: Section,
    onBack: () -> Unit,
) {
    AppPage(topBar = { DetailTopBar(stringResource(section.title()), onBack) }) {
        EmptyState(section.icon(), stringResource(Res.string.placeholder_coming))
    }
}

fun Section.title(): StringResource =
    when (this) {
        Section.TASKS -> Res.string.nav_tasks
        Section.SHOPPING -> Res.string.nav_shopping
        Section.CALENDAR -> Res.string.nav_calendar
        Section.MAP -> Res.string.section_map
        Section.BUDGET -> Res.string.section_budget
    }

fun Section.icon(): DrawableResource =
    when (this) {
        Section.TASKS -> Res.drawable.ic_task_alt
        Section.SHOPPING -> Res.drawable.ic_shopping_cart
        Section.CALENDAR -> Res.drawable.ic_calendar_month
        Section.MAP -> Res.drawable.ic_location_on
        Section.BUDGET -> Res.drawable.ic_account_balance_wallet
    }
