package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.server.platform.http.ApiException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey

class RequireSessionConfig {
    lateinit var authService: AuthService
}

private val principalKey = AttributeKey<MemberPrincipal>("MemberPrincipal")

/**
 * Authenticates `Authorization: Bearer <access token>` and stores the [MemberPrincipal] in call attributes.
 * Fails with `UNAUTHENTICATED`, `SESSION_EXPIRED` or `SESSION_REVOKED` (architecture/auth.md).
 */
val RequireSession =
    createRouteScopedPlugin("RequireSession", ::RequireSessionConfig) {
        val authService = pluginConfig.authService
        onCall { call ->
            val header = call.request.headers[HttpHeaders.Authorization]
            val token =
                header
                    ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                    ?.substring(BEARER_PREFIX.length)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Missing bearer token")
            call.attributes.put(principalKey, authService.authenticate(token))
        }
    }

private const val BEARER_PREFIX = "Bearer "

/** The authenticated caller; only available inside [authenticated] routes. */
val ApplicationCall.memberPrincipal: MemberPrincipal
    get() = attributes.getOrNull(principalKey) ?: error("No principal: route is not inside authenticated { }")

private val requireSessionInstalled = AttributeKey<Unit>("RequireSessionInstalled")

/** Groups routes that require a valid session. Adds no path segment. */
fun Route.authenticated(
    authService: AuthService,
    build: Route.() -> Unit,
): Route {
    val child = createChild(AuthenticatedSelector)
    if (!child.attributes.contains(requireSessionInstalled)) {
        child.install(RequireSession) { this.authService = authService }
        child.attributes.put(requireSessionInstalled, Unit)
    }
    child.build()
    return child
}

private object AuthenticatedSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(authenticated)"
}
