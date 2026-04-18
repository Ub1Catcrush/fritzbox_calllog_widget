package com.tvcs.fritzboxcallwidget.prefs

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.tvcs.fritzboxcallwidget.R
import android.content.Intent
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

        // ── Activity Result API — replaces deprecated requestPermissions / onRequestPermissionsResult
        private val notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                // User denied — turn the switch back off
                AppPreferences(requireContext()).missedCallNotificationsEnabled = false
                findPreference<androidx.preference.SwitchPreferenceCompat>(
                    AppPreferences.KEY_MISSED_NOTIFICATIONS)?.isChecked = false
                Toast.makeText(requireContext(),
                    R.string.notif_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            seedColorPickers()
            wireChangeListeners()
            setupPasswordSummary()
        }

        // ── Password summary: show bullets when set, hint text when empty ─────

        private fun setupPasswordSummary() {
            findPreference<EditTextPreference>(AppPreferences.KEY_PASSWORD)?.let { pref ->
                pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                    val v = AppPreferences(requireContext()).fritzPassword
                    if (v.isBlank()) getString(R.string.pref_password_summary)
                    else "\u2022".repeat(8)   // ••••••••
                }
            }
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

            // Data / connection — profile changes handled via ConnectionProfilesActivity
            listOf(
                AppPreferences.KEY_USERNAME,
                AppPreferences.KEY_PASSWORD,
                AppPreferences.KEY_PHONE_PREFIX,
                AppPreferences.KEY_LOCAL_AREA_CODE,
                AppPreferences.KEY_REFRESH,
                AppPreferences.KEY_MAX_ENTRIES
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

            findPreference<Preference>("pref_open_connections")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), ConnectionProfilesActivity::class.java))
                    true
                }

            findPreference<Preference>("pref_test_connection")
                ?.setOnPreferenceClickListener { testConnection(); true }

            // ── Optional features ─────────────────────────────────────────────

            listOf(
                AppPreferences.KEY_SHOW_DURATION,
                AppPreferences.KEY_SHOW_LAST_UPDATED,
                AppPreferences.KEY_CALL_FILTER,
                AppPreferences.KEY_CLICK_TO_DIAL,
            ).forEach { key ->
                findPreference<Preference>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> scheduleWidgetRefresh(); true }
            }

            // Click-to-Dial extension: visible only when click_to_dial is on
            val extPref = findPreference<Preference>(AppPreferences.KEY_CLICK_TO_DIAL_EXT)
            extPref?.isVisible = AppPreferences(ctx).clickToDialEnabled
            findPreference<androidx.preference.SwitchPreferenceCompat>(
                AppPreferences.KEY_CLICK_TO_DIAL)
                ?.setOnPreferenceChangeListener { _, v ->
                    extPref?.isVisible = v as Boolean
                    scheduleWidgetRefresh(); true
                }

            // Phonebook lookup — invalidate cache on toggle
            findPreference<Preference>(AppPreferences.KEY_PHONEBOOK_LOOKUP)
                ?.setOnPreferenceChangeListener { _, _ ->
                    com.tvcs.fritzboxcallwidget.api.PhonebookRepository.invalidate()
                    scheduleWidgetRefresh(); true
                }

            // Missed call notifications — schedule/cancel WorkManager worker
            findPreference<Preference>(AppPreferences.KEY_MISSED_NOTIFICATIONS)
                ?.setOnPreferenceChangeListener { _, v ->
                    if (v as Boolean) {
                        requestNotificationPermissionIfNeeded()
                        com.tvcs.fritzboxcallwidget.widget.MissedCallWorker.schedule(ctx)
                    } else {
                        com.tvcs.fritzboxcallwidget.widget.MissedCallWorker.cancel(ctx)
                    }
                    true
                }

            // Exact alarm permission banner
            showExactAlarmBannerIfNeeded()
        }

        private fun showExactAlarmBannerIfNeeded() {
            val ctx = requireContext()
            val pref = findPreference<Preference>("pref_exact_alarm_hint") ?: return
            if (!com.tvcs.fritzboxcallwidget.widget.WidgetScheduler.canScheduleExactAlarms(ctx)) {
                pref.isVisible = true
                pref.setOnPreferenceClickListener {
                    val intent = com.tvcs.fritzboxcallwidget.widget.WidgetScheduler
                        .requestExactAlarmPermissionIntent(ctx)
                    if (intent != null) startActivity(intent)
                    true
                }
            } else {
                pref.isVisible = false
            }
        }

        private fun requestNotificationPermissionIfNeeded() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (requireContext().checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
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
                val result = CallRepository(AppPreferences(ctx)).fetchCallLog(ctx)
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
