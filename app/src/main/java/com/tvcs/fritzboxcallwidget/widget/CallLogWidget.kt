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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CallLogWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH     = "com.tvcs.fritzboxcallwidget.ACTION_REFRESH"
        const val ACTION_NEXT_FILTER = "com.tvcs.fritzboxcallwidget.ACTION_NEXT_FILTER"

        /** Cycling order for the header filter button. */
        private val FILTER_CYCLE = listOf(
            "all", "missed", "incoming", "outgoing", "blocked", "voicemail", "fax"
        )

        // Application-level scope for widget work that must outlive onReceive().
        // goAsync() below keeps the BroadcastReceiver process alive while the
        // coroutine is running, preventing the OS from killing the job mid-fetch.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun triggerRefresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CallLogWidget::class.java))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(Intent(context, CallLogWidget::class.java).apply {
                    action = ACTION_REFRESH
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
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
        WidgetForegroundService.start(context)
        for (id in ids) showLoading(context, manager, id)
        val pendingResult = goAsync()
        scope.launch {
            try { fetchAndUpdateSuspend(context, manager, ids) }
            finally { pendingResult.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        val prefs  = AppPreferences(context)
        val cached = CallRepository(prefs).getCachedEntries()
        if (cached != null) {
            val filtered = applyFilter(cached, prefs)
            updateWidget(context, manager, id,
                State.Success(filtered.take(prefs.maxEntries)), prefs)
        } else {
            val pendingResult = goAsync()
            scope.launch {
                try { fetchAndUpdateSuspend(context, manager, intArrayOf(id)) }
                finally { pendingResult.finish() }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val manager = AppWidgetManager.getInstance(context)
        when (intent.action) {
            ACTION_REFRESH -> {
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: manager.getAppWidgetIds(ComponentName(context, CallLogWidget::class.java))
                for (id in ids) showLoading(context, manager, id)
                // goAsync() tells Android to keep this process alive until
                // pendingResult.finish() is called — without it the OS may
                // kill the process before the coroutine fetch completes.
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        fetchAndUpdateSuspend(context, manager, ids)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_NEXT_FILTER -> {
                // Cycle to next filter value and refresh display from cache
                val prefs   = AppPreferences(context)
                val current = prefs.callFilter
                val next    = FILTER_CYCLE[(FILTER_CYCLE.indexOf(current) + 1) % FILTER_CYCLE.size]
                prefs.callFilter = next
                val ids = manager.getAppWidgetIds(ComponentName(context, CallLogWidget::class.java))
                val cached = CallRepository(prefs).getCachedEntries()
                if (cached != null) {
                    val filtered = applyFilter(cached, prefs)
                    for (id in ids)
                        updateWidget(context, manager, id,
                            State.Success(filtered.take(prefs.maxEntries)), prefs)
                } else {
                    val pr2 = goAsync()
                    scope.launch {
                        try { fetchAndUpdateSuspend(context, manager, ids) }
                        finally { pr2.finish() }
                    }
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

    // ── Fetch ─────────────────────────────────────────────────────────────────

    private suspend fun fetchAndUpdateSuspend(context: Context, manager: AppWidgetManager, ids: IntArray) {
        run {
            val prefs = AppPreferences(context)
            val repo  = CallRepository(prefs)

            // Show cached data immediately
            val cached = repo.getCachedEntries()
            if (cached != null) {
                val filtered = applyFilter(cached, prefs)
                val s = State.Success(filtered.take(prefs.maxEntries))
                for (id in ids) updateWidget(context, manager, id, s, prefs)
            }

            val result = repo.fetchCallLog(context)
            val state = result.fold(
                onSuccess = { calls ->
                    // Record the successful fetch time for staleness checks
                    prefs.lastSuccessfulRefreshMs = System.currentTimeMillis()
                    State.Success(calls.take(prefs.maxEntries))
                },
                onFailure = { error ->
                    val c = repo.getCachedEntries()
                    val filtered = c?.let { applyFilter(it, prefs) }
                    if (filtered != null)
                        State.SuccessWithError(filtered.take(prefs.maxEntries),
                                               error.message ?: "Fehler")
                    else
                        State.Error(error.message ?: "Unknown error")
                }
            )
            for (id in ids) updateWidget(context, manager, id, state, prefs)
        }
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
