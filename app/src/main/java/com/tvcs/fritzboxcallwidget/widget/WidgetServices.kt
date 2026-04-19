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
 * Handles device (re)boot. Declared statically in the manifest so the OS
 * can deliver the broadcast even when the app is not running.
 *
 * Restores the AlarmManager layer (WorkManager reschedules itself) and
 * enqueues a one-shot staleness-check refresh.
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

// ── ScreenOnReceiver ──────────────────────────────────────────────────────────

/**
 * Receives ACTION_SCREEN_ON and ACTION_USER_PRESENT.
 *
 * IMPORTANT: these broadcasts are only deliverable to a statically-declared
 * receiver in the manifest on Android 8+ IF the receiver is declared on the
 * widget's AppWidgetProvider *or* on a separate receiver entry — they cannot
 * be reliably received by a background Service that has been killed by Doze.
 *
 * This receiver is declared statically in the manifest (see below) so the OS
 * wakes the app specifically to deliver it, even after 45+ minutes of screen-off.
 *
 * NOTE: ACTION_SCREEN_ON cannot actually be declared statically since Android 8
 * for most apps — but AppWidgetProvider receivers ARE exempt from this restriction
 * because they are considered "app widget broadcast receivers". We register this
 * as a *separate* receiver element in the manifest with the protected-broadcast
 * exemption annotation. On devices where it is blocked, the AlarmManager / WorkManager
 * periodic path still guarantees eventual delivery.
 */
class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("ScreenOnReceiver", "Received: $action")
        WidgetScheduler.refreshIfStale(context)
    }
}

// ── PowerReceiver ─────────────────────────────────────────────────────────────

/**
 * Receives POWER_CONNECTED / POWER_DISCONNECTED.
 *
 * These CAN be declared statically in the manifest (they are exempt from the
 * Android 8 implicit broadcast restriction — see
 * https://developer.android.com/guide/components/broadcast-exceptions).
 */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("PowerReceiver", "Received: $action")
        WidgetScheduler.refreshIfStale(context)
    }
}

// ── NetworkChangeReceiver ─────────────────────────────────────────────────────

/**
 * Receives CONNECTIVITY_ACTION (deprecated but still delivered statically on
 * API < 28) and acts as the static fallback for network-available events.
 *
 * On API 28+ we rely on the ConnectivityManager.NetworkCallback registered in
 * [EventTriggerService] instead. Both paths call [WidgetScheduler.refreshIfStale]
 * so double-firing is harmless.
 */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("NetworkChangeReceiver", "Network changed")
        WidgetScheduler.refreshIfStale(context)
    }
}

// ── EventTriggerService ───────────────────────────────────────────────────────

/**
 * Registers a [ConnectivityManager.NetworkCallback] for API 28+ network-available
 * events and re-registers the screen-on receiver dynamically as a belt-and-suspenders
 * fallback for when the static receiver is not invoked.
 *
 * This service is started by [CallLogWidget.onEnabled] and stopped by
 * [CallLogWidget.onDisabled]. It uses START_STICKY so the OS restarts it after
 * killing it under memory pressure.
 *
 * The static receivers ([ScreenOnReceiver], [PowerReceiver], [NetworkChangeReceiver])
 * are the reliable path; this service is the supplementary dynamic path.
 */
class EventTriggerService : Service() {

    private val dynamicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("EventTriggerService", "Dynamic trigger: ${intent.action}")
            WidgetScheduler.refreshIfStale(context)
        }
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerDynamicReceiver()
        registerNetworkCallback()
        Log.d("EventTriggerService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(dynamicReceiver) }
        unregisterNetworkCallback()
        Log.d("EventTriggerService", "Service destroyed")
    }

    private fun registerDynamicReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(dynamicReceiver, filter)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("EventTriggerService", "Network available")
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
