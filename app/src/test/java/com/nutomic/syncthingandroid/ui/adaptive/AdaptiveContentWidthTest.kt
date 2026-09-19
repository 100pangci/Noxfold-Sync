package com.nutomic.syncthingandroid.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies that [AdaptiveContent] caps single-column content on wide windows and keeps
 * the full width on compact ones. Robolectric window sizes come from the qualifiers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveContentWidthTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent() {
        composeRule.setContent {
            AdaptiveContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(CONTENT_TAG)
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-xhdpi")
    fun compactWindow_contentFillsWindow() {
        setContent()
        composeRule.onNodeWithTag(CONTENT_TAG).assertWidthIsEqualTo(400.dp)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun wideWindow_contentIsCapped() {
        setContent()
        composeRule.onNodeWithTag(CONTENT_TAG).assertWidthIsEqualTo(AdaptiveContentMaxWidth)
    }

    private companion object {
        const val CONTENT_TAG = "adaptive-content"
    }
}
