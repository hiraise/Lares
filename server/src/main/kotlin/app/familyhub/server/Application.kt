package app.familyhub.server

import app.familyhub.server.household.GroupAdminService
import app.familyhub.server.household.GroupSyncHandler
import app.familyhub.server.household.HouseholdAdminService
import app.familyhub.server.household.HouseholdSyncHandler
import app.familyhub.server.household.PermissionService
import app.familyhub.server.household.PersonSyncHandler
import app.familyhub.server.household.householdRoutes
import app.familyhub.server.household.installPermissionChecks
import app.familyhub.server.identity.AccountService
import app.familyhub.server.identity.AuthService
import app.familyhub.server.identity.FailedAttemptLimiter
import app.familyhub.server.identity.InviteService
import app.familyhub.server.identity.LoginCodeService
import app.familyhub.server.identity.MeQuery
import app.familyhub.server.identity.MemberAdminService
import app.familyhub.server.identity.MemberSyncHandler
import app.familyhub.server.identity.SetupCodeProvider
import app.familyhub.server.identity.SetupService
import app.familyhub.server.identity.crypto.CodeGenerator
import app.familyhub.server.identity.crypto.PasswordHasher
import app.familyhub.server.identity.crypto.TokenGenerator
import app.familyhub.server.identity.identityRoutes
import app.familyhub.server.identity.memberAdminRoutes
import app.familyhub.server.platform.AppConfig
import app.familyhub.server.platform.BuildInfo
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.http.healthRoutes
import app.familyhub.server.platform.http.installHttpPlumbing
import app.familyhub.server.platform.http.instanceRoute
import app.familyhub.server.platform.http.protocolChecked
import app.familyhub.server.platform.sync.HouseholdChangeBus
import app.familyhub.server.sync.SyncEventsService
import app.familyhub.server.sync.SyncHandlers
import app.familyhub.server.sync.SyncHousekeeping
import app.familyhub.server.sync.SyncPullService
import app.familyhub.server.sync.SyncPushService
import app.familyhub.server.sync.syncRoutes
import app.familyhub.server.tags.TagSyncHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Everything the application needs, wired by hand (no DI framework on the server). */
class AppDeps(
    val config: AppConfig,
    val db: AppDatabase,
    val changeBus: HouseholdChangeBus,
    val buildInfo: BuildInfo,
    val clock: Clock = Clock.System,
    val passwordHasher: PasswordHasher = PasswordHasher(),
    /** Daily sync housekeeping; tests switch it off and call the job functions directly. */
    val backgroundJobs: Boolean = true,
) {
    val tokenGenerator = TokenGenerator()
    val codeGenerator = CodeGenerator()
    val loginLimiter = FailedAttemptLimiter.forLogin(clock)
    val setupLimiter = FailedAttemptLimiter.forSetup(clock)
    val setupCode = SetupCodeProvider(config.setupCode, codeGenerator)
    val permissionService = PermissionService(db)
    val meQuery = MeQuery(permissionService)
    val authService = AuthService(db, clock, passwordHasher, tokenGenerator, loginLimiter, meQuery)
    val setupService = SetupService(db, clock, passwordHasher, setupLimiter, setupCode, authService)
    val accountService = AccountService(db, clock, passwordHasher, loginLimiter, meQuery)
    val householdAdminService = HouseholdAdminService(db, clock)
    val groupAdminService = GroupAdminService(db, clock)
    val memberAdminService = MemberAdminService(db, clock, passwordHasher)

    /** Wrong invite and login codes count per IP like failed logins (10 per 15 minutes); keys have their own prefixes. */
    val inviteService = InviteService(db, clock, passwordHasher, codeGenerator, authService, loginLimiter, config.publicUrl)
    val loginCodeService = LoginCodeService(db, clock, codeGenerator, authService, permissionService, loginLimiter, config.publicUrl)

    val syncHandlers =
        SyncHandlers(
            listOf(HouseholdSyncHandler(), MemberSyncHandler(), GroupSyncHandler(), TagSyncHandler(), PersonSyncHandler()),
        )
    val syncPullService = SyncPullService(db, syncHandlers, permissionService)
    val syncPushService = SyncPushService(db, clock, syncHandlers, permissionService)
    val syncEventsService = SyncEventsService(db, changeBus)
    val syncHousekeeping = SyncHousekeeping(db, clock)
}

fun main() {
    val config = AppConfig.fromEnv()
    // Must happen before the first logger is created.
    if (config.logFormat == AppConfig.LogFormat.JSON) {
        System.setProperty("logback.configurationFile", "logback-json.xml")
    }
    val log = KotlinLogging.logger("app.familyhub.server.Main")
    val buildInfo = BuildInfo.load()
    log.info { "Starting FamilyHub server ${buildInfo.version} on port ${config.port}" }

    val dataSource = AppDatabase.createDataSource(config.db)
    AppDatabase.migrate(dataSource)
    val changeBus = HouseholdChangeBus()
    val deps =
        AppDeps(
            config = config,
            db = AppDatabase(AppDatabase.connect(dataSource), changeBus),
            changeBus = changeBus,
            buildInfo = buildInfo,
        )

    runBlocking { deps.setupService.announceSetupCodeIfNeeded() }

    embeddedServer(Netty, port = config.port) { module(deps) }.start(wait = true)
}

fun Application.module(deps: AppDeps) {
    installHttpPlumbing()
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }
    installPermissionChecks(deps.permissionService)
    routing {
        healthRoutes(deps.db)
        route("/api/v1") {
            instanceRoute(deps.db, deps.buildInfo)
            protocolChecked {
                identityRoutes(deps.setupService, deps.authService, deps.accountService)
                memberAdminRoutes(deps.authService, deps.memberAdminService, deps.inviteService, deps.loginCodeService)
                householdRoutes(deps.authService, deps.householdAdminService, deps.groupAdminService)
                syncRoutes(deps.authService, deps.syncPullService, deps.syncPushService, deps.syncEventsService)
            }
        }
    }
    if (deps.backgroundJobs) deps.syncHousekeeping.start(this)
}
