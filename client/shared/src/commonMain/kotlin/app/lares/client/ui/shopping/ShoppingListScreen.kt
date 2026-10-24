package app.lares.client.ui.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.data.shopping.DepartmentSection
import app.lares.client.data.shopping.ShoppingItemView
import app.lares.client.data.shopping.ShoppingListDetail
import app.lares.client.resources.Res
import app.lares.client.resources.action_delete
import app.lares.client.resources.action_save
import app.lares.client.resources.action_undo
import app.lares.client.resources.ic_add_shopping_cart
import app.lares.client.resources.ic_category
import app.lares.client.resources.ic_check
import app.lares.client.resources.ic_delete
import app.lares.client.resources.ic_edit
import app.lares.client.resources.ic_more_vert
import app.lares.client.resources.ic_remove_done
import app.lares.client.resources.shopping_all_bought
import app.lares.client.resources.shopping_clear_bought
import app.lares.client.resources.shopping_delete_list
import app.lares.client.resources.shopping_delete_text
import app.lares.client.resources.shopping_delete_title
import app.lares.client.resources.shopping_group_by_department
import app.lares.client.resources.shopping_list_actions
import app.lares.client.resources.shopping_list_empty
import app.lares.client.resources.shopping_not_allowed
import app.lares.client.resources.shopping_rename
import app.lares.client.resources.shopping_rename_title
import app.lares.client.ui.common.load
import app.lares.client.ui.common.messageRes
import app.lares.client.ui.common.rememberTimeFormatter
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.NoticeBanner
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the list page can do; defaults do nothing (tests). */
class ShoppingListActions(
    val onBack: () -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onToggle: (ShoppingItemView) -> Unit = {},
    /** Long press: the item sheet (M2-05). */
    val onItemSheet: (ShoppingItemView) -> Unit = {},
    val onToggleBought: () -> Unit = {},
    val onClearBought: () -> Unit = {},
    val onToggleGrouping: () -> Unit = {},
    val onDialog: (ShoppingListDialog) -> Unit = {},
    val onCloseDialog: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
fun ShoppingListRoute(
    id: String,
    onBack: () -> Unit,
    viewModel: ShoppingListViewModel = koinViewModel(key = "shopping-$id") { parametersOf(ShoppingListArgs(id)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Deleted here or on another phone.
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    ShoppingEventSnackbars(viewModel.events, snackbar, onUndoClear = viewModel::undoClear)
    Box(Modifier.fillMaxSize()) {
        ShoppingListScreen(
            state,
            ShoppingListActions(
                onBack = onBack,
                onAdd = viewModel::add,
                onToggle = viewModel::toggle,
                onToggleBought = viewModel::toggleBought,
                onClearBought = viewModel::clearBought,
                onToggleGrouping = viewModel::toggleGrouping,
                onDialog = viewModel::openDialog,
                onCloseDialog = viewModel::closeDialog,
                onRename = viewModel::rename,
                onDelete = viewModel::delete,
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

/** «Уже в списке: …», «Убрано: 6» with «Отменить» for 5 s, «Нет права изменять список», errors. */
@Composable
private fun ShoppingEventSnackbars(
    events: Flow<ShoppingEvent>,
    snackbar: SnackbarHostState,
    onUndoClear: (List<String>) -> Unit,
) {
    LaunchedEffect(events) {
        events.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
                when (event) {
                    is ShoppingEvent.AlreadyThere -> {
                        snackbar.showSnackbar(ShoppingLogic.alreadyThere(event.names).load())
                    }

                    is ShoppingEvent.Cleared -> {
                        val result =
                            withTimeoutOrNull(UNDO_MS) {
                                snackbar.showSnackbar(
                                    ShoppingLogic.cleared(event.ids.size).load(),
                                    getString(Res.string.action_undo),
                                    duration = SnackbarDuration.Indefinite,
                                )
                            }
                        if (result == SnackbarResult.ActionPerformed) onUndoClear(event.ids)
                    }

                    ShoppingEvent.NotAllowed -> {
                        snackbar.showSnackbar(getString(Res.string.shopping_not_allowed))
                    }

                    is ShoppingEvent.Failed -> {
                        snackbar.showSnackbar(getString(event.error.messageRes()))
                    }
                }
            }
        }
    }
}

private const val UNDO_MS = 5_000L

/**
 * The list page (plan M2-04, mockup `Shopping.dc.html`): offline banner, progress, quick add, items by department,
 * «Куплено · N».
 */
@Composable
fun ShoppingListScreen(
    state: ShoppingListUiState,
    actions: ShoppingListActions = ShoppingListActions(),
) {
    val detail = state.detail
    val time = rememberTimeFormatter()
    AppPage(topBar = { TopBar(detail, actions) }) {
        state.banner?.let { NoticeBanner(it.text(), Modifier.testTag("shopping_banner")) }
        if (detail == null) return@AppPage
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            ShoppingProgress(detail.list)
            if (detail.canEdit) QuickAddField(actions.onAdd)
        }
        Items(detail, actions)
        if (detail.bought.isNotEmpty()) {
            BoughtSection(
                detail.bought,
                expanded = state.boughtExpanded,
                canEdit = detail.canEdit,
                time = time,
                onToggleExpanded = actions.onToggleBought,
                onUntick = actions.onToggle,
                onClear = actions.onClearBought,
            )
        }
    }
    when (state.dialog.takeIf { detail != null }) {
        ShoppingListDialog.RENAME -> {
            ListNameDialog(
                title = stringResource(Res.string.shopping_rename_title),
                initial = detail!!.list.data.name,
                confirmLabel = stringResource(Res.string.action_save),
                onConfirm = actions.onRename,
                onDismiss = actions.onCloseDialog,
            )
        }

        ShoppingListDialog.DELETE -> {
            ConfirmDialog(
                title = stringResource(Res.string.shopping_delete_title, detail!!.list.data.name),
                text = stringResource(Res.string.shopping_delete_text),
                confirmLabel = stringResource(Res.string.action_delete),
                onConfirm = actions.onDelete,
                onDismiss = actions.onCloseDialog,
                destructive = true,
            )
        }

        null -> {}
    }
}

@Composable
private fun Items(
    detail: ShoppingListDetail,
    actions: ShoppingListActions,
) {
    val needed = detail.sections.sumOf { it.items.size }
    when {
        needed == 0 && detail.bought.isEmpty() -> {
            EmptyState(Res.drawable.ic_add_shopping_cart, stringResource(Res.string.shopping_list_empty))
        }

        needed == 0 -> {
            ListGroup {
                ListRow(
                    stringResource(Res.string.shopping_all_bought),
                    icon = Res.drawable.ic_check,
                    iconTint = LaresTheme.colors.success,
                    titleColor = LaresTheme.colors.success,
                )
            }
        }

        else -> {
            detail.sections.forEach { Section(it, detail.canEdit, actions) }
        }
    }
}

@Composable
private fun Section(
    section: DepartmentSection,
    canEdit: Boolean,
    actions: ShoppingListActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        section.category?.let {
            SectionHeader(stringResource(ShoppingLogic.categoryLabel(it)), Modifier.testTag("shopping_department_${it.key}"))
        }
        ListGroup {
            section.items.forEachIndexed { index, item ->
                if (index > 0) ItemDivider()
                ShoppingItemRow(
                    item,
                    checked = false,
                    onClick = { actions.onToggle(item) },
                    meta = item.data.note,
                    adder = ShoppingLogic.adder(item),
                    enabled = canEdit,
                    onLongClick = { actions.onItemSheet(item) },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    detail: ShoppingListDetail?,
    actions: ShoppingListActions,
) {
    var menu by remember { mutableStateOf(false) }
    DetailTopBar(
        detail
            ?.list
            ?.data
            ?.name
            .orEmpty(),
        actions.onBack,
    ) {
        if (detail == null) return@DetailTopBar
        val canClear = detail.canEdit && detail.bought.isNotEmpty()
        if (!detail.canManage && !canClear) return@DetailTopBar
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("shopping_more")) {
                AppIcon(Res.drawable.ic_more_vert, stringResource(Res.string.shopping_list_actions))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val close = { action: () -> Unit ->
                    menu = false
                    action()
                }
                if (detail.canManage) {
                    MenuItem(Res.string.shopping_rename, Res.drawable.ic_edit) { close { actions.onDialog(ShoppingListDialog.RENAME) } }
                    MenuItem(Res.string.shopping_group_by_department, Res.drawable.ic_category, detail.list.data.groupByCategory) {
                        close(actions.onToggleGrouping)
                    }
                }
                if (canClear) MenuItem(Res.string.shopping_clear_bought, Res.drawable.ic_remove_done) { close(actions.onClearBought) }
                if (detail.canManage) {
                    MenuItem(
                        Res.string.shopping_delete_list,
                        Res.drawable.ic_delete,
                    ) { close { actions.onDialog(ShoppingListDialog.DELETE) } }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: StringResource,
    icon: DrawableResource,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { AppIcon(icon, null) },
        trailingIcon = if (checked) ({ AppIcon(Res.drawable.ic_check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
        onClick = onClick,
    )
}
