package app.lares.client.ui.location

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable
actual fun rememberLocationPermissionRequest(onResult: (fineGranted: Boolean) -> Unit): () -> Unit {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            onResult(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
        }
    return { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
}

@Composable
actual fun rememberBackgroundLocationRequest(
    openSettings: () -> Unit,
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> onResult(true)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> openSettings()
        }
    }
}

actual fun backgroundLocationInSettings(): Boolean = Build.VERSION.SDK_INT > Build.VERSION_CODES.Q
