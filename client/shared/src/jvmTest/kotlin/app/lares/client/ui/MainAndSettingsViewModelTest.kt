package app.lares.client.ui

import app.lares.client.auth.SessionState
import app.lares.client.notifications.NotificationSettingsRepository
import app.lares.client.platform.AppLanguage
import app.lares.client.platform.AppearanceSettings
import app.lares.client.platform.ThemeMode
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.main.MainTab
import app.lares.client.ui.main.MainViewModel
import app.lares.client.ui.settings.ChangePasswordViewModel
import app.lares.client.ui.settings.DevicesViewModel
import app.lares.client.ui.settings.LoginQrViewModel
import app.lares.client.ui.settings.NotificationSettingsViewModel
import app.lares.client.ui.settings.SettingsViewModel
import app.lares.client.ui.settings.SwitchableNotificationTypes
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.admin.LoginCodeCreated
import app.lares.contract.api.auth.SessionInfo
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Section
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MainAndSettingsViewModelTest {
    @Test
    fun `sections and bottom bar follow view permissions (FND-028, FND-064)`() {
        val kid = EffectivePermissions(isAdmin = false, permissions = setOf(Permission.TASKS_VIEW, Permission.SHOPPING_EDIT))
        assertEquals(setOf(Section.TASKS), MainViewModel.visibleSections(kid))
        assertEquals(listOf(MainTab.TODAY, MainTab.TASKS, MainTab.MORE), MainTab.visible(MainViewModel.visibleSections(kid)))
        val admin = EffectivePermissions(isAdmin = true, permissions = emptySet())
        assertEquals(Section.entries.toSet(), MainViewModel.visibleSections(admin))
        assertEquals(MainTab.entries, MainTab.visible(MainViewModel.visibleSections(admin)))
        assertEquals(listOf(MainTab.TODAY, MainTab.MORE), MainTab.visible(emptySet()))
    }

    @Test
    fun `main state shows the own profile and permissions from login`() =
        loggedIn { core ->
            val vm =
                MainViewModel(
                    core.session,
                    core.koin.get(),
                    core.koin.get(),
                    core.koin.get(),
                    core.koin.get(),
                    core.koin.get(),
                    core.koin.get(),
                )
            val state = awaitState(vm.state) { it.name.isNotEmpty() }
            assertEquals("Olga", state.name)
            assertEquals(setOf(Section.TASKS), state.sections)
        }

    @Test
    fun `theme and language are stored and the language is applied`() =
        loggedIn { core ->
            val appearance: AppearanceSettings = core.koin.get()
            val vm = SettingsViewModel(core.session, appearance, core.koin.get())
            vm.setTheme(ThemeMode.DARK)
            vm.setLanguage(AppLanguage.RUSSIAN)
            val prefs = awaitState(vm.state) { it.preferences.theme == ThemeMode.DARK && it.preferences.language == AppLanguage.RUSSIAN }
            assertTrue(prefs.hasPassword)
        }

    @Test
    fun `task notification types have switches that persist (TSK-034, FND-052)`() {
        assertEquals(
            listOf(
                NotificationType.TASK_ASSIGNED,
                NotificationType.TASK_COMPLETED,
                NotificationType.TASK_OVERDUE,
                NotificationType.TASK_REMINDER,
                NotificationType.SYSTEM_TEST,
            ),
            SwitchableNotificationTypes,
        )
        var onServer = NotificationSettings()
        loggedIn(extra = { request ->
            if (request.url.encodedPath != "/api/v1/me/notification-settings") return@loggedIn null
            if (request.method == HttpMethod.Put) {
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                onServer = LaresJson.decodeFromString(NotificationSettings.serializer(), body)
            }
            json(NotificationSettings.serializer(), onServer)
        }) { core ->
            val vm = core.koin.get<NotificationSettingsViewModel>()
            awaitState(vm.state) { it.muted.isEmpty() }
            vm.setEnabled(NotificationType.TASK_REMINDER, enabled = false)
            awaitState(vm.state) { it.muted == setOf(NotificationType.TASK_REMINDER) }
            vm.setEnabled(NotificationType.TASK_OVERDUE, enabled = false)
            awaitState(vm.state) { it.muted == setOf(NotificationType.TASK_REMINDER, NotificationType.TASK_OVERDUE) }
            assertEquals(setOf(NotificationType.TASK_REMINDER, NotificationType.TASK_OVERDUE), onServer.muted)
            vm.setEnabled(NotificationType.TASK_OVERDUE, enabled = true)
            awaitState(vm.state) { it.muted == setOf(NotificationType.TASK_REMINDER) }
            val stored = runBlocking { core.koin.get<NotificationSettingsRepository>().isMuted(NotificationType.TASK_REMINDER) }
            assertTrue(stored, "mirrored on the phone for the reminder planner")
        }
    }

    @Test
    fun `delete account - LAST_ADMIN keeps the session, success ends it`() {
        loggedIn(extra = { request ->
            if (request.url.encodedPath == "/api/v1/me" && request.method == HttpMethod.Delete) {
                apiError(HttpStatusCode.Conflict, ErrorCode.LAST_ADMIN)
            } else {
                null
            }
        }) { core ->
            val vm = SettingsViewModel(core.session, core.koin.get(), core.koin.get())
            vm.deleteAccount("secret123")
            assertEquals(UiError.Api(ErrorCode.LAST_ADMIN), awaitState(vm.state) { it.deleteError != null }.deleteError)
            assertIs<SessionState.LoggedIn>(core.session.state.value)
        }
        loggedIn(extra = { request ->
            if (request.url.encodedPath == "/api/v1/me" &&
                request.method == HttpMethod.Delete
            ) {
                respond("", HttpStatusCode.NoContent)
            } else {
                null
            }
        }) { core ->
            val vm = SettingsViewModel(core.session, core.koin.get(), core.koin.get())
            vm.deleteAccount("secret123")
            awaitState(core.session.state) { it is SessionState.ServerKnown }
        }
    }

    @Test
    fun `change password checks fields and maps the server answer`() =
        loggedIn(extra = { request ->
            if (request.url.encodedPath ==
                "/api/v1/me/password"
            ) {
                apiError(HttpStatusCode.Unauthorized, ErrorCode.INVALID_CREDENTIALS)
            } else {
                null
            }
        }) { core ->
            val vm = ChangePasswordViewModel(core.session, core.koin.get())
            vm.submit()
            assertEquals(FieldError.of(FieldErrorCode.REQUIRED), vm.state.value.fieldErrors[ChangePasswordViewModel.CURRENT])
            assertEquals(FieldError.of(FieldErrorCode.REQUIRED), vm.state.value.fieldErrors[ChangePasswordViewModel.NEW])
            vm.onCurrentChange("wrong-one")
            vm.onNewChange("new-password")
            vm.onRepeatChange("new-password")
            vm.submit()
            assertEquals(UiError.Api(ErrorCode.INVALID_CREDENTIALS), awaitState(vm.state) { it.error != null }.error)
            assertFalse(vm.state.value.done)
        }

    @Test
    fun `devices - list with this device first, revoke reloads`() {
        val now = Clock.System.now()
        var sessions =
            listOf(
                SessionInfo("other", "Tablet", "android", now - 10.minutes, now - 5.minutes, current = false),
                SessionInfo("this", "Phone", "android", now - 60.minutes, now - 1.minutes, current = true),
            )
        loggedIn(extra = { request ->
            when {
                request.url.encodedPath == "/api/v1/me/sessions" -> {
                    json(ListSerializer(SessionInfo.serializer()), sessions)
                }

                request.url.encodedPath == "/api/v1/me/sessions/other" -> {
                    sessions = sessions.filter { it.id != "other" }
                    respond("", HttpStatusCode.NoContent)
                }

                else -> {
                    null
                }
            }
        }) { core ->
            val vm = DevicesViewModel(core.koin.get())
            assertEquals(listOf("this", "other"), awaitState(vm.state) { it.sessions != null }.sessions!!.map { it.id })
            vm.revoke("other")
            assertEquals(listOf("this"), awaitState(vm.state) { it.sessions?.size == 1 && !it.loading }.sessions!!.map { it.id })
        }
    }

    @Test
    fun `login QR counts down and expires`() {
        val expiresAt: Instant = Clock.System.now() + 2.seconds
        loggedIn(extra = { request ->
            if (request.url.encodedPath == "/api/v1/login-codes") {
                json(LoginCodeCreated.serializer(), LoginCodeCreated("ABCDEFGH", "lares://login?server=x&code=ABCDEFGH", expiresAt))
            } else {
                null
            }
        }) { core ->
            val vm = LoginQrViewModel(core.koin.get(), Clock.System)
            val shown = awaitState(vm.state) { it.payload != null }
            assertEquals("ABCDEFGH", shown.code)
            assertTrue(awaitState(vm.state) { it.expired }.expired)
        }
    }

    private fun loggedIn(
        extra: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData? = { null },
        block: (UiTestCore) -> Unit,
    ) = UiTestCore { request ->
        extra(request) ?: when (request.url.encodedPath) {
            "/api/v1/instance" -> instance()
            "/api/v1/auth/login" -> authResult()
            else -> unavailable()
        }
    }.use { core ->
        runBlocking {
            core.session.connect("https://family.test")
            core.session.login("olga", "secret123")
        }
        block(core)
    }
}
