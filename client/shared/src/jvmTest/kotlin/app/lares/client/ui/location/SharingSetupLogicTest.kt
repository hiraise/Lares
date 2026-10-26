package app.lares.client.ui.location

import app.lares.client.data.location.DeviceLocationStatus
import app.lares.client.data.location.FakeLocationDevice.Companion.ALL_ALLOWED
import app.lares.client.data.location.FakeLocationDevice.Companion.NOTHING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sharing setup steps (plan M4-07, LOC-001, LOC-008). */
class SharingSetupLogicTest {
    private val logic = SharingSetupLogic

    @Test
    fun `LOC-001 every step in order on a phone that allows nothing yet`() {
        var status = NOTHING
        var state = logic.start()
        assertEquals(SetupStep.EXPLAIN, state.step)
        state = logic.onContinue(state, status)
        assertEquals(SetupStep.FINE, state.step)

        status = status.copy(fine = true)
        state = logic.onResult(state, SetupStep.FINE, status)
        assertEquals(SetupStep.BACKGROUND, state.step)

        // API 30+: the app settings open; the answer comes when the app is visible again.
        state = logic.onAsk(state)
        status = status.copy(background = true)
        state = logic.onResume(state, status)
        assertEquals(SetupStep.NOTIFICATIONS, state.step)

        status = status.copy(notifications = true)
        state = logic.onResult(state, SetupStep.NOTIFICATIONS, status)
        assertEquals(SetupStep.BATTERY, state.step)
        assertFalse(state.batteryRequested)

        state = logic.onBatteryRequested(state)
        assertEquals(SetupStep.BATTERY, state.step, "the vendor hints and «Готово» follow the battery dialog")
        assertTrue(state.batteryRequested)
        state = logic.onBatteryDone(state)
        assertEquals(SetupStep.DONE, state.step)
    }

    @Test
    fun `steps the phone already allows are skipped`() {
        // API 26–28: background comes with the foreground permission; below 33 notifications need no permission.
        val old = NOTHING.copy(notifications = true)
        var state = logic.onContinue(logic.start(), old)
        assertEquals(SetupStep.FINE, state.step)
        state = logic.onResult(state, SetupStep.FINE, old.copy(fine = true, background = true))
        assertEquals(SetupStep.BATTERY, state.step)

        assertEquals(SetupStep.DONE, logic.onContinue(logic.start(), ALL_ALLOWED).step)
    }

    @Test
    fun `LOC-008 the battery step is skipped when the app is unrestricted`() {
        val status = ALL_ALLOWED.copy(notifications = false)
        val state = logic.onResult(SetupState(SetupStep.NOTIFICATIONS), SetupStep.NOTIFICATIONS, ALL_ALLOWED)
        assertEquals(SetupStep.DONE, state.step)
        assertEquals(SetupStep.NOTIFICATIONS, logic.onContinue(logic.start(), status).step)
        val restricted = ALL_ALLOWED.copy(batteryUnrestricted = false)
        val battery = logic.onContinue(logic.start(), restricted)
        assertEquals(SetupStep.BATTERY, battery.step)
        assertEquals(SetupStep.DONE, logic.onBatteryDone(battery).step, "«Пропустить» goes on without the battery dialog")
    }

    @Test
    fun `LOC-001 refusals keep the step and explain, the settings bring it back`() {
        var state = logic.onContinue(logic.start(), NOTHING)
        state = logic.onResult(state, SetupStep.FINE, NOTHING)
        assertEquals(SetupStep.FINE, state.step)
        assertTrue(state.refused)
        // Approximate only is a refusal: fine stays false.
        state = logic.onResult(state, SetupStep.FINE, NOTHING)
        assertEquals(SetupStep.FINE, state.step)
        // Allowed in the app settings, then back to the app.
        val fine = NOTHING.copy(fine = true)
        state = logic.onResume(state, fine)
        assertEquals(SetupStep.BACKGROUND, state.step)
        assertFalse(state.refused)

        // Back from the settings without «Разрешить в любом режиме»: refused, never DONE.
        state = logic.onResume(logic.onAsk(state), fine)
        assertEquals(SetupStep.BACKGROUND, state.step)
        assertTrue(state.refused)
        state = logic.onResume(state, fine.copy(background = true))
        assertEquals(SetupStep.NOTIFICATIONS, state.step)
    }

    @Test
    fun `a resume before asking is not a refusal, a late answer for a left step is ignored`() {
        val fine = NOTHING.copy(fine = true)
        var state = SetupState(SetupStep.BACKGROUND)
        state = logic.onResume(state, fine)
        assertFalse(state.refused)

        // The resume after the system dialog already moved on; the dialog's callback comes later.
        state = logic.onResume(SetupState(SetupStep.FINE, asked = true), fine)
        assertEquals(SetupStep.BACKGROUND, state.step)
        assertEquals(state, logic.onResult(state, SetupStep.FINE, fine))
    }

    @Test
    fun `LOC-001 without background location the setup never reaches DONE`() {
        val noBackground = ALL_ALLOWED.copy(background = false)
        var state = logic.onContinue(logic.start(), noBackground)
        assertEquals(SetupStep.BACKGROUND, state.step)
        repeat(3) { state = logic.onResume(logic.onAsk(state), noBackground) }
        assertEquals(SetupStep.BACKGROUND, state.step)
        assertEquals(SetupStep.BACKGROUND, logic.next(SetupStep.FINE, noBackground))
        val noFine: DeviceLocationStatus = ALL_ALLOWED.copy(fine = false)
        assertEquals(SetupStep.FINE, logic.next(SetupStep.FINE, noFine))
    }
}
