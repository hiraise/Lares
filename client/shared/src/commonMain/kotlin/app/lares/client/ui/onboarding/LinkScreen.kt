package app.lares.client.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.action_retry
import app.lares.client.resources.link_connecting
import app.lares.client.resources.link_title
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FullScreenLoading
import app.lares.client.ui.components.TonalButton
import app.lares.contract.DeepLink
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Connects to the server of a link, then continues with the invite or the QR login. */
@Composable
fun LinkRoute(
    link: DeepLink,
    onInvite: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: LinkViewModel = koinViewModel(key = "link-${link.serverUrl}-${link.code}") { parametersOf(link) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.inviteCode) {
        state.inviteCode?.let {
            viewModel.onNavigated()
            onInvite(it)
        }
    }
    if (state.working || state.inviteCode != null) {
        FullScreenLoading(text = stringResource(Res.string.link_connecting))
        return
    }
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.link_title), onBack) }) {
        ErrorText(state.error)
        TonalButton(stringResource(Res.string.action_retry), viewModel::run)
    }
}
