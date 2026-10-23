package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.ChangePasswordRequest
import app.lares.contract.api.auth.DeleteAccountRequest
import app.lares.contract.api.auth.LoginRequest
import app.lares.contract.api.auth.RefreshRequest
import app.lares.contract.api.auth.SetupRequest
import app.lares.server.platform.http.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException

/** Setup, login, sessions and own-account endpoints (architecture/api.md). Mounted under `/api/v1`, protocol-checked. */
fun Route.identityRoutes(
    setupService: SetupService,
    authService: AuthService,
    accountService: AccountService,
) {
    post("/setup") {
        call.respond(setupService.setup(call.receive<SetupRequest>(), call.clientIp))
    }
    route("/auth") {
        post("/login") {
            call.respond(authService.login(call.receive<LoginRequest>(), call.clientIp))
        }
        post("/refresh") {
            call.respond(authService.refresh(call.receive<RefreshRequest>()))
        }
        authenticated(authService) {
            post("/logout") {
                authService.logout(call.memberPrincipal)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    authenticated(authService) {
        route("/me") {
            get {
                call.respond(accountService.me(call.memberPrincipal))
            }
            post("/password") {
                accountService.changePassword(call.memberPrincipal, call.receive<ChangePasswordRequest>())
                call.respond(HttpStatusCode.NoContent)
            }
            get("/sessions") {
                call.respond(accountService.sessions(call.memberPrincipal))
            }
            delete("/sessions/{id}") {
                accountService.revokeSession(call.memberPrincipal, call.parameters["id"].orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
            delete {
                // The body is optional: users without a password may send none.
                val text = call.receiveText()
                val request =
                    if (text.isBlank()) {
                        DeleteAccountRequest()
                    } else {
                        try {
                            LaresJson.decodeFromString<DeleteAccountRequest>(text)
                        } catch (e: SerializationException) {
                            throw ApiException(ErrorCode.VALIDATION_FAILED, "Malformed request body")
                        } catch (e: IllegalArgumentException) {
                            throw ApiException(ErrorCode.VALIDATION_FAILED, "Malformed request body")
                        }
                    }
                accountService.deleteAccount(call.memberPrincipal, request)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** Client IP; behind Caddy it comes from `X-Forwarded-For` (XForwardedHeaders is installed). */
val ApplicationCall.clientIp: String get() = request.origin.remoteHost
