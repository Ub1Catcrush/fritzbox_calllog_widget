package com.tvcs.fritzboxcallwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences

object WidgetScheduler {

    private const val TAG = "WidgetScheduler"
    private const val REQUEST_CODE = 42

    fun schedule(context: Context) {
        val prefs = AppPreferences(context)
        val intervalMs = prefs.refreshIntervalSeconds.toLong() * 1000L

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context)

        alarmManager.cancel(pi)

        Log.d(TAG, "Scheduling widget refresh every ${prefs.refreshIntervalSeconds}s")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + intervalMs,
                intervalMs,
                pi
            )
        } else {
            alarmManager.setRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + intervalMs,
                intervalMs,
                pi
            )
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
        Log.d(TAG, "Widget refresh alarm cancelled")
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        // Explicit intent targeting our own receiver → FLAG_MUTABLE is allowed.
        val intent = Intent(context, CallLogWidget::class.java).apply {
            action = CallLogWidget.ACTION_REFRESH
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
