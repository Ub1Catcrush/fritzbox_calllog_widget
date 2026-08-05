package com.tvcs.fritzboxcallwidget.prefs

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.tvcs.fritzboxcallwidget.FritzCallApplication
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.tvcs.fritzboxcallwidget.R
import com.tvcs.fritzboxcallwidget.api.CallRepository
import com.tvcs.fritzboxcallwidget.widget.WidgetScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tvcs.fritzboxcallwidget.api.UpdateManager
import com.tvcs.fritzboxcallwidget.BuildConfig
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapLocale(newBase, AppPreferences(newBase).language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // applyTheme() wird jetzt in FritzCallApplication.onCreate() aufgerufen
        // bevor diese Activity startet — hier nicht mehr nötig.
        // Ab SDK 35 ist Edge-to-Edge Pflicht.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // MaterialToolbar als SupportActionBar registrieren, damit der
        // Fragment-Container durch appbar_scrolling_view_behavior korrekt
        // positioniert wird und nie hinter der Toolbar liegt.
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.settings_title)

        // Navigationsleisten-Inset auf den settings_container übertragen,
        // damit die letzten Einstellungen nicht hinter der Navigationsleiste
        // enden (gilt vor allem für Gesture-Navigation ohne sichtbare Nav-Bar).
        val container = findViewById<android.view.View>(R.id.settings_container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                navInsets.bottom
            )
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    class SettingsFragment : PreferenceFragmentCompat() {

        // ── Activity Result launchers ─────────────────────────────────────────

        private val notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                AppPreferences(requireContext()).missedCallNotificationsEnabled = false
                findPreference<androidx.preference.SwitchPreferenceCompat>(
                    AppPreferences.KEY_MISSED_NOTIFICATIONS)?.isChecked = false
                Toast.makeText(requireContext(),
                    R.string.notif_permission_denied, Toast.LENGTH_LONG).show()
            }
            // Re-evaluate all banners after permission result
            refreshPermissionBanners()
        }

        // Returned from system settings pages (battery, exact alarm, app details)
        private val systemSettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { refreshPermissionBanners() }

        // ── Lifecycle ─────────────────────────────────────────────────────────

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            seedColorPickers()
            wireChangeListeners()
            setupPasswordSummary()
        }

        override fun onResume() {
            super.onResume()
            // Re-check every time the user returns to settings (e.g. after
            // granting a permission in system settings)
            refreshPermissionBanners()
        }

        // ── Permission banners ────────────────────────────────────────────────

        /**
         * Shows / hides the three permission banners at the bottom of the settings
         * (in their own "Required permissions" category) based on current system state.
         * The category header itself is hidden when no banner is needed so no empty
         * section appears. Called on resume and after any permission result.
         */
        private fun refreshPermissionBanners() {
            val ctx = requireContext()
            showExactAlarmBanner(ctx)
            showBatteryOptimisationBanner(ctx)
            showNotificationPermissionBanner(ctx)
            // Show the category header only when at least one banner is visible
            val bannerKeys = listOf("pref_exact_alarm_hint", "pref_battery_hint", "pref_notif_perm_hint")
            val anyVisible = bannerKeys.any { findPreference<Preference>(it)?.isVisible == true }
            findPreference<androidx.preference.PreferenceCategory>("pref_category_permissions")
                ?.isVisible = anyVisible
        }

        // ── Exact alarm ───────────────────────────────────────────────────────

        private fun showExactAlarmBanner(ctx: Context) {
            val pref = findPreference<Preference>("pref_exact_alarm_hint") ?: return
            // Ab API 33 (TIRAMISU) wird USE_EXACT_ALARM normalerweise automatisch
            // gewährt. Ab API 36 kann Google Play die Permission für Apps ohne
            // Kalender-/Alarm-Funktion einschränken — daher auch hier prüfen.
            // canScheduleExactAlarms() gibt false zurück wenn die Permission fehlt,
            // unabhängig vom API-Level (API 31–36).
            val needed = !WidgetScheduler.canScheduleExactAlarms(ctx)
            pref.isVisible = needed
            if (needed) {
                pref.setOnPreferenceClickListener {
                    val intent = WidgetScheduler.requestExactAlarmPermissionIntent(ctx)
                    if (intent != null) systemSettingsLauncher.launch(intent)
                    true
                }
            }
        }

        // ── Battery optimisation ──────────────────────────────────────────────

        private fun showBatteryOptimisationBanner(ctx: Context) {
            val pref = findPreference<Preference>("pref_battery_hint") ?: return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            pref.isVisible = !isIgnoring
            if (!isIgnoring) {
                pref.setOnPreferenceClickListener {
                    // Direct the user to the battery optimisation exemption screen
                    // for this specific app.
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                    systemSettingsLauncher.launch(intent)
                    true
                }
            }
        }

        // ── Notification permission ───────────────────────────────────────────

        private fun showNotificationPermissionBanner(ctx: Context) {
            val pref = findPreference<Preference>("pref_notif_perm_hint") ?: return
            // Only relevant on Android 13+ where POST_NOTIFICATIONS is runtime
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                pref.isVisible = false
                return
            }
            val granted = ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            pref.isVisible = !granted
            if (!granted) {
                pref.setOnPreferenceClickListener {
                    if (shouldShowRequestPermissionRationale(
                            android.Manifest.permission.POST_NOTIFICATIONS)) {
                        // Can still ask in-app
                        notificationPermissionLauncher.launch(
                            android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // User permanently denied — send to app settings
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}"))
                        systemSettingsLauncher.launch(intent)
                    }
                    true
                }
            }
        }

        // ── Password summary ──────────────────────────────────────────────────

        private fun setupPasswordSummary() {
            findPreference<EditTextPreference>(AppPreferences.KEY_PASSWORD)?.let { pref ->
                pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                    val v = AppPreferences(requireContext()).fritzPassword
                    if (v.isBlank()) getString(R.string.pref_password_summary)
                    else "\u2022".repeat(8)
                }
            }
        }

        // ── Color pickers ─────────────────────────────────────────────────────

        private fun seedColorPickers() {
            val p = AppPreferences(requireContext())
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

        // ── Change listeners ──────────────────────────────────────────────────

        private fun wireChangeListeners() {
            val ctx = requireContext()

            findPreference<ListPreference>(AppPreferences.KEY_LANGUAGE)
                ?.setOnPreferenceChangeListener { _, v ->
                    AppPreferences(ctx).language = v as String
                    activity?.recreate(); true
                }

            findPreference<ListPreference>(AppPreferences.KEY_THEME)
                ?.setOnPreferenceChangeListener { _, v ->
                    applyTheme(v as String)
                    scheduleWidgetRefresh()
                    // recreate() ist nötig damit die Activity mit dem neuen
                    // Night-Mode-Kontext neu inflated wird — ohne recreate()
                    // bleiben alle @color/@drawable-Referenzen auf dem alten
                    // Light/Dark-Stand bis zum nächsten Kaltstart.
                    activity?.recreate()
                    true
                }

            AppPreferences.ALL_COLOR_KEYS.forEach { key ->
                findPreference<ColorPickerPreference>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> scheduleWidgetRefresh(); true }
            }

            listOf(AppPreferences.KEY_FONT_FAMILY, AppPreferences.KEY_FONT_SIZE).forEach { key ->
                findPreference<Preference>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> scheduleWidgetRefresh(); true }
            }

            listOf(
                AppPreferences.KEY_USERNAME,
                AppPreferences.KEY_PASSWORD,
                AppPreferences.KEY_PHONE_PREFIX,
                AppPreferences.KEY_LOCAL_AREA_CODE,
                AppPreferences.KEY_REFRESH,
                AppPreferences.KEY_MAX_ENTRIES
            ).forEach { key ->
                findPreference<Preference>(key)
                    ?.setOnPreferenceChangeListener { _, _ ->
                        // Reschedule the alarm with new interval when refresh changes
                        if (key == AppPreferences.KEY_REFRESH) {
                            WidgetScheduler.reschedule(ctx)
                        }
                        scheduleWidgetRefresh(); true
                    }
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

            listOf(
                AppPreferences.KEY_SHOW_DURATION,
                AppPreferences.KEY_SHOW_LAST_UPDATED,
                AppPreferences.KEY_CALL_FILTER,
                AppPreferences.KEY_CLICK_TO_DIAL,
            ).forEach { key ->
                findPreference<Preference>(key)
                    ?.setOnPreferenceChangeListener { _, _ -> scheduleWidgetRefresh(); true }
            }

            val extPref = findPreference<Preference>(AppPreferences.KEY_CLICK_TO_DIAL_EXT)
            extPref?.isVisible = AppPreferences(ctx).clickToDialEnabled
            findPreference<androidx.preference.SwitchPreferenceCompat>(
                AppPreferences.KEY_CLICK_TO_DIAL)
                ?.setOnPreferenceChangeListener { _, v ->
                    extPref?.isVisible = v as Boolean
                    scheduleWidgetRefresh(); true
                }

            findPreference<Preference>(AppPreferences.KEY_PHONEBOOK_LOOKUP)
                ?.setOnPreferenceChangeListener { _, _ ->
                    com.tvcs.fritzboxcallwidget.api.PhonebookRepository.invalidate()
                    scheduleWidgetRefresh(); true
                }

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

            findPreference<Preference>("pref_check_update")
                ?.setOnPreferenceClickListener { checkForUpdates(); true }
        }


        private fun checkForUpdates() {
            val updatePref = findPreference<Preference>("pref_check_update") ?: return
            updatePref.isEnabled = false
            updatePref.summary   = getString(R.string.update_checking)

            lifecycleScope.launch {
                UpdateManager.checkForUpdates(requireContext())
                    .onSuccess { info ->
                        if (!isResumed) return@onSuccess
                        if (info.hasUpdate && info.downloadUrl != null) {
                            updatePref.summary   = getString(R.string.update_available, info.latestVersion)
                            updatePref.isEnabled = true
                            updatePref.setOnPreferenceClickListener {
                                UpdateManager.downloadAndInstall(
                                    requireContext(),
                                    info.downloadUrl,
                                    "fritzcalllog-${info.latestVersion}.apk"
                                )
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.update_download_started),
                                    Toast.LENGTH_SHORT
                                ).show()
                                true
                            }

                            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.update_dialog_title))
                                .setMessage(getString(R.string.update_dialog_message,
                                    info.latestVersion, info.releaseNotes ?: ""))
                                .setPositiveButton(getString(R.string.update_dialog_install)) { _, _ ->
                                    UpdateManager.downloadAndInstall(
                                        requireContext(),
                                        info.downloadUrl,
                                        "fritzcalllog-${info.latestVersion}.apk"
                                    )
                                }
                                .setNegativeButton(getString(R.string.update_dialog_later), null)
                                .show()
                        } else {
                            updatePref.summary   = getString(R.string.update_up_to_date)
                            updatePref.isEnabled = false
                        }
                    }
                    .onFailure {
                        if (!isResumed) return@onFailure
                        updatePref.summary   = getString(R.string.update_error)
                        updatePref.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.update_check_failed, it.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }

        // ── Notification permission request ───────────────────────────────────

        private fun requestNotificationPermissionIfNeeded() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (requireContext().checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private fun scheduleWidgetRefresh() {
            lifecycleScope.launch {
                delay(300)
                // A settings change (credentials, host, filters, ...) can
                // invalidate the cache, so this needs an actual fresh fetch —
                // not just a cache re-render (that's what triggerRefresh()
                // means now: it's reserved for WidgetRefreshWorker's
                // render-only post-fetch broadcast). forceRefreshNow() runs
                // the fetch via WorkManager, same as any other trigger.
                WidgetScheduler.forceRefreshNow(requireContext())
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

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        /**
         * Delegates to [FritzCallApplication.applyTheme] which calls
         * AppCompatDelegate.setDefaultNightMode(). Kept here so existing
         * call-sites in this file don't need to be changed.
         */
        fun applyTheme(theme: String) {
            FritzCallApplication.applyTheme(theme)
        }

        fun wrapLocale(context: Context, lang: String): Context {
            if (lang == "system" || lang.isBlank()) return context
            // Locale.of(String) ist ab API 35 (BAKLAVA / Android 15) verfügbar.
            // Auf älteren Geräten (API 26–34) wird Locale.forLanguageTag() genutzt,
            // das IETF-BCP-47-Tags (z. B. "de", "fr") korrekt verarbeitet.
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
