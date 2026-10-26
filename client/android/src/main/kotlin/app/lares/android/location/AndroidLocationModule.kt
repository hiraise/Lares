package app.lares.android.location

import android.content.Context
import app.lares.client.data.location.LocationDevice
import app.lares.client.data.location.LocationTracking
import app.lares.client.di.AppScope
import org.koin.core.module.Module
import org.koin.dsl.module

/** Android location sharing (plan M4-07): the core's `SharingController` picks these up instead of its no-ops. */
fun androidLocationModule(context: Context): Module {
    val app = context.applicationContext
    return module {
        single<LocationDevice> { AndroidLocationDevice(app) }
        single<LocationTracking> { AndroidLocationTracking(app) { get() } }
        single { LocationResume(app, { get() }, get(), get(), get(), get(AppScope)) }
    }
}
