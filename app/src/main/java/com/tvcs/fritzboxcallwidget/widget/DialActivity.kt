package com.tvcs.fritzboxcallwidget.widget

import android.app.Activity
import android.content.Intent
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

        // Log all extras for debugging
        intent?.extras?.keySet()?.forEach { key ->
            Log.d(TAG, "Extra: $key = ${intent.extras?.get(key)}")
        }

        val number = intent?.getStringExtra(EXTRA_NUMBER)
        Log.d(TAG, "Dialling number: '$number'")

        if (!number.isNullOrBlank()) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } else {
            Log.w(TAG, "No number received — intent data: ${intent?.data}, extras: ${intent?.extras}")
        }
        finish()
    }
}
