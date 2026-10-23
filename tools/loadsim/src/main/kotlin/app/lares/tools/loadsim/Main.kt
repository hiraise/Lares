package app.lares.tools.loadsim

import app.lares.contract.api.InstanceInfo
import app.lares.contract.api.LaresJson
import app.lares.contract.api.admin.CreateInviteRequest
import app.lares.contract.api.admin.CreateLoginCodeRequest
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.admin.InviteCreated
import app.lares.contract.api.admin.LoginCodeCreated
import app.lares.contract.api.admin.LoginWithCodeRequest
import app.lares.contract.api.admin.MemberCreated
import app.lares.contract.api.admin.UpdateGroupRequest
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.auth.DeviceInfo
import app.lares.contract.api.auth.LoginRequest
import app.lares.contract.api.auth.SetupRequest
import app.lares.contract.entities.GroupData
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.PullResponse
import io.ktor.http.HttpMethod
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

fun main(args: Array<String>) {
    if ("--help" in args || "-h" in args) {
        println(Config.USAGE)
        return
    }
    val config =
        try {
            Config.parse(args.toList())
        } catch (e: IllegalArgumentException) {
            System.err.println("loadsim: ${e.message}\n\n${Config.USAGE}")
            exitProcess(2)
        }
    exitProcess(runBlocking { run(config) })
}

/** Runs the whole simulation. Exit code: 0 passed, 1 too many errors, 2 setup failed. */
suspend fun run(config: Config): Int =
    coroutineScope {
        val recorder = Recorder()
        var phaseStart = System.currentTimeMillis()
        val runId = (1..4).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        log("run $runId: ${config.devices} devices (${config.members} members) against ${config.server}")
        val family =
            try {
                setUp(config, recorder, runId)
            } catch (e: Exception) {
                System.err.println("loadsim: setup failed: ${e.message}")
                return@coroutineScope 2
            }

        val spans = mutableListOf<PhaseSpan>()

        fun enter(next: Phase) {
            val now = System.currentTimeMillis()
            spans += PhaseSpan(recorder.phase, phaseStart, now)
            recorder.phase = next
            phaseStart = now
            log("phase ${next.name.lowercase()}")
        }

        val load =
            launch {
                family.devices.forEach { it.launchIn(this) }
                launch { family.admin.run() }
                launch { refreshLoop(family.admin.api, config.refreshInterval, Random(runId.hashCode()), recorder) }
                launch { progress(recorder) }
            }

        enter(Phase.WARMUP)
        delay(config.warmup)
        enter(Phase.STEADY)
        val burstAt = config.effectiveBurstAt
        if (burstAt == null) {
            delay(config.duration)
        } else {
            delay(burstAt)
            enter(Phase.BURST)
            delay(config.burst)
            enter(Phase.STEADY)
            delay(config.duration - burstAt - config.burst)
        }
        spans += PhaseSpan(recorder.phase, phaseStart, System.currentTimeMillis())
        recorder.phase = Phase.SETUP // Requests cut off by the shutdown do not count.
        load.cancelAndJoin()
        family.devices.forEach { it.api.close() }
        family.admin.api.close()

        val report = Report(config, recorder, spans)
        report.write(config.out)
        print(report.text())
        log("results in ${config.out.toAbsolutePath()}")
        if (report.passed) 0 else 1
    }

private class Family(
    val admin: AdminDevice,
    val devices: List<Device>,
)

/**
 * Creates (or logs into) the household through the public API, adds members to the group that may manage tags and
 * persons ("Adults"), and signs in two devices per member with login codes.
 */
private suspend fun setUp(
    config: Config,
    recorder: Recorder,
    runId: String,
): Family {
    val admin = DeviceApi(config.server, config.insecure, recorder)
    val instance = admin.get<InstanceInfo>("GET /instance", "/instance", authenticated = false)
    val device = DeviceInfo("loadsim $runId admin", "loadsim")
    admin.auth =
        if (!instance.initialized) {
            val code = requireNotNull(config.setupCode) { "server is not set up yet: pass --setup-code" }
            log("setting up a new household")
            admin.call<SetupRequest, AuthResult>(
                "POST /setup",
                HttpMethod.Post,
                "/setup",
                SetupRequest(code, "Load test", "Europe/Berlin", "EUR", "en", "Admin", "loadadmin", "loadsim-$runId-password", device),
                authenticated = false,
            )
        } else {
            val login = requireNotNull(config.adminLogin) { "server is already set up: pass --admin-login and --admin-password" }
            log("logging into the existing household as $login")
            admin.call<LoginRequest, AuthResult>(
                "POST /auth/login",
                HttpMethod.Post,
                "/auth/login",
                LoginRequest(login, requireNotNull(config.adminPassword), device),
                authenticated = false,
            )
        }

    val groups = loadGroups(admin)
    val adults =
        groups.entries.firstOrNull { (_, group) ->
            group.builtIn == null && Permission.TAGS_MANAGE in group.permissions && Permission.PERSONS_MANAGE in group.permissions
        } ?: error("no group may manage tags and persons (expected the \"Adults\" template)")
    val renamed = groups.entries.firstOrNull { (id, group) -> group.builtIn == null && id != adults.key } ?: adults

    val adminMember = checkNotNull(admin.auth).me.memberId
    val members =
        listOf(adminMember) +
            (1 until config.members).map { n ->
                admin
                    .call<CreateMemberRequest, MemberCreated>(
                        "POST /members",
                        HttpMethod.Post,
                        "/members",
                        CreateMemberRequest(displayName = "Load $runId member $n", groupIds = setOf(adults.key)),
                    ).memberId
            }
    log("created ${members.size - 1} members; signing in ${config.devices} devices")

    val devices =
        (0 until config.devices).map { index ->
            val code =
                admin.call<CreateLoginCodeRequest, LoginCodeCreated>(
                    "POST /login-codes",
                    HttpMethod.Post,
                    "/login-codes",
                    CreateLoginCodeRequest(members[index / 2]),
                )
            val api = DeviceApi(config.server, config.insecure, recorder)
            api.auth =
                api.call<LoginWithCodeRequest, AuthResult>(
                    "POST /auth/login-code",
                    HttpMethod.Post,
                    "/auth/login-code",
                    LoginWithCodeRequest(code.code, DeviceInfo("loadsim $runId device $index", "loadsim")),
                    authenticated = false,
                )
            Device(index, api, config, recorder, runId).also { it.pull() }
        }
    return Family(AdminDevice(admin, config, recorder, adults.key, renamed.key, renamed.value.name), devices)
}

/** All live groups of the household, read with a full pull. */
private suspend fun loadGroups(admin: DeviceApi): Map<String, GroupData> {
    val groups = mutableMapOf<String, GroupData>()
    var cursor = 0L
    do {
        val page = admin.get<PullResponse>("GET /sync/changes", "/sync/changes?after=$cursor&limit=1000")
        for (change in page.changes) {
            if (change.type == EntityTypes.GROUP && !change.deleted && change.data != null) {
                groups[change.id] = LaresJson.decodeFromJsonElement(GroupData.serializer(), change.data!!)
            }
        }
        cursor = page.cursor
    } while (page.hasMore)
    return groups
}

/** The administrator's device: every admin interval one REST call, in turn rename a group, create and revoke an invite. */
private class AdminDevice(
    val api: DeviceApi,
    private val config: Config,
    private val recorder: Recorder,
    private val inviteGroupId: String,
    private val renameGroupId: String,
    private val originalName: String,
) {
    private var step = 0
    private var renamed = false
    private var inviteId: String? = null

    suspend fun run() {
        while (true) {
            delay(config.adminInterval)
            guarded(recorder, "admin") {
                when (step++ % 3) {
                    0 -> {
                        val name = if (renamed) originalName else "${originalName.take(36)} LT"
                        api.send("PATCH /groups/{id}", HttpMethod.Patch, "/groups/$renameGroupId", UpdateGroupRequest(name = name))
                        renamed = !renamed
                    }

                    1 -> {
                        inviteId =
                            api
                                .call<CreateInviteRequest, InviteCreated>(
                                    "POST /invites",
                                    HttpMethod.Post,
                                    "/invites",
                                    CreateInviteRequest(setOf(inviteGroupId)),
                                ).id
                    }

                    else -> {
                        inviteId?.let { api.exchange("DELETE /invites/{id}", HttpMethod.Delete, "/invites/$it", null, true) }
                        inviteId = null
                    }
                }
            }
        }
    }
}

/** One line per minute so a long run shows it is alive. */
private suspend fun progress(recorder: Recorder) {
    while (true) {
        delay(1.minutes)
        val samples = recorder.samples().filter { it.phase != Phase.SETUP }
        log(
            "${recorder.phase.name.lowercase()}: ${samples.size} requests, ${samples.count { it.failed }} failed, " +
                "${recorder.mutationsSent.get()} mutations, ${recorder.eventsReceived.get()} events",
        )
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun log(message: String) = println("${LocalTime.now().format(timeFormat)} loadsim: $message")
