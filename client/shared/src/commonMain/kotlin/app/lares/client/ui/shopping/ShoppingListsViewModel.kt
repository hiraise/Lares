package app.lares.client.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.shopping.ShoppingInputs
import app.lares.client.data.shopping.ShoppingListView
import app.lares.client.data.shopping.ShoppingRepository
import app.lares.client.data.shopping.ShoppingViews
import app.lares.client.data.shopping.ShoppingWriter
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShoppingListsUiState(
    val loaded: Boolean = false,
    /** In creation order. */
    val lists: List<ShoppingListView> = emptyList(),
    /** `shopping.manage_lists`: the FAB and the empty state's button. */
    val canCreate: Boolean = false,
)

sealed interface ShoppingListsEvent {
    /** A new list was created: open it. */
    data class Created(
        val id: String,
    ) : ShoppingListsEvent

    data class Failed(
        val error: UiError,
    ) : ShoppingListsEvent
}

/** The Shopping tab (plan M2-04, SHP-001): the family's lists as cards, creating a list. */
class ShoppingListsViewModel(
    repository: ShoppingRepository,
    private val writer: ShoppingWriter,
) : ViewModel() {
    private val eventChannel = Channel<ShoppingListsEvent>(Channel.BUFFERED)

    val events: Flow<ShoppingListsEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<ShoppingListsUiState> =
        repository
            .observeInputs()
            .map(::build)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ShoppingListsUiState())

    /** Creates a list named [name] (trimmed; blank is ignored) and asks the screen to open it. */
    fun createList(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            attempt { writer.createList(trimmed) }
                .onSuccess { eventChannel.send(ShoppingListsEvent.Created(it)) }
                .onFailure { eventChannel.send(ShoppingListsEvent.Failed(it.toUiError())) }
        }
    }

    private fun build(inputs: ShoppingInputs) =
        ShoppingListsUiState(
            loaded = true,
            lists = ShoppingViews.lists(inputs),
            canCreate = inputs.viewer?.permissions?.has(Permission.SHOPPING_MANAGE_LISTS) == true,
        )
}
