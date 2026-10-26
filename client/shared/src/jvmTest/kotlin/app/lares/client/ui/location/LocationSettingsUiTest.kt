package app.lares.client.ui.location

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lares.client.data.location.FakeLocationDevice
import app.lares.client.data.location.MySharing
import app.lares.client.data.location.PauseChoice
import app.lares.client.ui.theme.LaresTheme
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Settings › Геопозиция, the pause sheet and the sharing setup (plan M4-07, LOC-001, LOC-008, LOC-010). */
@OptIn(ExperimentalTestApi::class)
class LocationSettingsUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val calls = mutableListOf<String>()
    private val actions =
        LocationSettingsActions(
            onBack = { calls += "back" },
            onSetup = { calls += "setup" },
            onTurnOnLocation = { calls += "location" },
            onPause = { calls += "pause" },
            onResume = { calls += "resume" },
            onBattery = { calls += "battery" },
        )

    private fun state(
        sharing: MySharing,
        canPause: Boolean = true,
        unrestricted: Boolean = false,
    ) = LocationSettingsUiState(
        loaded = true,
        sharing = sharing,
        canPause = canPause,
        device = FakeLocationDevice.ALL_ALLOWED.copy(batteryUnrestricted = unrestricted),
    )

    @Test
    fun `LOC-001 off - turn on sharing and the battery row`() =
        runComposeUiTest {
            setContent { LaresTheme { LocationSettingsScreen(state(MySharing.Off), actions) } }
            onNodeWithText("Off").assertIsDisplayed()
            onNodeWithText("Pause").assertDoesNotExist()
            onNodeWithText("Turn on sharing").performClick()
            onNodeWithText("On — may stop sharing").assertIsDisplayed()
            onNodeWithTag("loc_battery").performClick()
            assertEquals(listOf("setup", "battery"), calls)
        }

    @Test
    fun `LOC-010 sharing - an adult pauses, a kid has only the note`() =
        runComposeUiTest {
            var current = state(MySharing.Sharing, unrestricted = true)
            setContent { LaresTheme { LocationSettingsScreen(current, actions) } }
            onNodeWithText("You are sharing your location").assertIsDisplayed()
            onNodeWithText("Off for Lares").assertIsDisplayed()
            onNodeWithText("Sharing can't be turned off in the app.").assertDoesNotExist()
            onNodeWithText("Pause").performClick()
            assertEquals(listOf("pause"), calls)
        }

    @Test
    fun `LOC-010 a kid sees no pause`() =
        runComposeUiTest {
            setContent { LaresTheme { LocationSettingsScreen(state(MySharing.Sharing, canPause = false), actions) } }
            onNodeWithText("Pause").assertDoesNotExist()
            onNodeWithText("Sharing can't be turned off in the app.").assertIsDisplayed()
        }

    @Test
    fun `LOC-010 paused until a time, resume`() =
        runComposeUiTest {
            setContent { LaresTheme { LocationSettingsScreen(state(MySharing.Paused(Clock.System.now() + 1.hours)), actions) } }
            onNodeWithText("Paused until", substring = true).assertIsDisplayed()
            onNodeWithText("Resume").performClick()
            assertEquals(listOf("resume"), calls)
        }

    @Test
    fun `LOC-012 permissions missing or location off`() =
        runComposeUiTest {
            setContent {
                LaresTheme {
                    LocationSettingsScreen(
                        state(MySharing.NeedsPermission).copy(device = FakeLocationDevice.ALL_ALLOWED.copy(locationOn = false)),
                        actions,
                    )
                }
            }
            onNodeWithText("Location is off on the phone").assertIsDisplayed()
            onNodeWithText("Turn on location").performClick()
            assertEquals(listOf("location"), calls)
        }

    @Test
    fun `LOC-010 the pause sheet offers three choices`() =
        runComposeUiTest {
            val chosen = mutableListOf<PauseChoice>()
            setContent { LaresTheme { PauseContent { chosen += it } } }
            onNodeWithText("Until morning").assertIsDisplayed()
            onNodeWithText("Until I turn it on").assertIsDisplayed()
            onNodeWithText("For 1 hour").performClick()
            assertEquals(listOf(PauseChoice.HOUR), chosen)
        }

    @Test
    fun `LOC-001 LOC-008 the setup walks every step with the phone's answers`() =
        runComposeUiTest {
            val device = FakeLocationDevice(FakeLocationDevice.NOTHING)
            val owner = TestLifecycleOwner()
            var enabled = 0
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LaresTheme {
                        SharingSetupSheet(device, listOf("Olga"), enabling = false, error = null, onEnable = { enabled++ }, onDismiss = {})
                    }
                }
            }
            onNodeWithText("Who sees you: Olga").assertIsDisplayed()
            onNodeWithText("Continue").performClick()
            onNodeWithTag("setup_fine").assertIsDisplayed()

            // Refused («approximate only» or «Don't allow»): the explanation and the settings.
            onNodeWithText("Allow").performClick()
            onNodeWithText("Without precise location", substring = true).assertIsDisplayed()
            device.current = device.current.copy(fine = true)
            onNodeWithText("Open settings").performClick()
            owner.resumeAgain()
            onNodeWithTag("setup_background").assertIsDisplayed()
            onNodeWithText("Allow all the time", substring = true).assertIsDisplayed()

            onNodeWithText("Open settings").performClick()
            owner.resumeAgain()
            onNodeWithText("Without this permission sharing can't start.").assertIsDisplayed()
            device.current = device.current.copy(background = true)
            owner.resumeAgain()
            onNodeWithTag("setup_notifications").assertIsDisplayed()

            device.current = device.current.copy(notifications = true)
            onNodeWithText("Allow").performClick()
            onNodeWithTag("setup_battery").assertIsDisplayed()
            onNodeWithTag("battery_hint").assertDoesNotExist()
            onNodeWithText("Turn off battery saving").performClick()
            onNodeWithTag("battery_hint").assertIsDisplayed()
            onNodeWithText("Done").performClick()
            onNodeWithText("Turning on sharing…").assertIsDisplayed()
            waitForIdle()
            assertEquals(1, enabled)
            assertEquals(listOf("appSettings", "appSettings", "battery"), device.calls)
        }

    /** A lifecycle the test moves: away from the app (settings) and back. */
    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry

        fun resumeAgain() {
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }
    }
}
