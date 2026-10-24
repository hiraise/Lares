package app.lares.client.ui.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_shopping_cart
import app.lares.client.resources.nav_shopping
import app.lares.client.resources.shopping_create
import app.lares.client.resources.shopping_empty_create
import app.lares.client.resources.shopping_empty_text
import app.lares.client.resources.shopping_empty_title
import app.lares.client.resources.shopping_list_name
import app.lares.client.resources.shopping_list_name_hint
import app.lares.client.resources.shopping_new_list
import app.lares.client.resources.shopping_new_list_title
import app.lares.client.ui.common.messageRes
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ExtendedGradientFab
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.ScreenHeader
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The Shopping tab inside the main scaffold; [snackbar] is the scaffold's. */
@Composable
fun ShoppingRoute(
    snackbar: SnackbarHostState,
    onList: (String) -> Unit,
    viewModel: ShoppingListsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ShoppingListsEvent.Created -> onList(event.id)
                is ShoppingListsEvent.Failed -> snackbar.showSnackbar(getString(event.error.messageRes()))
            }
        }
    }
    ShoppingListsScreen(state, onOpen = onList, onCreate = viewModel::createList)
}

/** The Shopping tab (plan M2-04): header, list cards, «Список» FAB, empty state. */
@Composable
fun ShoppingListsScreen(
    state: ShoppingListsUiState,
    onOpen: (String) -> Unit = {},
    onCreate: (String) -> Unit = {},
) {
    var newList by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(stringResource(Res.string.nav_shopping))
            Column(
                Modifier.padding(horizontal = Spacing.screen).padding(bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                if (state.loaded && state.lists.isEmpty()) EmptyLists(state.canCreate, onCreate = { newList = true })
                state.lists.forEach { list -> ShoppingListCard(list, onClick = { onOpen(list.id) }) }
            }
        }
        if (state.canCreate) {
            ExtendedGradientFab(
                Res.drawable.ic_add,
                stringResource(Res.string.shopping_new_list),
                { newList = true },
                Modifier.align(Alignment.BottomEnd).padding(Spacing.l).testTag("shopping_fab"),
            )
        }
    }
    if (newList) {
        ListNameDialog(
            title = stringResource(Res.string.shopping_new_list_title),
            initial = "",
            confirmLabel = stringResource(Res.string.shopping_create),
            onConfirm = {
                newList = false
                onCreate(it)
            },
            onDismiss = { newList = false },
        )
    }
}

@Composable
private fun EmptyLists(
    canCreate: Boolean,
    onCreate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (canCreate) {
            EmptyState(
                Res.drawable.ic_shopping_cart,
                stringResource(Res.string.shopping_empty_text),
                title = stringResource(Res.string.shopping_empty_title),
            )
            PrimaryButton(stringResource(Res.string.shopping_empty_create), onCreate, Modifier.testTag("shopping_empty_create"))
        } else {
            EmptyState(Res.drawable.ic_shopping_cart, stringResource(Res.string.shopping_empty_title))
        }
    }
}

/** «Новый список» / «Переименовать список»: one name field; the confirm button is off while the name is blank. */
@Composable
internal fun ListNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val confirm = { if (name.isNotBlank()) onConfirm(name.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            FormField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(Res.string.shopping_list_name),
                hint = stringResource(Res.string.shopping_list_name_hint),
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
                onDone = confirm,
                focusRequester = focus,
            )
        },
        confirmButton = {
            LinkButton(confirmLabel, confirm, Modifier.testTag("shopping_name_confirm"), enabled = name.isNotBlank())
        },
        dismissButton = { LinkButton(stringResource(Res.string.action_cancel), onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}
