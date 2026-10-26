package app.lares.client.ui.location

import androidx.compose.runtime.Composable

@Composable
actual fun rememberLocationPermissionRequest(onResult: (fineGranted: Boolean) -> Unit): () -> Unit = { onResult(true) }

@Composable
actual fun rememberBackgroundLocationRequest(
    openSettings: () -> Unit,
    onResult: (granted: Boolean) -> Unit,
): () -> Unit = openSettings

actual fun backgroundLocationInSettings(): Boolean = true
