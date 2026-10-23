package app.familyhub.client.ui.common

import androidx.compose.runtime.Composable

/** Returns a function that offers [text] to other apps (Android share sheet). On the JVM it does nothing. */
@Composable
expect fun rememberShareText(): (text: String) -> Unit
