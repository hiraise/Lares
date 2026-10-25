package app.lares.client.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Theme override (FND-062). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** UI language override (FND-061); [tag] is the BCP 47 tag, `null` = follow the system. */
enum class AppLanguage(
    val tag: String?,
) {
    SYSTEM(null),
    ENGLISH("en"),
    RUSSIAN("ru"),
}

data class AppPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** The post-login explanation for the notification permission was shown (M0-09); it is shown once per device. */
    val notificationPromptShown: Boolean = false,
    /** The last Calendar view (CAL-008): `month`, `week` or `list`. */
    val calendarView: String = CALENDAR_VIEW_MONTH,
) {
    companion object {
        const val CALENDAR_VIEW_MONTH = "month"
        const val CALENDAR_VIEW_WEEK = "week"
        const val CALENDAR_VIEW_LIST = "list"
    }
}

/** Device-level preferences; they survive logout. Android keeps them in DataStore, the JVM in memory. */
interface PreferencesStore {
    val preferences: Flow<AppPreferences>

    suspend fun setTheme(theme: ThemeMode)

    suspend fun setLanguage(language: AppLanguage)

    suspend fun setNotificationPromptShown()

    suspend fun setCalendarView(view: String)
}

class InMemoryPreferencesStore : PreferencesStore {
    private val state = MutableStateFlow(AppPreferences())

    override val preferences: Flow<AppPreferences> = state.asStateFlow()

    override suspend fun setTheme(theme: ThemeMode) = state.update { it.copy(theme = theme) }

    override suspend fun setLanguage(language: AppLanguage) = state.update { it.copy(language = language) }

    override suspend fun setNotificationPromptShown() = state.update { it.copy(notificationPromptShown = true) }

    override suspend fun setCalendarView(view: String) = state.update { it.copy(calendarView = view) }
}

/** Switches the UI language of the running app. Android: per-app locales via AppCompat; elsewhere a no-op for now. */
fun interface LanguageApplier {
    fun apply(language: AppLanguage)
}

/** Facts about the installed app, shown in Settings › About. */
data class AppInfo(
    val version: String,
)
