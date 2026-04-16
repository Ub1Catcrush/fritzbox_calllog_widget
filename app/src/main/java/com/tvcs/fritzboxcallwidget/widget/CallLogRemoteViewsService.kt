package com.tvcs.fritzboxcallwidget.widget

import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.tvcs.fritzboxcallwidget.R
import com.tvcs.fritzboxcallwidget.model.CallEntry
import com.tvcs.fritzboxcallwidget.model.CallType
import java.time.format.DateTimeFormatter

class CallLogRemoteViewsService : RemoteViewsService() {

    companion object {
        @Volatile private var cachedCalls: List<CallEntry> = emptyList()
        @Volatile var colors: WidgetColors = WidgetColors(
            0xFF1565C0.toInt(), 0xFFFFFFFF.toInt(),
            0xFFE3F2FD.toInt(), 0xFF1565C0.toInt(),
            0xF0FFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFF5F5F5.toInt(),
            0xFF212121.toInt(), 0xFF757575.toInt(), 0xFFBDBDBD.toInt(), 0xFFD32F2F.toInt()
        )
        @Volatile var fontSizeSp: Float = 11f
        @Volatile var showDuration: Boolean = false

        fun update(calls: List<CallEntry>, c: WidgetColors, size: Float, duration: Boolean = false) {
            cachedCalls    = calls
            colors         = c
            fontSizeSp     = size
            showDuration   = duration
        }
        fun getCalls() = cachedCalls
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = CallLogViewsFactory()
}

private class CallLogViewsFactory : RemoteViewsService.RemoteViewsFactory {

    private val dateFmt      = DateTimeFormatter.ofPattern("dd.MM.")
    private val timeFmt      = DateTimeFormatter.ofPattern("HH:mm")
    private var calls        = listOf<CallEntry>()
    private var colors       = CallLogRemoteViewsService.colors
    private var fontSize     = CallLogRemoteViewsService.fontSizeSp
    private var showDuration = CallLogRemoteViewsService.showDuration

    override fun onCreate()         { refresh() }
    override fun onDataSetChanged() { refresh() }
    override fun onDestroy()        {}

    private fun refresh() {
        calls        = CallLogRemoteViewsService.getCalls()
        colors       = CallLogRemoteViewsService.colors
        fontSize     = CallLogRemoteViewsService.fontSizeSp
        showDuration = CallLogRemoteViewsService.showDuration
    }

    override fun getCount()          = calls.size
    override fun getViewTypeCount()  = 1
    override fun hasStableIds()      = true
    override fun getItemId(pos: Int) = pos.toLong()
    override fun getLoadingView()    = null

    override fun getViewAt(position: Int): RemoteViews {
        val pkg = "com.tvcs.fritzboxcallwidget"
        if (position >= calls.size) return RemoteViews(pkg, R.layout.widget_call_row)

        val entry = calls[position]
        val views = RemoteViews(pkg, R.layout.widget_call_row)

        // Text content
        views.setTextViewText(R.id.tv_date, entry.date.format(dateFmt))
        views.setTextViewText(R.id.tv_time, entry.date.format(timeFmt))
        views.setTextViewText(R.id.tv_name, entry.displayName)

        // Duration column: show as own narrow column when enabled
        if (showDuration && entry.duration > 0) {
            val mins = entry.duration / 60
            val secs = entry.duration % 60
            views.setTextViewText(R.id.tv_duration, "%d:%02d".format(mins, secs))
            views.setViewVisibility(R.id.tv_duration, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.tv_duration, android.view.View.GONE)
        }

        // Text colors
        views.setTextColor(R.id.tv_date, colors.textPrimary)
        views.setTextColor(R.id.tv_time, colors.textSecondary)
        views.setTextColor(R.id.tv_name, colors.textPrimary)
        views.setTextColor(R.id.tv_duration, colors.textSecondary)

        // Font size — setTextViewTextSize is officially supported by RemoteViews
        views.setTextViewTextSize(R.id.tv_date, TypedValue.COMPLEX_UNIT_SP, fontSize)
        views.setTextViewTextSize(R.id.tv_time, TypedValue.COMPLEX_UNIT_SP, fontSize)
        views.setTextViewTextSize(R.id.tv_name, TypedValue.COMPLEX_UNIT_SP, fontSize)
        views.setTextViewTextSize(R.id.tv_duration, TypedValue.COMPLEX_UNIT_SP, fontSize)

        // NOTE: setTypeface via setInt reflection is intentionally omitted —
        // RemoteViews does not support it and throws ActionException at runtime,
        // which causes "Couldn't add widget".

        // Call type icon
        val iconRes = when (entry.type) {
            CallType.INCOMING         -> R.drawable.ic_call_incoming
            CallType.OUTGOING         -> R.drawable.ic_call_outgoing
            CallType.MISSED           -> R.drawable.ic_call_missed
            CallType.BLOCKED          -> R.drawable.ic_call_blocked
            CallType.VOICEMAIL        -> R.drawable.ic_call_voicemail
            CallType.FAX_RECEIVED     -> R.drawable.ic_call_fax_received
            CallType.FAX_SENT         -> R.drawable.ic_call_fax_sent
            CallType.ACTIVE_INCOMING  -> R.drawable.ic_call_active_incoming
            CallType.ACTIVE_OUTGOING  -> R.drawable.ic_call_active_outgoing
        }
        views.setImageViewResource(R.id.iv_call_type, iconRes)

        // Alternating row background
        val bgColor = if (position % 2 == 0) colors.rowEven else colors.rowOdd
        views.setInt(R.id.row_root, "setBackgroundColor", bgColor)

        // Fill-in intent carries the phone number to DialActivity
        views.setOnClickFillInIntent(
            R.id.row_root,
            Intent().apply { putExtra(DialActivity.EXTRA_NUMBER, entry.number) }
        )

        return views
    }
}
