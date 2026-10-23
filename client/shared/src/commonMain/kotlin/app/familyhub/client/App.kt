package app.familyhub.client

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familyhub.client.auth.SessionManager
import app.familyhub.client.auth.SessionState
import app.familyhub.client.platform.AppStartup
import app.familyhub.client.platform.AppearanceSettings
import app.familyhub.client.platform.ThemeMode
import app.familyhub.client.ui.MainNavHost
import app.familyhub.client.ui.OnboardingNavHost
import app.familyhub.client.ui.SystemBarsEffect
import app.familyhub.client.ui.components.FullScreenLoading
import app.familyhub.client.ui.theme.FamilyHubTheme
import org.koin.compose.koinInject

/**
 * The whole app. Needs a Koin context with `sharedModule(platform)` and `uiModule()`. Until the saved session is
 * restored it shows a spinner; then the onboarding graph, or the main graph for a logged-in member.
 */
@Composable
fun App() {
    val startup: AppStartup = koinInject()
    val sessionManager: SessionManager = koinInject()
    val appearance: AppearanceSettings = koinInject()
    LaunchedEffect(Unit) { startup.start() }

    val ready by startup.ready.collectAsStateWithLifecycle()
    val session by sessionManager.state.collectAsStateWithLifecycle()
    val preferences by appearance.preferences.collectAsStateWithLifecycle()
    val dark =
        when (preferences?.theme ?: ThemeMode.SYSTEM) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    SystemBarsEffect(dark)
    FamilyHubTheme(darkTheme = dark) {
        // The surface sets the default content color, so text outside cards follows the theme.
        Surface(color = MaterialTheme.colorScheme.background) {
            when {
                !ready -> FullScreenLoading()
                session is SessionState.LoggedIn -> MainNavHost()
                else -> OnboardingNavHost(session)
            }
        }
    }
}
