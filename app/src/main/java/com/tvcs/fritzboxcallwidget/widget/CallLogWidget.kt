package com.tvcs.fritzboxcallwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tvcs.fritzboxcallwidget.R
import com.tvcs.fritzboxcallwidget.api.CallRepository
import com.tvcs.fritzboxcallwidget.model.CallEntry
import com.tvcs.fritzboxcallwidget.model.CallType
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import com.tvcs.fritzboxcallwidget.prefs.SettingsActivity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CallLogWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH     = "com.tvcs.fritzboxcallwidget.ACTION_REFRESH"
        const val ACTION_NEXT_FILTER = "com.tvcs.fritzboxcallwidget.ACTION_NEXT_FILTER"

        /**
         * Marks an ACTION_REFRESH broadcast as "render only, do not fetch".
         * Set by [triggerRefresh], which [WidgetRefreshWorker] calls after it
         * has *already* performed the network fetch. Without this flag,
         * onReceive() would have no way to tell "the worker just finished,
         * just show the result" apart from "the user tapped refresh, please
         * fetch fresh data" — and mistakenly doing a second live fetch here
         * is exactly what caused real Android ANR dialogs ("App reagiert
         * nicht"): this class is a manifest BroadcastReceiver, and unlike a
         * WorkManager Worker it IS subject to the ~10s broadcast ANR
         * watchdog. This receiver must never perform network I/O itself —
         * only ever render from cache, synchronously and fast, and delegate
         * any actual fetch to WorkManager via [WidgetScheduler.forceRefreshNow].
         */
        private const val EXTRA_RENDER_ONLY   = "render_only"
        private const val EXTRA_ERROR_MESSAGE = "error_message"

        /** Cycling order for the header filter button. */
        private val FILTER_CYCLE = listOf(
            "all", "missed", "incoming", "outgoing", "blocked", "voicemail", "fax"
        )

        private const val TAG = "CallLogWidget"

        /**
         * Called by [WidgetRefreshWorker] after it finishes its own fetch
         * (success or failure) purely to have the widget re-render the
         * result. [errorMessage] is passed along so the render-only path can
         * show the right state without needing to touch the network itself.
         */
        fun triggerRefresh(context: Context, errorMessage: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CallLogWidget::class.java))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(Intent(context, CallLogWidget::class.java).apply {
                    action = ACTION_REFRESH
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    putExtra(EXTRA_RENDER_ONLY, true)
                    errorMessage?.let { putExtra(EXTRA_ERROR_MESSAGE, it) }
                })
            }
        }

        fun mutableFlags() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // ForegroundService-Start hier ist sicher: onUpdate() wird vom System
        // aus einem privilegierten Kontext aufgerufen (Widget-Update vom Launcher),
        // der als Vordergrundkontext gilt. start() fängt ForegroundServiceStart-
        // NotAllowedException intern ab falls es doch blockiert wird.
        WidgetForegroundService.start(context)
        // Scheduler hier (neu-)starten: onEnabled() feuert nur beim allerersten
        // Widget-Hinzufügen. Nach App-Updates, Neuinstallationen oder Geräteneustart
        // (falls BootReceiver nicht greift) sorgt onUpdate() dafür dass die
        // Alarm-Kette wieder anläuft.
        WidgetScheduler.scheduleExactAlarm(context)

        // Render whatever is cached right away — fast and synchronous, no
        // network I/O. If there's nothing cached yet, show loading and hand
        // the actual fetch off to WorkManager (WidgetRefreshWorker), which,
        // unlike this BroadcastReceiver, isn't bound by Android's ~10s
        // broadcast ANR watchdog. This method must never block on network
        // I/O itself — see EXTRA_RENDER_ONLY doc comment above for why.
        val rendered = renderFromCache(context, manager, ids)
        if (!rendered) {
            for (id in ids) showLoading(context, manager, id)
            WidgetScheduler.forceRefreshNow(context)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        val rendered = renderFromCache(context, manager, intArrayOf(id))
        if (!rendered) {
            showLoading(context, manager, id)
            WidgetScheduler.forceRefreshNow(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val manager = AppWidgetManager.getInstance(context)
        when (intent.action) {
            ACTION_REFRESH -> {
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: manager.getAppWidgetIds(ComponentName(context, CallLogWidget::class.java))
                val renderOnly    = intent.getBooleanExtra(EXTRA_RENDER_ONLY, false)
                val errorMessage  = intent.getStringExtra(EXTRA_ERROR_MESSAGE)
                if (renderOnly) {
                    // WidgetRefreshWorker already performed the fetch — just
                    // show its result from cache. Never fetch again here:
                    // this handler must stay fast and synchronous. Unlike a
                    // WorkManager Worker, a manifest BroadcastReceiver IS
                    // subject to Android's ANR watchdog, and a live network
                    // fetch here (which used to happen) is exactly what
                    // caused real "App reagiert nicht" dialogs.
                    Log.d(TAG, "ACTION_REFRESH (render-only) — rendering from cache")
                    renderFromCache(context, manager, ids, errorMessage)
                } else {
                    // Manual refresh tap (or any other ACTION_REFRESH sender
                    // that isn't the worker): show loading immediately, then
                    // hand the actual fetch off to WorkManager. This method
                    // returns fast either way — no network I/O happens here.
                    Log.d(TAG, "ACTION_REFRESH (fetch requested) — delegating to WorkManager")
                    for (id in ids) showLoading(context, manager, id)
                    WidgetScheduler.forceRefreshNow(context)
                }
            }
            ACTION_NEXT_FILTER -> {
                // Cycle to next filter value and refresh display from cache
                val prefs   = AppPreferences(context)
                val current = prefs.callFilter
                val next    = FILTER_CYCLE[(FILTER_CYCLE.indexOf(current) + 1) % FILTER_CYCLE.size]
                prefs.callFilter = next
                val ids = manager.getAppWidgetIds(ComponentName(context, CallLogWidget::class.java))
                val rendered = renderFromCache(context, manager, ids)
                if (!rendered) {
                    for (id in ids) showLoading(context, manager, id)
                    WidgetScheduler.forceRefreshNow(context)
                }
            }
        }
    }

    override fun onEnabled(context: Context)  {
        WidgetScheduler.schedule(context)
        MissedCallWorker.createNotificationChannel(context)
    }
    override fun onDisabled(context: Context) {
        WidgetScheduler.cancel(context)
        MissedCallWorker.cancel(context)
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private sealed class State {
        object Loading : State()
        data class Error(val message: String) : State()
        data class Success(val calls: List<CallEntry>,
                           val updatedAt: LocalDateTime = LocalDateTime.now()) : State()
        data class SuccessWithError(val calls: List<CallEntry>, val errorMsg: String,
                                    val updatedAt: LocalDateTime = LocalDateTime.now()) : State()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun applyFilter(entries: List<CallEntry>, prefs: AppPreferences): List<CallEntry> =
        prefs.activeCallTypeFilter()
            ?.let { allowed -> entries.filter { it.type in allowed } }
            ?: entries

    // ── Render (cache-only, synchronous, no network I/O) ────────────────────────

    /**
     * Renders the widget(s) purely from whatever is already cached in
     * [CallRepository], plus an optional [errorMessage] to overlay (used by
     * the worker's render-only ACTION_REFRESH broadcast to convey a failed
     * fetch without this receiver having to touch the network itself).
     *
     * This function is intentionally synchronous and fast — no coroutines,
     * no network I/O, no goAsync(). That is the whole point: this class is a
     * manifest BroadcastReceiver, and unlike a WorkManager Worker it IS
     * subject to Android's broadcast ANR watchdog. Any actual fetch must be
     * delegated to WorkManager via [WidgetScheduler.forceRefreshNow] instead.
     *
     * @return `false` if there was nothing to render (no cache and no error
     *   — e.g. the very first time the widget is placed), so the caller
     *   knows to show a loading placeholder and kick off a fetch.
     */
    private fun renderFromCache(
        context: Context, manager: AppWidgetManager, ids: IntArray, errorMessage: String? = null
    ): Boolean {
        val prefs  = AppPreferences(context)
        val repo   = CallRepository(prefs)
        val cached = repo.getCachedEntries()
        if (cached == null && errorMessage == null) return false

        val state: State = if (cached != null) {
            val filtered = applyFilter(cached, prefs).take(prefs.maxEntries)
            if (errorMessage != null) State.SuccessWithError(filtered, errorMessage)
            else State.Success(filtered)
        } else {
            State.Error(errorMessage!!)
        }
        for (id in ids) updateWidget(context, manager, id, state, prefs)
        return true
    }

    private fun showLoading(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_call_log)
        views.setViewVisibility(R.id.widget_loading,   View.VISIBLE)
        views.setViewVisibility(R.id.tv_error,         View.GONE)
        views.setViewVisibility(R.id.list_calls,       View.GONE)
        views.setViewVisibility(R.id.tv_empty,         View.GONE)
        views.setViewVisibility(R.id.tv_error_overlay, View.GONE)
        views.setViewVisibility(R.id.tv_last_updated,  View.GONE)
        manager.updateAppWidget(id, views)
    }

    // ── Build RemoteViews ─────────────────────────────────────────────────────

    private fun updateWidget(
        context: Context, manager: AppWidgetManager, id: Int,
        state: State, prefs: AppPreferences = AppPreferences(context)
    ) {
        val colors = prefs.resolvedColors(context)
        val ctx    = SettingsActivity.wrapLocale(context, prefs.language)
        val views  = RemoteViews(context.packageName, R.layout.widget_call_log)

        // Background and header colours
        views.setInt(R.id.widget_root,    "setBackgroundColor", colors.widgetBg)
        views.setInt(R.id.header_row,     "setBackgroundColor", colors.headerBg)
        views.setInt(R.id.col_header_row, "setBackgroundColor", colors.colHeaderBg)

        // Header texts
        views.setTextColor(R.id.tv_widget_title,    colors.headerText)
        views.setTextColor(R.id.tv_col_date,        colors.colHeaderText)
        views.setTextColor(R.id.tv_col_time,        colors.colHeaderText)
        views.setTextColor(R.id.tv_col_name,        colors.colHeaderText)
        views.setTextColor(R.id.tv_col_duration,    colors.colHeaderText)
        views.setViewVisibility(R.id.tv_col_duration,
            if (prefs.showDuration) View.VISIBLE else View.GONE)

        // Header button icon tint — must be set programmatically because
        // android:tint in XML is ignored by RemoteViews on API < 31, and
        // ?attr/colorControlNormal cannot be resolved in widget context.
        // setColorFilter(color, PorterDuff.Mode.SRC_IN) works on all APIs.
        views.setInt(R.id.btn_refresh,  "setColorFilter", colors.headerText)
        views.setInt(R.id.btn_settings, "setColorFilter", colors.headerText)

        // Column header text (use filter label when not "all")
        val filterLabel = filterLabel(ctx, prefs.callFilter)
        val titleText   = if (prefs.callFilter == "all")
            ctx.getString(R.string.widget_title)
        else
            "${ctx.getString(R.string.widget_title)} · $filterLabel"
        views.setTextViewText(R.id.tv_widget_title, titleText)
        views.setTextViewText(R.id.tv_col_date, ctx.getString(R.string.col_date))
        views.setTextViewText(R.id.tv_col_time, ctx.getString(R.string.col_time))
        views.setTextViewText(R.id.tv_col_name, ctx.getString(R.string.col_name))

        // Header buttons
        views.setOnClickPendingIntent(R.id.btn_refresh,
            PendingIntent.getBroadcast(context, 0,
                Intent(context, CallLogWidget::class.java).apply { action = ACTION_REFRESH },
                mutableFlags()))
        views.setOnClickPendingIntent(R.id.btn_settings,
            PendingIntent.getActivity(context, 1,
                Intent(context, SettingsActivity::class.java), mutableFlags()))
        // Filter cycle button (tap widget title to cycle)
        views.setOnClickPendingIntent(R.id.tv_widget_title,
            PendingIntent.getBroadcast(context, 3,
                Intent(context, CallLogWidget::class.java).apply { action = ACTION_NEXT_FILTER },
                mutableFlags()))

        // "Last updated" timestamp (optional)
        val showTs = prefs.showLastUpdated
        views.setViewVisibility(R.id.tv_last_updated,
            if (showTs && state is State.Success) View.VISIBLE else View.GONE)
        if (showTs && state is State.Success) {
            val ts = state.updatedAt.format(DateTimeFormatter.ofPattern("HH:mm"))
            views.setTextViewText(R.id.tv_last_updated,
                ctx.getString(R.string.last_updated, ts))
            views.setTextColor(R.id.tv_last_updated, colors.textSecondary)
        }

        when (state) {
            is State.Loading -> {
                views.setViewVisibility(R.id.widget_loading,   View.VISIBLE)
                views.setViewVisibility(R.id.tv_error,         View.GONE)
                views.setViewVisibility(R.id.list_calls,       View.GONE)
                views.setViewVisibility(R.id.tv_empty,         View.GONE)
                views.setViewVisibility(R.id.tv_error_overlay, View.GONE)
                views.setTextViewText(R.id.widget_loading, ctx.getString(R.string.loading))
                views.setTextColor(R.id.widget_loading, colors.textSecondary)
            }
            is State.Error -> {
                views.setViewVisibility(R.id.widget_loading,   View.GONE)
                views.setViewVisibility(R.id.tv_error,         View.VISIBLE)
                views.setViewVisibility(R.id.list_calls,       View.GONE)
                views.setViewVisibility(R.id.tv_empty,         View.GONE)
                views.setViewVisibility(R.id.tv_error_overlay, View.GONE)
                views.setTextViewText(R.id.tv_error,
                    ctx.getString(R.string.error_loading, state.message))
                views.setTextColor(R.id.tv_error, colors.error)
            }
            is State.Success -> {
                val hasCalls = state.calls.isNotEmpty()
                views.setViewVisibility(R.id.widget_loading,   View.GONE)
                views.setViewVisibility(R.id.tv_error,         View.GONE)
                views.setViewVisibility(R.id.list_calls,       if (hasCalls) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.tv_empty,         if (hasCalls) View.GONE   else View.VISIBLE)
                views.setViewVisibility(R.id.tv_error_overlay, View.GONE)
                views.setTextViewText(R.id.tv_empty, ctx.getString(R.string.no_calls))
                views.setTextColor(R.id.tv_empty, colors.textSecondary)
                if (hasCalls) bindListAdapter(context, views, state.calls, colors, prefs)
            }
            is State.SuccessWithError -> {
                val hasCalls = state.calls.isNotEmpty()
                views.setViewVisibility(R.id.widget_loading,   View.GONE)
                views.setViewVisibility(R.id.tv_error,         View.GONE)
                views.setViewVisibility(R.id.list_calls,       if (hasCalls) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.tv_empty,         if (hasCalls) View.GONE   else View.VISIBLE)
                views.setViewVisibility(R.id.tv_error_overlay, View.VISIBLE)
                views.setTextViewText(R.id.tv_empty, ctx.getString(R.string.no_calls))
                views.setTextColor(R.id.tv_empty, colors.textSecondary)
                views.setTextViewText(R.id.tv_error_overlay, "⚠ ${state.errorMsg}")
                views.setInt(R.id.tv_error_overlay, "setBackgroundColor", 0xCC8B0000.toInt())
                views.setTextColor(R.id.tv_error_overlay, 0xFFFFCCCC.toInt())
                if (hasCalls) bindListAdapter(context, views, state.calls, colors, prefs)
            }
        }

        manager.updateAppWidget(id, views)
    }

    private fun filterLabel(ctx: Context, filter: String): String = when (filter) {
        "missed"    -> ctx.getString(R.string.call_type_missed)
        "incoming"  -> ctx.getString(R.string.call_type_incoming)
        "outgoing"  -> ctx.getString(R.string.call_type_outgoing)
        "blocked"   -> ctx.getString(R.string.call_type_blocked)
        "voicemail" -> ctx.getString(R.string.call_type_voicemail)
        "fax"       -> ctx.getString(R.string.call_type_fax_received)
        else        -> ""
    }

    private fun bindListAdapter(
        context: Context, views: RemoteViews, calls: List<CallEntry>,
        colors: WidgetColors, prefs: AppPreferences
    ) {
        views.setPendingIntentTemplate(
            R.id.list_calls,
            PendingIntent.getActivity(context, 2,
                Intent(context, DialActivity::class.java), mutableFlags()))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setRemoteAdapter(R.id.list_calls,
                buildCollectionItems(calls, colors, prefs.fontSizeSp, prefs.showDuration))
        } else {
            CallLogRemoteViewsService.update(calls, colors, prefs.fontSizeSp, prefs.showDuration)
            val svcIntent = Intent(context, CallLogRemoteViewsService::class.java).apply {
                data = Uri.parse("fritz://calllog?t=${System.currentTimeMillis()}")
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.list_calls, svcIntent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildCollectionItems(
        calls: List<CallEntry>, colors: WidgetColors,
        fontSizeSp: Float, showDuration: Boolean
    ): RemoteViews.RemoteCollectionItems {
        val pkg     = "com.tvcs.fritzboxcallwidget"
        val dateFmt = DateTimeFormatter.ofPattern("dd.MM.")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val builder = RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(true).setViewTypeCount(1)

        calls.forEachIndexed { index, entry ->
            val row = RemoteViews(pkg, R.layout.widget_call_row)
            row.setTextViewText(R.id.tv_date, entry.date.format(dateFmt))
            row.setTextViewText(R.id.tv_time, entry.date.format(timeFmt))
            row.setTextViewText(R.id.tv_name, entry.displayName)

            // Duration column: show as own narrow column when enabled
            if (showDuration && entry.duration > 0) {
                val mins = entry.duration / 60
                val secs = entry.duration % 60
                row.setTextViewText(R.id.tv_duration, "%d:%02d".format(mins, secs))
                row.setViewVisibility(R.id.tv_duration, View.VISIBLE)
            } else {
                row.setViewVisibility(R.id.tv_duration, View.GONE)
            }

            row.setTextColor(R.id.tv_date, colors.textPrimary)
            row.setTextColor(R.id.tv_time, colors.textSecondary)
            row.setTextColor(R.id.tv_name, colors.textPrimary)
            row.setTextColor(R.id.tv_duration, colors.textSecondary)
            row.setTextViewTextSize(R.id.tv_date, TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            row.setTextViewTextSize(R.id.tv_time, TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            row.setTextViewTextSize(R.id.tv_name, TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            row.setTextViewTextSize(R.id.tv_duration, TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            row.setImageViewResource(R.id.iv_call_type, when (entry.type) {
                CallType.INCOMING         -> R.drawable.ic_call_incoming
                CallType.OUTGOING         -> R.drawable.ic_call_outgoing
                CallType.MISSED           -> R.drawable.ic_call_missed
                CallType.BLOCKED          -> R.drawable.ic_call_blocked
                CallType.VOICEMAIL        -> R.drawable.ic_call_voicemail
                CallType.FAX_RECEIVED     -> R.drawable.ic_call_fax_received
                CallType.FAX_SENT         -> R.drawable.ic_call_fax_sent
                CallType.ACTIVE_INCOMING  -> R.drawable.ic_call_active_incoming
                CallType.ACTIVE_OUTGOING  -> R.drawable.ic_call_active_outgoing
            })
            row.setInt(R.id.row_root, "setBackgroundColor",
                if (index % 2 == 0) colors.rowEven else colors.rowOdd)
            row.setOnClickFillInIntent(R.id.row_root,
                Intent().apply { putExtra(DialActivity.EXTRA_NUMBER, entry.number) })
            builder.addItem(index.toLong(), row)
        }
        return builder.build()
    }
}

data class WidgetColors(
    val headerBg: Int, val headerText: Int,
    val colHeaderBg: Int, val colHeaderText: Int,
    val widgetBg: Int, val rowEven: Int, val rowOdd: Int,
    val textPrimary: Int, val textSecondary: Int,
    val divider: Int, val error: Int
)
