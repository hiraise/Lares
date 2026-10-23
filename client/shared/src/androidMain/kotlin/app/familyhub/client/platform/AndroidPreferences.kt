package app.familyhub.client.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Theme and language in DataStore Preferences. */
class DataStorePreferencesStore(
    context: Context,
) : PreferencesStore {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { java.io.File(context.filesDir, "datastore/familyhub_ui.preferences_pb") })

    override val preferences: Flow<AppPreferences> =
        dataStore.data.map { prefs ->
            AppPreferences(
                theme = ThemeMode.entries.firstOrNull { it.name == prefs[THEME] } ?: ThemeMode.SYSTEM,
                language = AppLanguage.entries.firstOrNull { it.name == prefs[LANGUAGE] } ?: AppLanguage.SYSTEM,
            )
        }

    override suspend fun setTheme(theme: ThemeMode) {
        dataStore.edit { it[THEME] = theme.name }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[LANGUAGE] = language.name }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
    }
}

/**
 * Per-app language through AppCompat: Android 13+ hands it to the system, older versions keep it themselves
 * (`autoStoreLocales` in the manifest). The activity is recreated with the new configuration, and Compose
 * resources follow the configuration's locale.
 */
class AppCompatLanguageApplier : LanguageApplier {
    override fun apply(language: AppLanguage) {
        val locales = language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
        // AppCompat recreates activities, so it must run on the main thread.
        Handler(Looper.getMainLooper()).post { AppCompatDelegate.setApplicationLocales(locales) }
    }
}
