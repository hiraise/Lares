package app.familyhub.client.auth

import app.familyhub.client.api.ApiException
import app.familyhub.client.api.AuthEvent
import app.familyhub.client.api.FamilyHubApi
import app.familyhub.client.db.LocalDb
import app.familyhub.client.platform.ServerStore
import app.familyhub.client.sync.SyncEngine
import app.familyhub.contract.Codes
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.InstanceInfo
import app.familyhub.contract.api.Protocol
import app.familyhub.contract.api.admin.AcceptInviteRequest
import app.familyhub.contract.api.admin.InvitePreview
import app.familyhub.contract.api.admin.InvitePreviewRequest
import app.familyhub.contract.api.admin.LoginWithCodeRequest
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.api.auth.DeviceInfo
import app.familyhub.contract.api.auth.LoginRequest
import app.familyhub.contract.api.auth.Me
import app.familyhub.contract.api.auth.SetupRequest
import app.familyhub.contract.validation.Validators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionState {
    /** No server chosen yet: onboarding starts with the server address. */
    data object NoServer : SessionState

    /** A server is chosen but nobody is logged in. [info] is null when it could not be fetched (offline app start). */
    data class ServerKnown(
        val serverUrl: String,
        val info: InstanceInfo?,
    ) : SessionState

    data class LoggedIn(
        val serverUrl: String,
        val me: Me,
    ) : SessionState
}

/**
 * The session lifecycle: connect to a server, set up / log in / join, log out. A successful login stores the tokens
 * and the member, then starts the sync engine. Logout and server-side revocation stop the engine and wipe local data.
 */
class SessionManager(
    private val api: FamilyHubApi,
    private val tokenStore: TokenStore,
    private val serverStore: ServerStore,
    private val localDb: LocalDb,
    private val syncEngine: SyncEngine,
    private val device: DeviceInfo,
    private val appScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.NoServer)

    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    init {
        appScope.launch {
            api.authEvents.collect { event ->
                if (event == AuthEvent.LoggedOut) endSession()
            }
        }
    }

    /** Restores the state saved on this device (app start); works offline. */
    suspend fun restore(): SessionState =
        mutex.withLock {
            val url = serverStore.serverUrl()
            val tokens = tokenStore.load()
            val me = localDb.read { LocalSession.me(this) }
            mutableState.value =
                when {
                    url == null -> SessionState.NoServer
                    tokens != null && me != null -> SessionState.LoggedIn(url, me).also { syncEngine.start(appScope) }
                    else -> SessionState.ServerKnown(url, null)
                }
            mutableState.value
        }

    /** Checks the server and remembers it. Fails with `PROTOCOL_UNSUPPORTED` if this app cannot talk to it. */
    suspend fun connect(serverUrl: String): InstanceInfo {
        val url = normalizeServerUrl(serverUrl)
        val info = api.instance(url)
        if (Protocol.VERSION !in info.protocol.min..info.protocol.max) {
            throw ApiException(ErrorCode.PROTOCOL_UNSUPPORTED, "Server supports protocol ${info.protocol.min}..${info.protocol.max}")
        }
        mutex.withLock {
            serverStore.setServerUrl(url)
            mutableState.value = SessionState.ServerKnown(url, info)
        }
        return info
    }

    /** First-run setup of a new server (FND-001). */
    suspend fun setup(
        setupCode: String,
        householdName: String,
        timeZone: String,
        currency: String,
        defaultLanguage: String,
        displayName: String,
        login: String,
        password: String,
    ): Me =
        startSession(
            api.setup(
                SetupRequest(
                    setupCode = Codes.normalize(setupCode),
                    householdName = householdName,
                    timeZone = timeZone,
                    currency = currency,
                    defaultLanguage = defaultLanguage,
                    displayName = displayName,
                    login = Validators.normalizeLogin(login),
                    password = password,
                    device = device,
                ),
            ),
        )

    suspend fun login(
        login: String,
        password: String,
    ): Me = startSession(api.login(LoginRequest(Validators.normalizeLogin(login), password, device)))

    /** QR login on a new device (FND-014). */
    suspend fun loginWithCode(code: String): Me = startSession(api.loginWithCode(LoginWithCodeRequest(Codes.normalize(code), device)))

    suspend fun previewInvite(code: String): InvitePreview = api.previewInvite(InvitePreviewRequest(Codes.normalize(code)))

    /** Joins a household by invite (FND-024). */
    suspend fun acceptInvite(
        code: String,
        displayName: String,
        login: String,
        password: String,
    ): Me =
        startSession(
            api.acceptInvite(AcceptInviteRequest(Codes.normalize(code), displayName, Validators.normalizeLogin(login), password, device)),
        )

    /** Ends the session on the server if reachable, then wipes local data either way. */
    suspend fun logout() {
        try {
            api.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or already revoked: the local session ends anyway.
        }
        endSession()
    }

    private suspend fun startSession(result: AuthResult): Me =
        mutex.withLock {
            val url = serverStore.serverUrl() ?: throw IllegalStateException("No server configured")
            syncEngine.stop()
            // Data of a previous session never mixes with the new one.
            localDb.clearAll()
            tokenStore.save(Tokens.from(result))
            localDb.transaction { LocalSession.save(this, result.me) }
            mutableState.value = SessionState.LoggedIn(url, result.me)
            syncEngine.start(appScope)
            result.me
        }

    private suspend fun endSession() =
        mutex.withLock {
            syncEngine.stop()
            tokenStore.clear()
            localDb.clearAll()
            val current = mutableState.value
            if (current is SessionState.LoggedIn) mutableState.value = SessionState.ServerKnown(current.serverUrl, null)
        }

    companion object {
        /** Trims spaces and trailing slashes; adds `https://` when no scheme is given. */
        fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            val lower = trimmed.lowercase()
            return if (lower.startsWith("http://") || lower.startsWith("https://")) trimmed else "https://$trimmed"
        }
    }
}
