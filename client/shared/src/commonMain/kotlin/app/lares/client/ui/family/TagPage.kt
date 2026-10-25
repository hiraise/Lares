package app.lares.client.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.lares.client.data.Tag
import app.lares.client.data.TagsRepository
import app.lares.client.data.TagsWriter
import app.lares.client.data.calendar.CalendarItem
import app.lares.client.data.calendar.CalendarRepository
import app.lares.client.data.shopping.ShoppingItemView
import app.lares.client.data.shopping.ShoppingListView
import app.lares.client.data.shopping.ShoppingRepository
import app.lares.client.data.shopping.ShoppingTagView
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TaskViews
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.resources.Res
import app.lares.client.resources.ic_edit
import app.lares.client.resources.ic_sell
import app.lares.client.resources.ic_shopping_cart
import app.lares.client.resources.tag_action_edit
import app.lares.client.resources.tag_events_section
import app.lares.client.resources.tag_shopping_section
import app.lares.client.resources.tag_tasks_empty
import app.lares.client.resources.tag_tasks_section
import app.lares.client.ui.calendar.CalendarLogic
import app.lares.client.ui.calendar.DATED_ROW_INSET
import app.lares.client.ui.calendar.DatedItemRow
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.shopping.ShoppingLogic
import app.lares.client.ui.tasks.TaskEvent
import app.lares.client.ui.tasks.TaskEventSnackbars
import app.lares.client.ui.tasks.TaskRow
import app.lares.client.ui.tasks.TaskRowUi
import app.lares.client.ui.tasks.TaskRows
import app.lares.client.ui.tasks.TaskTicker
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tones
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class TagArgs(
    val id: String,
)

data class TagPageUiState(
    val loaded: Boolean = false,
    /** Archived tags open too: they still list their tasks. */
    val tag: Tag? = null,
    /** Open tasks with the tag, ordered like "All". */
    val tasks: List<TaskRowUi> = emptyList(),
    /** Items to buy with the tag in all lists, with their list's name (SHP-014). */
    val shoppingItems: List<Pair<ShoppingItemView, String>> = emptyList(),
    /** Shopping lists with the tag. */
    val shoppingLists: List<ShoppingListView> = emptyList(),
    val canManage: Boolean = false,
    val allNames: List<String> = emptyList(),
    /** The next occurrence of each event with the tag (TAG-004); empty without `calendar.view`. */
    val events: List<CalendarItem.Event> = emptyList(),
    /** The household's today, for the dates of [events]. */
    val today: LocalDate? = null,
) {
    /** Deleted here or on another phone. */
    val gone: Boolean get() = loaded && tag == null

    val hasShopping: Boolean get() = shoppingItems.isNotEmpty() || shoppingLists.isNotEmpty()

    /** Participant names of [events], for distinct initials. */
    val eventNames: List<String> get() = events.flatMap { it.participants.map { p -> p.data.name } }.distinct()
}

/** The tag page (TAG-004): the tag's tasks, shopping and events. Editing the tag uses the shared tag sheet. */
class TagViewModel(
    tasksRepository: TasksRepository,
    tasksWriter: TasksWriter,
    shoppingRepository: ShoppingRepository,
    tagsRepository: TagsRepository,
    tagsWriter: TagsWriter,
    args: TagArgs,
    calendar: CalendarRepository? = null,
) : ViewModel() {
    private val id = args.id
    private val ticker = TaskTicker(viewModelScope, tasksWriter)

    val tagEditor = TagEditor(viewModelScope, tagsRepository, tagsWriter)
    val tagSheet = tagEditor.sheet
    val events: Flow<TaskEvent> = ticker.events

    val state: StateFlow<TagPageUiState> =
        combine(
            tasksRepository.observeInputs(),
            shoppingRepository.observeForTag(id),
            calendar?.observeForTag(id) ?: flowOf(emptyList()),
        ) { inputs, shopping, events -> build(inputs, id, shopping, events) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TagPageUiState())

    fun toggle(row: TaskRowUi) = ticker.toggle(row)

    fun undo(completionId: String) = ticker.undo(completionId)

    fun editTag() {
        state.value.tag?.let(tagEditor::edit)
    }

    companion object {
        fun build(
            inputs: TaskInputs,
            tagId: String,
            shopping: ShoppingTagView? = null,
            events: List<CalendarItem.Event> = emptyList(),
        ): TagPageUiState {
            val rows = TaskRows(inputs)
            val seesShopping = inputs.viewer?.permissions?.has(Permission.SHOPPING_VIEW) == true
            val seesCalendar = inputs.viewer?.permissions?.has(Permission.CALENDAR_VIEW) == true
            return TagPageUiState(
                loaded = true,
                tag = inputs.tags.firstOrNull { it.id == tagId },
                tasks = TaskViews.forTag(TaskViews.build(inputs), tagId).map(rows::row),
                shoppingItems = if (seesShopping) shopping?.items.orEmpty() else emptyList(),
                shoppingLists = if (seesShopping) shopping?.lists.orEmpty() else emptyList(),
                canManage = inputs.viewer?.permissions?.has(Permission.TAGS_MANAGE) == true,
                allNames = rows.allNames,
                events = if (seesCalendar) events else emptyList(),
                today = inputs.now.toLocalDateTime(inputs.zone).date,
            )
        }
    }
}

@Composable
fun TagRoute(
    id: String,
    onBack: () -> Unit,
    onTask: (String) -> Unit,
    /** A shopping list page, filtered by the tag when opened from an item. */
    onShoppingList: (id: String, tagId: String?) -> Unit = { _, _ -> },
    /** An event of the «События» section: its id and the occurrence. */
    onEvent: (String, LocalDate) -> Unit = { _, _ -> },
    viewModel: TagViewModel = koinViewModel(key = "tag-$id") { parametersOf(TagArgs(id)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.tagSheet.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    TaskEventSnackbars(viewModel.events, snackbar, onUndo = viewModel::undo)
    Box(Modifier.fillMaxSize()) {
        TagPage(
            state,
            onBack,
            onTask,
            onToggle = viewModel::toggle,
            onEdit = viewModel::editTag,
            onShoppingList = onShoppingList,
            onEvent = onEvent,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
    sheet?.let { TagSheetDialog(it, viewModel.tagEditor) }
}

@Composable
fun TagPage(
    state: TagPageUiState,
    onBack: () -> Unit,
    onTask: (String) -> Unit = {},
    onToggle: (TaskRowUi) -> Unit = {},
    onEdit: () -> Unit = {},
    onShoppingList: (id: String, tagId: String?) -> Unit = { _, _ -> },
    onEvent: (String, LocalDate) -> Unit = { _, _ -> },
) {
    val tag = state.tag
    AppPage(
        topBar = {
            DetailTopBar(
                tag?.data?.name.orEmpty(),
                onBack,
                leading =
                    tag?.let {
                        {
                            Box(
                                Modifier.size(14.dp).background(
                                    it.data.color
                                        .tones()
                                        .base,
                                    CircleShape,
                                ),
                            )
                        }
                    },
            ) {
                if (state.canManage && tag != null) {
                    IconButton(onClick = onEdit, modifier = Modifier.testTag("tag_edit")) {
                        AppIcon(Res.drawable.ic_edit, stringResource(Res.string.tag_action_edit))
                    }
                }
            }
        },
    ) {
        if (!state.loaded || tag == null) return@AppPage
        // The tasks empty state only when the page has nothing else to show.
        if (state.tasks.isNotEmpty() || (!state.hasShopping && state.events.isEmpty())) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("tag_tasks")) {
                SectionHeader(stringResource(Res.string.tag_tasks_section, state.tasks.size.toString()))
                if (state.tasks.isEmpty()) {
                    EmptyState(Res.drawable.ic_sell, stringResource(Res.string.tag_tasks_empty))
                } else {
                    ListGroup {
                        state.tasks.forEachIndexed { index, row ->
                            if (index > 0) ListDivider(inset = 52.dp)
                            TaskRow(row, onToggle = { onToggle(row) }, onClick = { onTask(row.view.id) }, allNames = state.allNames)
                        }
                    }
                }
            }
        }
        if (state.hasShopping) TagShopping(state, tag.id, onShoppingList)
        if (state.events.isNotEmpty() && state.today != null) TagEvents(state.events, state.today, state.eventNames, onEvent)
    }
}

/** «События · N» (TAG-004): the next occurrence of each event with the tag, with a date tile. */
@Composable
private fun TagEvents(
    events: List<CalendarItem.Event>,
    today: LocalDate,
    allNames: List<String>,
    onEvent: (String, LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("tag_events")) {
        SectionHeader(stringResource(Res.string.tag_events_section, events.size.toString()))
        ListGroup {
            events.forEachIndexed { index, item ->
                if (index > 0) ListDivider(inset = DATED_ROW_INSET)
                DatedItemRow(
                    date = item.occ.startDate,
                    item = item,
                    supporting = CalendarLogic.tagWhen(item, today).text(),
                    today = today,
                    allNames = allNames,
                    onClick = { onEvent(item.occ.eventId, item.occ.occurrence) },
                    modifier = Modifier.testTag("tag_event_${item.occ.eventId}"),
                )
            }
        }
    }
}

/** «Покупки · N»: items to buy with the tag (a tap opens the list filtered by the tag), then the tagged lists. */
@Composable
private fun TagShopping(
    state: TagPageUiState,
    tagId: String,
    onShoppingList: (id: String, tagId: String?) -> Unit,
) {
    val count = state.shoppingItems.size + state.shoppingLists.size
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("tag_shopping")) {
        SectionHeader(stringResource(Res.string.tag_shopping_section, count.toString()))
        ListGroup {
            state.shoppingItems.forEachIndexed { index, (item, listName) ->
                if (index > 0) ListDivider(inset = Spacing.l)
                ListRow(
                    item.data.name,
                    modifier = Modifier.testTag("tag_shopping_item_${item.id}"),
                    supporting = listName,
                    trailingText = item.data.quantity,
                    onClick = { onShoppingList(item.data.listId, tagId) },
                )
            }
            state.shoppingLists.forEachIndexed { index, list ->
                if (index > 0 || state.shoppingItems.isNotEmpty()) ListDivider()
                ListRow(
                    list.data.name,
                    modifier = Modifier.testTag("tag_shopping_list_${list.id}"),
                    icon = Res.drawable.ic_shopping_cart,
                    supporting = ShoppingLogic.left(list).text(),
                    showChevron = true,
                    onClick = { onShoppingList(list.id, null) },
                )
            }
        }
    }
}
