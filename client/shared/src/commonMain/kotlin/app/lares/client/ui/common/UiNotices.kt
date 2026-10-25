package app.lares.client.ui.common

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Snackbar texts for the main screen from pages that close themselves (the event page: «Повторение удалено»). Buffered,
 * so a text posted while the main screen is not shown waits until it is.
 */
class UiNotices {
    private val channel = Channel<UiText>(Channel.BUFFERED)

    val notices: Flow<UiText> = channel.receiveAsFlow()

    fun post(text: UiText) {
        channel.trySend(text)
    }
}
