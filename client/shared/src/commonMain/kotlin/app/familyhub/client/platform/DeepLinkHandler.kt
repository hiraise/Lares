package app.familyhub.client.platform

import app.familyhub.contract.DeepLink
import app.familyhub.contract.DeepLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * The latest `familyhub://invite` or `familyhub://login` link that the app was opened with or that was scanned.
 * The platform submits links; the UI consumes them (onboarding connects and continues, a logged-in app explains).
 */
class DeepLinkHandler {
    private val pending = MutableStateFlow<DeepLink?>(null)

    val link: StateFlow<DeepLink?> = pending.asStateFlow()

    /** Returns false if [uri] is not a FamilyHub link (a QR code may contain anything). */
    fun submit(uri: String): Boolean {
        val parsed = DeepLinks.parse(uri) ?: return false
        pending.value = parsed
        return true
    }

    /** Takes the pending link so it is handled once. */
    fun consume(): DeepLink? = pending.getAndUpdate { null }
}
