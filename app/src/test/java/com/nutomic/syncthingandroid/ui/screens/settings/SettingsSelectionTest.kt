package com.nutomic.syncthingandroid.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsSelectionTest {

    @Test
    fun directRootDestination_selectsItself() {
        assertEquals(
            SettingsRoute.RunConditions,
            SettingsRoute.RunConditions.selectedSettingsRoot(),
        )
        assertEquals(
            SettingsRoute.ImportExport,
            SettingsRoute.ImportExport.selectedSettingsRoot(),
        )
    }

    @Test
    fun licenses_selectsAboutRoot() {
        assertEquals(
            SettingsRoute.About,
            SettingsRoute.Licenses.selectedSettingsRoot(),
        )
    }

    @Test
    fun customCertificate_selectsSyncthingOptionsRoot() {
        assertEquals(
            SettingsRoute.SyncthingOptions,
            SettingsRoute.CustomCertificate.selectedSettingsRoot(),
        )
    }

    @Test
    fun rootAndAbsentRoute_haveNoSelection() {
        assertNull(SettingsRoute.Root.selectedSettingsRoot())
        assertNull((null as SettingsRoute?).selectedSettingsRoot())
    }
}
