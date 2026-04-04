package com.tvcs.fritzboxcallwidget.widget

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, rescheduling widget refresh")
            WidgetScheduler.schedule(context)
        }
    }
}

class WidgetUpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallLogWidget.triggerRefresh(this)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
