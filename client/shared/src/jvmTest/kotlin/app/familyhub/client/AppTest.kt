package app.familyhub.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppTest {
    @Test
    fun showsAppName() =
        runComposeUiTest {
            setContent { App() }
            onNodeWithText("FamilyHub").assertIsDisplayed()
        }
}
