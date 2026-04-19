package com.tvcs.fritzboxcallwidget.widget

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log

// ── BootReceiver ──────────────────────────────────────────────────────────────

/**
 * Re-schedules the AlarmManager layer after a reboot.
 * WorkManager reschedules itself automatically; this ensures the alarm layer
 * is also restored.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Boot completed — rescheduling and refreshing")
            WidgetScheduler.schedule(context)
            WidgetScheduler.refreshIfStale(context)
        }
    }
}

// ── EventTriggerReceiver ──────────────────────────────────────────────────────

/**
 * Listens for system events that are good proxies for "the user is about to
 * look at the widget":
 *
 *  - [Intent.ACTION_SCREEN_ON]          — user wakes the device
 *  - [Intent.ACTION_USER_PRESENT]       — user unlocks (past lock screen)
 *  - [Intent.ACTION_POWER_CONNECTED]    — USB / charger plugged in
 *  - [Intent.ACTION_POWER_DISCONNECTED] — unplugged (often picked up for a quick check)
 *
 * These intents cannot be declared statically in the manifest (Android 8+
 * restriction); they must be registered dynamically from a Service that runs
 * as long as the widget exists.  [EventTriggerService] handles that lifecycle.
 *
 * On each event a one-shot WorkManager job is enqueued.  The staleness check
 * inside [WidgetRefreshWorker] prevents unnecessary network calls when events
 * fire in rapid succession.
 */
class EventTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("EventTrigger", "Trigger: $action")
        WidgetScheduler.refreshIfStale(context)
    }
}

// ── EventTriggerService ───────────────────────────────────────────────────────

/**
 * Lightweight non-sticky service whose sole purpose is to hold a dynamically
 * registered [EventTriggerReceiver] and a [ConnectivityManager.NetworkCallback]
 * for as long as the widget is active.
 *
 * Started by [CallLogWidget.onEnabled], stopped by [CallLogWidget.onDisabled].
 *
 * The service is NOT a foreground service — it does not post a notification.
 * Android may kill it under memory pressure, but [CallLogWidget.onUpdate] and
 * [BootReceiver] will restart it whenever the widget or device wakes up.
 */
class EventTriggerService : Service() {

    private val receiver = EventTriggerReceiver()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerEventReceiver()
        registerNetworkCallback()
        Log.d("EventTriggerService", "Service started, listeners registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY   // restart automatically if killed by OS

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        unregisterNetworkCallback()
        Log.d("EventTriggerService", "Service destroyed, listeners unregistered")
    }

    // ── Dynamic BroadcastReceiver ─────────────────────────────────────────────

    private fun registerEventReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(receiver, filter)
    }

    // ── Network callback (Wi-Fi / mobile connected) ───────────────────────────

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("EventTriggerService", "Network available — checking staleness")
                WidgetScheduler.refreshIfStale(this@EventTriggerService)
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

// ── WidgetUpdateService (legacy stub, kept for manifest compat) ───────────────

class WidgetUpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallLogWidget.triggerRefresh(this)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
