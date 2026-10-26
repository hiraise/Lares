package app.lares.tools.loadsim

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Command-line options of loadsim. Defaults are the "family" scenario of plan M0-11. */
data class Config(
    val server: String = "https://localhost",
    /** Setup code for a fresh server; the household is created by first-run setup. */
    val setupCode: String? = null,
    /** Reuse an existing household instead: login of one of its administrators. */
    val adminLogin: String? = null,
    val adminPassword: String? = null,
    val devices: Int = 10,
    /** Measured time after the warm-up. */
    val duration: Duration = 30.minutes,
    val warmup: Duration = 2.minutes,
    /** Length of the burst phase; zero disables it. */
    val burst: Duration = 60.seconds,
    /** Start of the burst, counted from the end of the warm-up. Moved to the middle of the run if it does not fit. */
    val burstAt: Duration = 5.minutes,
    val pushInterval: Duration = 60.seconds,
    val burstInterval: Duration = 2.seconds,
    val refreshInterval: Duration = 10.minutes,
    val adminInterval: Duration = 5.minutes,
    val out: Path = Path("build/loadsim"),
    /** Trust any TLS certificate (Caddy's local CA for https://localhost). */
    val insecure: Boolean = false,
    /** The run fails above this share of failed requests or rejected mutations. */
    val maxErrorRate: Double = 0.01,
    /** Phones sharing location (plan M4-05): the first device of the first N members; null = every member. */
    val sharingDevices: Int? = null,
    /** Pause between position uploads of a sharing phone (one fix per upload). */
    val positionInterval: Duration = 30.seconds,
    /** Devices holding the live map channel, taken from the second devices of the members; null = up to 3. */
    val mapViewersOption: Int? = null,
    /** Family places on the simulated route. */
    val places: Int = 2,
) {
    /** Members of the simulated family: two devices each. */
    val members: Int get() = (devices + 1) / 2

    /** Number of sharing phones: one per member at most (two sharing phones of one member would take turns, LOC-019). */
    val sharing: Int get() = sharingDevices ?: members

    /** Indexes of the sharing devices: the first device of each of the first [sharing] members. */
    val sharingIndexes: List<Int> get() = (0 until sharing).map { it * 2 }

    /** Number of map viewers: 3 by default, fewer when the family has fewer second devices. */
    val mapViewers: Int get() = mapViewersOption ?: minOf(DEFAULT_MAP_VIEWERS, devices / 2)

    /** Indexes of the map viewers: the second devices of the first [mapViewers] members. */
    val viewerIndexes: List<Int> get() = (0 until mapViewers).map { it * 2 + 1 }

    /** Offset of the burst from the end of the warm-up, or null without a burst. */
    val effectiveBurstAt: Duration?
        get() =
            when {
                burst <= Duration.ZERO || burst > duration -> null
                burstAt + burst <= duration -> burstAt
                else -> (duration - burst) / 2
            }

    companion object {
        const val MAX_PLACES = 8
        const val DEFAULT_MAP_VIEWERS = 3

        val USAGE =
            """
            Usage: loadsim [options]
              --server URL            server base URL (default https://localhost)
              --setup-code CODE       set up a fresh server with this code
              --admin-login LOGIN     reuse an existing household: administrator login
              --admin-password PASS   ... and password
              --devices N             simulated devices, two per member (default 10)
              --duration D            measured time after the warm-up (default 30m)
              --warmup D              warm-up excluded from statistics (default 2m)
              --burst D               burst length, 0 disables it (default 60s)
              --burst-at D            burst start after the warm-up (default 5m)
              --push-interval D       pause between pushes of a device, ±20 % (default 60s)
              --burst-interval D      pause between pushes during the burst (default 2s)
              --refresh-interval D    token refresh period (default 10m)
              --admin-interval D      admin REST call period (default 5m)
              --out DIR               output directory (default build/loadsim)
              --insecure              trust any TLS certificate (local Caddy CA)
              --max-error-rate R      fail above this share of errors (default 0.01)
              --sharing-devices N     phones sharing location, one per member (default: every member)
              --position-interval D   pause between position uploads of a sharing phone (default 30s)
              --map-viewers N         devices holding the live map channel, second devices (default 3)
              --places N              family places on the route, 0..$MAX_PLACES (default 2)
            Durations: 90s, 5m, 1h, 1h30m, 0.
            """.trimIndent()

        fun parse(args: List<String>): Config {
            var config = Config()
            val iterator = args.iterator()
            while (iterator.hasNext()) {
                val flag = iterator.next()

                fun value(): String {
                    require(iterator.hasNext()) { "$flag needs a value" }
                    return iterator.next()
                }
                config =
                    when (flag) {
                        "--server" -> {
                            config.copy(server = value().trimEnd('/'))
                        }

                        "--setup-code" -> {
                            config.copy(setupCode = value())
                        }

                        "--admin-login" -> {
                            config.copy(adminLogin = value())
                        }

                        "--admin-password" -> {
                            config.copy(adminPassword = value())
                        }

                        "--devices" -> {
                            config.copy(devices = positiveInt(flag, value()))
                        }

                        "--duration" -> {
                            config.copy(duration = duration(flag, value()))
                        }

                        "--warmup" -> {
                            config.copy(warmup = duration(flag, value()))
                        }

                        "--burst" -> {
                            config.copy(burst = duration(flag, value()))
                        }

                        "--burst-at" -> {
                            config.copy(burstAt = duration(flag, value()))
                        }

                        "--push-interval" -> {
                            config.copy(pushInterval = duration(flag, value()))
                        }

                        "--burst-interval" -> {
                            config.copy(burstInterval = duration(flag, value()))
                        }

                        "--refresh-interval" -> {
                            config.copy(refreshInterval = duration(flag, value()))
                        }

                        "--admin-interval" -> {
                            config.copy(adminInterval = duration(flag, value()))
                        }

                        "--out" -> {
                            config.copy(out = Path(value()))
                        }

                        "--insecure" -> {
                            config.copy(insecure = true)
                        }

                        "--max-error-rate" -> {
                            config.copy(
                                maxErrorRate =
                                    value().toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
                                        ?: throw IllegalArgumentException("$flag must be a number in 0..1"),
                            )
                        }

                        "--sharing-devices" -> {
                            config.copy(sharingDevices = nonNegativeInt(flag, value()))
                        }

                        "--position-interval" -> {
                            config.copy(positionInterval = duration(flag, value()))
                        }

                        "--map-viewers" -> {
                            config.copy(mapViewersOption = nonNegativeInt(flag, value()))
                        }

                        "--places" -> {
                            config.copy(places = nonNegativeInt(flag, value()))
                        }

                        else -> {
                            throw IllegalArgumentException("Unknown option $flag")
                        }
                    }
            }
            require(config.duration > Duration.ZERO) { "--duration must be positive" }
            for (
            (name, interval) in
            listOf(
                "--push-interval" to config.pushInterval,
                "--burst-interval" to config.burstInterval,
                "--refresh-interval" to config.refreshInterval,
                "--admin-interval" to config.adminInterval,
                "--position-interval" to config.positionInterval,
            )
            ) {
                require(interval > Duration.ZERO) { "$name must be positive" }
            }
            require((config.adminLogin == null) == (config.adminPassword == null)) {
                "--admin-login and --admin-password go together"
            }
            require(config.sharing <= config.members) { "--sharing-devices must be at most ${config.members} (one per member)" }
            require(config.mapViewers <= config.devices / 2) {
                "--map-viewers must be at most ${config.devices / 2} (second devices of the members)"
            }
            require(config.places <= MAX_PLACES) { "--places must be at most $MAX_PLACES" }
            return config
        }

        /** Parses `90s`, `5m`, `1h30m`, `0` (and anything [Duration.parse] accepts). */
        fun duration(
            flag: String,
            text: String,
        ): Duration =
            if (text.trim() == "0") {
                Duration.ZERO
            } else {
                Duration.parseOrNull(text.trim())?.takeIf { !it.isNegative() }
                    ?: throw IllegalArgumentException("$flag: '$text' is not a duration like 90s, 5m or 1h")
            }

        private fun nonNegativeInt(
            flag: String,
            text: String,
        ): Int = text.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("$flag must be a number >= 0")

        private fun positiveInt(
            flag: String,
            text: String,
        ): Int = text.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$flag must be a positive number")
    }
}
