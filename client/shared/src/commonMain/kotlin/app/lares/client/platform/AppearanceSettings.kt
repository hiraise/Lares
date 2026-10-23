package app.lares.client.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Theme and language of the app (FND-061, FND-062): stored on the device and applied right away. */
class AppearanceSettings(
    private val store: PreferencesStore,
    private val languageApplier: LanguageApplier,
    private val scope: CoroutineScope,
) {
    val preferences: StateFlow<AppPreferences?> = store.preferences.stateIn(scope, SharingStarted.Eagerly, null)

    fun setTheme(theme: ThemeMode) {
        scope.launch { store.setTheme(theme) }
    }

    fun setLanguage(language: AppLanguage) {
        scope.launch {
            store.setLanguage(language)
            languageApplier.apply(language)
        }
    }
}
