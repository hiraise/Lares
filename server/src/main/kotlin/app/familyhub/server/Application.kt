package app.familyhub.server

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

/** Everything the application needs, wired by hand (no DI framework on the server). */
class AppDeps(
    val config: AppConfig,
    val db: AppDatabase,
    val changeBus: HouseholdChangeBus,
    val buildInfo: BuildInfo,
)

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

    embeddedServer(Netty, port = config.port) { module(deps) }.start(wait = true)
}

fun Application.module(deps: AppDeps) {
    installHttpPlumbing()
    routing {
        healthRoutes(deps.db)
        route("/api/v1") {
            instanceRoute(deps.db, deps.buildInfo)
            protocolChecked {
                // Feature routes (M0-03+) go here.
            }
        }
    }
}
