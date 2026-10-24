package app.lares.client.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.GroupsRepository
import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.client.data.TagsRepository
import app.lares.client.data.TagsWriter
import app.lares.client.data.tasks.TaskInputs
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.sync.LocalWriteException
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.client.ui.family.TagEditor
import app.lares.client.ui.family.TagSheet
import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.TaskRules
import app.lares.contract.tasks.TaskSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime

/**
 * Parameters of [TaskEditorViewModel] (Koin `parametersOf`): [id] null = a new task. A new task opened from the
 * "Mine" scope (or Today) is assigned to the member ([assignToMe]); from a tag-filtered list it gets that tag.
 */
data class TaskEditorArgs(
    val id: String? = null,
    val assignToMe: Boolean = false,
    val tagId: String? = null,
)

/** Where the editor goes after saving: the new task's page, or back. */
sealed interface TaskEditorResult {
    data class Created(
        val id: String,
    ) : TaskEditorResult

    data object Saved : TaskEditorResult

    /** The task was deleted meanwhile. */
    data object Gone : TaskEditorResult
}

data class TaskEditorUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val form: TaskForm = TaskForm(),
    /** The form as opened, for "discard changes?". */
    val initial: TaskForm = TaskForm(),
    val fieldErrors: Map<String, FieldError> = emptyMap(),
    val error: UiError? = null,
    val saving: Boolean = false,
    val today: LocalDate = LocalDate(1970, 1, 1),
    /** Members with `tasks.view`, the assignee choices. */
    val members: List<MemberView> = emptyList(),
    val allNames: List<String> = emptyList(),
    /** All tags, archived ones included (shown only when already on the task). */
    val tags: List<Tag> = emptyList(),
    /** The current occurrence of the routine as the form describes it ("Next time"). */
    val nextTime: LocalDate? = null,
    val canCreate: Boolean = false,
    val canEdit: Boolean = false,
    val canManageTags: Boolean = false,
    /** "Tell me when it's done" is for the task's creator. */
    val isCreator: Boolean = true,
    val result: TaskEditorResult? = null,
) {
    /** The member may save this form (create or edit). */
    val allowed: Boolean get() = if (isNew) canCreate else canEdit

    val changed: Boolean get() = form != initial

    val canSave: Boolean get() = allowed && form.title.isNotBlank() && !saving

    /** Tag choices: active tags and the archived ones already on the task. */
    val tagChoices: List<Tag>
        get() = tags.filter { !it.data.archived || it.id in form.tagIds }.sortedBy { it.data.name.lowercase() }
}

/**
 * The task editor (plan M1-06, TSK-001, TSK-002, TSK-010, TSK-011, TSK-030, TSK-032): one form for new and existing
 * tasks, written through [TasksWriter] (offline, same rights and validation as the server).
 */
class TaskEditorViewModel(
    repository: TasksRepository,
    groups: GroupsRepository,
    tagsRepository: TagsRepository,
    tagsWriter: TagsWriter,
    private val writer: TasksWriter,
    private val args: TaskEditorArgs,
) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val tagEditor = TagEditor(viewModelScope, tagsRepository, tagsWriter)
    val tagSheet: StateFlow<TagSheet?> = tagEditor.sheet

    private var inputs: TaskInputs? = null

    val state: StateFlow<TaskEditorUiState> =
        combine(local, repository.observeInputs(), groups.observeAll()) { local, inputs, groups ->
            this.inputs = inputs
            build(local, inputs, TasksViewModel.taskMembers(inputs.members, groups))
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TaskEditorUiState(isNew = args.id == null))

    init {
        viewModelScope.launch {
            val first = repository.observeInputs().first()
            val me = first.viewer?.memberId
            val form =
                if (args.id == null) {
                    TaskForm(
                        assigneeIds = if (args.assignToMe && me != null) setOf(me) else emptySet(),
                        tagIds = setOfNotNull(args.tagId?.takeIf { id -> first.tags.any { it.id == id } }),
                    )
                } else {
                    first.tasks
                        .firstOrNull { it.first == args.id }
                        ?.second
                        ?.let(TaskForm::of)
                }
            local.update {
                if (form ==
                    null
                ) {
                    it.copy(loaded = true, result = TaskEditorResult.Gone)
                } else {
                    it.copy(loaded = true, form = form, initial = form)
                }
            }
        }
    }

    fun onTitle(value: String) = edit("title") { it.withTitle(value) }

    fun onNotes(value: String) = edit { it.withNotes(value) }

    fun onDate(date: LocalDate?) = edit("dueDate") { it.withDate(date) }

    fun onTime(time: LocalTime?) = edit { it.withTime(time) }

    fun onPreset(preset: RepeatPreset) = edit("dueDate") { it.withPreset(preset, state.value.today) }

    fun onRecurrence(rule: Recurrence?) = edit("dueDate") { it.withRecurrence(rule, state.value.today) }

    fun onAnyone() = edit { it.withAnyone() }

    fun toggleAssignee(memberId: String) = edit { it.toggleAssignee(memberId) }

    fun toggleTag(tagId: String) = edit { it.toggleTag(tagId) }

    fun onReminder(minutesBefore: Int?) = edit { it.withReminder(minutesBefore) }

    fun onNotifyCreator(value: Boolean) = edit { it.copy(notifyCreatorOnDone = value) }

    fun addChecklistItem() = edit { it.copy(checklist = it.checklist + "") }

    fun onChecklistItem(
        index: Int,
        text: String,
    ) = edit { form -> form.copy(checklist = form.checklist.mapIndexed { i, t -> if (i == index) text else t }) }

    fun removeChecklistItem(index: Int) = edit { form -> form.copy(checklist = form.checklist.filterIndexed { i, _ -> i != index }) }

    /** "+ New tag" in the tags sheet: the saved tag is selected. */
    fun newTag() =
        tagEditor.new(
            state.value.tags
                .filter { !it.data.archived }
                .map { it.data.color },
        ) { id ->
            edit { if (id in it.tagIds) it else it.toggleTag(id) }
        }

    fun save() {
        val current = state.value
        if (!current.canSave || current.result != null) return
        val form = local.value.form
        val errors = form.validate()
        if (errors.isNotEmpty()) {
            local.update { it.copy(fieldErrors = TaskForm.fieldErrors(errors), error = otherError(errors.keys)) }
            return
        }
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result =
                attempt {
                    val id = args.id
                    if (id == null) {
                        TaskEditorResult.Created(writer.create(form.toData(), form.checklistItems()))
                    } else {
                        val base = inputs?.tasks?.firstOrNull { it.first == id }?.second ?: form.original ?: error("No task")
                        writer.update(id, form.applyTo(base))
                        TaskEditorResult.Saved
                    }
                }
            val failure = result.exceptionOrNull()
            local.update {
                when (failure) {
                    null -> {
                        it.copy(saving = false, result = result.getOrNull())
                    }

                    is LocalWriteException -> {
                        it.copy(
                            saving = false,
                            fieldErrors = TaskForm.fieldErrors(failure.fields),
                            // Not about the title or the date (rights, other fields): said under the form.
                            error =
                                if (failure.fields.isNotEmpty() &&
                                    failure.fields.keys.all { f -> f in TaskForm.FIELDS }
                                ) {
                                    null
                                } else {
                                    UiError.Api(failure.code)
                                },
                        )
                    }

                    else -> {
                        it.copy(saving = false, error = failure.toUiError())
                    }
                }
            }
        }
    }

    private fun otherError(fields: Set<String>): UiError? =
        if (fields.any { it !in TaskForm.FIELDS }) UiError.Api(ErrorCode.VALIDATION_FAILED) else null

    private fun edit(
        field: String? = null,
        change: (TaskForm) -> TaskForm,
    ) = local.update {
        it.copy(
            form = change(it.form),
            fieldErrors = if (field == null) it.fieldErrors else it.fieldErrors - field,
            error = null,
        )
    }

    private fun build(
        local: Local,
        inputs: TaskInputs,
        members: List<MemberView>,
    ): TaskEditorUiState {
        val viewer = inputs.viewer
        val today = inputs.now.toLocalDateTime(inputs.zone).date
        val original = local.form.original
        val form = local.form
        val nextTime =
            if (form.isRoutine && form.dueDate != null) {
                val completions = inputs.completions.filter { it.data.taskId == args.id }.map { it.data }
                TaskSchedule.state(form.toData(), completions, today, inputs.zone).occurrence
            } else {
                null
            }
        return TaskEditorUiState(
            loaded = local.loaded,
            isNew = args.id == null,
            form = form,
            initial = local.initial,
            fieldErrors = local.fieldErrors,
            error = local.error,
            saving = local.saving,
            today = today,
            members = members,
            allNames = inputs.members.map { it.name },
            tags = inputs.tags,
            nextTime = nextTime,
            canCreate = viewer != null && TaskRules.canCreate(viewer, TaskData("", viewer.memberId)),
            canEdit = viewer != null && original != null && TaskRules.canEdit(viewer, original),
            canManageTags = viewer?.permissions?.has(Permission.TAGS_MANAGE) == true,
            isCreator = original == null || original.createdBy == viewer?.memberId,
            result = local.result,
        )
    }

    /** What the editor holds besides the synced data. */
    private data class Local(
        val loaded: Boolean = false,
        val form: TaskForm = TaskForm(),
        val initial: TaskForm = TaskForm(),
        val fieldErrors: Map<String, FieldError> = emptyMap(),
        val error: UiError? = null,
        val saving: Boolean = false,
        val result: TaskEditorResult? = null,
    )
}
