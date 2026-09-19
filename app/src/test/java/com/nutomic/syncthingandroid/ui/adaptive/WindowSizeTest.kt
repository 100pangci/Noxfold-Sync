package com.nutomic.syncthingandroid.ui.adaptive

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSizeTest {

    private fun widthClass(widthDp: Float): AdaptiveWidthClass =
        WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, 800f).adaptiveWidthClass

    @Test
    fun widthBelowMediumBreakpoint_isCompact() {
        assertEquals(AdaptiveWidthClass.Compact, widthClass(320f))
        assertEquals(AdaptiveWidthClass.Compact, widthClass(599f))
    }

    @Test
    fun widthFromMediumToExpanded_isMedium() {
        assertEquals(AdaptiveWidthClass.Medium, widthClass(600f))
        assertEquals(AdaptiveWidthClass.Medium, widthClass(839f))
    }

    @Test
    fun widthFromExpandedBreakpoint_isExpanded() {
        assertEquals(AdaptiveWidthClass.Expanded, widthClass(840f))
        assertEquals(AdaptiveWidthClass.Expanded, widthClass(1280f))
    }
}
