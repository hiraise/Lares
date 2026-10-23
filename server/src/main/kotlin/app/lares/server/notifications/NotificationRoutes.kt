package app.lares.server.notifications

import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.PushRegistrationRequest
import app.lares.contract.api.notifications.PushTestResponse
import app.lares.server.identity.AuthService
import app.lares.server.identity.authenticated
import app.lares.server.identity.memberPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Push registration and notification settings of the caller (architecture/api.md, FND-051, FND-052). */
fun Route.notificationRoutes(
    authService: AuthService,
    notifications: NotificationService,
) {
    authenticated(authService) {
        route("/me/push") {
            put {
                notifications.register(call.memberPrincipal, call.receive<PushRegistrationRequest>())
                call.respond(HttpStatusCode.NoContent)
            }
            delete {
                notifications.unregister(call.memberPrincipal)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/test") {
                call.respond(PushTestResponse(notifications.sendTest(call.memberPrincipal)))
            }
        }
        route("/me/notification-settings") {
            get {
                call.respond(notifications.settings(call.memberPrincipal))
            }
            put {
                call.respond(notifications.setSettings(call.memberPrincipal, call.receive<NotificationSettings>()))
            }
        }
    }
}
