package app.lares.contract.api

import kotlinx.serialization.json.Json

/** The JSON configuration shared by server and client (see architecture/api.md conventions). */
val LaresJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
