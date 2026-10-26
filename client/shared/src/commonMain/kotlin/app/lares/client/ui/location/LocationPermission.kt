package app.lares.client.ui.location

import androidx.compose.runtime.Composable

/**
 * Returns a function that asks for precise location (FINE together with COARSE) and reports whether precise location
 * was granted; «approximate only» counts as a refusal (plan M4-07).
 */
@Composable
expect fun rememberLocationPermissionRequest(onResult: (fineGranted: Boolean) -> Unit): () -> Unit

/**
 * Returns a function for the background step: API 29 asks in the system dialog and reports the result; API 30+ opens
 * the app settings through [openSettings] (the caller checks again when the app is back); below 29 it is granted with
 * the foreground permission.
 */
@Composable
expect fun rememberBackgroundLocationRequest(
    openSettings: () -> Unit,
    onResult: (granted: Boolean) -> Unit,
): () -> Unit

/** Whether the background step sends the user to the app settings (API 30+) instead of a system dialog. */
expect fun backgroundLocationInSettings(): Boolean
