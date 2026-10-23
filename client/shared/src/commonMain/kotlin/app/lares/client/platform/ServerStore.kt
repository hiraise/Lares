package app.lares.client.platform

import app.lares.contract.Ids
import kotlin.concurrent.Volatile

/** The server this installation talks to and the installation's device id (UUIDv7, generated once). */
interface ServerStore {
    suspend fun serverUrl(): String?

    suspend fun setServerUrl(url: String?)

    suspend fun deviceId(): String
}

class InMemoryServerStore(
    @Volatile private var url: String? = null,
) : ServerStore {
    private val deviceId = Ids.newId()

    override suspend fun serverUrl(): String? = url

    override suspend fun setServerUrl(url: String?) {
        this.url = url
    }

    override suspend fun deviceId(): String = deviceId
}
