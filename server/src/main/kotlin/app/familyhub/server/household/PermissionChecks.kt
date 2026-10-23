package app.familyhub.server.household

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.permissions.Permission
import app.familyhub.server.identity.memberPrincipal
import app.familyhub.server.platform.http.ApiException
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

private val permissionServiceKey = AttributeKey<PermissionService>("PermissionService")

/** Makes [service] available to [requirePermission]; called once by `Application.module`. */
fun Application.installPermissionChecks(service: PermissionService) {
    attributes.put(permissionServiceKey, service)
}

/** Answers `FORBIDDEN` unless the authenticated caller has [permission] (ADR-0014; administrators have all). */
suspend fun ApplicationCall.requirePermission(permission: Permission) {
    val principal = memberPrincipal
    val service = application.attributes[permissionServiceKey]
    if (!service.effective(principal.householdId, principal.memberId).has(permission)) {
        throw ApiException(ErrorCode.FORBIDDEN, "Missing permission ${permission.key}")
    }
}
