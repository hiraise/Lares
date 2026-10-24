package app.lares.client.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.ui.common.UiError
import app.lares.client.ui.main.MainNavigation
import app.lares.client.ui.main.MainScaffold
import app.lares.client.ui.main.MainTab
import app.lares.client.ui.main.MainUiState
import app.lares.client.ui.main.TodayUiState
import app.lares.client.ui.onboarding.LoginScreen
import app.lares.client.ui.onboarding.LoginUiState
import app.lares.client.ui.onboarding.ServerScreen
import app.lares.client.ui.onboarding.ServerUiState
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.api.ErrorCode
import app.lares.contract.permissions.Section
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ScreensUiTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @Test
    fun `server screen sends typed address and shows errors`() =
        runComposeUiTest {
            var typed = ""
            var connects = 0
            var scans = 0
            setContent {
                LaresTheme(darkTheme = false) {
                    ServerScreen(ServerUiState(url = typed, error = UiError.NotLares), { typed = it }, { connects++ }, { scans++ })
                }
            }
            onNodeWithText("Server address").assertIsDisplayed()
            onNodeWithText("This address doesn’t look like a Lares server.").assertIsDisplayed()
            onNodeWithText("Continue").performClick()
            onNodeWithText("Scan QR invitation").performClick()
            assertEquals(1, connects)
            assertEquals(1, scans)
        }

    @Test
    fun `server screen input reaches the callback`() =
        runComposeUiTest {
            var typed = ""
            setContent {
                LaresTheme(darkTheme = false) { ServerScreen(ServerUiState(), { typed = it }, {}, {}) }
            }
            onNode(
                androidx.compose.ui.test
                    .hasSetTextAction(),
            ).performTextInput("family.example.org")
            assertEquals("family.example.org", typed)
        }

    @Test
    fun `login screen shows the server, the error and the alternatives`() =
        runComposeUiTest {
            var submits = 0
            setContent {
                LaresTheme(darkTheme = true) {
                    LoginScreen(
                        LoginUiState(serverUrl = "https://hub.test", error = UiError.Api(ErrorCode.INVALID_CREDENTIALS)),
                        {},
                        {},
                        { submits++ },
                        {},
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("https://hub.test").assertIsDisplayed()
            onNodeWithText("Wrong login or password.").assertIsDisplayed()
            onNodeWithText("Log in with QR code").assertIsDisplayed()
            onNodeWithText("I have an invitation").assertIsDisplayed()
            onNode(
                androidx.compose.ui.test
                    .hasText("Log in") and
                    androidx.compose.ui.test
                        .hasClickAction(),
            ).performClick()
            assertEquals(1, submits)
        }

    @Test
    fun `bottom bar shows only permitted sections (FND-028, FND-064)`() =
        runComposeUiTest {
            setContent {
                LaresTheme(darkTheme = false) {
                    MainScaffold(
                        MainUiState(name = "Olga", sections = setOf(Section.SHOPPING)),
                        SnackbarHostState(),
                        MainNavigation({}, {}, {}, {}),
                        onOpenSync = {},
                        today = TodayUiState(loaded = true),
                    )
                }
            }
            onNodeWithTag("tab_${MainTab.TODAY.name}").assertIsDisplayed()
            onNodeWithTag("tab_${MainTab.SHOPPING.name}").assertIsDisplayed()
            onNodeWithTag("tab_${MainTab.MORE.name}").assertIsDisplayed()
            onNodeWithTag("tab_${MainTab.TASKS.name}").assertDoesNotExist()
            onNodeWithTag("tab_${MainTab.CALENDAR.name}").assertDoesNotExist()
            onNodeWithText("Here will be the day summary: tasks, events, shopping").assertIsDisplayed()
            onNodeWithTag("tab_${MainTab.MORE.name}").performClick()
            onNodeWithText("Settings").assertIsDisplayed()
            // No map or budget access: their tiles are hidden; Family and Tags wait for M0-08.
            onNodeWithText("Map").assertDoesNotExist()
            onNodeWithText("Budget").assertDoesNotExist()
            onNodeWithText("Family").assertIsDisplayed()
        }
}
