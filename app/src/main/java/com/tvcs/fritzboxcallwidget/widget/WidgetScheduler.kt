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
            // API 33+ (TIRAMISU … BAKLAVA … 36+): USE_EXACT_ALARM wird automatisch
            // gewährt für Kalender- und Alarm-Apps; alle anderen Apps bekommen es
            // ebenfalls, sofern der Nutzer die App nicht manuell einschränkt.
            // Ab API 36 kann Google Play den Zugriff auf USE_EXACT_ALARM für
            // Apps einschränken, die keine Kalender-/Alarm-Funktion deklarieren.
            // Wir prüfen deshalb ab API 33 auch hier canScheduleExactAlarms()
            // und fallen sicher auf setAndAllowWhileIdle() zurück.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    Log.d(TAG, "Exact alarm (API33+) in ${prefs.refreshIntervalSeconds}s")
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    Log.w(TAG, "Inexact alarm fallback (API33+) — USE_EXACT_ALARM nicht verfügbar")
                }
            }
            // API 31-32: SCHEDULE_EXACT_ALARM — erfordert explizite Nutzer-Freigabe
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    Log.d(TAG, "Exact alarm (API31-32, granted) in ${prefs.refreshIntervalSeconds}s")
                } else {
                    // Fallback auf inexact — Nutzer hat Permission noch nicht erteilt
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    Log.w(TAG, "Inexact alarm fallback — SCHEDULE_EXACT_ALARM not granted")
                }
            }
            // API 26-30: setExactAndAllowWhileIdle immer verfügbar, keine Permission nötig
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
        // API 31+ (S): canScheduleExactAlarms() prüft sowohl SCHEDULE_EXACT_ALARM
        // (API 31-32, muss vom Nutzer gewährt werden) als auch USE_EXACT_ALARM
        // (API 33+, automatisch gewährt, aber ab API 36 von Google Play
        // einschränkbar für Apps ohne Kalender-/Alarm-Deklaration).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms()
        else true  // API 26-30: kein Alarm-Permission-System, immer true
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
