package app.familyhub.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.app_name
import org.jetbrains.compose.resources.stringResource

@Composable
fun App() {
    MaterialTheme {
        Surface {
            Text(stringResource(Res.string.app_name))
        }
    }
}
