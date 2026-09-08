package com.nutomic.syncthingandroid.ui.screens.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.ui.components.AppCard

/**
 * One group section of the home lists (folders AND devices): a single card
 * framing all members of the group. Tapping the header collapses or expands
 * the body.
 *
 * Performance: the body is ALWAYS composed and measured once at its natural
 * height; collapsing animates the height of a clipping window over that
 * preloaded content while a parallel fade keeps the body in a cached
 * hardware layer (alpha in (0,1)) for the duration of the animation. That
 * turns the per-frame work into cheap texture composites instead of
 * re-recording/re-rasterizing every row, which is what made the toggle
 * animation stutter before. The clipped-away content is removed from the
 * accessibility tree while collapsed.
 */
@Composable
internal fun HomeGroupCard(
    title: String,
    itemCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    // Natural height of the (always composed) body, captured once.
    var fullBodyHeight by remember { mutableStateOf(0.dp) }
    // Animations only run for user toggles; the first frame(s) after a cold
    // start / config restore must snap to the stored state without animation.
    var seenUserToggle by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(200),
        label = "groupChevron",
    )
    // While the body is fading (alpha in (0,1)) Android's renderer promotes
    // the subtree to a cached hardware layer, so the height animation below
    // composites a texture instead of re-recording/re-rasterizing every row
    // on each frame. The height window and the fade run in parallel: the
    // visual collapse/expand stays but per-frame cost collapses.
    val bodyAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(if (seenUserToggle) 260 else 0),
        label = "groupBodyAlpha",
    )
    val bodyHeight by animateDpAsState(
        targetValue = if (expanded) fullBodyHeight else 0.dp,
        animationSpec = tween(if (seenUserToggle) 260 else 0),
        label = "groupBodyHeight",
    )
    fun toggle() {
        seenUserToggle = true
        onToggle()
    }
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = ::toggle)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (itemCount > 1) {
                Text(
                    text = "($itemCount)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        // The window height animates between 0 and the preloaded natural
        // height; the body below ignores the window constraints (so it is
        // measured exactly once) and is simply clipped while the alpha fade
        // keeps it inside a cached hardware layer.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bodyHeight)
                .clipToBounds()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeightIn(max = Dp.Infinity)
                    .onSizeChanged { size ->
                        fullBodyHeight = with(density) { size.height.toDp() }
                    }
                    .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {})
                    // Fading alpha promotes this subtree to a cached hardware
                    // layer for the duration of the animation (see above).
                    .graphicsLayer { alpha = bodyAlpha }
            ) {
                body()
            }
        }
    }
}
