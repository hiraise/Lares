package app.lares.client.di

import app.lares.client.api.HttpClientCustomizer
import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.SessionManager
import app.lares.client.data.GroupsRepository
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.PersonsRepository
import app.lares.client.data.PersonsWriter
import app.lares.client.data.TagsRepository
import app.lares.client.data.TagsWriter
import app.lares.client.data.shopping.ShoppingRepository
import app.lares.client.data.shopping.ShoppingWriter
import app.lares.client.data.tasks.EnglishTaskReminderTexts
import app.lares.client.data.tasks.TaskReminderPlanner
import app.lares.client.data.tasks.TaskReminderTexts
import app.lares.client.data.tasks.TasksRepository
import app.lares.client.data.tasks.TasksWriter
import app.lares.client.db.LocalDb
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.Materializer
import app.lares.client.sync.SyncConfig
import app.lares.client.sync.SyncEngine
import app.lares.client.sync.defaultEntityAdapters
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
        single { LaresApi(get(), get(), get(), clock()) }
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
        single { TasksRepository(get(), get(), get(), get(), get(), clock()) }
        single { TasksWriter(get(), get(), clock()) }
        single { ShoppingRepository(get(), get(), get(), get(), clock()) }
        single { ShoppingWriter(get(), get(), clock()) }
        // Overridden by the app UI with resource texts (uiModule).
        single<TaskReminderTexts> { EnglishTaskReminderTexts(clock()) }
        // Needs the notification core (notificationsModule) for the scheduler and the mute settings.
        single { TaskReminderPlanner(get(), get(), get(), get(), get(), clock()) }
    }

private fun org.koin.core.scope.Scope.clock(): Clock = getOrNull<Clock>() ?: Clock.System
