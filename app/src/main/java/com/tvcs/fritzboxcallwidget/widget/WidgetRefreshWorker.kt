package com.tvcs.fritzboxcallwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvcs.fritzboxcallwidget.api.CallRepository
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences

/**
 * WorkManager worker that drives widget refreshes.
 *
 * The worker performs the fetch directly (not via Broadcast) so the result
 * is guaranteed to land before the worker finishes — Broadcasts sent from
 * workers can be deferred or dropped by Doze before the worker's process
 * ends.
 *
 * Staleness check: the widget is only refreshed when the elapsed time since
 * [AppPreferences.lastSuccessfulRefreshMs] exceeds [AppPreferences.refreshIntervalSeconds].
 * This prevents redundant network calls when multiple triggers fire in a short
 * window (e.g. screen-on shortly after an alarm-manager tick).
 *
 * After the fetch (or skip) the exact alarm is re-armed so the
 * self-rescheduling chain continues.
 */
class WidgetRefreshWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "WidgetRefreshWorker"

        /** Boolean input-data key: bypass the staleness/interval check and
         *  fetch immediately. Set by [WidgetScheduler.forceRefreshNow]. */
        const val INPUT_FORCE = "force"
    }

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(context)
        val force = inputData.getBoolean(INPUT_FORCE, false)

        // ── Staleness check ───────────────────────────────────────────────────
        if (!force) {
            val intervalMs = prefs.refreshIntervalSeconds.toLong() * 1_000L
            val lastMs     = prefs.lastSuccessfulRefreshMs
            val elapsedMs  = System.currentTimeMillis() - lastMs

            if (lastMs > 0L && elapsedMs < intervalMs) {
                Log.d(TAG, "Skipping refresh — only ${elapsedMs / 1000}s since last update " +
                      "(interval ${prefs.refreshIntervalSeconds}s)")
                // Re-arm alarm so the self-rescheduling chain continues
                WidgetScheduler.scheduleExactAlarm(context)
                return Result.success()
            }
        }

        Log.d(TAG, "Refreshing widget (force=$force)")

        // ── Direct fetch — no Broadcast intermediary ──────────────────────────
        // Using sendBroadcast() here is unreliable: Doze can delay or drop the
        // broadcast before the worker's process ends.  We fetch directly and
        // trigger the widget update ourselves.
        return try {
            val repo   = CallRepository(prefs)
            val result = repo.fetchCallLog(context)

            val manager = AppWidgetManager.getInstance(context)
            val ids     = manager.getAppWidgetIds(
                ComponentName(context, CallLogWidget::class.java))

            if (ids.isNotEmpty()) {
                if (result.isSuccess) {
                    prefs.lastSuccessfulRefreshMs = System.currentTimeMillis()
                    Log.d(TAG, "Fetch succeeded — triggering widget update for ${ids.size} widget(s)")
                    // Render-only broadcast: CallLogWidget must NOT fetch
                    // again here, only show the result we already have.
                    CallLogWidget.triggerRefresh(context)
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Unbekannter Fehler"
                    Log.w(TAG, "Fetch failed: $msg — using cached data")
                    // Pass the error message along so the render-only path
                    // can show it without touching the network itself.
                    CallLogWidget.triggerRefresh(context, errorMessage = msg)
                }
            }

            // Re-arm alarm so the self-rescheduling chain continues
            WidgetScheduler.scheduleExactAlarm(context)
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Worker error: ${e.message}", e)
            WidgetScheduler.scheduleExactAlarm(context)
            Result.retry()
        }
    }
}
