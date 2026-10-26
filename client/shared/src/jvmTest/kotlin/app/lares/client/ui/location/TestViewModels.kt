package app.lares.client.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the view models a test creates and clears them in `@AfterTest` before `Dispatchers.resetMain()`, waiting until
 * their coroutines have finished: otherwise a cancelled coroutine (or a `WhileSubscribed` timeout) completes later on
 * the reset Main dispatcher and fails whichever test runs next.
 */
class TestViewModels {
    private val store = ViewModelStore()
    private val jobs = mutableListOf<Job>()
    private var count = 0

    fun <T : ViewModel> keep(vm: T): T {
        store.put("vm-${count++}", vm)
        jobs += vm.viewModelScope.coroutineContext.job
        return vm
    }

    fun clear() {
        store.clear()
        runBlocking { withTimeoutOrNull(5.seconds) { jobs.joinAll() } }
    }
}
