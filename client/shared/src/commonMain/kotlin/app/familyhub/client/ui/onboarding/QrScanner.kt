package app.familyhub.client.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.familyhub.client.platform.DeepLinkHandler
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.qr_hint
import app.familyhub.client.resources.qr_title
import app.familyhub.client.ui.common.UiError
import app.familyhub.client.ui.common.message
import app.familyhub.client.ui.components.DetailTopBar
import app.familyhub.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Camera QR scanner. Android: CameraX preview with a ZXing analyzer (no ML Kit, ADR-0006), asks for the camera
 * permission with an explanation. JVM: a text field to paste the payload. [onResult] may be called repeatedly
 * with the same text while the code stays in view.
 */
@Composable
expect fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
)

/** Scans a `familyhub://` invite or login link and hands it to [DeepLinkHandler]; onboarding continues from there. */
@Composable
fun QrScanRoute(
    onBack: () -> Unit,
    deepLinks: DeepLinkHandler = koinInject(),
) {
    var error by remember { mutableStateOf<UiError?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        DetailTopBar(stringResource(Res.string.qr_title), onBack)
        Text(
            error?.message() ?: stringResource(Res.string.qr_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.m),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrScanner(
                onResult = { text ->
                    if (deepLinks.submit(text)) onBack() else error = UiError.NotFamilyHubQr
                },
                onCancel = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
