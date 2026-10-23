package app.lares.server.platform.http

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.Protocol
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey

/** Rejects requests without a supported `X-Lares-Protocol` header (architecture/sync.md § Versioning). */
val ProtocolCheck =
    createRouteScopedPlugin("ProtocolCheck") {
        onCall { call ->
            val header =
                call.request.headers[Protocol.HEADER]
                    ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Missing ${Protocol.HEADER} header")
            val version =
                header.trim().toIntOrNull()
                    ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Invalid ${Protocol.HEADER} header")
            if (version !in Protocol.MIN_SUPPORTED..Protocol.VERSION) {
                throw ApiException(
                    ErrorCode.PROTOCOL_UNSUPPORTED,
                    "Protocol $version is not supported; supported ${Protocol.MIN_SUPPORTED}..${Protocol.VERSION}",
                )
            }
        }
    }

private val protocolCheckInstalled = AttributeKey<Unit>("ProtocolCheckInstalled")

/** Groups routes that require the protocol header. Adds no path segment; may be called several times per parent. */
fun Route.protocolChecked(build: Route.() -> Unit): Route {
    // Equal selectors resolve to the same child node, so install the plugin only once.
    val child = createChild(ProtocolCheckedSelector)
    if (!child.attributes.contains(protocolCheckInstalled)) {
        child.install(ProtocolCheck)
        child.attributes.put(protocolCheckInstalled, Unit)
    }
    child.build()
    return child
}

private object ProtocolCheckedSelector : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(protocol checked)"
}
