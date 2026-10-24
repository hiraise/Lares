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
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TaskViews
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.resources.Res
import app.lares.client.resources.ic_edit
import app.lares.client.resources.ic_sell
import app.lares.client.resources.tag_action_edit
import app.lares.client.resources.tag_tasks_empty
import app.lares.client.resources.tag_tasks_section
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.SectionHeader
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val canManage: Boolean = false,
    val allNames: List<String> = emptyList(),
) {
    /** Deleted here or on another phone. */
    val gone: Boolean get() = loaded && tag == null
}

/** The tag page (TAG-004): the tag's items by module; in M1 only tasks. Editing the tag uses the shared tag sheet. */
class TagViewModel(
    tasksRepository: TasksRepository,
    tasksWriter: TasksWriter,
    tagsRepository: TagsRepository,
    tagsWriter: TagsWriter,
    args: TagArgs,
) : ViewModel() {
    private val id = args.id
    private val ticker = TaskTicker(viewModelScope, tasksWriter)

    val tagEditor = TagEditor(viewModelScope, tagsRepository, tagsWriter)
    val tagSheet = tagEditor.sheet
    val events: Flow<TaskEvent> = ticker.events

    val state: StateFlow<TagPageUiState> =
        tasksRepository
            .observeInputs()
            .map { build(it, id) }
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
        ): TagPageUiState {
            val rows = TaskRows(inputs)
            return TagPageUiState(
                loaded = true,
                tag = inputs.tags.firstOrNull { it.id == tagId },
                tasks = TaskViews.forTag(TaskViews.build(inputs), tagId).map(rows::row),
                canManage = inputs.viewer?.permissions?.has(Permission.TAGS_MANAGE) == true,
                allNames = rows.allNames,
            )
        }
    }
}

@Composable
fun TagRoute(
    id: String,
    onBack: () -> Unit,
    onTask: (String) -> Unit,
    viewModel: TagViewModel = koinViewModel(key = "tag-$id") { parametersOf(TagArgs(id)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.tagSheet.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    TaskEventSnackbars(viewModel.events, snackbar, onUndo = viewModel::undo)
    Box(Modifier.fillMaxSize()) {
        TagPage(state, onBack, onTask, onToggle = viewModel::toggle, onEdit = viewModel::editTag)
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
}
