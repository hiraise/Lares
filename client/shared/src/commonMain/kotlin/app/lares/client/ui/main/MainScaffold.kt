package app.lares.client.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.platform.DeepLinkHandler
import app.lares.client.platform.NavTarget
import app.lares.client.platform.PendingNavigation
import app.lares.client.resources.Res
import app.lares.client.resources.action_details
import app.lares.client.resources.ic_apps
import app.lares.client.resources.ic_apps_filled
import app.lares.client.resources.ic_calendar_month
import app.lares.client.resources.ic_calendar_month_filled
import app.lares.client.resources.ic_home
import app.lares.client.resources.ic_home_filled
import app.lares.client.resources.ic_shopping_cart
import app.lares.client.resources.ic_shopping_cart_filled
import app.lares.client.resources.ic_task_alt
import app.lares.client.resources.ic_task_alt_filled
import app.lares.client.resources.link_logged_in
import app.lares.client.resources.nav_calendar
import app.lares.client.resources.nav_more
import app.lares.client.resources.nav_shopping
import app.lares.client.resources.nav_tasks
import app.lares.client.resources.nav_today
import app.lares.client.resources.sync_rejected_snackbar
import app.lares.client.resources.task_deleted
import app.lares.client.ui.common.messageRes
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.family.FamilyTab
import app.lares.client.ui.settings.NotificationPermissionPromptHost
import app.lares.client.ui.tasks.TaskEditorArgs
import app.lares.client.ui.tasks.TaskEventSnackbars
import app.lares.client.ui.tasks.TasksRoute
import app.lares.client.ui.tasks.TasksViewModel
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.permissions.Section
import kotlinx.coroutines.flow.filterNotNull
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Bottom bar items (FND-064). Sections without their `*.view` permission are hidden (FND-028). */
enum class MainTab(
    val label: StringResource,
    val icon: DrawableResource,
    val selectedIcon: DrawableResource,
    val section: Section?,
) {
    TODAY(Res.string.nav_today, Res.drawable.ic_home, Res.drawable.ic_home_filled, null),
    TASKS(Res.string.nav_tasks, Res.drawable.ic_task_alt, Res.drawable.ic_task_alt_filled, Section.TASKS),
    SHOPPING(Res.string.nav_shopping, Res.drawable.ic_shopping_cart, Res.drawable.ic_shopping_cart_filled, Section.SHOPPING),
    CALENDAR(Res.string.nav_calendar, Res.drawable.ic_calendar_month, Res.drawable.ic_calendar_month_filled, Section.CALENDAR),
    MORE(Res.string.nav_more, Res.drawable.ic_apps, Res.drawable.ic_apps_filled, null),
    ;

    companion object {
        fun visible(sections: Set<Section>): List<MainTab> = entries.filter { it.section == null || it.section in sections }
    }
}

/** What the main scaffold opens outside its tabs. */
class MainNavigation(
    val onSettings: () -> Unit,
    val onProfile: () -> Unit,
    val onDevices: () -> Unit,
    val onSection: (Section) -> Unit,
    val onFamily: (FamilyTab) -> Unit = {},
    val onTask: (String) -> Unit = {},
    /** The task editor (a new task with its defaults, or an existing one). */
    val onTaskEditor: (TaskEditorArgs) -> Unit = {},
)

@Composable
fun MainScaffoldRoute(
    navigation: MainNavigation,
    viewModel: MainViewModel = koinViewModel(),
    todayViewModel: TodayViewModel = koinViewModel(),
    tasksViewModel: TasksViewModel = koinViewModel(),
    deepLinks: DeepLinkHandler = koinInject(),
    pendingNavigation: PendingNavigation = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today by todayViewModel.state.collectAsStateWithLifecycle()
    val tab = rememberSaveable { mutableStateOf(MainTab.TODAY) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val shownRejections = rememberSaveable { mutableListOf<Long>() }
    NotificationPermissionPromptHost()

    // New rejections also show a snackbar; the sheet lists them until the user marks them seen.
    LaunchedEffect(state.rejections) {
        val fresh = state.rejections.filter { it.id !in shownRejections }
        if (fresh.isEmpty()) return@LaunchedEffect
        shownRejections += fresh.map { it.id }
        val message = getString(Res.string.sync_rejected_snackbar, getString(fresh.last().code.messageRes()))
        if (snackbar.showSnackbar(message, getString(Res.string.action_details)) == SnackbarResult.ActionPerformed) sheetOpen = true
    }
    // A link opened while logged in: say why nothing happens.
    // Collected rather than keyed on the value: consuming the link must not cancel the snackbar.
    LaunchedEffect(deepLinks) {
        deepLinks.link.filterNotNull().collect {
            if (deepLinks.consume() != null) snackbar.showSnackbar(getString(Res.string.link_logged_in))
        }
    }

    // A tapped notification of a task that is gone (plan M1-07).
    LaunchedEffect(pendingNavigation) {
        pendingNavigation.target.filterNotNull().collect { target ->
            if (target == NavTarget.TaskGone && pendingNavigation.consume(target)) {
                tasksViewModel.resetFilters()
                tab.value = MainTab.TASKS
                snackbar.showSnackbar(getString(Res.string.task_deleted))
            }
        }
    }
    TaskEventSnackbars(todayViewModel.events, snackbar, onUndo = todayViewModel::undo)

    MainScaffold(
        state = state,
        snackbar = snackbar,
        navigation = navigation,
        onOpenSync = { sheetOpen = true },
        tab = tab,
        today = today,
        todayActions =
            TodayActions(
                onToggle = todayViewModel::toggle,
                onTask = navigation.onTask,
                onAllTasks = {
                    tasksViewModel.resetFilters()
                    tab.value = MainTab.TASKS
                },
                onCreate = { navigation.onTaskEditor(TaskEditorArgs(assignToMe = true)) },
            ),
    )
    if (sheetOpen) {
        SyncSheet(
            status = state.sync,
            rejections = state.rejections,
            syncingNow = state.syncingNow,
            onSyncNow = viewModel::syncNow,
            onRejectionsSeen = viewModel::markRejectionsSeen,
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
fun MainScaffold(
    state: MainUiState,
    snackbar: SnackbarHostState,
    navigation: MainNavigation,
    onOpenSync: () -> Unit,
    tab: MutableState<MainTab> = rememberSaveable { mutableStateOf(MainTab.TODAY) },
    today: TodayUiState = TodayUiState(),
    todayActions: TodayActions = TodayActions(),
) {
    var selected by tab
    val tabs = MainTab.visible(state.sections)
    // Access can be taken away while the app is open: fall back to Today.
    val current = if (selected in tabs) selected else MainTab.TODAY
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { MainNavigationBar(tabs, current, onSelect = { selected = it }) },
        contentWindowInsets = WindowInsets.statusBars,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (current) {
                MainTab.TODAY -> {
                    TodayScreen(state, onOpenSync = onOpenSync, onOpenMore = { selected = MainTab.MORE }, today, todayActions)
                }

                MainTab.TASKS -> {
                    TasksRoute(snackbar, onTask = navigation.onTask, onNewTask = navigation.onTaskEditor)
                }

                MainTab.SHOPPING, MainTab.CALENDAR -> {
                    SectionPlaceholder(current.section!!)
                }

                MainTab.MORE -> {
                    MoreScreen(state, navigation, onOpenSync)
                }
            }
        }
    }
}

@Composable
private fun MainNavigationBar(
    tabs: List<MainTab>,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LaresTheme.colors.divider))
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(tab) },
                    icon = { AppIcon(if (isSelected) tab.selectedIcon else tab.icon, null) },
                    label = {
                        Text(
                            org.jetbrains.compose.resources
                                .stringResource(tab.label),
                            style =
                                MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                ),
                        )
                    },
                    colors =
                        NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    modifier = Modifier.testTag("tab_${tab.name}"),
                )
            }
        }
    }
}
