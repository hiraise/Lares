package app.familyhub.client.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.action_continue
import app.familyhub.client.resources.qr_paste_label
import app.familyhub.client.ui.components.FormField
import app.familyhub.client.ui.components.PrimaryButton
import app.familyhub.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** No camera on the JVM (tests, future desktop): paste the link instead. */
@Composable
actual fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    var text by remember { mutableStateOf("") }
    Column(modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        FormField(
            text,
            { text = it },
            stringResource(Res.string.qr_paste_label),
            imeAction = ImeAction.Done,
            onDone = { onResult(text.trim()) },
        )
        PrimaryButton(stringResource(Res.string.action_continue), { onResult(text.trim()) })
    }
}
