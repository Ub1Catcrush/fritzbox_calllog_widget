package com.tvcs.fritzboxcallwidget.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import com.tvcs.fritzboxcallwidget.api.ConnectionProfile
import com.tvcs.fritzboxcallwidget.api.profilesFromJsonString
import com.tvcs.fritzboxcallwidget.api.toJsonString
import com.tvcs.fritzboxcallwidget.model.CallType
import com.tvcs.fritzboxcallwidget.widget.WidgetColors

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    // ── Credentials ────────────────────────────────────────────────────────────
    var fritzUsername: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_USERNAME, v).apply()

    var fritzPassword: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    // ── Connection profiles ────────────────────────────────────────────────────
    fun getOrderedProfiles(): List<ConnectionProfile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return ConnectionProfile.defaults()
        return profilesFromJsonString(json)
    }
    fun saveOrderedProfiles(profiles: List<ConnectionProfile>) {
        prefs.edit().putString(KEY_PROFILES, profiles.toJsonString()).apply()
    }
    fun updateProfile(index: Int, updated: ConnectionProfile) {
        val list = getOrderedProfiles().toMutableList()
        if (index in list.indices) { list[index] = updated; saveOrderedProfiles(list) }
    }

    // ── Refresh / data ─────────────────────────────────────────────────────────
    var refreshIntervalSeconds: Int
        get() = prefs.getString(KEY_REFRESH, "300")?.toIntOrNull() ?: 300
        set(v) = prefs.edit().putString(KEY_REFRESH, v.toString()).apply()

    var phonePrefix: String
        get() = prefs.getString(KEY_PHONE_PREFIX, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PHONE_PREFIX, v).apply()

    /**
     * Local area code (Ortsvorwahl) prepended to numbers that start with a
     * digit other than '0' and do not already begin with '+' or '00'.
     * Applied before [phonePrefix]. Example: "0621" + "12345" → "062112345".
     */
    var localAreaCode: String
        get() = prefs.getString(KEY_LOCAL_AREA_CODE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LOCAL_AREA_CODE, v).apply()

    var maxEntries: Int
        get() = prefs.getString(KEY_MAX_ENTRIES, "20")?.toIntOrNull() ?: 20
        set(v) = prefs.edit().putString(KEY_MAX_ENTRIES, v.toString()).apply()

    // ── Display options ────────────────────────────────────────────────────────
    var theme: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        set(v) = prefs.edit().putString(KEY_LANGUAGE, v).apply()

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "default") ?: "default"
        set(v) = prefs.edit().putString(KEY_FONT_FAMILY, v).apply()

    var fontSizeSp: Float
        get() = prefs.getString(KEY_FONT_SIZE, "11")?.toFloatOrNull() ?: 11f
        set(v) = prefs.edit().putString(KEY_FONT_SIZE, v.toString()).apply()

    // ── Optional features (all off by default to not require extra permissions) ─

    /** Show call duration in the widget row (optional 5th data point). */
    var showDuration: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DURATION, false)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_DURATION, v).apply()

    /** Show a "last updated" timestamp in the widget header. */
    var showLastUpdated: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LAST_UPDATED, false)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_LAST_UPDATED, v).apply()

    /**
     * Active call type filter. Empty string = show all.
     * Values: "all", "missed", "incoming", "outgoing", "blocked", "voicemail", "fax"
     */
    var callFilter: String
        get() = prefs.getString(KEY_CALL_FILTER, "all") ?: "all"
        set(v) = prefs.edit().putString(KEY_CALL_FILTER, v).apply()

    /**
     * Click-to-Dial via FritzBox TR-064 (X_AVM-DE_DialNumber).
     * When enabled, tapping a row initiates the call through the FritzBox
     * rather than opening the local phone app.
     */
    var clickToDialEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLICK_TO_DIAL, false)
        set(v) = prefs.edit().putBoolean(KEY_CLICK_TO_DIAL, v).apply()

    /**
     * Phone number to use as the "from" extension for Click-to-Dial.
     * If empty, FritzBox uses its default outgoing line.
     */
    var clickToDialExtension: String
        get() = prefs.getString(KEY_CLICK_TO_DIAL_EXT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CLICK_TO_DIAL_EXT, v).apply()

    /**
     * Phonebook lookup: enrich unknown numbers with names from the FritzBox phonebook.
     * Requires an additional TR-064 call on first use; results are cached.
     */
    var phonebookLookupEnabled: Boolean
        get() = prefs.getBoolean(KEY_PHONEBOOK_LOOKUP, false)
        set(v) = prefs.edit().putBoolean(KEY_PHONEBOOK_LOOKUP, v).apply()

    /**
     * Missed call notifications via WorkManager background check.
     * Requires POST_NOTIFICATIONS permission on Android 13+.
     */
    var missedCallNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_MISSED_NOTIFICATIONS, false)
        set(v) = prefs.edit().putBoolean(KEY_MISSED_NOTIFICATIONS, v).apply()

    /** Last seen call ID — used by notification worker to detect new missed calls. */
    var lastSeenCallId: String
        get() = prefs.getString(KEY_LAST_SEEN_CALL_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_SEEN_CALL_ID, v).apply()

    // ── Call type filter helper ────────────────────────────────────────────────

    /** Returns the set of CallTypes that should be shown based on [callFilter]. */
    fun activeCallTypeFilter(): Set<CallType>? = when (callFilter) {
        "all"       -> null   // null = no filter, show everything
        "missed"    -> setOf(CallType.MISSED)
        "incoming"  -> setOf(CallType.INCOMING, CallType.VOICEMAIL,
                             CallType.FAX_RECEIVED, CallType.ACTIVE_INCOMING)
        "outgoing"  -> setOf(CallType.OUTGOING, CallType.FAX_SENT,
                             CallType.ACTIVE_OUTGOING)
        "blocked"   -> setOf(CallType.BLOCKED)
        "voicemail" -> setOf(CallType.VOICEMAIL)
        "fax"       -> setOf(CallType.FAX_RECEIVED, CallType.FAX_SENT)
        else        -> null
    }

    // ── Colors ─────────────────────────────────────────────────────────────────
    var lightHeaderBg: Int
        get() = prefs.getInt(KEY_LIGHT_HEADER_BG, DEFAULT_HEADER_BG)
        set(v) = prefs.edit().putInt(KEY_LIGHT_HEADER_BG, v).apply()
    var lightHeaderText: Int
        get() = prefs.getInt(KEY_LIGHT_HEADER_TEXT, DEFAULT_HEADER_TEXT)
        set(v) = prefs.edit().putInt(KEY_LIGHT_HEADER_TEXT, v).apply()
    var lightColHeaderBg: Int
        get() = prefs.getInt(KEY_LIGHT_COL_HEADER_BG, DEFAULT_COL_HEADER_BG)
        set(v) = prefs.edit().putInt(KEY_LIGHT_COL_HEADER_BG, v).apply()
    var lightColHeaderText: Int
        get() = prefs.getInt(KEY_LIGHT_COL_HEADER_TEXT, DEFAULT_COL_HEADER_TEXT)
        set(v) = prefs.edit().putInt(KEY_LIGHT_COL_HEADER_TEXT, v).apply()
    var lightWidgetBg: Int
        get() = prefs.getInt(KEY_LIGHT_WIDGET_BG, DEFAULT_WIDGET_BG)
        set(v) = prefs.edit().putInt(KEY_LIGHT_WIDGET_BG, v).apply()
    var lightRowEven: Int
        get() = prefs.getInt(KEY_LIGHT_ROW_EVEN, DEFAULT_ROW_EVEN)
        set(v) = prefs.edit().putInt(KEY_LIGHT_ROW_EVEN, v).apply()
    var lightRowOdd: Int
        get() = prefs.getInt(KEY_LIGHT_ROW_ODD, DEFAULT_ROW_ODD)
        set(v) = prefs.edit().putInt(KEY_LIGHT_ROW_ODD, v).apply()
    var lightTextPrimary: Int
        get() = prefs.getInt(KEY_LIGHT_TEXT_PRIMARY, DEFAULT_TEXT_PRIMARY)
        set(v) = prefs.edit().putInt(KEY_LIGHT_TEXT_PRIMARY, v).apply()
    var lightTextSecondary: Int
        get() = prefs.getInt(KEY_LIGHT_TEXT_SECONDARY, DEFAULT_TEXT_SECONDARY)
        set(v) = prefs.edit().putInt(KEY_LIGHT_TEXT_SECONDARY, v).apply()
    var lightDivider: Int
        get() = prefs.getInt(KEY_LIGHT_DIVIDER, DEFAULT_DIVIDER)
        set(v) = prefs.edit().putInt(KEY_LIGHT_DIVIDER, v).apply()
    var lightError: Int
        get() = prefs.getInt(KEY_LIGHT_ERROR, DEFAULT_ERROR)
        set(v) = prefs.edit().putInt(KEY_LIGHT_ERROR, v).apply()
    var darkHeaderBg: Int
        get() = prefs.getInt(KEY_DARK_HEADER_BG, DARK_HEADER_BG)
        set(v) = prefs.edit().putInt(KEY_DARK_HEADER_BG, v).apply()
    var darkHeaderText: Int
        get() = prefs.getInt(KEY_DARK_HEADER_TEXT, DARK_HEADER_TEXT)
        set(v) = prefs.edit().putInt(KEY_DARK_HEADER_TEXT, v).apply()
    var darkColHeaderBg: Int
        get() = prefs.getInt(KEY_DARK_COL_HEADER_BG, DARK_COL_HEADER_BG)
        set(v) = prefs.edit().putInt(KEY_DARK_COL_HEADER_BG, v).apply()
    var darkColHeaderText: Int
        get() = prefs.getInt(KEY_DARK_COL_HEADER_TEXT, DARK_COL_HEADER_TEXT)
        set(v) = prefs.edit().putInt(KEY_DARK_COL_HEADER_TEXT, v).apply()
    var darkWidgetBg: Int
        get() = prefs.getInt(KEY_DARK_WIDGET_BG, DARK_WIDGET_BG)
        set(v) = prefs.edit().putInt(KEY_DARK_WIDGET_BG, v).apply()
    var darkRowEven: Int
        get() = prefs.getInt(KEY_DARK_ROW_EVEN, DARK_ROW_EVEN)
        set(v) = prefs.edit().putInt(KEY_DARK_ROW_EVEN, v).apply()
    var darkRowOdd: Int
        get() = prefs.getInt(KEY_DARK_ROW_ODD, DARK_ROW_ODD)
        set(v) = prefs.edit().putInt(KEY_DARK_ROW_ODD, v).apply()
    var darkTextPrimary: Int
        get() = prefs.getInt(KEY_DARK_TEXT_PRIMARY, DARK_TEXT_PRIMARY)
        set(v) = prefs.edit().putInt(KEY_DARK_TEXT_PRIMARY, v).apply()
    var darkTextSecondary: Int
        get() = prefs.getInt(KEY_DARK_TEXT_SECONDARY, DARK_TEXT_SECONDARY)
        set(v) = prefs.edit().putInt(KEY_DARK_TEXT_SECONDARY, v).apply()
    var darkDivider: Int
        get() = prefs.getInt(KEY_DARK_DIVIDER, DARK_DIVIDER)
        set(v) = prefs.edit().putInt(KEY_DARK_DIVIDER, v).apply()
    var darkError: Int
        get() = prefs.getInt(KEY_DARK_ERROR, DARK_ERROR)
        set(v) = prefs.edit().putInt(KEY_DARK_ERROR, v).apply()

    fun resolvedColors(context: Context): WidgetColors = if (effectiveIsDark(context)) {
        WidgetColors(darkHeaderBg, darkHeaderText, darkColHeaderBg, darkColHeaderText,
            darkWidgetBg, darkRowEven, darkRowOdd, darkTextPrimary, darkTextSecondary,
            darkDivider, darkError)
    } else {
        WidgetColors(lightHeaderBg, lightHeaderText, lightColHeaderBg, lightColHeaderText,
            lightWidgetBg, lightRowEven, lightRowOdd, lightTextPrimary, lightTextSecondary,
            lightDivider, lightError)
    }

    fun effectiveIsDark(context: Context): Boolean = when (theme) {
        "dark"  -> true
        "light" -> false
        else    -> (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun resetColors() {
        prefs.edit().also { e -> ALL_COLOR_KEYS.forEach { e.remove(it) } }.apply()
    }

    companion object {
        const val KEY_PROFILES               = "pref_connection_profiles"
        const val KEY_USERNAME               = "pref_username"
        const val KEY_PASSWORD               = "pref_password"
        const val KEY_REFRESH                = "pref_refresh"
        const val KEY_PHONE_PREFIX           = "pref_phone_prefix"
        const val KEY_LOCAL_AREA_CODE        = "pref_local_area_code"
        const val KEY_MAX_ENTRIES            = "pref_max_entries"
        const val KEY_THEME                  = "pref_theme"
        const val KEY_LANGUAGE               = "pref_language"
        const val KEY_FONT_FAMILY            = "pref_font_family"
        const val KEY_FONT_SIZE              = "pref_font_size"
        // Optional features
        const val KEY_SHOW_DURATION          = "pref_show_duration"
        const val KEY_SHOW_LAST_UPDATED      = "pref_show_last_updated"
        const val KEY_CALL_FILTER            = "pref_call_filter"
        const val KEY_CLICK_TO_DIAL          = "pref_click_to_dial"
        const val KEY_CLICK_TO_DIAL_EXT      = "pref_click_to_dial_ext"
        const val KEY_PHONEBOOK_LOOKUP       = "pref_phonebook_lookup"
        const val KEY_MISSED_NOTIFICATIONS   = "pref_missed_notifications"
        const val KEY_LAST_SEEN_CALL_ID      = "pref_last_seen_call_id"

        const val KEY_LIGHT_HEADER_BG        = "pref_color_header_bg"
        const val KEY_LIGHT_HEADER_TEXT      = "pref_color_header_text"
        const val KEY_LIGHT_COL_HEADER_BG    = "pref_color_col_header_bg"
        const val KEY_LIGHT_COL_HEADER_TEXT  = "pref_color_col_header_text"
        const val KEY_LIGHT_WIDGET_BG        = "pref_color_widget_bg"
        const val KEY_LIGHT_ROW_EVEN         = "pref_color_row_even"
        const val KEY_LIGHT_ROW_ODD          = "pref_color_row_odd"
        const val KEY_LIGHT_TEXT_PRIMARY     = "pref_color_text_primary"
        const val KEY_LIGHT_TEXT_SECONDARY   = "pref_color_text_secondary"
        const val KEY_LIGHT_DIVIDER          = "pref_color_divider"
        const val KEY_LIGHT_ERROR            = "pref_color_error"
        const val KEY_DARK_HEADER_BG         = "pref_color_header_bg_dark"
        const val KEY_DARK_HEADER_TEXT       = "pref_color_header_text_dark"
        const val KEY_DARK_COL_HEADER_BG     = "pref_color_col_header_bg_dark"
        const val KEY_DARK_COL_HEADER_TEXT   = "pref_color_col_header_text_dark"
        const val KEY_DARK_WIDGET_BG         = "pref_color_widget_bg_dark"
        const val KEY_DARK_ROW_EVEN          = "pref_color_row_even_dark"
        const val KEY_DARK_ROW_ODD           = "pref_color_row_odd_dark"
        const val KEY_DARK_TEXT_PRIMARY      = "pref_color_text_primary_dark"
        const val KEY_DARK_TEXT_SECONDARY    = "pref_color_text_secondary_dark"
        const val KEY_DARK_DIVIDER           = "pref_color_divider_dark"
        const val KEY_DARK_ERROR             = "pref_color_error_dark"

        val ALL_COLOR_KEYS = listOf(
            KEY_LIGHT_HEADER_BG, KEY_LIGHT_HEADER_TEXT, KEY_LIGHT_COL_HEADER_BG,
            KEY_LIGHT_COL_HEADER_TEXT, KEY_LIGHT_WIDGET_BG, KEY_LIGHT_ROW_EVEN,
            KEY_LIGHT_ROW_ODD, KEY_LIGHT_TEXT_PRIMARY, KEY_LIGHT_TEXT_SECONDARY,
            KEY_LIGHT_DIVIDER, KEY_LIGHT_ERROR,
            KEY_DARK_HEADER_BG, KEY_DARK_HEADER_TEXT, KEY_DARK_COL_HEADER_BG,
            KEY_DARK_COL_HEADER_TEXT, KEY_DARK_WIDGET_BG, KEY_DARK_ROW_EVEN,
            KEY_DARK_ROW_ODD, KEY_DARK_TEXT_PRIMARY, KEY_DARK_TEXT_SECONDARY,
            KEY_DARK_DIVIDER, KEY_DARK_ERROR
        )

        val DEFAULT_HEADER_BG       = 0xFF1565C0.toInt()
        val DEFAULT_HEADER_TEXT     = 0xFFFFFFFF.toInt()
        val DEFAULT_COL_HEADER_BG   = 0xFFE3F2FD.toInt()
        val DEFAULT_COL_HEADER_TEXT = 0xFF1565C0.toInt()
        val DEFAULT_WIDGET_BG       = 0xF0FFFFFF.toInt()
        val DEFAULT_ROW_EVEN        = 0xFFFFFFFF.toInt()
        val DEFAULT_ROW_ODD         = 0xFFF5F5F5.toInt()
        val DEFAULT_TEXT_PRIMARY    = 0xFF212121.toInt()
        val DEFAULT_TEXT_SECONDARY  = 0xFF757575.toInt()
        val DEFAULT_DIVIDER         = 0xFFBDBDBD.toInt()
        val DEFAULT_ERROR           = 0xFFD32F2F.toInt()
        val DARK_HEADER_BG          = 0xFF0D47A1.toInt()
        val DARK_HEADER_TEXT        = 0xFFE3F2FD.toInt()
        val DARK_COL_HEADER_BG      = 0xFF1A237E.toInt()
        val DARK_COL_HEADER_TEXT    = 0xFF90CAF9.toInt()
        val DARK_WIDGET_BG          = 0xF0212121.toInt()
        val DARK_ROW_EVEN           = 0xFF212121.toInt()
        val DARK_ROW_ODD            = 0xFF2C2C2C.toInt()
        val DARK_TEXT_PRIMARY       = 0xFFE0E0E0.toInt()
        val DARK_TEXT_SECONDARY     = 0xFF9E9E9E.toInt()
        val DARK_DIVIDER            = 0xFF424242.toInt()
        val DARK_ERROR              = 0xFFEF9A9A.toInt()
    }
}
