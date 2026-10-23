package app.familyhub.contract.api.admin

import app.familyhub.contract.api.auth.DeviceInfo
import app.familyhub.contract.permissions.Permission
import app.familyhub.contract.permissions.PermissionSetSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Household administration, invites and login codes (architecture/api.md, architecture/auth.md).
// Fields left null in PATCH-style requests are not changed.

/** `PATCH /api/v1/household` (FND-003). */
@Serializable
data class UpdateHouseholdRequest(
    val name: String? = null,
    val timeZone: String? = null,
    val currency: String? = null,
    val defaultLanguage: String? = null,
)

/**
 * `POST /api/v1/members` (FND-022). [login] and [password] are optional: without a login the member logs in by QR code only.
 * A [password] requires a [login]. With [personId] an existing relative becomes the member's profile (FND-032).
 */
@Serializable
data class CreateMemberRequest(
    val displayName: String,
    val login: String? = null,
    val password: String? = null,
    val groupIds: Set<String> = emptySet(),
    val personId: String? = null,
)

@Serializable
data class MemberCreated(
    val memberId: String,
    val personId: String,
)

/** `POST /api/v1/members/{id}/password` (FND-012). Revokes all sessions of that member. */
@Serializable
data class ResetPasswordRequest(
    val newPassword: String,
)

/** `PUT /api/v1/members/{id}/groups`: the complete set of the member's groups. */
@Serializable
data class SetGroupsRequest(
    val groupIds: Set<String>,
)

/** `POST /api/v1/groups`. Only the given permissions are stored; implied section access is added on evaluation. */
@Serializable
data class CreateGroupRequest(
    val name: String,
    @Serializable(with = PermissionSetSerializer::class) val permissions: Set<Permission> = emptySet(),
    val memberIds: Set<String> = emptySet(),
)

/** `PATCH /api/v1/groups/{id}`. [permissions] and [memberIds] replace the whole set when given. */
@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    @Serializable(with = PermissionSetSerializer::class) val permissions: Set<Permission>? = null,
    val memberIds: Set<String>? = null,
)

@Serializable
data class GroupCreated(
    val groupId: String,
)

/** `POST /api/v1/invites` (FND-024). With [personId] the accepting user becomes that relative (FND-032). */
@Serializable
data class CreateInviteRequest(
    val groupIds: Set<String>,
    val expiresInHours: Int = DEFAULT_INVITE_HOURS,
    val personId: String? = null,
) {
    companion object {
        const val DEFAULT_INVITE_HOURS = 72
        const val MAX_INVITE_HOURS = 30 * 24
    }
}

/** [code] is normalized (no dash) and shown only once; [payload] is the QR / link content (`DeepLinks.invite`). */
@Serializable
data class InviteCreated(
    val id: String,
    val code: String,
    val payload: String,
    val expiresAt: Instant,
)

/** One entry of `GET /api/v1/invites` (active invites only). [createdBy] is a member id. */
@Serializable
data class InviteInfo(
    val id: String,
    val groupIds: Set<String>,
    val createdBy: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** `POST /api/v1/invites/preview` (public). */
@Serializable
data class InvitePreviewRequest(
    val code: String,
)

@Serializable
data class InvitePreview(
    val householdName: String,
    val inviterName: String,
)

/** `POST /api/v1/invites/accept` (public) → `AuthResult`. */
@Serializable
data class AcceptInviteRequest(
    val code: String,
    val displayName: String,
    val login: String,
    val password: String,
    val device: DeviceInfo,
)

/** `POST /api/v1/login-codes` (FND-014). Without [memberId] the code is for the caller. */
@Serializable
data class CreateLoginCodeRequest(
    val memberId: String? = null,
)

/** [code] is normalized (no dash); [payload] is the QR content (`DeepLinks.login`). */
@Serializable
data class LoginCodeCreated(
    val code: String,
    val payload: String,
    val expiresAt: Instant,
)

/** `POST /api/v1/auth/login-code` (public) → `AuthResult`. */
@Serializable
data class LoginWithCodeRequest(
    val code: String,
    val device: DeviceInfo,
)
