package app.familyhub.client.ui.common

import androidx.compose.runtime.Composable

@Composable
actual fun rememberNotificationPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit = { onResult(true) }
