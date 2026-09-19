package com.nutomic.syncthingandroid.activities

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * QR scanner that preserves the portrait-first phone UX without letterboxing tablets.
 *
 * The embedded scanner declares a fixed landscape orientation. A single manifest
 * orientation cannot vary by window configuration, so this host applies the compact
 * phone preference at runtime and leaves sw600dp+ devices fully resizable.
 */
class AdaptiveCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp < 600) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        super.onCreate(savedInstanceState)
    }
}
