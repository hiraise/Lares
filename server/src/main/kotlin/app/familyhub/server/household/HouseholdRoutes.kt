package app.familyhub.server.household

import app.familyhub.contract.api.admin.CreateGroupRequest
import app.familyhub.contract.api.admin.UpdateGroupRequest
import app.familyhub.contract.api.admin.UpdateHouseholdRequest
import app.familyhub.contract.permissions.Permission
import app.familyhub.server.identity.AuthService
import app.familyhub.server.identity.authenticated
import app.familyhub.server.identity.memberPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Household settings and groups (architecture/api.md, requires `household.manage`). Mounted under `/api/v1`, protocol-checked. */
fun Route.householdRoutes(
    authService: AuthService,
    householdAdmin: HouseholdAdminService,
    groupAdmin: GroupAdminService,
) {
    authenticated(authService) {
        patch("/household") {
            call.requirePermission(Permission.HOUSEHOLD_MANAGE)
            householdAdmin.update(call.memberPrincipal.householdId, call.receive<UpdateHouseholdRequest>())
            call.respond(HttpStatusCode.NoContent)
        }
        route("/groups") {
            post {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                call.respond(
                    HttpStatusCode.Created,
                    groupAdmin.create(call.memberPrincipal.householdId, call.receive<CreateGroupRequest>()),
                )
            }
            patch("/{id}") {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                groupAdmin.update(call.memberPrincipal.householdId, call.parameters["id"].orEmpty(), call.receive<UpdateGroupRequest>())
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/{id}") {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                groupAdmin.delete(call.memberPrincipal.householdId, call.parameters["id"].orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
