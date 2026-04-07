package com.tvcs.fritzboxcallwidget.widget

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log

class DialActivity : Activity() {
    companion object {
        const val EXTRA_NUMBER = "number"
        private const val TAG = "DialActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use typed getStringExtra — avoids deprecated Bundle.get(String): Any?
        val number = intent?.getStringExtra(EXTRA_NUMBER)
        Log.d(TAG, "Dialling: '$number'")

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            if (!number.isNullOrBlank()) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            } else {
                Log.w(TAG, "No number in intent extras")
            }
        } else {
            Log.w(TAG, "No telephony")
        }

        finish()
    }
}
