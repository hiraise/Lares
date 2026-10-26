package app.lares.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.lares.client.auth.SessionState
import app.lares.client.platform.DeepLinkHandler
import app.lares.client.platform.EventLinks
import app.lares.client.platform.NavTarget
import app.lares.client.platform.PendingNavigation
import app.lares.client.platform.TaskLinks
import app.lares.client.ui.calendar.EventEditorArgs
import app.lares.client.ui.calendar.EventEditorRoute
import app.lares.client.ui.calendar.EventRoute
import app.lares.client.ui.calendar.RepeatScope
import app.lares.client.ui.family.AddMemberMode
import app.lares.client.ui.family.AddMemberRoute
import app.lares.client.ui.family.FamilyNavigation
import app.lares.client.ui.family.FamilyRoute
import app.lares.client.ui.family.FamilySettingsRoute
import app.lares.client.ui.family.FamilyTab
import app.lares.client.ui.family.GroupEditorRoute
import app.lares.client.ui.family.MemberDetailRoute
import app.lares.client.ui.family.PersonRoute
import app.lares.client.ui.family.TagRoute
import app.lares.client.ui.location.LocationSettingsRoute
import app.lares.client.ui.main.MainNavigation
import app.lares.client.ui.main.MainScaffoldRoute
import app.lares.client.ui.main.SectionPlaceholderPage
import app.lares.client.ui.onboarding.AfterConnect
import app.lares.client.ui.onboarding.InviteAcceptRoute
import app.lares.client.ui.onboarding.LinkRoute
import app.lares.client.ui.onboarding.LoginRoute
import app.lares.client.ui.onboarding.QrScanRoute
import app.lares.client.ui.onboarding.ServerRoute
import app.lares.client.ui.onboarding.SetupRoute
import app.lares.client.ui.settings.AboutRoute
import app.lares.client.ui.settings.ChangePasswordRoute
import app.lares.client.ui.settings.DevicesRoute
import app.lares.client.ui.settings.LicensesRoute
import app.lares.client.ui.settings.LoginQrRoute
import app.lares.client.ui.settings.NotificationSettingsRoute
import app.lares.client.ui.settings.ProfileRoute
import app.lares.client.ui.settings.SettingsNavigation
import app.lares.client.ui.settings.SettingsRoute
import app.lares.client.ui.shopping.ShoppingListRoute
import app.lares.client.ui.tasks.TaskDetailRoute
import app.lares.client.ui.tasks.TaskEditorArgs
import app.lares.client.ui.tasks.TaskEditorRoute
import app.lares.contract.DeepLink
import app.lares.contract.permissions.Section
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.datetime.LocalDate
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
data object NotificationSettingsDestination

@Serializable
data object LocationSettingsDestination

@Serializable
data object AboutDestination

@Serializable
data object LicensesDestination

@Serializable
data class FamilyDestination(
    val tab: FamilyTab = FamilyTab.MEMBERS,
)

@Serializable
data class MemberDestination(
    val memberId: String,
)

@Serializable
data class AddMemberDestination(
    val mode: AddMemberMode,
)

/** The login QR of another member (FND-014); [name] is shown in the title. */
@Serializable
data class MemberLoginQrDestination(
    val memberId: String,
    val name: String,
)

@Serializable
data class GroupDestination(
    val groupId: String? = null,
)

@Serializable
data class PersonDestination(
    val personId: String? = null,
)

@Serializable
data object FamilySettingsDestination

/** The tag page (TAG-004). */
@Serializable
data class TagDestination(
    val id: String,
)

/** A shopping list page (M2-04). */
@Serializable
data class ShoppingListDestination(
    val id: String,
    /** Open filtered by this tag (from the tag page). */
    val tagId: String? = null,
)

@Serializable
data class TaskDetailDestination(
    val id: String,
)

/** The task editor (M1-06); [id] null = a new task, with the defaults of [TaskEditorArgs]. */
@Serializable
data class TaskEditorDestination(
    val id: String? = null,
    val assignToMe: Boolean = false,
    val tagId: String? = null,
) {
    fun toArgs() = TaskEditorArgs(id, assignToMe, tagId)

    companion object {
        fun of(args: TaskEditorArgs) = TaskEditorDestination(args.id, args.assignToMe, args.tagId)
    }
}

/** The event page (CAL-014); [occurrence] is the ISO date of the opened occurrence. */
@Serializable
data class EventDestination(
    val id: String,
    val occurrence: String? = null,
)

/** The event editor (M3-06); dates are ISO strings, [scope] a [RepeatScope] name. */
@Serializable
data class EventEditorDestination(
    val id: String? = null,
    val occurrence: String? = null,
    val scope: String = "ALL",
    val date: String? = null,
    val hour: Int? = null,
    val memorable: Boolean = false,
    val personId: String? = null,
    val tagId: String? = null,
) {
    fun toArgs() =
        EventEditorArgs(
            id = id,
            occurrence = occurrence?.let(LocalDate::parse),
            scope = RepeatScope.entries.firstOrNull { it.name == scope } ?: RepeatScope.ALL,
            date = date?.let(LocalDate::parse),
            hour = hour,
            memorable = memorable,
            personId = personId,
            tagId = tagId,
        )

    companion object {
        fun of(args: EventEditorArgs) =
            EventEditorDestination(
                args.id,
                args.occurrence?.toString(),
                args.scope.name,
                args.date?.toString(),
                args.hour,
                args.memorable,
                args.personId,
                args.tagId,
            )
    }
}

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
    NotificationTaps(navController)
    NavHost(navController, startDestination = SectionsDestination) {
        composable<SectionsDestination> {
            MainScaffoldRoute(
                MainNavigation(
                    onSettings = { navController.navigate(SettingsDestination) },
                    onProfile = { navController.navigate(ProfileDestination) },
                    onDevices = { navController.navigate(DevicesDestination) },
                    onSection = { navController.navigate(SectionPageDestination(it)) },
                    onFamily = { navController.navigate(FamilyDestination(it)) },
                    onTask = { navController.navigate(TaskDetailDestination(it)) },
                    onTaskEditor = { navController.navigate(TaskEditorDestination.of(it)) },
                    onShoppingList = { navController.navigate(ShoppingListDestination(it)) },
                    onEvent = { id, occurrence -> navController.navigate(EventDestination(id, occurrence?.toString())) },
                    onEventEditor = { navController.navigate(EventEditorDestination.of(it)) },
                    onPerson = { navController.navigate(PersonDestination(it)) },
                ),
            )
        }
        composable<EventDestination> { entry ->
            val route = entry.toRoute<EventDestination>()
            EventRoute(
                route.id,
                route.occurrence?.let(LocalDate::parse),
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EventEditorDestination.of(it)) },
                onTag = { navController.navigate(TagDestination(it)) },
            )
        }
        composable<EventEditorDestination> { entry ->
            EventEditorRoute(
                entry.toRoute<EventEditorDestination>().toArgs(),
                onBack = { navController.popBackStack() },
                // The new event's page replaces the editor, so Back returns to the calendar.
                onCreated = { id ->
                    navController.navigate(EventDestination(id)) { popUpTo<EventEditorDestination> { inclusive = true } }
                },
                // «Это и следующие» made a new series: its page replaces the old event's page and the editor.
                onSplit = { id, occurrence ->
                    navController.navigate(EventDestination(id, occurrence?.toString())) { popUpTo<EventDestination> { inclusive = true } }
                },
            )
        }
        composable<ShoppingListDestination> { entry ->
            val route = entry.toRoute<ShoppingListDestination>()
            ShoppingListRoute(route.id, onBack = { navController.popBackStack() }, tagId = route.tagId)
        }
        composable<TaskDetailDestination> { entry ->
            val id = entry.toRoute<TaskDetailDestination>().id
            TaskDetailRoute(
                id,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(TaskEditorDestination(id)) },
                onTag = { navController.navigate(TagDestination(it)) },
            )
        }
        composable<TaskEditorDestination> { entry ->
            TaskEditorRoute(
                entry.toRoute<TaskEditorDestination>().toArgs(),
                onBack = { navController.popBackStack() },
                // The new task's page replaces the editor, so Back returns to the list.
                onCreated = { id ->
                    navController.navigate(TaskDetailDestination(id)) { popUpTo<TaskEditorDestination> { inclusive = true } }
                },
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
                    onNotifications = { navController.navigate(NotificationSettingsDestination) },
                    onLocation = { navController.navigate(LocationSettingsDestination) },
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
        composable<NotificationSettingsDestination> { NotificationSettingsRoute(onBack = { navController.popBackStack() }) }
        composable<LocationSettingsDestination> { LocationSettingsRoute(onBack = { navController.popBackStack() }) }
        composable<AboutDestination> {
            AboutRoute(onBack = { navController.popBackStack() }, onLicenses = { navController.navigate(LicensesDestination) })
        }
        composable<LicensesDestination> { LicensesRoute(onBack = { navController.popBackStack() }) }
        familyGraph(navController)
    }
}

/**
 * A tapped task notification or reminder opens the task over the main screen; a task that is gone opens the Tasks tab
 * with a notice instead (plan M1-07). Events open their page (the reminded occurrence, else the next one), a gone
 * event opens the Calendar tab with a notice; birthday reminders open the person (plan M3-08).
 */
@Composable
private fun NotificationTaps(
    navController: NavHostController,
    pending: PendingNavigation = koinInject(),
    taskLinks: TaskLinks = koinInject(),
    eventLinks: EventLinks = koinInject(),
) {
    LaunchedEffect(pending) {
        pending.target.collect { target ->
            when (target) {
                is NavTarget.Task -> {
                    if (!pending.consume(target)) return@collect
                    if (taskLinks.exists(target.id)) {
                        navController.navigate(TaskDetailDestination(target.id)) { popUpTo<SectionsDestination>() }
                    } else {
                        navController.popBackStack<SectionsDestination>(inclusive = false)
                        pending.submit(NavTarget.TaskGone)
                    }
                }

                is NavTarget.Event -> {
                    if (!pending.consume(target)) return@collect
                    if (eventLinks.exists(target.id)) {
                        navController.navigate(
                            EventDestination(target.id, target.occurrence?.toString()),
                        ) { popUpTo<SectionsDestination>() }
                    } else {
                        navController.popBackStack<SectionsDestination>(inclusive = false)
                        pending.submit(NavTarget.EventGone)
                    }
                }

                is NavTarget.Person -> {
                    if (!pending.consume(target)) return@collect
                    navController.navigate(PersonDestination(target.id)) { popUpTo<SectionsDestination>() }
                }

                else -> {}
            }
        }
    }
}

/** Family (plan M0-08): tabs, member detail, adding members, groups, persons, family settings. */
private fun NavGraphBuilder.familyGraph(navController: NavHostController) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<FamilyDestination> { entry ->
        FamilyRoute(
            initialTab = entry.toRoute<FamilyDestination>().tab,
            navigation =
                FamilyNavigation(
                    onBack = back,
                    onMember = { navController.navigate(MemberDestination(it)) },
                    onAddMember = { navController.navigate(AddMemberDestination(it)) },
                    onGroup = { navController.navigate(GroupDestination(it)) },
                    onPerson = { navController.navigate(PersonDestination(it)) },
                    onSettings = { navController.navigate(FamilySettingsDestination) },
                    onTag = { navController.navigate(TagDestination(it)) },
                ),
        )
    }
    composable<TagDestination> { entry ->
        TagRoute(
            entry.toRoute<TagDestination>().id,
            onBack = back,
            onTask = { navController.navigate(TaskDetailDestination(it)) },
            onShoppingList = { id, tagId -> navController.navigate(ShoppingListDestination(id, tagId)) },
            onEvent = { id, occurrence -> navController.navigate(EventDestination(id, occurrence.toString())) },
        )
    }
    composable<MemberDestination> { entry ->
        MemberDetailRoute(
            memberId = entry.toRoute<MemberDestination>().memberId,
            onBack = back,
            onPerson = { navController.navigate(PersonDestination(it)) },
            onLoginQr = { id, name -> navController.navigate(MemberLoginQrDestination(id, name)) },
        )
    }
    composable<AddMemberDestination> { entry ->
        AddMemberRoute(
            mode = entry.toRoute<AddMemberDestination>().mode,
            onBack = back,
            onMemberCreated = { id, name ->
                // The new account logs in by QR next; Back from there returns to the family.
                navController.navigate(MemberLoginQrDestination(id, name)) { popUpTo<AddMemberDestination> { inclusive = true } }
            },
        )
    }
    composable<MemberLoginQrDestination> { entry ->
        val route = entry.toRoute<MemberLoginQrDestination>()
        LoginQrRoute(onBack = back, memberId = route.memberId, memberName = route.name)
    }
    composable<GroupDestination> { entry -> GroupEditorRoute(entry.toRoute<GroupDestination>().groupId, onBack = back) }
    composable<PersonDestination> { entry ->
        PersonRoute(
            entry.toRoute<PersonDestination>().personId,
            onBack = back,
            onEvent = { navController.navigate(EventDestination(it)) },
            onAddMemorable = { navController.navigate(EventEditorDestination.of(EventEditorArgs(memorable = true, personId = it))) },
        )
    }
    composable<FamilySettingsDestination> { FamilySettingsRoute(onBack = back) }
}
