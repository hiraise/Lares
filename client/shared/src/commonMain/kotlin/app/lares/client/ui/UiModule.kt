package app.lares.client.ui

import app.lares.client.data.calendar.CalendarReminderTexts
import app.lares.client.data.tasks.TaskReminderTexts
import app.lares.client.di.AppScope
import app.lares.client.notifications.notificationsModule
import app.lares.client.platform.AppStartup
import app.lares.client.platform.AppearanceSettings
import app.lares.client.platform.DeepLinkHandler
import app.lares.client.platform.PendingNavigation
import app.lares.client.platform.TaskLinks
import app.lares.client.ui.common.CalendarReminderResourceTexts
import app.lares.client.ui.common.TaskReminderResourceTexts
import app.lares.client.ui.family.AddMemberViewModel
import app.lares.client.ui.family.FamilySettingsViewModel
import app.lares.client.ui.family.FamilyViewModel
import app.lares.client.ui.family.GroupArgs
import app.lares.client.ui.family.GroupEditorViewModel
import app.lares.client.ui.family.MemberDetailViewModel
import app.lares.client.ui.family.PersonArgs
import app.lares.client.ui.family.PersonViewModel
import app.lares.client.ui.family.TagViewModel
import app.lares.client.ui.main.MainViewModel
import app.lares.client.ui.main.TodayViewModel
import app.lares.client.ui.onboarding.InviteAcceptViewModel
import app.lares.client.ui.onboarding.LinkViewModel
import app.lares.client.ui.onboarding.LoginViewModel
import app.lares.client.ui.onboarding.ServerViewModel
import app.lares.client.ui.onboarding.SetupViewModel
import app.lares.client.ui.settings.AboutViewModel
import app.lares.client.ui.settings.ChangePasswordViewModel
import app.lares.client.ui.settings.DevicesViewModel
import app.lares.client.ui.settings.LoginQrViewModel
import app.lares.client.ui.settings.NotificationPromptViewModel
import app.lares.client.ui.settings.NotificationSettingsViewModel
import app.lares.client.ui.settings.ProfileViewModel
import app.lares.client.ui.settings.SettingsViewModel
import app.lares.client.ui.shopping.ShoppingListViewModel
import app.lares.client.ui.shopping.ShoppingListsViewModel
import app.lares.client.ui.tasks.TaskDetailViewModel
import app.lares.client.ui.tasks.TaskEditorViewModel
import app.lares.client.ui.tasks.TasksViewModel
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
        includes(notificationsModule())
        single { DeepLinkHandler() }
        single { PendingNavigation() }
        single { TaskLinks(get(), get()) }
        // Replaces the core's English fallback.
        single<TaskReminderTexts> { TaskReminderResourceTexts(getOrNull<Clock>() ?: Clock.System) }
        single<CalendarReminderTexts> { CalendarReminderResourceTexts() }
        single { AppStartup(get(), get(AppScope)) }
        single { AppearanceSettings(get(), get(), get(AppScope)) }
        viewModel { ServerViewModel(get()) }
        viewModel { params -> SetupViewModel(get(), params.get()) }
        viewModel { LoginViewModel(get()) }
        viewModel { params -> InviteAcceptViewModel(get(), params.get()) }
        viewModel { params -> LinkViewModel(get(), params.get()) }
        viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get()) }
        viewModel { SettingsViewModel(get(), get(), get()) }
        viewModel { ProfileViewModel(get(), get(), get()) }
        viewModel { ChangePasswordViewModel(get(), get()) }
        viewModel { params -> LoginQrViewModel(get(), getOrNull<Clock>() ?: Clock.System, params.getOrNull<String>()) }
        viewModel { DevicesViewModel(get()) }
        viewModel { NotificationSettingsViewModel(get(), get(), get(), get(), getOrNull<Clock>() ?: Clock.System) }
        viewModel { NotificationPromptViewModel(get(), get()) }
        viewModel { AboutViewModel(get(), get(), get()) }
        viewModel { FamilyViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { params -> MemberDetailViewModel(get(), get(), get(), get(), get(), get(), params.get()) }
        viewModel { params -> AddMemberViewModel(get(), get(), get(), get(), get(), params.get()) }
        viewModel { params -> GroupEditorViewModel(get(), get(), get(), get(), get(), get(), params.get<GroupArgs>().groupId) }
        viewModel { params ->
            PersonViewModel(get(), get(), get(), get(), get(), getOrNull<Clock>() ?: Clock.System, params.get<PersonArgs>().personId)
        }
        viewModel { FamilySettingsViewModel(get(), get(), get(), get()) }
        viewModel { TasksViewModel(get(), get(), get()) }
        viewModel { TodayViewModel(get(), get(), get()) }
        viewModel { params -> TagViewModel(get(), get(), get(), get(), get(), params.get()) }
        viewModel { params -> TaskDetailViewModel(get(), get(), params.get()) }
        viewModel { params -> TaskEditorViewModel(get(), get(), get(), get(), get(), params.get()) }
        viewModel { ShoppingListsViewModel(get(), get()) }
        viewModel { params -> ShoppingListViewModel(get(), get(), get(), get(), get(), params.get()) }
    }
