package com.nutomic.syncthingandroid.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.util.isTelevision

/** Default cap for single-column content on wide windows (tablets, desktop mode). */
val AdaptiveContentMaxWidth: Dp = 840.dp

/**
 * Centres [content] and caps its width on wide windows so single-column screens do not
 * stretch across a whole tablet. Compact windows keep the full width.
 *
 * Televisions are deliberately left uncapped: their layouts are designed for the full
 * 10-foot width and must stay independent from the tablet look.
 */
@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = AdaptiveContentMaxWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    val isTelevision = LocalConfiguration.current.isTelevision
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = if (isTelevision) {
                Modifier.fillMaxSize()
            } else {
                Modifier.widthIn(max = maxWidth).fillMaxSize()
            },
            content = content,
        )
    }
}
