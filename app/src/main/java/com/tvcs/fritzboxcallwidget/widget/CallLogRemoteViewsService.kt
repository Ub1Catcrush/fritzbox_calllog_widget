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

        fun update(calls: List<CallEntry>, c: WidgetColors, size: Float) {
            cachedCalls = calls
            colors      = c
            fontSizeSp  = size
        }
        fun getCalls() = cachedCalls
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = CallLogViewsFactory()
}

private class CallLogViewsFactory : RemoteViewsService.RemoteViewsFactory {

    private val dateFmt  = DateTimeFormatter.ofPattern("dd.MM.")
    private val timeFmt  = DateTimeFormatter.ofPattern("HH:mm")
    private var calls    = listOf<CallEntry>()
    private var colors   = CallLogRemoteViewsService.colors
    private var fontSize = CallLogRemoteViewsService.fontSizeSp

    override fun onCreate()         { refresh() }
    override fun onDataSetChanged() { refresh() }
    override fun onDestroy()        {}

    private fun refresh() {
        calls    = CallLogRemoteViewsService.getCalls()
        colors   = CallLogRemoteViewsService.colors
        fontSize = CallLogRemoteViewsService.fontSizeSp
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

        // Text colors
        views.setTextColor(R.id.tv_date, colors.textPrimary)
        views.setTextColor(R.id.tv_time, colors.textSecondary)
        views.setTextColor(R.id.tv_name, colors.textPrimary)

        // Font size — setTextViewTextSize is officially supported by RemoteViews
        views.setTextViewTextSize(R.id.tv_date, TypedValue.COMPLEX_UNIT_SP, fontSize)
        views.setTextViewTextSize(R.id.tv_time, TypedValue.COMPLEX_UNIT_SP, fontSize)
        views.setTextViewTextSize(R.id.tv_name, TypedValue.COMPLEX_UNIT_SP, fontSize)

        // NOTE: setTypeface via setInt reflection is intentionally omitted —
        // RemoteViews does not support it and throws ActionException at runtime,
        // which causes "Couldn't add widget".

        // Call type icon
        val iconRes = when (entry.type) {
            CallType.INCOMING -> R.drawable.ic_call_incoming
            CallType.OUTGOING -> R.drawable.ic_call_outgoing
            CallType.MISSED   -> R.drawable.ic_call_missed
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
