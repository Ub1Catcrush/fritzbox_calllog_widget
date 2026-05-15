package com.tvcs.fritzboxcallwidget

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences

/**
 * Application subclass — the only place where AppCompatDelegate.setDefaultNightMode()
 * is called reliably before any Activity inflates its layout.
 *
 * WHY THIS IS NECESSARY
 * ─────────────────────
 * AppCompatDelegate.setDefaultNightMode() must be applied before any Activity
 * inflates its layout. If called inside Activity.onCreate() it is already too
 * late for that Activity's first frame: the Resources/Configuration object used
 * to resolve @color, @drawable and @style references is determined before
 * onCreate() runs. This causes:
 *   - First-launch: wrong background/text colours when a "dark"/"light" pref
 *     is saved but the Activity inflates with the system default.
 *   - Mode switch at runtime: old colours persist until recreate(); the
 *     SettingsActivity calls recreate() after saving the new theme pref so
 *     the Activity re-inflates with the correct Resources/Configuration.
 *
 * Setting the mode in Application.onCreate() guarantees it is applied before
 * the first Activity ever starts.
 */
class FritzCallApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        applyTheme(AppPreferences(this).theme)
    }

    companion object {
        /**
         * Apply the given theme string as the global night mode.
         * Called from [FritzCallApplication.onCreate] on cold start and from
         * [com.tvcs.fritzboxcallwidget.prefs.SettingsActivity] when the user
         * changes the theme preference (followed by Activity.recreate()).
         */
        fun applyTheme(theme: String) {
            AppCompatDelegate.setDefaultNightMode(
                when (theme) {
                    "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
