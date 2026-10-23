package app.lares.client.platform

import app.lares.client.auth.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Restores the saved session once per process (works offline); the UI waits for [ready] before choosing a graph. */
@OptIn(ExperimentalAtomicApi::class)
class AppStartup(
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val mutableReady = MutableStateFlow(false)

    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        scope.launch {
            try {
                sessionManager.restore()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable saved state: start as a fresh installation rather than not at all.
            } finally {
                mutableReady.value = true
            }
        }
    }
}
