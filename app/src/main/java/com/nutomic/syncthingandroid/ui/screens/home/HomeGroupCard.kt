package com.nutomic.syncthingandroid.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.ui.theme.AMOLED_CARD_BORDER_ALPHA
import com.nutomic.syncthingandroid.ui.theme.LocalAmoledTheme

/**
 * One slice of a visually continuous group card. Each slice is its own lazy
 * item, so neither an expanded group nor a collapsed header loads hidden rows.
 */
@Composable
internal fun HomeGroupItemSurface(
    first: Boolean,
    last: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardShape = MaterialTheme.shapes.medium
    val square = CornerSize(0.dp)
    val shape = cardShape.copy(
        topStart = if (first) cardShape.topStart else square,
        topEnd = if (first) cardShape.topEnd else square,
        bottomStart = if (last) cardShape.bottomStart else square,
        bottomEnd = if (last) cardShape.bottomEnd else square,
    )
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AMOLED_CARD_BORDER_ALPHA)
    val amoled = LocalAmoledTheme.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = if (first) 6.dp else 0.dp, bottom = if (last) 6.dp else 0.dp)
            .drawWithCache {
                val stroke = Stroke(1.dp.toPx())
                // Extend the outline beyond interior edges, then clip it to
                // this slice. Cache geometry so scrolling does not recreate it
                // every frame, and avoid a border seam across each lazy row.
                val extension = maxOf(
                    cardShape.topStart.toPx(size, this), cardShape.topEnd.toPx(size, this),
                    cardShape.bottomStart.toPx(size, this), cardShape.bottomEnd.toPx(size, this),
                ) + stroke.width
                val above = if (first) 0f else extension
                val below = if (last) 0f else extension
                val inset = stroke.width / 2
                val outline = if (amoled) cardShape.createOutline(
                    Size((size.width - stroke.width).coerceAtLeast(0f), (size.height + above + below - stroke.width).coerceAtLeast(0f)),
                    layoutDirection, this,
                ) else null
                onDrawWithContent {
                    drawContent()
                    if (outline != null) {
                        clipRect {
                            translate(left = inset, top = inset - above) {
                                drawOutline(outline, borderColor, style = stroke)
                            }
                        }
                    }
                }
            },
    ) {
        Column(content = content)
    }
}

/** Header only; expanded members are independent LazyColumn items. */
@Composable
internal fun HomeGroupCard(
    title: String,
    itemCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(200),
        label = "groupChevron",
    )
    HomeGroupItemSurface(first = true, last = !expanded || itemCount == 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = rotation.value },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (itemCount > 1) {
                Text(
                    text = "($itemCount)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
