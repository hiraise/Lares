package app.lares.client.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.Tag
import app.lares.client.data.TagsRepository
import app.lares.client.data.TagsWriter
import app.lares.client.data.shopping.ShoppingItemView
import app.lares.client.data.shopping.ShoppingListDetail
import app.lares.client.data.shopping.ShoppingRepository
import app.lares.client.data.shopping.ShoppingViews
import app.lares.client.data.shopping.ShoppingWriter
import app.lares.client.data.shopping.Suggestion
import app.lares.client.sync.SyncEngine
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.client.ui.family.TagEditor
import app.lares.client.ui.family.TagSheet
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.permissions.Permission
import app.lares.contract.shopping.ShoppingNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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

/** The editable fields of the item sheet, as typed. */
data class ItemForm(
    val name: String,
    val quantity: String,
    val note: String,
    val category: ProductCategory,
    val tagIds: Set<String>,
) {
    companion object {
        fun of(data: ShoppingItemData) = ItemForm(data.name, data.quantity.orEmpty(), data.note.orEmpty(), data.category, data.tagIds)
    }
}

/** What the item sheet opens on top of itself. */
enum class ItemPicker { DEPARTMENT, TAGS }

/** The item sheet (SHP-010): [item] as it was when opened (for the info lines), the form and its pickers. */
data class ItemSheetState(
    val item: ShoppingItemView,
    val form: ItemForm,
    val initial: ItemForm,
    val picker: ItemPicker? = null,
    /** «Не сохранять изменения?» is shown. */
    val confirmDiscard: Boolean = false,
) {
    val changed: Boolean get() = form != initial
    val canSave: Boolean get() = changed && form.name.isNotBlank()
}

data class ShoppingListUiState(
    val loaded: Boolean = false,
    val detail: ShoppingListDetail? = null,
    /** The in-store offline banner (SHP-011); null while online. */
    val banner: UiText? = null,
    /** «Куплено · N» expanded; collapsed by default, not persisted. */
    val boughtExpanded: Boolean = false,
    val dialog: ShoppingListDialog? = null,
    /** The tag the page is filtered by (TAG-003); null = «Все». */
    val tagId: String? = null,
    /** Names matching the typed text (SHP-008); empty for fewer than 2 characters. */
    val autocomplete: List<Suggestion> = emptyList(),
    val sheet: ItemSheetState? = null,
    /** The list tags sheet is open with this selection. */
    val listTags: Set<String>? = null,
    /** «Больше не предлагать» is asked for this suggestion. */
    val forget: ShoppingItemView? = null,
    /** All tags, archived included (pickers). */
    val tags: List<Tag> = emptyList(),
    /** `tags.manage`: «+ Новая метка» in the pickers. */
    val canManageTags: Boolean = false,
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

    /** «Убрать из списка»; undo puts the item back to [previous]. */
    data class Removed(
        val id: String,
        val previous: ShoppingItemStatus,
    ) : ShoppingEvent

    /** The member may not change the list. */
    data object NotAllowed : ShoppingEvent

    data class Failed(
        val error: UiError,
    ) : ShoppingEvent
}

/**
 * The list page (plans M2-04, M2-05): quick add with suggestions and autocomplete, ticking, «Куплено», clearing with
 * undo, the item sheet, list tags and the tag filter, rename, grouping, delete, and the offline banner.
 */
@OptIn(FlowPreview::class)
class ShoppingListViewModel(
    repository: ShoppingRepository,
    private val writer: ShoppingWriter,
    syncEngine: SyncEngine,
    tagsRepository: TagsRepository,
    tagsWriter: TagsWriter,
    args: ShoppingListArgs,
) : ViewModel() {
    private val id = args.id
    private val eventChannel = Channel<ShoppingEvent>(Channel.BUFFERED)
    private val local = MutableStateFlow(Local())
    private val query = MutableStateFlow("")

    val events: Flow<ShoppingEvent> = eventChannel.receiveAsFlow()

    /** «+ Новая метка» in the tag pickers. */
    val tagEditor = TagEditor(viewModelScope, tagsRepository, tagsWriter)
    val tagSheet: StateFlow<TagSheet?> = tagEditor.sheet

    val state: StateFlow<ShoppingListUiState> =
        combine(
            repository.observeInputs(),
            syncEngine.status,
            local,
            query.debounce { if (it.isBlank()) 0L else AUTOCOMPLETE_DEBOUNCE_MS },
        ) { inputs, sync, local, query ->
            var tagId = local.tagId
            var detail = ShoppingViews.detail(inputs, id, tagId)
            if (tagId != null && detail != null && detail.tagsInUse.none { it.id == tagId }) {
                // The tag is no longer on any item of the list: back to «Все».
                tagId = null
                this.local.update { it.copy(tagId = null) }
                detail = ShoppingViews.detail(inputs, id, null)
            }
            ShoppingListUiState(
                loaded = true,
                detail = detail,
                banner = ShoppingLogic.banner(sync),
                boughtExpanded = local.boughtExpanded,
                dialog = local.dialog,
                tagId = tagId,
                autocomplete = if (detail?.canEdit == true) ShoppingViews.autocomplete(inputs, id, query) else emptyList(),
                sheet = local.sheet,
                listTags = local.listTags,
                forget = local.forget,
                tags = inputs.tags,
                canManageTags = inputs.viewer?.permissions?.has(Permission.TAGS_MANAGE) == true,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ShoppingListUiState())

    private val detail get() = state.value.detail

    /** Quick add (SHP-003, SHP-009); names already in the list come back as an event. While filtered, items get the tag. */
    fun add(text: String) {
        if (text.isBlank() || !allowed { it.canEdit }) return
        val tagId = local.value.tagId
        write {
            val result = writer.add(id, text, tagId)
            if (result.alreadyThere.isNotEmpty()) eventChannel.send(ShoppingEvent.AlreadyThere(result.alreadyThere))
        }
    }

    /** The typed text of the add field, for the autocomplete. */
    fun onQuery(text: String) {
        query.value = text
    }

    /** A suggestion chip or an autocomplete row (SHP-008): brings the item back or adds it with its department. */
    fun addSuggestion(suggestion: Suggestion) {
        if (!allowed { it.canEdit }) return
        val tagId = local.value.tagId
        write { writer.addSuggestion(id, suggestion, tagId) }
    }

    /** A tap on a suggestion chip: the list's own cleared row comes back. */
    fun addSuggestion(item: ShoppingItemView) = addSuggestion(Suggestion(item.data.name, item.data.category, item.data.note, item.id))

    /** Long press on a chip: asks «Больше не предлагать». */
    fun askForget(item: ShoppingItemView) {
        if (detail?.canEdit != true) return
        local.update { it.copy(forget = item) }
    }

    fun closeForget() = local.update { it.copy(forget = null) }

    fun forget() {
        val item = local.value.forget ?: return
        closeForget()
        write { writer.forget(item.id) }
    }

    /** A tap on a row: ticks an item to buy, returns a bought one to the list (SHP-004). */
    fun toggle(item: ShoppingItemView) {
        if (!allowed { it.canEdit }) return
        write { writer.setBought(item.id, item.data.status != ShoppingItemStatus.BOUGHT) }
    }

    /** «Убрать купленные» (only the tagged ones while filtered): the snackbar offers undo. */
    fun clearBought() {
        if (!allowed { it.canEdit }) return
        val tagId = local.value.tagId
        write {
            val ids = writer.clearBought(id, tagId)
            if (ids.isNotEmpty()) eventChannel.send(ShoppingEvent.Cleared(ids))
        }
    }

    fun undoClear(ids: List<String>) = write { writer.restore(ids, ShoppingItemStatus.BOUGHT) }

    fun toggleBought() = local.update { it.copy(boughtExpanded = !it.boughtExpanded) }

    /** The tag filter chips (TAG-003); null = «Все». */
    fun filterBy(tagId: String?) = local.update { it.copy(tagId = tagId) }

    // The item sheet (SHP-002, SHP-006, SHP-010).

    /** Long press on a row; without `shopping.edit` nothing happens. */
    fun openItem(item: ShoppingItemView) {
        if (detail?.canEdit != true) return
        val form = ItemForm.of(item.data)
        local.update { it.copy(sheet = ItemSheetState(item, form, form)) }
    }

    fun editItem(change: (ItemForm) -> ItemForm) = updateSheet { it.copy(form = change(it.form)) }

    fun openPicker(picker: ItemPicker?) = updateSheet { it.copy(picker = picker) }

    fun onDepartment(category: ProductCategory) = updateSheet { it.copy(form = it.form.copy(category = category), picker = null) }

    fun toggleItemTag(tagId: String) = editItem { it.copy(tagIds = it.tagIds.toggled(tagId)) }

    /** Dismissing the sheet: with changes it asks «Не сохранять изменения?» first. */
    fun requestCloseItem() {
        val sheet = local.value.sheet ?: return
        if (sheet.changed) updateSheet { it.copy(confirmDiscard = true) } else closeItem()
    }

    fun keepEditing() = updateSheet { it.copy(confirmDiscard = false) }

    fun closeItem() = local.update { it.copy(sheet = null) }

    /** «Сохранить»: the changed fields, then the department, remembered for the name in all lists (Q-019). */
    fun saveItem() {
        val sheet = local.value.sheet ?: return
        if (!sheet.canSave) return
        closeItem()
        val form = sheet.form
        write {
            writer.updateItem(
                sheet.item.id,
                ShoppingNames.normalize(form.name),
                form.quantity.trim().ifEmpty { null },
                form.note.trim().ifEmpty { null },
                form.tagIds,
            )
            if (form.category != sheet.initial.category) writer.setCategory(sheet.item.id, form.category)
        }
    }

    /** «Убрать из списка»: the item stays as a suggestion; the snackbar offers undo. */
    fun removeItem() {
        val sheet = local.value.sheet ?: return
        closeItem()
        write {
            val previous = writer.remove(sheet.item.id)
            eventChannel.send(ShoppingEvent.Removed(sheet.item.id, previous))
        }
    }

    fun undoRemove(
        itemId: String,
        previous: ShoppingItemStatus,
    ) = write { writer.restore(listOf(itemId), previous) }

    // List tags (SHP-014, TAG-002).

    fun openListTags() {
        val list = detail?.takeIf { it.canManage }?.list ?: return
        local.update { it.copy(listTags = list.data.tagIds) }
    }

    fun toggleListTag(tagId: String) = local.update { it.copy(listTags = it.listTags?.toggled(tagId)) }

    /** Closing the list tags sheet saves the selection if it changed. */
    fun closeListTags() {
        val selected = local.value.listTags ?: return
        local.update { it.copy(listTags = null) }
        if (selected == detail?.list?.data?.tagIds) return
        write { writer.updateList(id, tagIds = selected) }
    }

    /** «+ Новая метка» in the open tags picker: the saved tag is selected there. */
    fun newTag() =
        tagEditor.new(
            state.value.tags
                .filter { !it.data.archived }
                .map { it.data.color },
        ) { tagId ->
            local.update {
                when {
                    it.listTags != null -> it.copy(listTags = it.listTags + tagId)
                    it.sheet != null -> it.copy(sheet = it.sheet.copy(form = it.sheet.form.copy(tagIds = it.sheet.form.tagIds + tagId)))
                    else -> it
                }
            }
        }

    // List actions.

    fun openDialog(which: ShoppingListDialog) = local.update { it.copy(dialog = which) }

    fun closeDialog() = local.update { it.copy(dialog = null) }

    fun rename(name: String) {
        val trimmed = name.trim()
        closeDialog()
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
        closeDialog()
        write { writer.deleteList(id) }
    }

    private fun updateSheet(change: (ItemSheetState) -> ItemSheetState) = local.update { it.copy(sheet = it.sheet?.let(change)) }

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

    /** What the page holds besides the synced data. */
    private data class Local(
        val boughtExpanded: Boolean = false,
        val dialog: ShoppingListDialog? = null,
        val tagId: String? = null,
        val sheet: ItemSheetState? = null,
        val listTags: Set<String>? = null,
        val forget: ShoppingItemView? = null,
    )

    private companion object {
        const val AUTOCOMPLETE_DEBOUNCE_MS = 150L

        fun Set<String>.toggled(id: String) = if (id in this) this - id else this + id
    }
}
