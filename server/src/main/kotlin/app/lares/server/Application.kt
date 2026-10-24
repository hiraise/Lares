package app.lares.server

import app.lares.server.calendar.EventOccurrenceSyncHandler
import app.lares.server.calendar.EventReminderSyncHandler
import app.lares.server.calendar.EventSyncHandler
import app.lares.server.household.GroupAdminService
import app.lares.server.household.GroupSyncHandler
import app.lares.server.household.HouseholdAdminService
import app.lares.server.household.HouseholdSyncHandler
import app.lares.server.household.PermissionService
import app.lares.server.household.PersonSyncHandler
import app.lares.server.household.householdRoutes
import app.lares.server.household.installPermissionChecks
import app.lares.server.identity.AccountService
import app.lares.server.identity.AuthService
import app.lares.server.identity.FailedAttemptLimiter
import app.lares.server.identity.InviteService
import app.lares.server.identity.LoginCodeService
import app.lares.server.identity.MeQuery
import app.lares.server.identity.MemberAdminService
import app.lares.server.identity.MemberSyncHandler
import app.lares.server.identity.SetupCodeProvider
import app.lares.server.identity.SetupService
import app.lares.server.identity.crypto.CodeGenerator
import app.lares.server.identity.crypto.PasswordHasher
import app.lares.server.identity.crypto.TokenGenerator
import app.lares.server.identity.identityRoutes
import app.lares.server.identity.memberAdminRoutes
import app.lares.server.notifications.NotificationService
import app.lares.server.notifications.PushEndpointGuard
import app.lares.server.notifications.SyncTicklePublisher
import app.lares.server.notifications.TickleThrottle
import app.lares.server.notifications.WebPushSender
import app.lares.server.notifications.notificationRoutes
import app.lares.server.platform.AppConfig
import app.lares.server.platform.BuildInfo
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.http.healthRoutes
import app.lares.server.platform.http.installHttpPlumbing
import app.lares.server.platform.http.instanceRoute
import app.lares.server.platform.http.protocolChecked
import app.lares.server.platform.sync.HouseholdChangeBus
import app.lares.server.shopping.ShoppingItemSyncHandler
import app.lares.server.shopping.ShoppingListSyncHandler
import app.lares.server.sync.SyncEventsService
import app.lares.server.sync.SyncHandlers
import app.lares.server.sync.SyncHousekeeping
import app.lares.server.sync.SyncPullService
import app.lares.server.sync.SyncPushService
import app.lares.server.sync.syncRoutes
import app.lares.server.tags.TagSyncHandler
import app.lares.server.tasks.ChecklistItemSyncHandler
import app.lares.server.tasks.TaskCompletionSyncHandler
import app.lares.server.tasks.TaskNotifier
import app.lares.server.tasks.TaskOverdueJob
import app.lares.server.tasks.TaskSyncHandler
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
    /** Sync housekeeping and the overdue job; tests switch them off and call the job functions directly. */
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

    val pushEndpointGuard = PushEndpointGuard(config.pushAllowedHosts)
    val webPushSender = WebPushSender(db, pushEndpointGuard, WebPushSender.subjectFor(config.publicUrl))
    val notificationService = NotificationService(db, clock, pushEndpointGuard, webPushSender)
    val taskNotifier = TaskNotifier(db, permissionService, notificationService)
    val taskOverdueJob = TaskOverdueJob(db, clock, taskNotifier)

    val syncHandlers =
        SyncHandlers(
            listOf(
                HouseholdSyncHandler(),
                MemberSyncHandler(),
                GroupSyncHandler(),
                TagSyncHandler(),
                PersonSyncHandler(),
                TaskSyncHandler(taskNotifier),
                ChecklistItemSyncHandler(),
                TaskCompletionSyncHandler(taskNotifier),
                ShoppingListSyncHandler(),
                ShoppingItemSyncHandler(),
                EventSyncHandler(),
                EventOccurrenceSyncHandler(),
                EventReminderSyncHandler(),
            ),
        )
    val syncPullService = SyncPullService(db, syncHandlers, permissionService)
    val syncPushService = SyncPushService(db, clock, syncHandlers, permissionService)
    val syncEventsService = SyncEventsService(db, changeBus)
    val syncHousekeeping = SyncHousekeeping(db, clock)

    val syncTickles =
        SyncTicklePublisher(changeBus, clock, TickleThrottle(), send = { householdId, seq -> notificationService.tickle(householdId, seq) })
}

fun main() {
    val config = AppConfig.fromEnv()
    // Must happen before the first logger is created.
    if (config.logFormat == AppConfig.LogFormat.JSON) {
        System.setProperty("logback.configurationFile", "logback-json.xml")
    }
    val log = KotlinLogging.logger("app.lares.server.Main")
    val buildInfo = BuildInfo.load()
    log.info { "Starting Lares server ${buildInfo.version} on port ${config.port}" }

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
                notificationRoutes(deps.authService, deps.notificationService)
            }
        }
    }
    // Tickles only send to registered push endpoints, so they also run in tests.
    deps.syncTickles.start(this)
    // Task events come from requests and jobs alike; tests await them with `taskNotifier.awaitIdle()`.
    deps.taskNotifier.start(this)
    if (deps.backgroundJobs) {
        deps.syncHousekeeping.start(this)
        deps.taskOverdueJob.start(this)
    }
}
