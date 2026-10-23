package app.familyhub.client.ui

import app.familyhub.client.di.AppScope
import app.familyhub.client.platform.AppStartup
import app.familyhub.client.platform.AppearanceSettings
import app.familyhub.client.platform.DeepLinkHandler
import app.familyhub.client.ui.main.MainViewModel
import app.familyhub.client.ui.onboarding.InviteAcceptViewModel
import app.familyhub.client.ui.onboarding.LinkViewModel
import app.familyhub.client.ui.onboarding.LoginViewModel
import app.familyhub.client.ui.onboarding.ServerViewModel
import app.familyhub.client.ui.onboarding.SetupViewModel
import app.familyhub.client.ui.settings.AboutViewModel
import app.familyhub.client.ui.settings.ChangePasswordViewModel
import app.familyhub.client.ui.settings.DevicesViewModel
import app.familyhub.client.ui.settings.LoginQrViewModel
import app.familyhub.client.ui.settings.ProfileViewModel
import app.familyhub.client.ui.settings.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The app UI on top of the client core: ViewModels and UI services. The platform module must also provide
 * `PreferencesStore`, `LanguageApplier` and `AppInfo`. A new module per call, like `sharedModule`.
 */
fun uiModule(): Module =
    module {
        single { DeepLinkHandler() }
        single { AppStartup(get(), get(AppScope)) }
        single { AppearanceSettings(get(), get(), get(AppScope)) }
        viewModel { ServerViewModel(get()) }
        viewModel { params -> SetupViewModel(get(), params.get()) }
        viewModel { LoginViewModel(get()) }
        viewModel { params -> InviteAcceptViewModel(get(), params.get()) }
        viewModel { params -> LinkViewModel(get(), params.get()) }
        viewModel { MainViewModel(get(), get(), get(), get(), get()) }
        viewModel { SettingsViewModel(get(), get(), get()) }
        viewModel { ProfileViewModel(get(), get(), get()) }
        viewModel { ChangePasswordViewModel(get(), get()) }
        viewModel { LoginQrViewModel(get(), getOrNull<Clock>() ?: Clock.System) }
        viewModel { DevicesViewModel(get()) }
        viewModel { AboutViewModel(get(), get(), get()) }
    }
