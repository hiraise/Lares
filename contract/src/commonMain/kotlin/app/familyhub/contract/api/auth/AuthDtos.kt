package app.familyhub.contract.api.auth

import app.familyhub.contract.permissions.Permission
import app.familyhub.contract.permissions.PermissionSetSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Request and response bodies of setup, login, sessions and account endpoints (architecture/auth.md, api.md).

/** The app installation a session belongs to, shown in the session list. */
@Serializable
data class DeviceInfo(
    val name: String,
    /** Free text such as `android`, `ios`, `web`. */
    val platform: String,
)

/** `POST /api/v1/setup` (FND-001). */
@Serializable
data class SetupRequest(
    val setupCode: String,
    val householdName: String,
    val timeZone: String,
    val currency: String,
    val defaultLanguage: String,
    /** The administrator's name (their person). */
    val displayName: String,
    val login: String,
    val password: String,
    val device: DeviceInfo,
)

/** `POST /api/v1/auth/login` (FND-010). */
@Serializable
data class LoginRequest(
    val login: String,
    val password: String,
    val device: DeviceInfo,
)

/** `POST /api/v1/auth/refresh` (FND-011). */
@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

/** Tokens of a new or refreshed session. */
@Serializable
data class AuthResult(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val sessionId: String,
    val me: Me,
)

/** `GET /api/v1/me` (FND-020): who the caller is and what they may do. */
@Serializable
data class Me(
    val userId: String,
    val memberId: String,
    val householdId: String,
    val householdName: String,
    val personId: String,
    val displayName: String,
    val login: String?,
    val hasPassword: Boolean,
    val isAdmin: Boolean,
    @Serializable(with = PermissionSetSerializer::class) val permissions: Set<Permission>,
)

/** One entry of `GET /api/v1/me/sessions` (FND-011). */
@Serializable
data class SessionInfo(
    val id: String,
    val deviceName: String,
    val platform: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    /** True for the session that made the request. */
    val current: Boolean,
)

/** `POST /api/v1/me/password`. [currentPassword] is required when the user has a password. */
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String? = null,
    val newPassword: String,
)

/** `DELETE /api/v1/me` (FND-013). [password] is required when the user has one. */
@Serializable
data class DeleteAccountRequest(
    val password: String? = null,
)
