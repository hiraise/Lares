package app.familyhub.server.identity

import app.familyhub.contract.api.admin.AcceptInviteRequest
import app.familyhub.contract.api.admin.CreateInviteRequest
import app.familyhub.contract.api.admin.CreateLoginCodeRequest
import app.familyhub.contract.api.admin.CreateMemberRequest
import app.familyhub.contract.api.admin.InvitePreviewRequest
import app.familyhub.contract.api.admin.LoginWithCodeRequest
import app.familyhub.contract.api.admin.ResetPasswordRequest
import app.familyhub.contract.api.admin.SetGroupsRequest
import app.familyhub.contract.permissions.Permission
import app.familyhub.server.household.requirePermission
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Members, invites and login codes (architecture/api.md). Mounted under `/api/v1`, protocol-checked. */
fun Route.memberAdminRoutes(
    authService: AuthService,
    memberAdmin: MemberAdminService,
    invites: InviteService,
    loginCodes: LoginCodeService,
) {
    // Public: the code in the body is the credential.
    post("/invites/preview") {
        call.respond(invites.preview(call.receive<InvitePreviewRequest>(), call.clientIp))
    }
    post("/invites/accept") {
        call.respond(invites.accept(call.receive<AcceptInviteRequest>(), call.clientIp))
    }
    post("/auth/login-code") {
        call.respond(loginCodes.exchange(call.receive<LoginWithCodeRequest>(), call.clientIp))
    }

    authenticated(authService) {
        // Any member for themselves; for another member `household.manage` (checked by the service).
        post("/login-codes") {
            call.respond(HttpStatusCode.Created, loginCodes.create(call.memberPrincipal, call.receive<CreateLoginCodeRequest>()))
        }
        route("/members") {
            post {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                call.respond(
                    HttpStatusCode.Created,
                    memberAdmin.create(call.memberPrincipal.householdId, call.receive<CreateMemberRequest>()),
                )
            }
            delete("/{id}") {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                memberAdmin.remove(call.memberPrincipal.householdId, call.parameters["id"].orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/{id}/password") {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                memberAdmin.resetPassword(
                    call.memberPrincipal.householdId,
                    call.parameters["id"].orEmpty(),
                    call.receive<ResetPasswordRequest>(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            put("/{id}/groups") {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                memberAdmin.setGroups(call.memberPrincipal.householdId, call.parameters["id"].orEmpty(), call.receive<SetGroupsRequest>())
                call.respond(HttpStatusCode.NoContent)
            }
        }
        route("/invites") {
            post {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                call.respond(HttpStatusCode.Created, invites.create(call.memberPrincipal, call.receive<CreateInviteRequest>()))
            }
            get {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                call.respond(invites.listActive(call.memberPrincipal.householdId))
            }
            delete("/{id}") {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                invites.revoke(call.memberPrincipal.householdId, call.parameters["id"].orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
