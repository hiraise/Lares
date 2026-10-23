package app.familyhub.client.ui.common

import androidx.compose.runtime.Composable
import app.familyhub.client.platform.AppLanguage
import app.familyhub.client.platform.ThemeMode
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.language_en
import app.familyhub.client.resources.language_ru
import app.familyhub.client.resources.language_system
import app.familyhub.client.resources.theme_dark
import app.familyhub.client.resources.theme_light
import app.familyhub.client.resources.theme_system
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
