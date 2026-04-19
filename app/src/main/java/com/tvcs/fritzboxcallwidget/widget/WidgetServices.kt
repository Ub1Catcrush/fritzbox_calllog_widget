package com.tvcs.fritzboxcallwidget.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tvcs.fritzboxcallwidget.R
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import com.tvcs.fritzboxcallwidget.prefs.SettingsActivity

// ── BootReceiver ──────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Boot — rescheduling alarm + starting foreground service")
            WidgetScheduler.scheduleExactAlarm(context)
            WidgetForegroundService.start(context)
        }
    }
}

// ── PowerReceiver (statisch, Exemption-Liste) ─────────────────────────────────

class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("PowerReceiver", "Power event: ${intent.action}")
        WidgetScheduler.refreshIfStale(context)
        // Re-arm the exact alarm since power state change may affect scheduling
        WidgetScheduler.scheduleExactAlarm(context)
    }
}

// ── WidgetForegroundService ───────────────────────────────────────────────────
//
// WHY A FOREGROUND SERVICE?
//
// ACTION_SCREEN_ON is explicitly NOT on Android 8+'s static-receiver exemption
// list. It is a "protected broadcast" but Android only delivers it to processes
// that are already running. After 45 minutes of screen-off, Doze kills all
// background processes — so a static receiver never fires for SCREEN_ON.
//
// A ForegroundService survives Doze because the OS exempts it from process
// death (it shows a persistent notification as the trade-off).
// Inside the running service we register SCREEN_ON dynamically — reliably.
//
// The notification is placed in a low-importance, silent channel so it appears
// only in the notification shade pull-down, not as a heads-up popup. Users can
// additionally hide it in system settings if desired.

class WidgetForegroundService : Service() {

    companion object {
        private const val TAG          = "WidgetFgService"
        private const val CHANNEL_ID   = "widget_refresh_service"
        private const val NOTIF_ID     = 9001

        fun start(context: Context) {
            val intent = Intent(context, WidgetForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WidgetForegroundService::class.java))
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            Log.d(TAG, "Screen/unlock event: $action")
            // On SCREEN_ON just re-arm the alarm so it fires promptly.
            // On USER_PRESENT (unlock) we do the actual staleness-check refresh.
            when (action) {
                Intent.ACTION_SCREEN_ON   -> WidgetScheduler.scheduleExactAlarm(context)
                Intent.ACTION_USER_PRESENT -> WidgetScheduler.refreshIfStale(context)
                Intent.ACTION_SCREEN_OFF  -> {
                    // Screen turned off: cancel the running alarm to avoid
                    // unnecessary wake-locks while the screen is off, then
                    // schedule a single wake-up for when the interval expires
                    // so data is fresh when the user turns the screen on again.
                    WidgetScheduler.scheduleExactAlarm(context)
                }
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerScreenReceiver()
        registerNetworkCallback()
        Log.d(TAG, "Foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenOnReceiver) }
        unregisterNetworkCallback()
        Log.d(TAG, "Foreground service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_MIN   // silent, no heads-up, no sound
            ).apply {
                setShowBadge(false)
                description = getString(R.string.service_channel_desc)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openSettings = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_incoming)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(getString(R.string.service_notif_text))
            .setContentIntent(openSettings)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ── Dynamic receivers ─────────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenOnReceiver, filter)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available — refresh if stale")
                WidgetScheduler.refreshIfStale(this@WidgetForegroundService)
            }
        }
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        networkCallback = null
    }
}

// ── WidgetUpdateService (legacy stub) ─────────────────────────────────────────

class WidgetUpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallLogWidget.triggerRefresh(this)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
