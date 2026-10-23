package app.familyhub.client.ui.common

import androidx.compose.runtime.Composable

/** Returns a function that asks for the notification permission (Android 13+) and reports whether it was granted. */
@Composable
expect fun rememberNotificationPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit
