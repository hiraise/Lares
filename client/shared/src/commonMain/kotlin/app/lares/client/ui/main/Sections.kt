package app.lares.client.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.ic_account_balance_wallet
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
import app.lares.client.resources.today_empty
import app.lares.client.ui.common.todayLine
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ScreenHeader
import app.lares.client.ui.components.initialsFor
import app.lares.contract.permissions.Section
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** FND-063: date line and title, sync indicator and own avatar (→ More); the summary comes with the modules. */
@Composable
fun TodayScreen(
    state: MainUiState,
    onOpenSync: () -> Unit,
    onOpenMore: () -> Unit,
) {
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
        EmptyState(Res.drawable.ic_home, stringResource(Res.string.today_empty))
    }
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
