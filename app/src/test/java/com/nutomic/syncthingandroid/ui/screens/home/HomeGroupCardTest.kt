package com.nutomic.syncthingandroid.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGroupCardTest {

    @Test
    fun expandedCardBeforeUserToggle_usesNaturalBodyLayout() {
        assertTrue(useNaturalBodyLayout(expanded = true, seenUserToggle = false))
    }

    @Test
    fun collapsedCardBeforeUserToggle_usesFixedHeightLayout() {
        assertFalse(useNaturalBodyLayout(expanded = false, seenUserToggle = false))
    }

    @Test
    fun userToggledCard_usesFixedHeightLayoutRegardlessOfExpansion() {
        assertFalse(useNaturalBodyLayout(expanded = true, seenUserToggle = true))
        assertFalse(useNaturalBodyLayout(expanded = false, seenUserToggle = true))
    }
}
