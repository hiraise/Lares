package app.familyhub.client.api

import app.familyhub.client.auth.TokenStore
import app.familyhub.client.auth.Tokens
import app.familyhub.client.platform.ServerStore
import app.familyhub.contract.api.ApiError
import app.familyhub.contract.api.ApiErrorBody
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.InstanceInfo
import app.familyhub.contract.api.admin.AcceptInviteRequest
import app.familyhub.contract.api.admin.CreateGroupRequest
import app.familyhub.contract.api.admin.CreateInviteRequest
import app.familyhub.contract.api.admin.CreateLoginCodeRequest
import app.familyhub.contract.api.admin.CreateMemberRequest
import app.familyhub.contract.api.admin.GroupCreated
import app.familyhub.contract.api.admin.InviteCreated
import app.familyhub.contract.api.admin.InviteInfo
import app.familyhub.contract.api.admin.InvitePreview
import app.familyhub.contract.api.admin.InvitePreviewRequest
import app.familyhub.contract.api.admin.LoginCodeCreated
import app.familyhub.contract.api.admin.LoginWithCodeRequest
import app.familyhub.contract.api.admin.MemberCreated
import app.familyhub.contract.api.admin.ResetPasswordRequest
import app.familyhub.contract.api.admin.SetGroupsRequest
import app.familyhub.contract.api.admin.UpdateGroupRequest
import app.familyhub.contract.api.admin.UpdateHouseholdRequest
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.api.auth.ChangePasswordRequest
import app.familyhub.contract.api.auth.DeleteAccountRequest
import app.familyhub.contract.api.auth.LoginRequest
import app.familyhub.contract.api.auth.Me
import app.familyhub.contract.api.auth.RefreshRequest
import app.familyhub.contract.api.auth.SessionInfo
import app.familyhub.contract.api.auth.SetupRequest
import app.familyhub.contract.sync.PullResponse
import app.familyhub.contract.sync.PushRequest
import app.familyhub.contract.sync.PushResponse
import app.familyhub.contract.sync.SyncEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Typed client of the FamilyHub HTTP API (architecture/api.md). Authenticated calls carry the access token; on
 * `SESSION_EXPIRED` or `UNAUTHENTICATED` the token pair is refreshed once (single flight) and the call retried once.
 * `SESSION_REVOKED`, or a refresh that fails with 401, clears the tokens and emits [AuthEvent.LoggedOut].
 */
class FamilyHubApi(
    private val client: HttpClient,
    private val serverStore: ServerStore,
    private val tokenStore: TokenStore,
    private val clock: Clock,
) {
    private val events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 16)
    private val refreshMutex = Mutex()

    val authEvents: SharedFlow<AuthEvent> = events.asSharedFlow()

    // Public endpoints.

    /** [serverUrl] overrides the stored server, for connecting to a new one. */
    suspend fun instance(serverUrl: String? = null): InstanceInfo = publicCall(HttpMethod.Get, "/api/v1/instance", Unit, serverUrl)

    suspend fun setup(request: SetupRequest): AuthResult = publicCall(HttpMethod.Post, "/api/v1/setup", request)

    suspend fun login(request: LoginRequest): AuthResult = publicCall(HttpMethod.Post, "/api/v1/auth/login", request)

    suspend fun loginWithCode(request: LoginWithCodeRequest): AuthResult = publicCall(HttpMethod.Post, "/api/v1/auth/login-code", request)

    suspend fun previewInvite(request: InvitePreviewRequest): InvitePreview =
        publicCall(HttpMethod.Post, "/api/v1/invites/preview", request)

    suspend fun acceptInvite(request: AcceptInviteRequest): AuthResult = publicCall(HttpMethod.Post, "/api/v1/invites/accept", request)

    // Own account and sessions.

    suspend fun logout() = authed<Unit, Unit>(HttpMethod.Post, "/api/v1/auth/logout", Unit)

    suspend fun me(): Me = authed(HttpMethod.Get, "/api/v1/me", Unit)

    suspend fun changePassword(request: ChangePasswordRequest) =
        authed<ChangePasswordRequest, Unit>(HttpMethod.Post, "/api/v1/me/password", request)

    suspend fun sessions(): List<SessionInfo> = authed(HttpMethod.Get, "/api/v1/me/sessions", Unit)

    suspend fun revokeSession(id: String) = authed<Unit, Unit>(HttpMethod.Delete, "/api/v1/me/sessions/${id.encoded()}", Unit)

    suspend fun deleteMe(request: DeleteAccountRequest) = authed<DeleteAccountRequest, Unit>(HttpMethod.Delete, "/api/v1/me", request)

    suspend fun createLoginCode(request: CreateLoginCodeRequest): LoginCodeCreated = authed(HttpMethod.Post, "/api/v1/login-codes", request)

    // Household administration (household.manage).

    suspend fun updateHousehold(request: UpdateHouseholdRequest) =
        authed<UpdateHouseholdRequest, Unit>(HttpMethod.Patch, "/api/v1/household", request)

    suspend fun createMember(request: CreateMemberRequest): MemberCreated = authed(HttpMethod.Post, "/api/v1/members", request)

    suspend fun removeMember(id: String) = authed<Unit, Unit>(HttpMethod.Delete, "/api/v1/members/${id.encoded()}", Unit)

    suspend fun resetMemberPassword(
        id: String,
        request: ResetPasswordRequest,
    ) = authed<ResetPasswordRequest, Unit>(HttpMethod.Post, "/api/v1/members/${id.encoded()}/password", request)

    suspend fun setMemberGroups(
        id: String,
        request: SetGroupsRequest,
    ) = authed<SetGroupsRequest, Unit>(HttpMethod.Put, "/api/v1/members/${id.encoded()}/groups", request)

    suspend fun createGroup(request: CreateGroupRequest): GroupCreated = authed(HttpMethod.Post, "/api/v1/groups", request)

    suspend fun updateGroup(
        id: String,
        request: UpdateGroupRequest,
    ) = authed<UpdateGroupRequest, Unit>(HttpMethod.Patch, "/api/v1/groups/${id.encoded()}", request)

    suspend fun deleteGroup(id: String) = authed<Unit, Unit>(HttpMethod.Delete, "/api/v1/groups/${id.encoded()}", Unit)

    suspend fun createInvite(request: CreateInviteRequest): InviteCreated = authed(HttpMethod.Post, "/api/v1/invites", request)

    suspend fun invites(): List<InviteInfo> = authed(HttpMethod.Get, "/api/v1/invites", Unit)

    suspend fun revokeInvite(id: String) = authed<Unit, Unit>(HttpMethod.Delete, "/api/v1/invites/${id.encoded()}", Unit)

    // Sync (architecture/sync.md).

    suspend fun pull(
        after: Long,
        limit: Int? = null,
        accessVersion: Long? = null,
        epoch: String? = null,
    ): PullResponse =
        authed(HttpMethod.Get, "/api/v1/sync/changes", Unit) {
            parameter("after", after)
            limit?.let { parameter("limit", it) }
            accessVersion?.let { parameter("accessVersion", it) }
            epoch?.let { parameter("epoch", it) }
        }

    suspend fun push(request: PushRequest): PushResponse = authed(HttpMethod.Post, "/api/v1/sync/mutations", request)

    /**
     * Realtime events over WebSocket. Completes when the server closes the socket; fails on network errors.
     * The access token is refreshed first if it expires soon, because a failed upgrade carries no error body.
     */
    fun events(): Flow<SyncEvent> =
        channelFlow {
            val token = freshAccessToken()
            val url = baseUrl().replaceFirst("http", "ws") + "/api/v1/sync/events"
            client.webSocket(urlString = url, request = {
                bearerAuth(token)
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            }) {
                for (frame in incoming) {
                    if (frame is Frame.Text) send(FamilyHubJson.decodeFromString(SyncEvent.serializer(), frame.readText()))
                }
            }
        }

    // Plumbing.

    private suspend fun baseUrl(override: String? = null): String =
        (override ?: serverStore.serverUrl() ?: throw IllegalStateException("No server configured")).trimEnd('/')

    private suspend inline fun <reified B, reified T> publicCall(
        method: HttpMethod,
        path: String,
        body: B,
        serverUrl: String? = null,
    ): T {
        val url = baseUrl(serverUrl) + path
        val response =
            client.request(url) {
                this.method = method
                jsonBody(body)
            }
        return response.successBody()
    }

    private suspend inline fun <reified B, reified T> authed(
        method: HttpMethod,
        path: String,
        body: B,
        noinline extra: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val url = baseUrl() + path
        val response =
            authedResponse { token ->
                client.request(url) {
                    this.method = method
                    bearerAuth(token)
                    jsonBody(body)
                    extra()
                }
            }
        return response.successBody()
    }

    private inline fun <reified B> HttpRequestBuilder.jsonBody(body: B) {
        if (body is Unit) return
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun authedResponse(send: suspend (accessToken: String) -> HttpResponse): HttpResponse {
        val tokens = tokenStore.load() ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Not logged in", httpStatus = 401)
        val first = send(tokens.accessToken)
        if (first.status != HttpStatusCode.Unauthorized) return first
        val error = first.apiError()
        when (error.code) {
            ErrorCode.SESSION_EXPIRED, ErrorCode.UNAUTHENTICATED -> {
                val fresh = refresh(tokens.accessToken)
                val second = send(fresh.accessToken)
                if (second.status == HttpStatusCode.Unauthorized) {
                    val again = second.apiError()
                    if (again.code == ErrorCode.SESSION_REVOKED) loggedOut()
                    throw again.toException(401)
                }
                return second
            }

            ErrorCode.SESSION_REVOKED -> {
                loggedOut()
                throw error.toException(401)
            }

            else -> {
                throw error.toException(401)
            }
        }
    }

    /**
     * Single-flight refresh. If another caller already replaced [failedAccessToken], its result is reused.
     * The new pair is saved before it is used, so a lost response stays recoverable (refresh grace window, auth.md).
     */
    private suspend fun refresh(failedAccessToken: String): Tokens =
        refreshMutex.withLock {
            val current = tokenStore.load() ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Not logged in", httpStatus = 401)
            if (current.accessToken != failedAccessToken) return@withLock current
            val response =
                client.request(baseUrl() + "/api/v1/auth/refresh") {
                    method = HttpMethod.Post
                    jsonBody(RefreshRequest(current.refreshToken))
                }
            if (response.status == HttpStatusCode.Unauthorized) {
                val error = response.apiError()
                loggedOut()
                throw error.toException(401)
            }
            val tokens = Tokens.from(response.successBody<AuthResult>())
            tokenStore.save(tokens)
            tokens
        }

    private suspend fun freshAccessToken(): String {
        val tokens = tokenStore.load() ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Not logged in", httpStatus = 401)
        if (tokens.accessExpiresAt > clock.now() + 1.minutes) return tokens.accessToken
        return refresh(tokens.accessToken).accessToken
    }

    private suspend fun loggedOut() {
        tokenStore.clear()
        events.emit(AuthEvent.LoggedOut)
    }

    private suspend inline fun <reified T> HttpResponse.successBody(): T {
        if (!status.isSuccess()) throw apiError().toException(status.value)
        @Suppress("UNCHECKED_CAST")
        return if (T::class == Unit::class) Unit as T else body()
    }

    private suspend fun HttpResponse.apiError(): ApiError =
        try {
            body<ApiErrorBody>().error
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            val code =
                when {
                    status == HttpStatusCode.Unauthorized -> ErrorCode.UNAUTHENTICATED
                    status.value >= 500 -> ErrorCode.INTERNAL
                    else -> ErrorCode.UNKNOWN
                }
            ApiError(code, "HTTP ${status.value}")
        }

    private fun ApiError.toException(httpStatus: Int) = ApiException(code, message, fields, httpStatus)

    private fun String.encoded(): String = encodeURLPathPart()
}
