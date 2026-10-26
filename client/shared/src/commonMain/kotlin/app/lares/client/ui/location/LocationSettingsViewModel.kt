package app.lares.client.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.location.DeviceLocationStatus
import app.lares.client.data.location.LocationDevice
import app.lares.client.data.location.LocationRepository
import app.lares.client.data.location.MySharing
import app.lares.client.data.location.PauseChoice
import app.lares.client.data.location.PlacesRepository
import app.lares.client.data.location.SharingController
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.toUiError
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** The status row of this phone's sharing (LOC-010, LOC-008). */
enum class SharingStatusText { SHARING, PAUSED, PAUSED_UNTIL, OFF, NEEDS_PERMISSION, LOCATION_OFF, STALE }

fun statusText(
    sharing: MySharing,
    device: DeviceLocationStatus?,
): SharingStatusText =
    when (sharing) {
        MySharing.Off -> {
            SharingStatusText.OFF
        }

        MySharing.Sharing -> {
            SharingStatusText.SHARING
        }

        is MySharing.Paused -> {
            if (sharing.until == null) SharingStatusText.PAUSED else SharingStatusText.PAUSED_UNTIL
        }

        MySharing.NeedsPermission -> {
            if (device != null && device.fine && device.background && !device.locationOn) {
                SharingStatusText.LOCATION_OFF
            } else {
                SharingStatusText.NEEDS_PERMISSION
            }
        }

        MySharing.StaleUploads -> {
            SharingStatusText.STALE
        }
    }

/** A member who sees the viewer (LOC-015): «где сейчас» or «с историей». */
data class WatcherRow(
    val name: String,
    val history: Boolean,
)

/** A member who gets the viewer's arrive / leave alerts, with the place names («Мама — Школа №12, Дом»). */
data class AlertWatcherRow(
    val name: String,
    val places: List<String>,
)

/** «Кто меня видит» and «О вас сообщают» come from the server only (online). */
sealed interface Transparency {
    data object Loading : Transparency

    data object Unavailable : Transparency

    data class Loaded(
        val viewers: List<WatcherRow>,
        val alerts: List<AlertWatcherRow>,
    ) : Transparency
}

data class LocationSettingsUiState(
    val loaded: Boolean = false,
    val sharing: MySharing = MySharing.Off,
    val canPause: Boolean = false,
    val device: DeviceLocationStatus? = null,
    val setupOpen: Boolean = false,
    val pauseOpen: Boolean = false,
    val batteryOpen: Boolean = false,
    val enabling: Boolean = false,
    val enableError: UiError? = null,
    val watchers: List<String>? = null,
    val transparency: Transparency = Transparency.Loading,
    val error: UiError? = null,
) {
    val status: SharingStatusText get() = statusText(sharing, device)

    val pausedUntil: Instant? get() = (sharing as? MySharing.Paused)?.until

    /** «Пауза» only for members with `location.pause_own` (LOC-010). */
    val showPause: Boolean get() = canPause && (sharing == MySharing.Sharing || sharing == MySharing.StaleUploads)

    val showResume: Boolean get() = sharing is MySharing.Paused

    /** «Передачу нельзя выключить в приложении» for members without `location.pause_own`. */
    val showNoPauseNote: Boolean get() = loaded && !canPause && sharing != MySharing.Off
}

/**
 * Settings › Геопозиция (plan M4-07, LOC-001, LOC-008, LOC-010): this phone's sharing and its actions, the sharing
 * setup and the battery row. The phone's permissions are read again whenever the screen is visible again. «Кто меня
 * видит» and «О вас сообщают» (M4-10, LOC-015) are asked from the server on every visit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSettingsViewModel(
    private val controller: SharingController,
    val device: LocationDevice,
    permissions: PermissionsRepository,
    private val location: LocationRepository,
    private val members: MembersRepository,
    private val places: PlacesRepository? = null,
) : ViewModel() {
    private val local = MutableStateFlow(LocationSettingsUiState())

    /** Bumped on resume: the sharing state is computed again with the phone's current permissions. */
    private val resumes = MutableStateFlow(0)

    val state: StateFlow<LocationSettingsUiState> =
        combine(
            // The phone's status with every sharing change (location switched off in the shade, no resume).
            resumes.flatMapLatest { controller.observe().map { it to device.status() } },
            permissions.observeMine().map { it.has(Permission.LOCATION_PAUSE_OWN) }.distinctUntilChanged(),
            local,
        ) { (sharing, status), canPause, state ->
            state.copy(loaded = true, sharing = sharing, device = status, canPause = canPause)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationSettingsUiState())

    fun onResume() {
        local.update { it.copy(device = device.status()) }
        resumes.update { it + 1 }
    }

    fun openSetup() {
        local.update { it.copy(setupOpen = true, enableError = null) }
        if (local.value.watchers == null) loadWatchers()
    }

    fun closeSetup() = local.update { it.copy(setupOpen = false, enableError = null) }

    /** The setup reached DONE: `PUT SHARING`, then the service (online only). */
    fun enable() {
        if (local.value.enabling) return
        local.update { it.copy(enabling = true, enableError = null) }
        viewModelScope.launch {
            val result = controller.enable()
            local.update {
                it.copy(
                    enabling = false,
                    setupOpen = result.isFailure && it.setupOpen,
                    enableError = result.exceptionOrNull()?.toUiError(),
                    device = device.status(),
                )
            }
        }
    }

    fun openPause() = local.update { it.copy(pauseOpen = true) }

    fun closePause() = local.update { it.copy(pauseOpen = false) }

    fun pause(choice: PauseChoice) {
        local.update { it.copy(pauseOpen = false, error = null) }
        viewModelScope.launch {
            controller.pause(choice).onFailure { e -> local.update { it.copy(error = e.toUiError()) } }
        }
    }

    fun resume() {
        local.update { it.copy(error = null) }
        viewModelScope.launch { controller.resume() }
    }

    fun openBattery() = local.update { it.copy(batteryOpen = true) }

    fun closeBattery() = local.update { it.copy(batteryOpen = false) }

    fun openLocationSettings() = device.openLocationSettings()

    fun requestBattery() = device.requestIgnoreBatteryOptimizations()

    fun openBatterySettings() = device.openBatterySettings()

    /** «Кто меня видит», «О вас сообщают»: asked on every visit of Settings › Геопозиция. */
    fun loadTransparency() = loadWatchers()

    /** Who sees the member and who gets alerts about them; for the setup's explanation too. Nothing offline. */
    private fun loadWatchers() {
        viewModelScope.launch {
            val watchers = location.watchers().getOrNull()
            if (watchers == null) {
                local.update { if (it.transparency is Transparency.Loaded) it else it.copy(transparency = Transparency.Unavailable) }
                return@launch
            }
            val names = members.observeAll().first().associate { it.id to it.name }
            val placeNames =
                places
                    ?.observePlaces()
                    ?.first()
                    .orEmpty()
                    .associate { it.id to it.data.name }
            val viewers =
                watchers.viewers
                    .mapNotNull { w -> names[w.memberId]?.let { WatcherRow(it, w.level == VisibilityLevel.HISTORY) } }
                    .sortedBy { it.name.lowercase() }
            val alerts =
                watchers.alerts
                    .mapNotNull { a ->
                        val name = names[a.memberId] ?: return@mapNotNull null
                        val list = a.placeIds.mapNotNull { placeNames[it] }.sortedBy { it.lowercase() }
                        if (list.isEmpty()) null else AlertWatcherRow(name, list)
                    }.sortedBy { it.name.lowercase() }
            local.update { state ->
                state.copy(watchers = viewers.map { it.name }, transparency = Transparency.Loaded(viewers, alerts))
            }
        }
    }
}
