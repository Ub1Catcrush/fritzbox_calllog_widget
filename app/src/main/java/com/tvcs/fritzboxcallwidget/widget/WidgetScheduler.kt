package com.tvcs.fritzboxcallwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import java.util.concurrent.TimeUnit

/**
 * Manages the widget refresh schedule using a two-layer strategy:
 *
 *  1. **WorkManager periodic job** — the primary scheduler.
 *     Survives Doze, App Standby, reboots, and process death.
 *     Interval mirrors [AppPreferences.refreshIntervalSeconds] (minimum 15 min
 *     due to WorkManager constraints; shorter intervals are served by layer 2).
 *
 *  2. **AlarmManager** — kept as a supplementary exact-alarm layer for
 *     sub-15-minute intervals and for users who have granted SCHEDULE_EXACT_ALARM.
 *     When exact alarms are unavailable the alarm is still set inexactly as a
 *     best-effort ping, but WorkManager is the authoritative trigger.
 *
 * Both layers call [CallLogWidget.ACTION_REFRESH] which honours staleness:
 * a refresh is only executed when more than [AppPreferences.refreshIntervalSeconds]
 * have elapsed since [AppPreferences.lastSuccessfulRefreshMs].
 */
object WidgetScheduler {

    private const val TAG              = "WidgetScheduler"
    private const val ALARM_RC         = 42
    private const val WORK_NAME_PERIODIC = "widget_refresh_periodic"

    // ── Public API ────────────────────────────────────────────────────────────

    fun schedule(context: Context) {
        scheduleWorkManager(context)
        scheduleAlarm(context)
    }

    fun cancel(context: Context) {
        cancelAlarm(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        Log.d(TAG, "Widget refresh cancelled (alarm + WorkManager)")
    }

    /**
     * Reschedule both layers after the interval preference changes.
     * Cancels any existing work/alarm then re-enqueues with the new interval.
     */
    fun reschedule(context: Context) {
        cancel(context)
        schedule(context)
    }

    /**
     * Returns true if exact alarms are available.
     * On API < 31 always true; on API 31+ depends on user permission.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canScheduleExactAlarms(am)
    }

    /**
     * Returns an Intent that opens the system settings page for exact alarm
     * permissions (Android 12+ only). Returns null on older versions.
     */
    fun requestExactAlarmPermissionIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            android.net.Uri.parse("package:${context.packageName}")
        )
    }

    // ── WorkManager layer ─────────────────────────────────────────────────────

    private fun scheduleWorkManager(context: Context) {
        val prefs       = AppPreferences(context)
        val intervalSec = prefs.refreshIntervalSeconds.toLong()

        // WorkManager minimum is 15 minutes; clamp upward.
        val wmIntervalMin = maxOf(15L, intervalSec / 60L)

        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
            wmIntervalMin, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,   // re-enqueue with new interval on reschedule
            request
        )
        Log.d(TAG, "WorkManager periodic refresh scheduled every ${wmIntervalMin}min")
    }

    // ── AlarmManager layer ────────────────────────────────────────────────────

    private fun scheduleAlarm(context: Context) {
        val prefs      = AppPreferences(context)
        val intervalMs = prefs.refreshIntervalSeconds.toLong() * 1_000L
        val am         = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi         = buildAlarmPendingIntent(context)
        am.cancel(pi)

        val exact = canScheduleExactAlarms(am)
        if (exact) {
            am.setRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + intervalMs,
                intervalMs,
                pi
            )
        } else {
            am.setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + intervalMs,
                maxOf(intervalMs, AlarmManager.INTERVAL_FIFTEEN_MINUTES),
                pi
            )
        }
        Log.d(TAG, "Alarm set every ${prefs.refreshIntervalSeconds}s (exact=$exact)")
    }

    private fun cancelAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildAlarmPendingIntent(context))
    }

    private fun canScheduleExactAlarms(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms()
        else true

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CallLogWidget::class.java).apply {
            action = CallLogWidget.ACTION_REFRESH
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, ALARM_RC, intent, flags)
    }

    // ── One-shot "refresh if stale" ───────────────────────────────────────────

    /**
     * Enqueues a one-time WorkManager job that refreshes the widget only when
     * the last successful refresh is older than the configured interval.
     * Used by event triggers (screen-on, USB, network change, boot).
     */
    fun refreshIfStale(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "One-shot refresh-if-stale enqueued")
    }
}
