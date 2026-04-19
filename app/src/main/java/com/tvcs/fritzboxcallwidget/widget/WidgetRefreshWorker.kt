package com.tvcs.fritzboxcallwidget.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences

/**
 * WorkManager worker that drives widget refreshes.
 *
 * Staleness check: the widget is only refreshed when the elapsed time since
 * [AppPreferences.lastSuccessfulRefreshMs] exceeds [AppPreferences.refreshIntervalSeconds].
 * This prevents redundant network calls when multiple triggers fire in a short
 * window (e.g. screen-on shortly after an alarm-manager tick).
 *
 * On success [AppPreferences.lastSuccessfulRefreshMs] is updated so the next
 * trigger can correctly decide whether to fetch.
 *
 * Connection fallback reset: before each fetch attempt the profile-iteration
 * index is reset to 0 so all profiles are tried fresh rather than starting from
 * the last-failed profile.
 */
class WidgetRefreshWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "WidgetRefreshWorker"
    }

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(context)

        // ── Staleness check ───────────────────────────────────────────────────
        val intervalMs  = prefs.refreshIntervalSeconds.toLong() * 1_000L
        val lastMs      = prefs.lastSuccessfulRefreshMs
        val elapsedMs   = System.currentTimeMillis() - lastMs
        if (lastMs > 0L && elapsedMs < intervalMs) {
            Log.d(TAG, "Skipping refresh — only ${elapsedMs / 1000}s since last update " +
                  "(interval ${prefs.refreshIntervalSeconds}s)")
            return Result.success()
        }

        // ── Reset connection fallback ─────────────────────────────────────────
        // Ensure every fresh fetch attempt starts with the highest-priority
        // profile instead of the last-failed one.
        prefs.activeProfileFallbackIndex = 0
        Log.d(TAG, "Refreshing widget (elapsed ${elapsedMs / 1000}s)")

        // ── Trigger widget update ─────────────────────────────────────────────
        CallLogWidget.triggerRefresh(context)

        // lastSuccessfulRefreshMs is set by CallLogWidget.fetchAndUpdate on
        // successful fetch, so we don't update it here.
        return Result.success()
    }
}
