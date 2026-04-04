package com.tvcs.fritzboxcallwidget.prefs

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.tvcs.fritzboxcallwidget.R
import com.tvcs.fritzboxcallwidget.api.CallRepository
import com.tvcs.fritzboxcallwidget.widget.CallLogWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapLocale(newBase, AppPreferences(newBase).language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(AppPreferences(this).theme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = getString(R.string.settings_title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            seedColorPickers()
            wireChangeListeners()
        }

        // ── Seed pickers with current stored (or default) colors ──────────────

        private fun seedColorPickers() {
            val p = AppPreferences(requireContext())

            // Light
            mapOf(
                AppPreferences.KEY_LIGHT_HEADER_BG       to p.lightHeaderBg,
                AppPreferences.KEY_LIGHT_HEADER_TEXT      to p.lightHeaderText,
                AppPreferences.KEY_LIGHT_COL_HEADER_BG   to p.lightColHeaderBg,
                AppPreferences.KEY_LIGHT_COL_HEADER_TEXT  to p.lightColHeaderText,
                AppPreferences.KEY_LIGHT_WIDGET_BG       to p.lightWidgetBg,
                AppPreferences.KEY_LIGHT_ROW_EVEN        to p.lightRowEven,
                AppPreferences.KEY_LIGHT_ROW_ODD         to p.lightRowOdd,
                AppPreferences.KEY_LIGHT_TEXT_PRIMARY    to p.lightTextPrimary,
                AppPreferences.KEY_LIGHT_TEXT_SECONDARY  to p.lightTextSecondary,
                AppPreferences.KEY_LIGHT_DIVIDER         to p.lightDivider,
                AppPreferences.KEY_LIGHT_ERROR           to p.lightError,
            ).forEach { (key, color) ->
                findPreference<ColorPickerPreference>(key)?.setColor(color)
            }

            // Dark
            mapOf(
                AppPreferences.KEY_DARK_HEADER_BG        to p.darkHeaderBg,
                AppPreferences.KEY_DARK_HEADER_TEXT      to p.darkHeaderText,
                AppPreferences.KEY_DARK_COL_HEADER_BG    to p.darkColHeaderBg,
                AppPreferences.KEY_DARK_COL_HEADER_TEXT  to p.darkColHeaderText,
                AppPreferences.KEY_DARK_WIDGET_BG        to p.darkWidgetBg,
                AppPreferences.KEY_DARK_ROW_EVEN         to p.darkRowEven,
                AppPreferences.KEY_DARK_ROW_ODD          to p.darkRowOdd,
                AppPreferences.KEY_DARK_TEXT_PRIMARY     to p.darkTextPrimary,
                AppPreferences.KEY_DARK_TEXT_SECONDARY   to p.darkTextSecondary,
                AppPreferences.KEY_DARK_DIVIDER          to p.darkDivider,
                AppPreferences.KEY_DARK_ERROR            to p.darkError,
            ).forEach { (key, color) ->
                findPreference<ColorPickerPreference>(key)?.setColor(color)
            }
        }

        // ── Wire all change listeners ─────────────────────────────────────────

        private fun wireChangeListeners() {
            val ctx = requireContext()

            findPreference<ListPreference>(AppPreferences.KEY_LANGUAGE)
                ?.setOnPreferenceChangeListener { _, v ->
                    AppPreferences(ctx).language = v as String
                    activity?.recreate(); true
                }

            findPreference<ListPreference>(AppPreferences.KEY_THEME)
                ?.setOnPreferenceChangeListener { _, v ->
                    applyTheme(v as String); scheduleWidgetRefresh(); true
                }

            // All color pickers (both light and dark)
            AppPreferences.ALL_COLOR_KEYS.forEach { key ->
                findPreference<ColorPickerPreference>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> scheduleWidgetRefresh(); true }
            }

            // Font / display
            listOf(AppPreferences.KEY_FONT_FAMILY, AppPreferences.KEY_FONT_SIZE).forEach { key ->
                findPreference<Preference>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> scheduleWidgetRefresh(); true }
            }

            // Data / connection
            listOf(
                AppPreferences.KEY_HOST, AppPreferences.KEY_PORT,
                AppPreferences.KEY_USERNAME, AppPreferences.KEY_PASSWORD,
                AppPreferences.KEY_HTTPS, AppPreferences.KEY_PHONE_PREFIX,
                AppPreferences.KEY_REFRESH, AppPreferences.KEY_MAX_ENTRIES
            ).forEach { key ->
                findPreference<Preference>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> scheduleWidgetRefresh(); true }
            }

            findPreference<Preference>("pref_reset_colors")
                ?.setOnPreferenceClickListener {
                    AppPreferences(ctx).resetColors()
                    seedColorPickers()
                    Toast.makeText(ctx, R.string.colors_reset, Toast.LENGTH_SHORT).show()
                    scheduleWidgetRefresh(); true
                }

            findPreference<Preference>("pref_test_connection")
                ?.setOnPreferenceClickListener { testConnection(); true }
        }

        private fun scheduleWidgetRefresh() {
            lifecycleScope.launch {
                delay(300)
                CallLogWidget.triggerRefresh(requireContext())
            }
        }

        private fun testConnection() {
            val ctx = requireContext()
            Toast.makeText(ctx, R.string.testing_connection, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = CallRepository(AppPreferences(ctx)).fetchCallLog()
                val msg = if (result.isSuccess)
                    getString(R.string.connection_success, result.getOrDefault(emptyList()).size)
                else
                    getString(R.string.connection_failed, result.exceptionOrNull()?.message)
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        fun applyTheme(theme: String) {
            AppCompatDelegate.setDefaultNightMode(when (theme) {
                "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            })
        }

        fun wrapLocale(context: Context, lang: String): Context {
            if (lang == "system" || lang.isBlank()) return context
            val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                Locale.of(lang)
            } else {
                Locale.forLanguageTag(lang)
            }
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    }
}
