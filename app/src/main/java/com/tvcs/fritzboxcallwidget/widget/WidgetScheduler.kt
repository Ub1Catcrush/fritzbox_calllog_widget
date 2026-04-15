package com.tvcs.fritzboxcallwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences

/**
 * Manages the periodic AlarmManager refresh schedule for the widget.
 *
 * On Android 12+ (API 31+), exact alarms require the user to explicitly
 * grant SCHEDULE_EXACT_ALARM via the system settings page.  If the
 * permission is not granted, the widget falls back to [setInexactRepeating]
 * (the OS may delay the alarm by several minutes).
 *
 * Call [requestExactAlarmPermissionIntent] to get an Intent that opens
 * the system settings page where the user can grant the permission.
 * The settings Fragment shows a banner when on Android 12+ and the
 * permission is not yet granted, linking to that page.
 */
object WidgetScheduler {

    private const val TAG = "WidgetScheduler"
    private const val REQUEST_CODE = 42

    fun schedule(context: Context) {
        val prefs = AppPreferences(context)
        val intervalMs = prefs.refreshIntervalSeconds.toLong() * 1000L

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context)
        alarmManager.cancel(pi)

        val exact = canScheduleExactAlarms(alarmManager)
        Log.d(TAG, "Scheduling refresh every ${prefs.refreshIntervalSeconds}s " +
              "(exact=$exact)")

        if (exact) {
            alarmManager.setRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + intervalMs,
                intervalMs,
                pi
            )
        } else {
            alarmManager.setInexactRepeating(
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

    /**
     * Returns true if exact alarms are available.
     * On API < 31 always true; on API 31+ depends on user permission.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canScheduleExactAlarms(am)
    }

    private fun canScheduleExactAlarms(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms()
        else true

    /**
     * Returns an Intent that opens the system settings page for exact alarm
     * permissions (Android 12+ only).  Returns null on older versions.
     */
    fun requestExactAlarmPermissionIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            android.net.Uri.parse("package:${context.packageName}")
        )
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
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
