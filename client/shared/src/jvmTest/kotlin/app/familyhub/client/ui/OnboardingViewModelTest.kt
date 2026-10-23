package app.familyhub.client.ui

import app.familyhub.client.auth.SessionState
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.field_passwords_differ
import app.familyhub.client.ui.common.FieldError
import app.familyhub.client.ui.common.UiError
import app.familyhub.client.ui.onboarding.AfterConnect
import app.familyhub.client.ui.onboarding.InviteAcceptViewModel
import app.familyhub.client.ui.onboarding.LoginViewModel
import app.familyhub.client.ui.onboarding.ServerViewModel
import app.familyhub.client.ui.onboarding.SetupDefaults
import app.familyhub.client.ui.onboarding.SetupFields
import app.familyhub.client.ui.onboarding.SetupViewModel
import app.familyhub.client.ui.onboarding.setupDefaults
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.api.admin.InvitePreview
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingViewModelTest {
    @Test
    fun `server address is normalized and an initialized server leads to login`() =
        UiTestCore { instance(initialized = true) }.use { core ->
            val vm = ServerViewModel(core.session)
            vm.onUrlChange("  family.example.org/ ")
            vm.connect()
            val state = awaitState(vm.state) { it.next != null }
            assertEquals(AfterConnect.LOGIN, state.next)
            assertEquals(
                "https://family.example.org/api/v1/instance",
                core.requests
                    .single()
                    .url
                    .toString(),
            )
            assertEquals("https://family.example.org", (core.session.state.value as SessionState.ServerKnown).serverUrl)
        }

    @Test
    fun `a new server leads to setup`() =
        UiTestCore { instance(initialized = false) }.use { core ->
            val vm = ServerViewModel(core.session)
            vm.onUrlChange("http://10.0.2.2:8080")
            vm.connect()
            assertEquals(AfterConnect.SETUP, awaitState(vm.state) { it.next != null }.next)
            assertEquals(
                "http://10.0.2.2:8080/api/v1/instance",
                core.requests
                    .single()
                    .url
                    .toString(),
            )
        }

    @Test
    fun `an empty address is a field error without a request`() =
        UiTestCore { instance() }.use { core ->
            val vm = ServerViewModel(core.session)
            vm.connect()
            assertEquals(FieldError.of(FieldErrorCode.REQUIRED), vm.state.value.urlError)
            assertTrue(core.requests.isEmpty())
        }

    @Test
    fun `connect errors - not FamilyHub, protocol, unreachable`() {
        UiTestCore { respond("<html>hello</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }.use { core ->
            assertEquals(UiError.NotFamilyHub, connectAndAwaitError(core))
        }
        UiTestCore { respond("Not found", HttpStatusCode.NotFound) }.use { core ->
            assertEquals(UiError.NotFamilyHub, connectAndAwaitError(core))
        }
        UiTestCore {
            json(
                app.familyhub.contract.api.InstanceInfo
                    .serializer(),
                app.familyhub.contract.api
                    .InstanceInfo(
                        true,
                        "9.0",
                        app.familyhub.contract.api
                            .ProtocolRange(7, 9),
                    ),
            )
        }.use { core ->
            assertEquals(UiError.Api(ErrorCode.PROTOCOL_UNSUPPORTED), connectAndAwaitError(core))
        }
        UiTestCore { throw java.net.ConnectException("refused") }.use { core ->
            assertEquals(UiError.Network, connectAndAwaitError(core))
        }
    }

    private fun connectAndAwaitError(core: UiTestCore): UiError? {
        val vm = ServerViewModel(core.session)
        vm.onUrlChange("https://somewhere.test")
        vm.connect()
        return awaitState(vm.state) { it.error != null }.error
    }

    @Test
    fun `setup validates locally before sending`() =
        UiTestCore { instance() }.use { core ->
            val vm = SetupViewModel(core.session, SetupDefaults("Europe/Moscow", "RUB", "ru"))
            vm.onLoginChange("O")
            vm.onPasswordChange("short")
            vm.onPasswordRepeatChange("other")
            vm.onCurrencyChange("rub")
            vm.submit()
            val errors = vm.state.value.fieldErrors
            assertEquals(FieldError.of(FieldErrorCode.REQUIRED), errors[SetupFields.SETUP_CODE])
            assertEquals(FieldError.of(FieldErrorCode.REQUIRED), errors[SetupFields.HOUSEHOLD_NAME])
            assertEquals(FieldError.of(FieldErrorCode.REQUIRED), errors[SetupFields.DISPLAY_NAME])
            assertEquals(FieldError.of(FieldErrorCode.TOO_SHORT), errors[SetupFields.LOGIN])
            assertEquals(FieldError.of(FieldErrorCode.TOO_SHORT), errors[SetupFields.PASSWORD])
            assertEquals(FieldError(Res.string.field_passwords_differ), errors[SetupFields.PASSWORD_REPEAT])
            assertNull(errors[SetupFields.CURRENCY], "currency is uppercased while typing")
            assertEquals("RUB", vm.state.value.currency)
            assertTrue(core.requests.isEmpty())
            // Editing a field clears only its error.
            vm.onLoginChange("olga")
            assertNull(vm.state.value.fieldErrors[SetupFields.LOGIN])
            assertEquals(FieldError.of(FieldErrorCode.TOO_SHORT), vm.state.value.fieldErrors[SetupFields.PASSWORD])
        }

    @Test
    fun `setup maps server field errors to the form and logs in on success`() {
        UiTestCore { request ->
            when (request.url.encodedPath) {
                "/api/v1/instance" -> instance(initialized = false)
                "/api/v1/setup" -> apiError(HttpStatusCode.BadRequest, ErrorCode.VALIDATION_FAILED, mapOf("login" to FieldErrorCode.TAKEN))
                else -> unavailable()
            }
        }.use { core ->
            runBlocking { core.session.connect("https://family.test") }
            val vm = filledSetup(core)
            vm.submit()
            val state = awaitState(vm.state) { it.error != null }
            assertEquals(UiError.Api(ErrorCode.VALIDATION_FAILED), state.error)
            assertEquals(FieldError.of(FieldErrorCode.TAKEN), state.fieldErrors[SetupFields.LOGIN])
        }
        UiTestCore { request ->
            when (request.url.encodedPath) {
                "/api/v1/instance" -> instance(initialized = false)
                "/api/v1/setup" -> authResult()
                else -> unavailable()
            }
        }.use { core ->
            runBlocking { core.session.connect("https://family.test") }
            filledSetup(core).submit()
            val state = awaitState(core.session.state) { it is SessionState.LoggedIn }
            assertEquals("olga", (state as SessionState.LoggedIn).me.login)
        }
    }

    private fun filledSetup(core: UiTestCore) =
        SetupViewModel(core.session, SetupDefaults("Europe/Moscow", "RUB", "ru")).apply {
            onSetupCodeChange("abcde-fghjk")
            onHouseholdNameChange("Petrovy")
            onDisplayNameChange("Olga")
            onLoginChange("olga")
            onPasswordChange("secret123")
            onPasswordRepeatChange("secret123")
        }

    @Test
    fun `setup defaults follow the device locale`() {
        assertEquals("RUB", setupDefaults("ru", "RU").currency)
        assertEquals("EUR", setupDefaults("de", "DE").currency)
        assertEquals("USD", setupDefaults("en", "US").currency)
        assertEquals("RUB", setupDefaults("ru", "").currency)
        assertEquals("ru", setupDefaults("ru", "RU").language)
        assertEquals("en", setupDefaults("de", "DE").language)
    }

    @Test
    fun `login shows invalid credentials and rate limiting`() {
        for (code in listOf(ErrorCode.INVALID_CREDENTIALS, ErrorCode.RATE_LIMITED)) {
            UiTestCore { request ->
                when (request.url.encodedPath) {
                    "/api/v1/auth/login" -> {
                        apiError(
                            if (code ==
                                ErrorCode.RATE_LIMITED
                            ) {
                                HttpStatusCode.TooManyRequests
                            } else {
                                HttpStatusCode.Unauthorized
                            },
                            code,
                        )
                    }

                    "/api/v1/instance" -> {
                        instance()
                    }

                    else -> {
                        unavailable()
                    }
                }
            }.use { core ->
                runBlocking { core.session.connect("https://family.test") }
                val vm = LoginViewModel(core.session)
                vm.onLoginChange("olga")
                vm.onPasswordChange("wrong-password")
                vm.submit()
                assertEquals(UiError.Api(code), awaitState(vm.state) { it.error != null }.error)
                assertIs<SessionState.ServerKnown>(core.session.state.value)
            }
        }
    }

    @Test
    fun `login requires both fields and opens setup for a server that is not set up`() =
        UiTestCore { instance(initialized = false) }.use { core ->
            runBlocking { core.session.connect("https://family.test") }
            val vm = LoginViewModel(core.session)
            vm.submit()
            assertEquals(FieldError.of(FieldErrorCode.REQUIRED), vm.state.value.loginError)
            assertEquals(FieldError.of(FieldErrorCode.REQUIRED), vm.state.value.passwordError)
            assertTrue(awaitState(vm.state) { it.needsSetup }.needsSetup)
        }

    @Test
    fun `invite - preview, then join`() =
        UiTestCore { request ->
            when (request.url.encodedPath) {
                "/api/v1/instance" -> instance()
                "/api/v1/invites/preview" -> json(InvitePreview.serializer(), InvitePreview("Petrovy", "Olga"))
                "/api/v1/invites/accept" -> authResult(testMe.copy(login = "dima", displayName = "Dmitry"))
                else -> unavailable()
            }
        }.use { core ->
            runBlocking { core.session.connect("https://family.test") }
            val vm = InviteAcceptViewModel(core.session, "ABCD-EFGH")
            assertEquals(InvitePreview("Petrovy", "Olga"), awaitState(vm.state) { it.preview != null }.preview)
            vm.onDisplayNameChange("Dmitry")
            vm.onLoginChange("dima")
            vm.onPasswordChange("password1")
            vm.onPasswordRepeatChange("password2")
            vm.submit()
            assertEquals(FieldError(Res.string.field_passwords_differ), vm.state.value.fieldErrors[SetupFields.PASSWORD_REPEAT])
            vm.onPasswordRepeatChange("password1")
            vm.submit()
            awaitState(core.session.state) { it is SessionState.LoggedIn }
            val body = core.requests.first { it.url.encodedPath == "/api/v1/invites/accept" }.body
            val sent = String((body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes())
            assertTrue("\"code\":\"ABCDEFGH\"" in sent, sent)
        }

    @Test
    fun `invite - an invalid code is shown instead of the form`() =
        UiTestCore { request ->
            when (request.url.encodedPath) {
                "/api/v1/invites/preview" -> apiError(HttpStatusCode.BadRequest, ErrorCode.INVITE_INVALID)
                else -> instance()
            }
        }.use { core ->
            runBlocking { core.session.connect("https://family.test") }
            val vm = InviteAcceptViewModel(core.session, "WRONG")
            assertEquals(UiError.Api(ErrorCode.INVITE_INVALID), awaitState(vm.state) { it.previewError != null }.previewError)
        }
}
