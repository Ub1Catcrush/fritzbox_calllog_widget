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
import java.time.format.DateTimeFormatter

class CallLogWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.tvcs.fritzboxcallwidget.ACTION_REFRESH"

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) showLoading(context, manager, id)
        fetchAndUpdate(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        val cached = CallLogRemoteViewsService.getCalls()
        if (cached.isNotEmpty()) {
            val prefs = AppPreferences(context)
            updateWidget(context, manager, id, State.Success(cached.take(prefs.maxEntries)), prefs)
        } else {
            fetchAndUpdate(context, manager, intArrayOf(id))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: manager.getAppWidgetIds(ComponentName(context, CallLogWidget::class.java))
            for (id in ids) showLoading(context, manager, id)
            fetchAndUpdate(context, manager, ids)
        }
    }

    override fun onEnabled(context: Context)  { WidgetScheduler.schedule(context) }
    override fun onDisabled(context: Context) { WidgetScheduler.cancel(context) }

    // ── State ─────────────────────────────────────────────────────────────────

    private sealed class State {
        object Loading : State()
        data class Error(val message: String) : State()
        data class Success(val calls: List<CallEntry>) : State()
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────

    private fun fetchAndUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        scope.launch {
            val prefs = AppPreferences(context)
            val result = CallRepository(prefs).fetchCallLog()
            val state = result.fold(
                onSuccess = { State.Success(it.take(prefs.maxEntries)) },
                onFailure = { State.Error(it.message ?: "Unknown error") }
            )
            for (id in ids) updateWidget(context, manager, id, state, prefs)
        }
    }

    private fun showLoading(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_call_log)
        views.setViewVisibility(R.id.widget_loading, View.VISIBLE)
        views.setViewVisibility(R.id.tv_error,       View.GONE)
        views.setViewVisibility(R.id.list_calls,     View.GONE)
        views.setViewVisibility(R.id.tv_empty,       View.GONE)
        manager.updateAppWidget(id, views)
    }

    // ── Build RemoteViews ─────────────────────────────────────────────────────

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        state: State,
        prefs: AppPreferences = AppPreferences(context)
    ) {
        val colors = prefs.resolvedColors(context)
        val ctx    = SettingsActivity.wrapLocale(context, prefs.language)
        val views  = RemoteViews(context.packageName, R.layout.widget_call_log)

        // Header colors and localized text
        views.setInt(R.id.widget_root,    "setBackgroundColor", colors.widgetBg)
        views.setInt(R.id.header_row,     "setBackgroundColor", colors.headerBg)
        views.setInt(R.id.col_header_row, "setBackgroundColor", colors.colHeaderBg)
        views.setTextColor(R.id.tv_widget_title, colors.headerText)
        views.setTextColor(R.id.tv_col_date,     colors.colHeaderText)
        views.setTextColor(R.id.tv_col_time,     colors.colHeaderText)
        views.setTextColor(R.id.tv_col_name,     colors.colHeaderText)
        views.setTextViewText(R.id.tv_widget_title, ctx.getString(R.string.widget_title))
        views.setTextViewText(R.id.tv_col_date,     ctx.getString(R.string.col_date))
        views.setTextViewText(R.id.tv_col_time,     ctx.getString(R.string.col_time))
        views.setTextViewText(R.id.tv_col_name,     ctx.getString(R.string.col_name))

        // Header buttons
        views.setOnClickPendingIntent(R.id.btn_refresh,
            PendingIntent.getBroadcast(context, 0,
                Intent(context, CallLogWidget::class.java).apply { action = ACTION_REFRESH },
                mutableFlags()))
        views.setOnClickPendingIntent(R.id.btn_settings,
            PendingIntent.getActivity(context, 1,
                Intent(context, SettingsActivity::class.java),
                mutableFlags()))

        when (state) {
            is State.Loading -> {
                views.setViewVisibility(R.id.widget_loading, View.VISIBLE)
                views.setViewVisibility(R.id.tv_error,       View.GONE)
                views.setViewVisibility(R.id.list_calls,     View.GONE)
                views.setViewVisibility(R.id.tv_empty,       View.GONE)
                views.setTextViewText(R.id.widget_loading, ctx.getString(R.string.loading))
                views.setTextColor(R.id.widget_loading, colors.textSecondary)
            }

            is State.Error -> {
                views.setViewVisibility(R.id.widget_loading, View.GONE)
                views.setViewVisibility(R.id.tv_error,       View.VISIBLE)
                views.setViewVisibility(R.id.list_calls,     View.GONE)
                views.setViewVisibility(R.id.tv_empty,       View.GONE)
                views.setTextViewText(R.id.tv_error,
                    ctx.getString(R.string.error_loading, state.message))
                views.setTextColor(R.id.tv_error, colors.error)
            }

            is State.Success -> {
                val hasCalls = state.calls.isNotEmpty()
                views.setViewVisibility(R.id.widget_loading, View.GONE)
                views.setViewVisibility(R.id.tv_error,       View.GONE)
                views.setViewVisibility(R.id.list_calls,     if (hasCalls) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.tv_empty,       if (hasCalls) View.GONE   else View.VISIBLE)
                views.setTextViewText(R.id.tv_empty, ctx.getString(R.string.no_calls))
                views.setTextColor(R.id.tv_empty, colors.textSecondary)

                if (hasCalls) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // API 31+: RemoteCollectionItems does NOT support setOnClickPendingIntent
                        // per row — clicks must still use setPendingIntentTemplate + fill-in.
                        // The template targets DialActivity (explicit → MUTABLE allowed).
                        // Each row's fill-in supplies the tel: URI via FILL_IN_DATA.
                        views.setPendingIntentTemplate(
                            R.id.list_calls,
                            PendingIntent.getActivity(context, 2,
                                Intent(context, DialActivity::class.java), mutableFlags())
                        )
                        views.setRemoteAdapter(
                            R.id.list_calls,
                            buildCollectionItems(state.calls, colors, prefs.fontSizeSp)
                        )
                    } else {
                        // API 26–30: fall back to the service-based adapter.
                        CallLogRemoteViewsService.update(state.calls, colors, prefs.fontSizeSp)
                        val svcIntent = Intent(context, CallLogRemoteViewsService::class.java).apply {
                            data = Uri.parse("fritz://calllog?t=${System.currentTimeMillis()}")
                        }
                        @Suppress("DEPRECATION")
                        views.setRemoteAdapter(R.id.list_calls, svcIntent)

                        // Template intent: explicit → MUTABLE allowed on API 26–30
                        views.setPendingIntentTemplate(
                            R.id.list_calls,
                            PendingIntent.getActivity(context, 2,
                                Intent(context, DialActivity::class.java), mutableFlags())
                        )
                    }
                }
            }
        }

        // Standard updateAppWidget — no notifyAppWidgetViewDataChanged needed
        manager.updateAppWidget(id, views)
    }

    // ── RemoteCollectionItems builder (API 31+) ───────────────────────────────

    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildCollectionItems(
        calls: List<CallEntry>,
        colors: WidgetColors,
        fontSizeSp: Float
    ): RemoteViews.RemoteCollectionItems {
        val pkg     = "com.tvcs.fritzboxcallwidget"
        val dateFmt = DateTimeFormatter.ofPattern("dd.MM.")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        val builder = RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(true)
            .setViewTypeCount(1)

        calls.forEachIndexed { index, entry ->
            val row = RemoteViews(pkg, R.layout.widget_call_row)

            row.setTextViewText(R.id.tv_date, entry.date.format(dateFmt))
            row.setTextViewText(R.id.tv_time, entry.date.format(timeFmt))
            row.setTextViewText(R.id.tv_name, entry.displayName)

            row.setTextColor(R.id.tv_date, colors.textPrimary)
            row.setTextColor(R.id.tv_time, colors.textSecondary)
            row.setTextColor(R.id.tv_name, colors.textPrimary)

            row.setTextViewTextSize(R.id.tv_date, TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            row.setTextViewTextSize(R.id.tv_time, TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            row.setTextViewTextSize(R.id.tv_name, TypedValue.COMPLEX_UNIT_SP, fontSizeSp)

            val iconRes = when (entry.type) {
                CallType.INCOMING -> R.drawable.ic_call_incoming
                CallType.OUTGOING -> R.drawable.ic_call_outgoing
                CallType.MISSED   -> R.drawable.ic_call_missed
            }
            row.setImageViewResource(R.id.iv_call_type, iconRes)

            val bgColor = if (index % 2 == 0) colors.rowEven else colors.rowOdd
            row.setInt(R.id.row_root, "setBackgroundColor", bgColor)

            // RemoteCollectionItems rows must use setOnClickFillInIntent + template,
            // NOT setOnClickPendingIntent (which is silently ignored in collection rows).
            // The fill-in intent carries the tel: URI as data.
            // FILL_IN_DATA tells Android to overwrite the template's data with ours.
            // The fill-in intent carries the phone number as an extra.
            // Extras are always merged by Android's Intent.fillIn() regardless of flags,
            // so no special FILL_IN_* flag is needed.
            // DialActivity reads EXTRA_NUMBER and opens the system dialler.
            val fillIn = Intent().apply {
                putExtra(DialActivity.EXTRA_NUMBER, entry.number)
            }
            row.setOnClickFillInIntent(R.id.row_root, fillIn)

            builder.addItem(index.toLong(), row)
        }

        return builder.build()
    }
}

/** Resolved color set for a single widget render. */
data class WidgetColors(
    val headerBg: Int,
    val headerText: Int,
    val colHeaderBg: Int,
    val colHeaderText: Int,
    val widgetBg: Int,
    val rowEven: Int,
    val rowOdd: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val divider: Int,
    val error: Int
)
