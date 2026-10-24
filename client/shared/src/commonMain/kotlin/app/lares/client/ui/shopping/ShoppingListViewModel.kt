package app.lares.client.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.shopping.ShoppingItemView
import app.lares.client.data.shopping.ShoppingListDetail
import app.lares.client.data.shopping.ShoppingRepository
import app.lares.client.data.shopping.ShoppingWriter
import app.lares.client.sync.SyncEngine
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.entities.ShoppingItemStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Parameters of [ShoppingListViewModel] (Koin `parametersOf`). */
data class ShoppingListArgs(
    val id: String,
)

/** Dialogs of the list page. */
enum class ShoppingListDialog { RENAME, DELETE }

data class ShoppingListUiState(
    val loaded: Boolean = false,
    val detail: ShoppingListDetail? = null,
    /** The in-store offline banner (SHP-011); null while online. */
    val banner: UiText? = null,
    /** «Куплено · N» expanded; collapsed by default, not persisted. */
    val boughtExpanded: Boolean = false,
    val dialog: ShoppingListDialog? = null,
) {
    /** Loaded, but the list does not exist (deleted here or on another phone): the page closes. */
    val gone: Boolean get() = loaded && detail == null
}

/** One-shot results the list page shows as snackbars. */
sealed interface ShoppingEvent {
    /** Names that were already in the list to buy. */
    data class AlreadyThere(
        val names: List<String>,
    ) : ShoppingEvent

    /** Bought items cleared; the snackbar offers undo of [ids]. */
    data class Cleared(
        val ids: List<String>,
    ) : ShoppingEvent

    /** The member may not change the list. */
    data object NotAllowed : ShoppingEvent

    data class Failed(
        val error: UiError,
    ) : ShoppingEvent
}

/**
 * The list page (plan M2-04): quick add, ticking, «Куплено», clearing with undo, rename, grouping, delete, and the
 * offline banner from the sync status.
 */
class ShoppingListViewModel(
    repository: ShoppingRepository,
    private val writer: ShoppingWriter,
    syncEngine: SyncEngine,
    args: ShoppingListArgs,
) : ViewModel() {
    private val id = args.id
    private val eventChannel = Channel<ShoppingEvent>(Channel.BUFFERED)
    private val boughtExpanded = MutableStateFlow(false)
    private val dialog = MutableStateFlow<ShoppingListDialog?>(null)

    val events: Flow<ShoppingEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<ShoppingListUiState> =
        combine(repository.observeDetail(id), syncEngine.status, boughtExpanded, dialog) { detail, sync, expanded, dialog ->
            ShoppingListUiState(
                loaded = true,
                detail = detail,
                banner = ShoppingLogic.banner(sync),
                boughtExpanded = expanded,
                dialog = dialog,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ShoppingListUiState())

    private val detail get() = state.value.detail

    /** Quick add (SHP-003, SHP-009); names already in the list come back as an event. */
    fun add(text: String) {
        if (text.isBlank() || !allowed { it.canEdit }) return
        write {
            val result = writer.add(id, text)
            if (result.alreadyThere.isNotEmpty()) eventChannel.send(ShoppingEvent.AlreadyThere(result.alreadyThere))
        }
    }

    /** A tap on a row: ticks an item to buy, returns a bought one to the list (SHP-004). */
    fun toggle(item: ShoppingItemView) {
        if (!allowed { it.canEdit }) return
        write { writer.setBought(item.id, item.data.status != ShoppingItemStatus.BOUGHT) }
    }

    /** «Убрать купленные»: the snackbar offers undo. */
    fun clearBought() {
        if (!allowed { it.canEdit }) return
        write {
            val ids = writer.clearBought(id)
            if (ids.isNotEmpty()) eventChannel.send(ShoppingEvent.Cleared(ids))
        }
    }

    fun undoClear(ids: List<String>) = write { writer.restore(ids, ShoppingItemStatus.BOUGHT) }

    fun toggleBought() = boughtExpanded.update { !it }

    fun openDialog(which: ShoppingListDialog) {
        dialog.value = which
    }

    fun closeDialog() {
        dialog.value = null
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        dialog.value = null
        if (trimmed.isEmpty() || trimmed == detail?.list?.data?.name) return
        write { writer.updateList(id, name = trimmed) }
    }

    /** «Группировать по отделам» on or off (SHP-006). */
    fun toggleGrouping() {
        val list = detail?.list ?: return
        write { writer.updateList(id, groupByCategory = !list.data.groupByCategory) }
    }

    /** Deletes the list; the page closes when it disappears. */
    fun delete() {
        dialog.value = null
        write { writer.deleteList(id) }
    }

    /** True when the loaded detail grants [check]; otherwise tells the member why nothing happens. */
    private fun allowed(check: (ShoppingListDetail) -> Boolean): Boolean {
        val ok = detail?.let(check) == true
        if (!ok) eventChannel.trySend(ShoppingEvent.NotAllowed)
        return ok
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            attempt { block() }.exceptionOrNull()?.let { eventChannel.send(ShoppingEvent.Failed(it.toUiError())) }
        }
    }
}
