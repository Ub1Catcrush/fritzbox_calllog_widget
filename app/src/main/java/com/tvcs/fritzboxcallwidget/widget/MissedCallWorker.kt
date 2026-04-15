package com.tvcs.fritzboxcallwidget.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tvcs.fritzboxcallwidget.R
import com.tvcs.fritzboxcallwidget.api.CallRepository
import com.tvcs.fritzboxcallwidget.model.CallType
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import com.tvcs.fritzboxcallwidget.prefs.SettingsActivity
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that checks for new missed calls and posts
 * a notification if any are found since the last check.
 *
 * This is an opt-in feature (disabled by default).
 * Requires POST_NOTIFICATIONS permission on Android 13+.
 *
 * Schedule: every 15 minutes (minimum WorkManager interval) when on any network.
 * The worker only runs when notifications are enabled in preferences.
 */
class MissedCallWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG           = "MissedCallWorker"
        private const val WORK_NAME     = "missed_call_check"
        private const val CHANNEL_ID    = "missed_calls"
        private const val NOTIF_ID      = 1001

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MissedCallWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "MissedCallWorker scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "MissedCallWorker cancelled")
        }

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notif_channel_desc)
                }
                context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(context)

        if (!prefs.missedCallNotificationsEnabled) {
            Log.d(TAG, "Notifications disabled — skipping")
            return Result.success()
        }

        return try {
            val repo   = CallRepository(prefs)
            val result = repo.fetchCallLog(context)
            if (result.isFailure) {
                Log.w(TAG, "Fetch failed: ${result.exceptionOrNull()?.message}")
                return Result.retry()
            }

            val calls  = result.getOrDefault(emptyList())
            val missed = calls.filter { it.type == CallType.MISSED }

            if (missed.isEmpty()) return Result.success()

            // Detect new missed calls by comparing the newest call's composite key
            // (date + number) against what we last notified about
            val newestKey = "${missed.first().date}_${missed.first().number}"
            if (newestKey == prefs.lastSeenCallId) return Result.success()

            // Count new missed calls since last seen
            val newCount = if (prefs.lastSeenCallId.isBlank()) missed.size
            else missed.indexOfFirst { "${it.date}_${it.number}" == prefs.lastSeenCallId }
                .let { if (it < 0) missed.size else it }

            if (newCount > 0) postNotification(missed.take(newCount), prefs)

            prefs.lastSeenCallId = newestKey
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Worker error: ${e.message}", e)
            Result.retry()
        }
    }

    private fun postNotification(
        newMissed: List<com.tvcs.fritzboxcallwidget.model.CallEntry>,
        prefs: AppPreferences
    ) {
        createNotificationChannel(context)

        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !nm.areNotificationsEnabled()) return

        val ctx     = SettingsActivity.wrapLocale(context, prefs.language)
        val title   = ctx.resources.getQuantityString(
            R.plurals.notif_missed_title, newMissed.size, newMissed.size)
        val text    = newMissed.take(3).joinToString("\n") { entry ->
            val name = entry.name ?: entry.number
            val time = entry.date.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            "$name  $time"
        }

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, SettingsActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try { nm.notify(NOTIF_ID, notif) }
        catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission missing: ${e.message}")
        }
    }
}
