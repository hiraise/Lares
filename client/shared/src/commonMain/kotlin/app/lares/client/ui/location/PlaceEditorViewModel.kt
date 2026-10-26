package app.lares.client.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.location.LocationRepository
import app.lares.client.data.location.Place
import app.lares.client.data.location.PlacesRepository
import app.lares.client.data.location.PlacesWriter
import app.lares.client.data.observeViewer
import app.lares.client.db.LocalDb
import app.lares.client.sync.LocalWriteException
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.location.AddressResult
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.PositionFix
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.permissions.PlaceRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Koin parameters of [PlaceEditorViewModel]: an existing place ([id]) or a new one at [lat], [lon]. */
data class PlaceEditorArgs(
    val id: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

enum class PlaceEditorResult { SAVED, DELETED, GONE }

/** The address search (LOC-024): runs only on «Найти»; [results] null = not searched yet. */
data class AddressSearchUi(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<AddressResult>? = null,
    val error: UiError? = null,
)

data class PlaceEditorUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val form: PlaceForm = PlaceForm(),
    val initial: PlaceForm = PlaceForm(),
    val fieldErrors: Map<String, FieldError> = emptyMap(),
    val error: UiError? = null,
    val saving: Boolean = false,
    val canManage: Boolean = false,
    /** The other places, drawn around the draft circle. */
    val others: List<Place> = emptyList(),
    val config: LocationConfig? = null,
    /** «Я здесь»: the newest own fix; null hides the button. */
    val here: PositionFix? = null,
    val search: AddressSearchUi = AddressSearchUi(),
    val camera: MapCameraRequest? = null,
    val result: PlaceEditorResult? = null,
) {
    val changed: Boolean get() = form != initial

    val canSave: Boolean get() = canManage && form.canSave && !saving

    /** The search needs the server's address service and the right to manage places (ADR-0026). */
    val searchAvailable: Boolean get() = canManage && config?.addresses == true
}

/**
 * The place editor (plan M4-09, LOC-020, LOC-024): name, icon, centre (map tap, «Я здесь», address search) and radius;
 * saved through [PlacesWriter], so it works offline with the same rules as the server.
 */
class PlaceEditorViewModel(
    private val location: LocationRepository,
    private val places: PlacesRepository,
    private val writer: PlacesWriter,
    localDb: LocalDb,
    permissions: PermissionsRepository,
    private val args: PlaceEditorArgs,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private var serial = 0
    private val viewer = observeViewer(localDb, permissions)

    /** The own newest fix from the map data (the cached snapshot when offline). */
    private val here =
        combine(location.observeNow().map { it.members }.onStart { emit(emptyList()) }, viewer) { members, viewer ->
            members.firstOrNull { it.memberId == viewer?.memberId }?.last
        }

    val state: StateFlow<PlaceEditorUiState> =
        combine(local, places.observePlaces(), viewer, here) { local, places, viewer, here ->
            PlaceEditorUiState(
                loaded = local.loaded,
                isNew = args.id == null,
                form = local.form,
                initial = local.initial,
                fieldErrors = local.fieldErrors,
                error = local.error,
                saving = local.saving,
                canManage = viewer != null && PlaceRules.canManage(viewer),
                others = places.filter { it.id != args.id },
                config = local.config,
                here = here,
                search = local.search,
                camera = local.camera,
                result = local.result,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceEditorUiState(isNew = args.id == null))

    init {
        viewModelScope.launch {
            val id = args.id
            val form =
                if (id == null) {
                    PlaceForm.new(args.lat ?: 0.0, args.lon ?: 0.0)
                } else {
                    places
                        .observePlaces()
                        .first()
                        .firstOrNull { it.id == id }
                        ?.data
                        ?.let(PlaceForm::of)
                }
            local.update {
                if (form == null) {
                    it.copy(loaded = true, result = PlaceEditorResult.GONE)
                } else {
                    it.copy(loaded = true, form = form, initial = form, camera = request(form))
                }
            }
        }
        viewModelScope.launch {
            val config = location.config()
            local.update { it.copy(config = config) }
        }
    }

    fun onName(value: String) = edit("name") { it.withName(value) }

    fun onIcon(value: PlaceIcon) = edit { it.withIcon(value) }

    /** A tap on the editor map moves the centre (the camera stays). */
    fun onMapTap(
        lat: Double,
        lon: Double,
    ) = edit { it.withCentre(lat, lon) }

    /** «Я здесь». */
    fun useHere() {
        val fix = state.value.here ?: return
        edit { it.withFix(fix) }
        local.update { it.copy(camera = request(it.form)) }
    }

    fun onRadius(value: Float) = edit { it.withRadius(value) }

    fun onQuery(value: String) = local.update { it.copy(search = it.search.copy(query = value, error = null)) }

    /** «Найти» or the keyboard's search key; never as you type (ADR-0026). */
    fun search() {
        val current = local.value.search
        val query = current.query.trim()
        if (query.isEmpty() || current.searching || !state.value.searchAvailable) return
        local.update { it.copy(search = it.search.copy(searching = true, error = null)) }
        viewModelScope.launch {
            val result = location.search(query)
            local.update {
                val search =
                    result.fold(
                        { results -> it.search.copy(searching = false, results = results.take(MAX_RESULTS)) },
                        { e -> it.search.copy(searching = false, results = null, error = e.toUiError()) },
                    )
                it.copy(search = search)
            }
        }
    }

    /** A search result: the centre moves there, the address is filled, the list closes. */
    fun pick(result: AddressResult) {
        edit { it.withResult(result) }
        local.update { it.copy(search = it.search.copy(results = null), camera = request(it.form)) }
    }

    fun save() {
        val current = state.value
        if (!current.canSave || current.result != null) return
        val form = local.value.form
        val errors = form.validate()
        if (errors.isNotEmpty()) {
            local.update { it.copy(fieldErrors = PlaceForm.fieldErrors(errors), error = otherError(errors.keys)) }
            return
        }
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val id = args.id
            if (id == null) {
                finish(attempt { writer.create(form.toData()) }.exceptionOrNull(), PlaceEditorResult.SAVED)
                return@launch
            }
            // Deleted meanwhile (another phone): nothing to save.
            val stored =
                places
                    .observePlaces()
                    .first()
                    .firstOrNull { it.id == id }
                    ?.data
            if (stored == null) {
                local.update { it.copy(saving = false, result = PlaceEditorResult.GONE) }
                return@launch
            }
            finish(attempt { writer.update(id, form.applyTo(stored)) }.exceptionOrNull(), PlaceEditorResult.SAVED)
        }
    }

    fun delete() {
        val id = args.id ?: return
        if (!state.value.canManage || local.value.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch { finish(attempt { writer.delete(id) }.exceptionOrNull(), PlaceEditorResult.DELETED) }
    }

    private fun finish(
        failure: Throwable?,
        done: PlaceEditorResult,
    ) = local.update {
        when (failure) {
            null -> {
                it.copy(saving = false, result = done)
            }

            is LocalWriteException -> {
                it.copy(
                    saving = false,
                    fieldErrors = PlaceForm.fieldErrors(failure.fields),
                    error =
                        if (failure.fields.isNotEmpty() && failure.fields.keys.all { f -> f in PlaceForm.FIELDS }) {
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

    private fun otherError(fields: Set<String>): UiError? =
        if (fields.any { it !in PlaceForm.FIELDS }) UiError.Api(ErrorCode.VALIDATION_FAILED) else null

    private fun edit(
        field: String? = null,
        change: (PlaceForm) -> PlaceForm,
    ) = local.update {
        it.copy(form = change(it.form), fieldErrors = if (field == null) it.fieldErrors else it.fieldErrors - field, error = null)
    }

    private fun request(form: PlaceForm) = MapCameraRequest(CameraKind.Point(form.latitude, form.longitude, MEMBER_ZOOM), ++serial)

    private data class Local(
        val loaded: Boolean = false,
        val form: PlaceForm = PlaceForm(),
        val initial: PlaceForm = PlaceForm(),
        val fieldErrors: Map<String, FieldError> = emptyMap(),
        val error: UiError? = null,
        val saving: Boolean = false,
        val config: LocationConfig? = null,
        val search: AddressSearchUi = AddressSearchUi(),
        val camera: MapCameraRequest? = null,
        val result: PlaceEditorResult? = null,
    )

    companion object {
        const val MAX_RESULTS = 5
    }
}
