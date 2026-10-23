package app.lares.client.ui.common

import androidx.compose.runtime.Composable
import app.lares.client.platform.AppLanguage
import app.lares.client.platform.ThemeMode
import app.lares.client.resources.Res
import app.lares.client.resources.color_blue
import app.lares.client.resources.color_brown
import app.lares.client.resources.color_gray
import app.lares.client.resources.color_green
import app.lares.client.resources.color_orange
import app.lares.client.resources.color_pink
import app.lares.client.resources.color_purple
import app.lares.client.resources.color_red
import app.lares.client.resources.color_teal
import app.lares.client.resources.color_yellow
import app.lares.client.resources.contact_address
import app.lares.client.resources.contact_email
import app.lares.client.resources.contact_other
import app.lares.client.resources.contact_phone
import app.lares.client.resources.language_en
import app.lares.client.resources.language_ru
import app.lares.client.resources.language_system
import app.lares.client.resources.perm_budget_manage
import app.lares.client.resources.perm_budget_view
import app.lares.client.resources.perm_calendar_create
import app.lares.client.resources.perm_calendar_edit_any
import app.lares.client.resources.perm_calendar_view
import app.lares.client.resources.perm_household_manage
import app.lares.client.resources.perm_location_manage_places
import app.lares.client.resources.perm_location_pause_own
import app.lares.client.resources.perm_location_view
import app.lares.client.resources.perm_persons_manage
import app.lares.client.resources.perm_shopping_edit
import app.lares.client.resources.perm_shopping_manage_lists
import app.lares.client.resources.perm_shopping_view
import app.lares.client.resources.perm_tags_manage
import app.lares.client.resources.perm_tasks_create
import app.lares.client.resources.perm_tasks_edit_any
import app.lares.client.resources.perm_tasks_view
import app.lares.client.resources.theme_dark
import app.lares.client.resources.theme_light
import app.lares.client.resources.theme_system
import app.lares.contract.entities.ContactKind
import app.lares.contract.entities.PaletteColor
import app.lares.contract.permissions.Permission
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppLanguage.label(): String =
    stringResource(
        when (this) {
            AppLanguage.SYSTEM -> Res.string.language_system
            AppLanguage.ENGLISH -> Res.string.language_en
            AppLanguage.RUSSIAN -> Res.string.language_ru
        },
    )

@Composable
fun ThemeMode.label(): String =
    stringResource(
        when (this) {
            ThemeMode.SYSTEM -> Res.string.theme_system
            ThemeMode.LIGHT -> Res.string.theme_light
            ThemeMode.DARK -> Res.string.theme_dark
        },
    )

@Composable
fun PaletteColor.label(): String =
    stringResource(
        when (this) {
            PaletteColor.RED -> Res.string.color_red
            PaletteColor.ORANGE -> Res.string.color_orange
            PaletteColor.YELLOW -> Res.string.color_yellow
            PaletteColor.GREEN -> Res.string.color_green
            PaletteColor.TEAL -> Res.string.color_teal
            PaletteColor.BLUE -> Res.string.color_blue
            PaletteColor.PURPLE -> Res.string.color_purple
            PaletteColor.PINK -> Res.string.color_pink
            PaletteColor.BROWN -> Res.string.color_brown
            PaletteColor.GRAY -> Res.string.color_gray
        },
    )

@Composable
fun ContactKind.label(): String =
    stringResource(
        when (this) {
            ContactKind.PHONE -> Res.string.contact_phone
            ContactKind.EMAIL -> Res.string.contact_email
            ContactKind.ADDRESS -> Res.string.contact_address
            ContactKind.OTHER -> Res.string.contact_other
        },
    )

/** Plain-language permission labels for the group editor (plan M0-08). */
fun Permission.labelRes(): StringResource =
    when (this) {
        Permission.HOUSEHOLD_MANAGE -> Res.string.perm_household_manage
        Permission.PERSONS_MANAGE -> Res.string.perm_persons_manage
        Permission.TAGS_MANAGE -> Res.string.perm_tags_manage
        Permission.TASKS_VIEW -> Res.string.perm_tasks_view
        Permission.TASKS_CREATE -> Res.string.perm_tasks_create
        Permission.TASKS_EDIT_ANY -> Res.string.perm_tasks_edit_any
        Permission.SHOPPING_VIEW -> Res.string.perm_shopping_view
        Permission.SHOPPING_EDIT -> Res.string.perm_shopping_edit
        Permission.SHOPPING_MANAGE_LISTS -> Res.string.perm_shopping_manage_lists
        Permission.CALENDAR_VIEW -> Res.string.perm_calendar_view
        Permission.CALENDAR_CREATE -> Res.string.perm_calendar_create
        Permission.CALENDAR_EDIT_ANY -> Res.string.perm_calendar_edit_any
        Permission.LOCATION_VIEW -> Res.string.perm_location_view
        Permission.LOCATION_PAUSE_OWN -> Res.string.perm_location_pause_own
        Permission.LOCATION_MANAGE_PLACES -> Res.string.perm_location_manage_places
        Permission.BUDGET_VIEW -> Res.string.perm_budget_view
        Permission.BUDGET_MANAGE -> Res.string.perm_budget_manage
    }
