package app.familyhub.client.di

import app.familyhub.client.api.FamilyHubApi
import app.familyhub.client.api.HttpClientCustomizer
import app.familyhub.client.api.createHttpClient
import app.familyhub.client.auth.SessionManager
import app.familyhub.client.data.GroupsRepository
import app.familyhub.client.data.HouseholdRepository
import app.familyhub.client.data.MembersRepository
import app.familyhub.client.data.PermissionsRepository
import app.familyhub.client.data.PersonsRepository
import app.familyhub.client.data.PersonsWriter
import app.familyhub.client.data.TagsRepository
import app.familyhub.client.data.TagsWriter
import app.familyhub.client.db.LocalDb
import app.familyhub.client.sync.LocalWriter
import app.familyhub.client.sync.Materializer
import app.familyhub.client.sync.SyncConfig
import app.familyhub.client.sync.SyncEngine
import app.familyhub.client.sync.defaultEntityAdapters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock

/** Qualifier of the dispatcher all database work runs on (provided by the platform module). */
val DbDispatcher = named("dbDispatcher")

/** Qualifier of the scope that lives as long as the app (sync engine, session events). */
val AppScope = named("appScope")

/**
 * The client core. [platformModule] must provide: `SqlDriver`, a `CoroutineDispatcher` named [DbDispatcher],
 * `HttpClientEngine`, `TokenStore`, `ServerStore`, `DeviceInfo`; optionally `HttpClientCustomizer`, `SyncConfig`, `Clock`.
 */
fun sharedModule(platformModule: Module): Module =
    module {
        includes(platformModule, coreModule())
    }

// A new module per call: Koin keeps singleton instances inside the module object, and every client core
// (one per app, several in integration tests) needs its own.
private fun coreModule() =
    module {
        single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        single { LocalDb(get(), get(DbDispatcher)) }
        single { createHttpClient(get(), getOrNull<HttpClientCustomizer>()) }
        single { FamilyHubApi(get(), get(), get(), clock()) }
        single { defaultEntityAdapters(clock()) }
        single { Materializer(get()) }
        single { SyncEngine(get(), get(), get(), get(), clock(), get(AppScope), getOrNull<SyncConfig>() ?: SyncConfig()) }
        single { LocalWriter(get(), get(), clock(), get()) }
        single { SessionManager(get(), get(), get(), get(), get(), get(), get(AppScope)) }
        single { HouseholdRepository(get()) }
        single { MembersRepository(get()) }
        single { GroupsRepository(get()) }
        single { PersonsRepository(get()) }
        single { TagsRepository(get()) }
        single { PermissionsRepository(get()) }
        single { TagsWriter(get()) }
        single { PersonsWriter(get(), get()) }
    }

private fun org.koin.core.scope.Scope.clock(): Clock = getOrNull<Clock>() ?: Clock.System
