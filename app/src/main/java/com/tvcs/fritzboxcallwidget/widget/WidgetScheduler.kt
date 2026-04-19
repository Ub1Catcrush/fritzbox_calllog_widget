package com.tvcs.fritzboxcallwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences

/**
 * Widget refresh scheduler.
 *
 * Strategy: setExactAndAllowWhileIdle + WorkManager one-shot
 * ──────────────────────────────────────────────────────────
 *
 * setRepeating / setInexactRepeating are throttled heavily by Doze and
 * App Standby — they can be delayed by hours. WorkManager periodic jobs
 * are also deferred during Doze.
 *
 * The only alarm type guaranteed to fire even during Doze is
 * setExactAndAllowWhileIdle (or setAlarmClock). We use a self-rescheduling
 * chain: each alarm fires → BroadcastReceiver → refreshIfStale() →
 * [WidgetRefreshWorker] → scheduleExactAlarm() for the next tick.
 *
 * This means we need USE_EXACT_ALARM (API 33+, granted automatically) or
 * SCHEDULE_EXACT_ALARM (API 31-32, requires user grant). We request both.
 *
 * WorkManager is kept as a belt-and-suspenders fallback for when the alarm
 * chain breaks (first install, reboot before first alarm fires, etc.).
 */
object WidgetScheduler {

    private const val TAG     = "WidgetScheduler"
    private const val ALARM_RC = 42

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full schedule: exact alarm chain + WorkManager fallback + foreground service. */
    fun schedule(context: Context) {
        scheduleExactAlarm(context)
        WidgetForegroundService.start(context)
    }

    fun cancel(context: Context) {
        cancelExactAlarm(context)
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        WidgetForegroundService.stop(context)
        Log.d(TAG, "All refresh scheduling cancelled")
    }

    fun reschedule(context: Context) {
        cancelExactAlarm(context)
        scheduleExactAlarm(context)
    }

    /**
     * Schedule (or re-arm) a single exact alarm for one interval from now.
     * Called after each alarm fires so the chain continues, and on SCREEN_ON
     * so the next tick is always close to the configured interval.
     */
    fun scheduleExactAlarm(context: Context) {
        val prefs      = AppPreferences(context)
        val intervalMs = prefs.refreshIntervalSeconds.toLong() * 1_000L
        val triggerMs  = System.currentTimeMillis() + intervalMs

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildAlarmPendingIntent(context)
        am.cancel(pi)

        when {
            // API 33+: USE_EXACT_ALARM — no user permission needed
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                Log.d(TAG, "Exact alarm (API33+) in ${prefs.refreshIntervalSeconds}s")
            }
            // API 31-32: SCHEDULE_EXACT_ALARM — check user grant
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    Log.d(TAG, "Exact alarm (API31-32, granted) in ${prefs.refreshIntervalSeconds}s")
                } else {
                    // Fall back to inexact — user hasn't granted permission yet
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    Log.w(TAG, "Inexact alarm fallback — SCHEDULE_EXACT_ALARM not granted")
                }
            }
            // API 26-30: setExactAndAllowWhileIdle always available
            else -> {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                Log.d(TAG, "Exact alarm (API<31) in ${prefs.refreshIntervalSeconds}s")
            }
        }
    }

    /**
     * Enqueue a one-shot WorkManager job that performs a staleness check and
     * refreshes the widget if the interval has elapsed. No network constraint
     * so cached data is displayed immediately on screen-on.
     */
    fun refreshIfStale(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        // Re-arm the alarm so the chain continues from now
        scheduleExactAlarm(context)
        Log.d(TAG, "refreshIfStale enqueued")
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms()
        else true
    }

    fun requestExactAlarmPermissionIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            android.net.Uri.parse("package:${context.packageName}")
        )
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun cancelExactAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildAlarmPendingIntent(context))
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CallLogWidget::class.java).apply {
            action = CallLogWidget.ACTION_REFRESH
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, ALARM_RC, intent, flags)
    }
}
