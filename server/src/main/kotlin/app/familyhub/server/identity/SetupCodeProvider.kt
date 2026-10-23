package app.familyhub.server.identity

import app.familyhub.contract.Codes
import app.familyhub.server.identity.crypto.CodeGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest

private val log = KotlinLogging.logger {}

/**
 * The one-time code that protects first-run setup (FND-001, architecture/auth.md § Instance setup).
 * Taken from `FAMILYHUB_SETUP_CODE` or generated; kept only in memory and forgotten after a successful setup.
 */
class SetupCodeProvider(
    configured: String?,
    generator: CodeGenerator,
) {
    @Volatile
    private var code: String? = configured?.let(Codes::normalize) ?: generator.generate(CODE_LENGTH)

    /** Logs the code while the instance has no household; called on every start. */
    fun announce() {
        val current = code ?: return
        log.warn { "Setup code: ${Codes.format(current)} (enter it in the app to create your family)" }
    }

    fun matches(input: String): Boolean {
        val current = code ?: return false
        return MessageDigest.isEqual(Codes.normalize(input).toByteArray(), current.toByteArray())
    }

    fun forget() {
        code = null
    }

    companion object {
        const val CODE_LENGTH = 10
    }
}
