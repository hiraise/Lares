package app.familyhub.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.familyhub.client.auth.SessionState
import app.familyhub.client.platform.DeepLinkHandler
import app.familyhub.client.ui.main.MainNavigation
import app.familyhub.client.ui.main.MainScaffoldRoute
import app.familyhub.client.ui.main.SectionPlaceholderPage
import app.familyhub.client.ui.onboarding.AfterConnect
import app.familyhub.client.ui.onboarding.InviteAcceptRoute
import app.familyhub.client.ui.onboarding.LinkRoute
import app.familyhub.client.ui.onboarding.LoginRoute
import app.familyhub.client.ui.onboarding.QrScanRoute
import app.familyhub.client.ui.onboarding.ServerRoute
import app.familyhub.client.ui.onboarding.SetupRoute
import app.familyhub.client.ui.settings.AboutRoute
import app.familyhub.client.ui.settings.ChangePasswordRoute
import app.familyhub.client.ui.settings.DevicesRoute
import app.familyhub.client.ui.settings.LicensesRoute
import app.familyhub.client.ui.settings.LoginQrRoute
import app.familyhub.client.ui.settings.ProfileRoute
import app.familyhub.client.ui.settings.SettingsNavigation
import app.familyhub.client.ui.settings.SettingsRoute
import app.familyhub.contract.DeepLink
import app.familyhub.contract.permissions.Section
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

// Routes of the two graphs. The session state decides which graph is shown (App.kt).

@Serializable
data object ServerDestination

@Serializable
data object SetupDestination

@Serializable
data object LoginDestination

@Serializable
data class InviteAcceptDestination(
    val code: String,
)

@Serializable
data class LinkDestination(
    val invite: Boolean,
    val serverUrl: String,
    val code: String,
) {
    fun toLink(): DeepLink = if (invite) DeepLink.Invite(serverUrl, code) else DeepLink.Login(serverUrl, code)
}

@Serializable
data object QrScanDestination

@Serializable
data object SectionsDestination

@Serializable
data class SectionPageDestination(
    val section: Section,
)

@Serializable
data object SettingsDestination

@Serializable
data object ProfileDestination

@Serializable
data object ChangePasswordDestination

@Serializable
data object LoginQrDestination

@Serializable
data object DevicesDestination

@Serializable
data object AboutDestination

@Serializable
data object LicensesDestination

/** Server → Setup | Login | InviteAccept, QR scanner; links (opened or scanned) continue on [LinkDestination]. */
@Composable
fun OnboardingNavHost(
    session: SessionState,
    deepLinks: DeepLinkHandler = koinInject(),
) {
    val navController = rememberNavController()
    val start: Any =
        remember {
            if (session is SessionState.ServerKnown && session.info?.initialized != false) LoginDestination else ServerDestination
        }
    LaunchedEffect(deepLinks) {
        deepLinks.link.filterNotNull().collect {
            val pending = deepLinks.consume() ?: return@collect
            navController.navigate(LinkDestination(pending is DeepLink.Invite, pending.serverUrl, pending.code)) {
                popUpTo(navController.graph.id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
    NavHost(navController, startDestination = start) {
        composable<ServerDestination> {
            ServerRoute(
                onNext = { next ->
                    // Server stays below, so Back returns to the address.
                    navController.navigate(if (next == AfterConnect.SETUP) SetupDestination else LoginDestination)
                },
                onScanQr = { navController.navigate(QrScanDestination) },
            )
        }
        composable<SetupDestination> { SetupRoute(onBack = { navController.backOrServer() }) }
        composable<LoginDestination> {
            LoginRoute(
                onScanQr = { navController.navigate(QrScanDestination) },
                onInviteCode = { navController.navigate(InviteAcceptDestination(it)) },
                onChangeServer = { navController.backToServer() },
                onNeedsSetup = {
                    navController.navigate(SetupDestination) { popUpTo<LoginDestination> { inclusive = true } }
                },
            )
        }
        composable<InviteAcceptDestination> { entry ->
            InviteAcceptRoute(entry.toRoute<InviteAcceptDestination>().code, onBack = { navController.backOrServer() })
        }
        composable<LinkDestination> { entry ->
            LinkRoute(
                entry.toRoute<LinkDestination>().toLink(),
                onInvite = { code ->
                    navController.navigate(InviteAcceptDestination(code)) { popUpTo<LinkDestination> { inclusive = true } }
                },
                onBack = { navController.backOrServer() },
            )
        }
        composable<QrScanDestination> { QrScanRoute(onBack = { navController.popBackStack() }) }
    }
}

/** Setup and Login have no screen behind them when the app starts there; going back means choosing another server. */
private fun NavHostController.backToServer() {
    navigate(ServerDestination) {
        popUpTo(graph.id) { inclusive = true }
    }
}

private fun NavHostController.backOrServer() {
    if (previousBackStackEntry != null) popBackStack() else backToServer()
}

/** Sections (bottom bar) and the screens opened from them. */
@Composable
fun MainNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = SectionsDestination) {
        composable<SectionsDestination> {
            MainScaffoldRoute(
                MainNavigation(
                    onSettings = { navController.navigate(SettingsDestination) },
                    onProfile = { navController.navigate(ProfileDestination) },
                    onDevices = { navController.navigate(DevicesDestination) },
                    onSection = { navController.navigate(SectionPageDestination(it)) },
                ),
            )
        }
        composable<SectionPageDestination> { entry ->
            SectionPlaceholderPage(entry.toRoute<SectionPageDestination>().section, onBack = { navController.popBackStack() })
        }
        composable<SettingsDestination> {
            SettingsRoute(
                SettingsNavigation(
                    onBack = { navController.popBackStack() },
                    onProfile = { navController.navigate(ProfileDestination) },
                    onDevices = { navController.navigate(DevicesDestination) },
                    onAbout = { navController.navigate(AboutDestination) },
                ),
            )
        }
        composable<ProfileDestination> {
            ProfileRoute(
                onBack = { navController.popBackStack() },
                onChangePassword = { navController.navigate(ChangePasswordDestination) },
                onLoginOtherDevice = { navController.navigate(LoginQrDestination) },
            )
        }
        composable<ChangePasswordDestination> { ChangePasswordRoute(onBack = { navController.popBackStack() }) }
        composable<LoginQrDestination> { LoginQrRoute(onBack = { navController.popBackStack() }) }
        composable<DevicesDestination> { DevicesRoute(onBack = { navController.popBackStack() }) }
        composable<AboutDestination> {
            AboutRoute(onBack = { navController.popBackStack() }, onLicenses = { navController.navigate(LicensesDestination) })
        }
        composable<LicensesDestination> { LicensesRoute(onBack = { navController.popBackStack() }) }
    }
}
