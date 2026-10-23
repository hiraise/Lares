package app.lares.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lares.client.ui.theme.Spacing

/**
 * A full-screen page outside the main scaffold (onboarding, detail screens): background, system bar and keyboard
 * insets. [topBar] stays fixed; [content] scrolls.
 */
@Composable
fun AppPage(
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    contentPadding: Dp = Spacing.screen,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        topBar?.invoke()
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = contentPadding)
                .padding(bottom = Spacing.xl, top = if (topBar == null) 0.dp else Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            content = content,
        )
    }
}

private typealias Dp = androidx.compose.ui.unit.Dp
