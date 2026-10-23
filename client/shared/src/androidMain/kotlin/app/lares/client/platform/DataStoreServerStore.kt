package app.lares.client.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.lares.contract.Ids
import kotlinx.coroutines.flow.first

/** Server address and device id in DataStore Preferences (not secret, not encrypted). */
class DataStoreServerStore(
    context: Context,
) : ServerStore {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { java.io.File(context.filesDir, "datastore/lares_server.preferences_pb") })

    override suspend fun serverUrl(): String? = dataStore.data.first()[SERVER_URL]

    override suspend fun setServerUrl(url: String?) {
        dataStore.edit { if (url == null) it.remove(SERVER_URL) else it[SERVER_URL] = url }
    }

    override suspend fun deviceId(): String {
        dataStore.data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        dataStore.edit { prefs -> id = prefs[DEVICE_ID] ?: Ids.newId().also { prefs[DEVICE_ID] = it } }
        return id
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
