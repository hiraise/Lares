package app.familyhub.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.familyhub.client.ui.UiTestCore
import app.familyhub.client.ui.instance
import kotlinx.coroutines.Dispatchers
import org.koin.compose.KoinIsolatedContext
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppTest {
    @BeforeTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @Test
    fun `a fresh installation starts with the server address`() =
        UiTestCore(mainDispatcher = Dispatchers.Unconfined) { instance() }.use { core ->
            runComposeUiTest {
                setContent { KoinIsolatedContext(core.app) { App() } }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Server address").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("FamilyHub").assertIsDisplayed()
            }
        }
}
