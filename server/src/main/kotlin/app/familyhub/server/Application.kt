package app.familyhub.server

import app.familyhub.server.household.PermissionService
import app.familyhub.server.identity.AccountService
import app.familyhub.server.identity.AuthService
import app.familyhub.server.identity.FailedAttemptLimiter
import app.familyhub.server.identity.MeQuery
import app.familyhub.server.identity.SetupCodeProvider
import app.familyhub.server.identity.SetupService
import app.familyhub.server.identity.crypto.CodeGenerator
import app.familyhub.server.identity.crypto.PasswordHasher
import app.familyhub.server.identity.crypto.TokenGenerator
import app.familyhub.server.identity.identityRoutes
import app.familyhub.server.platform.AppConfig
import app.familyhub.server.platform.BuildInfo
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.http.healthRoutes
import app.familyhub.server.platform.http.installHttpPlumbing
import app.familyhub.server.platform.http.instanceRoute
import app.familyhub.server.platform.http.protocolChecked
import app.familyhub.server.platform.sync.HouseholdChangeBus
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock

/** Everything the application needs, wired by hand (no DI framework on the server). */
class AppDeps(
    val config: AppConfig,
    val db: AppDatabase,
    val changeBus: HouseholdChangeBus,
    val buildInfo: BuildInfo,
    val clock: Clock = Clock.System,
    val passwordHasher: PasswordHasher = PasswordHasher(),
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
    routing {
        healthRoutes(deps.db)
        route("/api/v1") {
            instanceRoute(deps.db, deps.buildInfo)
            protocolChecked {
                identityRoutes(deps.setupService, deps.authService, deps.accountService)
            }
        }
    }
}
