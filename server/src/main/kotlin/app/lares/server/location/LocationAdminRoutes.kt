package app.lares.server.location

import app.lares.contract.api.location.PutLocationRulesRequest
import app.lares.contract.permissions.Permission
import app.lares.server.household.requirePermission
import app.lares.server.identity.AuthService
import app.lares.server.identity.authenticated
import app.lares.server.identity.memberPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Visibility rules for administrators (ADR-0025, `household.manage`). Mounted under `/api/v1`, protocol-checked. */
fun Route.locationAdminRoutes(
    authService: AuthService,
    visibility: LocationVisibilityService,
) {
    authenticated(authService) {
        route("/location/rules") {
            get {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                call.respond(visibility.get(call.memberPrincipal.householdId))
            }
            put {
                call.requirePermission(Permission.HOUSEHOLD_MANAGE)
                visibility.put(call.memberPrincipal.householdId, call.receive<PutLocationRulesRequest>())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
