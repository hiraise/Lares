package app.lares.server.location

import app.lares.contract.api.location.SetSharingRequest
import app.lares.contract.api.location.UploadPositionsRequest
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
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket

/** Positions, sharing state, now, history, «Обновить», watchers and the live channel (M4-03). Mounted under `/api/v1`. */
fun Route.locationRoutes(
    authService: AuthService,
    locations: LocationService,
    live: LocationLiveService,
) {
    authenticated(authService) {
        route("/location") {
            put("/me/sharing") {
                locations.setSharing(call.memberPrincipal, call.receive<SetSharingRequest>())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/positions") {
                call.respond(locations.upload(call.memberPrincipal, call.receive<UploadPositionsRequest>()))
            }
            get("/now") {
                call.requirePermission(Permission.LOCATION_VIEW)
                call.respond(locations.now(call.memberPrincipal))
            }
            get("/history") {
                call.requirePermission(Permission.LOCATION_VIEW)
                val parameters = call.request.queryParameters
                call.respond(locations.history(call.memberPrincipal, parameters["memberId"], parameters["date"]))
            }
            post("/members/{id}/refresh") {
                call.requirePermission(Permission.LOCATION_VIEW)
                locations.refresh(call.memberPrincipal, call.parameters["id"].orEmpty())
                call.respond(HttpStatusCode.Accepted)
            }
            get("/me/watchers") {
                call.respond(locations.watchers(call.memberPrincipal))
            }
            get("/config") {
                call.respond(locations.config(call.memberPrincipal))
            }
            webSocket("/live") {
                live.serve(this, call.memberPrincipal)
            }
        }
    }
}
