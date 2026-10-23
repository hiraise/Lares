package app.lares.server.identity

import java.util.UUID

/** The authenticated caller, stored in call attributes by [RequireSession]. */
data class MemberPrincipal(
    val userId: UUID,
    val memberId: UUID,
    val householdId: UUID,
    val sessionId: UUID,
)
