package app.familyhub.server.sync

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.sync.PushRequest
import app.familyhub.contract.sync.SyncLimits
import app.familyhub.server.identity.AuthService
import app.familyhub.server.identity.authenticated
import app.familyhub.server.identity.memberPrincipal
import app.familyhub.server.platform.http.ApiException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket

/** Sync endpoints (architecture/sync.md). Mounted under `/api/v1`, protocol-checked; every member may sync. */
fun Route.syncRoutes(
    authService: AuthService,
    pull: SyncPullService,
    push: SyncPushService,
    events: SyncEventsService,
) {
    authenticated(authService) {
        route("/sync") {
            get("/changes") {
                val parameters = call.request.queryParameters

                fun long(name: String): Long? =
                    parameters[name]?.let { it.toLongOrNull() ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Invalid $name") }
                val request =
                    PullRequest(
                        after = long("after") ?: 0,
                        limit =
                            long("limit")?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
                                ?: SyncLimits.DEFAULT_PULL_LIMIT,
                        accessVersion = long("accessVersion"),
                        epoch = parameters["epoch"]?.takeIf { it.isNotEmpty() },
                    )
                call.respond(pull.pull(call.memberPrincipal, request))
            }
            post("/mutations") {
                call.respond(push.push(call.memberPrincipal, call.receive<PushRequest>()))
            }
            webSocket("/events") {
                events.serve(this, call.memberPrincipal.householdId)
            }
        }
    }
}
