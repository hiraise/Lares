package app.lares.client.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.api.ApiException
import app.lares.client.api.LaresApi
import app.lares.client.data.GroupsRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.location.LocationRepository
import app.lares.client.sync.SyncEngine
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.fieldErrors
import app.lares.client.ui.common.toUiError
import app.lares.client.ui.family.isOnline
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.UpdateHouseholdRequest
import app.lares.contract.api.location.RuleParty
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.permissions.BuiltInGroup
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The edit sheet of one rule. */
data class RuleEditor(
    val draft: RuleDraft,
    val isNew: Boolean,
)

data class LocationRulesUiState(
    /** The rules came from the server. */
    val loaded: Boolean = false,
    /** Loading failed (no network): the screen is read-only with the offline text. */
    val loadFailed: Boolean = false,
    val canEdit: Boolean = false,
    val online: Boolean = true,
    val rules: List<RuleDraft> = emptyList(),
    val saved: List<RuleDraft> = emptyList(),
    val names: RuleNames = RuleNames(),
    val editor: RuleEditor? = null,
    val saving: Boolean = false,
    val error: UiError? = null,
    /** Server validation errors by rule key. */
    val rowErrors: Map<Int, UiText> = emptyMap(),
    /** The history period from the server (cached offline); null before it is known. */
    val historyDays: Int? = null,
    val historySaving: Boolean = false,
    val historyError: UiError? = null,
) {
    val editable: Boolean get() = canEdit && online && loaded && !loadFailed

    val dirty: Boolean get() = LocationRulesLogic.changed(rules, saved)

    val canAdd: Boolean get() = editable && LocationRulesLogic.canAdd(rules)

    val historyEditable: Boolean get() = canEdit && online && historyDays != null
}

/**
 * Family › «Геопозиция: кто кого видит» (plan M4-10, LOC-005, LOC-006, ADR-0025): administrators edit the rules as a
 * list saved with one `PUT` and choose how long history is kept. Online only, like the other admin screens.
 */
class LocationRulesViewModel(
    private val location: LocationRepository,
    private val api: LaresApi,
    private val syncEngine: SyncEngine,
    groups: GroupsRepository,
    members: MembersRepository,
    permissions: PermissionsRepository,
) : ViewModel() {
    private val form = MutableStateFlow(LocationRulesUiState())
    private var loading = false

    private val names =
        combine(groups.observeAll(), members.observeAll()) { groups, members ->
            RuleNames.of(
                groups.map { Triple(it.id, it.data.name, it.data.builtIn == BuiltInGroup.ADMINISTRATORS) },
                members.associate { it.id to it.name },
            )
        }
    private val online = syncEngine.status.map { it.isOnline() }.distinctUntilChanged()

    val state: StateFlow<LocationRulesUiState> =
        combine(form, names, online, permissions.observeMine()) { form, names, online, mine ->
            form.copy(names = names, online = online, canEdit = mine.has(Permission.HOUSEHOLD_MANAGE))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, form.value)

    init {
        viewModelScope.launch { form.update { it.copy(historyDays = location.config()?.historyDays) } }
        // Back online after a failed load: try again.
        viewModelScope.launch { online.collect { if (it && !form.value.loaded) load() } }
    }

    /** `GET /location/rules`; a failure leaves the screen read-only. */
    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            val result = location.rules()
            loading = false
            form.update { state ->
                result.fold(
                    onSuccess = {
                        val rules = LocationRulesLogic.fromServer(it.rules)
                        state.copy(loaded = true, loadFailed = false, rules = rules, saved = rules, error = null, rowErrors = emptyMap())
                    },
                    onFailure = { state.copy(loadFailed = true, error = it.toUiError().takeIf { _ -> it is ApiException }) },
                )
            }
            if (result.isSuccess && form.value.historyDays == null) {
                form.update { it.copy(historyDays = location.config(reload = true)?.historyDays) }
            }
        }
    }

    fun openNew() {
        if (!current().canAdd) return
        form.update { it.copy(editor = RuleEditor(LocationRulesLogic.newDraft(it.rules), isNew = true)) }
    }

    fun openRule(key: Int) {
        if (!current().editable) return
        val rule = form.value.rules.firstOrNull { it.key == key } ?: return
        form.update { it.copy(editor = RuleEditor(rule, isNew = false)) }
    }

    fun closeEditor() = form.update { it.copy(editor = null) }

    fun onSubject(party: RuleParty) = editDraft { it.copy(subject = party) }

    fun onTarget(party: RuleParty) = editDraft { it.copy(target = party) }

    fun onLevel(level: VisibilityLevel) = editDraft { it.copy(level = level) }

    /** «Готово» in the sheet: the rule goes into the list (saved with «Сохранить»). */
    fun applyEditor() {
        val editor = form.value.editor ?: return
        form.update {
            it.copy(
                rules = LocationRulesLogic.put(it.rules, editor.draft),
                editor = null,
                rowErrors = it.rowErrors - editor.draft.key,
                error = null,
            )
        }
    }

    fun deleteRule(key: Int) =
        form.update {
            it.copy(rules = LocationRulesLogic.delete(it.rules, key), editor = null, rowErrors = it.rowErrors - key, error = null)
        }

    /** «Сохранить»: the whole list with one `PUT`; validation errors are shown on their rows. */
    fun save() {
        val current = current()
        if (!current.editable || current.saving || !current.dirty) return
        val rules = current.rules
        form.update { it.copy(saving = true, error = null, rowErrors = emptyMap()) }
        viewModelScope.launch {
            val failure = location.saveRules(rules.map { it.toInput() }).exceptionOrNull()
            form.update {
                when {
                    failure == null -> {
                        it.copy(saving = false, saved = rules)
                    }

                    (failure as? ApiException)?.code == ErrorCode.VALIDATION_FAILED -> {
                        it.copy(
                            saving = false,
                            error = failure.toUiError(),
                            rowErrors = LocationRulesLogic.rowErrors(rules, failure.fieldErrors),
                        )
                    }

                    else -> {
                        it.copy(saving = false, error = failure.toUiError())
                    }
                }
            }
        }
    }

    /** «Хранить историю»: `PATCH /household`, then the period again from the server (LOC-005). */
    fun setHistoryDays(days: Int) {
        val current = current()
        if (!current.historyEditable || current.historySaving || current.historyDays == days) return
        form.update { it.copy(historySaving = true, historyError = null) }
        viewModelScope.launch {
            val failure =
                attempt { api.updateHousehold(UpdateHouseholdRequest(locationHistoryDays = days)) }
                    .onSuccess { syncEngine.request() }
                    .exceptionOrNull()
            val fresh = if (failure == null) location.config(reload = true)?.historyDays ?: days else null
            form.update {
                if (failure == null) {
                    it.copy(historySaving = false, historyDays = fresh)
                } else {
                    it.copy(historySaving = false, historyError = failure.toUiError())
                }
            }
        }
    }

    /** The latest edits with the permission and network flags ([state] may not have caught up with [form] yet). */
    private fun current(): LocationRulesUiState {
        val flags = state.value
        return form.value.copy(names = flags.names, online = flags.online, canEdit = flags.canEdit)
    }

    private fun editDraft(change: (RuleDraft) -> RuleDraft) =
        form.update { state -> state.editor?.let { state.copy(editor = it.copy(draft = change(it.draft))) } ?: state }
}
