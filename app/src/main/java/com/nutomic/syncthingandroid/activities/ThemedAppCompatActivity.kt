package com.nutomic.syncthingandroid.activities

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.service.Constants

/**
 * Provides a themed instance of AppCompatActivity.
 */
abstract class ThemedAppCompatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Opt-in to edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Load theme. The custom value 3 means "AMOLED black" which is based
        // on the dark (night) resource set, so map it to MODE_NIGHT_YES.
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val prefAppTheme = sharedPreferences.getString(
            Constants.PREF_APP_THEME,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString()
        )?.toIntOrNull() ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppCompatDelegate.setDefaultNightMode(
            if (prefAppTheme == 3) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                prefAppTheme
            }
        )
        super.onCreate(savedInstanceState)
    }
}
