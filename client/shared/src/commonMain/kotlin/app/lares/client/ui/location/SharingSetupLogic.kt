package app.lares.client.ui.location

import app.lares.client.data.location.DeviceLocationStatus

/** The steps of the sharing setup (plan M4-07, LOC-001, LOC-008), in order. */
enum class SetupStep { EXPLAIN, FINE, BACKGROUND, NOTIFICATIONS, BATTERY, DONE }

/**
 * Where the setup is. [refused]: the user said no on this step, the sheet explains and offers the settings;
 * [asked]: the step's action ran (the background step learns its result only when the app is back);
 * [batteryRequested]: the battery dialog was shown, the vendor hints and «Готово» follow.
 */
data class SetupState(
    val step: SetupStep = SetupStep.EXPLAIN,
    val refused: Boolean = false,
    val asked: Boolean = false,
    val batteryRequested: Boolean = false,
)

/**
 * The sharing setup without platform code: EXPLAIN → FINE → BACKGROUND → NOTIFICATIONS → BATTERY → DONE, skipping
 * what the phone already allows (the background step is satisfied by the foreground permission below API 29, the
 * notification step below API 33; [DeviceLocationStatus] says so). A refusal keeps the step; without background
 * location the setup never reaches DONE, so sharing never starts without it (LOC-001).
 */
object SharingSetupLogic {
    fun start(): SetupState = SetupState(SetupStep.EXPLAIN)

    /** «Продолжить» on the explanation. */
    fun onContinue(
        state: SetupState,
        status: DeviceLocationStatus,
    ): SetupState = if (state.step == SetupStep.EXPLAIN) SetupState(next(SetupStep.FINE, status)) else state

    /** The step's action ran (system dialog, app settings). */
    fun onAsk(state: SetupState): SetupState = state.copy(asked = true)

    /** The answer of the system dialog for [step]; an answer for a step already left is ignored. */
    fun onResult(
        state: SetupState,
        step: SetupStep,
        status: DeviceLocationStatus,
    ): SetupState =
        when {
            state.step != step -> state
            met(step, status) -> SetupState(next(step, status))
            else -> state.copy(refused = true, asked = true)
        }

    /**
     * The app is visible again (back from the settings or a dialog): a step now satisfied moves on; the background step
     * asked for and still missing counts as refused.
     */
    fun onResume(
        state: SetupState,
        status: DeviceLocationStatus,
    ): SetupState =
        when (state.step) {
            SetupStep.FINE, SetupStep.BACKGROUND, SetupStep.NOTIFICATIONS -> {
                when {
                    met(state.step, status) -> SetupState(next(state.step, status))
                    state.step == SetupStep.BACKGROUND && state.asked -> state.copy(refused = true)
                    else -> state
                }
            }

            else -> {
                state
            }
        }

    /** «Отключить экономию»: the system dialog was shown; the vendor hints and «Готово» follow. */
    fun onBatteryRequested(state: SetupState): SetupState =
        if (state.step == SetupStep.BATTERY) state.copy(batteryRequested = true, asked = true) else state

    /** «Готово» or «Пропустить» on the battery step. */
    fun onBatteryDone(state: SetupState): SetupState = if (state.step == SetupStep.BATTERY) SetupState(SetupStep.DONE) else state

    /** The first step from [from] on that the phone still needs; DONE when none. */
    fun next(
        from: SetupStep,
        status: DeviceLocationStatus,
    ): SetupStep = SetupStep.entries.drop(from.ordinal).first { it == SetupStep.DONE || (it != SetupStep.EXPLAIN && !met(it, status)) }

    private fun met(
        step: SetupStep,
        status: DeviceLocationStatus,
    ): Boolean =
        when (step) {
            SetupStep.EXPLAIN -> true

            SetupStep.FINE -> status.fine

            // Background location includes precise location (a later step never runs before an earlier one is met).
            SetupStep.BACKGROUND -> status.fine && status.background

            SetupStep.NOTIFICATIONS -> status.notifications

            SetupStep.BATTERY -> status.batteryUnrestricted

            SetupStep.DONE -> true
        }
}
