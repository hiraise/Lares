package app.lares.client.ui

import androidx.compose.runtime.Composable

/** Light or dark system bar icons to match the app theme, which may differ from the system one (FND-062). */
@Composable
expect fun SystemBarsEffect(darkTheme: Boolean)
