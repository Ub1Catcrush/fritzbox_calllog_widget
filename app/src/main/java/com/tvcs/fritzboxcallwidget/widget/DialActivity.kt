package com.tvcs.fritzboxcallwidget.widget

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.tvcs.fritzboxcallwidget.R
import com.tvcs.fritzboxcallwidget.api.FritzBoxClient
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trampoline activity that handles a tap on a call log row.
 *
 * Behaviour depends on the "Click-to-Dial" preference:
 *   - Disabled (default): opens the system phone app via ACTION_DIAL
 *   - Enabled: calls X_AVM-DE_DialNumber on the FritzBox via TR-064 so the
 *     FritzBox rings the configured extension first, then connects to the
 *     remote number. Falls back to ACTION_DIAL if the TR-064 call fails.
 *
 * The activity finishes immediately in all cases (it is intentionally
 * transparent — no UI of its own).
 */
class DialActivity : Activity() {

    companion object {
        const val EXTRA_NUMBER = "number"
        private const val TAG = "DialActivity"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val number = intent?.getStringExtra(EXTRA_NUMBER)
        Log.d(TAG, "Dial requested: '$number'")

        if (number.isNullOrBlank()) {
            Log.w(TAG, "No number in intent extras")
            finish()
            return
        }

        val prefs = AppPreferences(this)

        if (prefs.clickToDialEnabled) {
            dialViaTR064(number, prefs)
        } else {
            dialViaSystemApp(number)
            finish()
        }
    }

    // ── Click-to-Dial via FritzBox TR-064 ─────────────────────────────────────

    private fun dialViaTR064(number: String, prefs: AppPreferences) {
        scope.launch {
            val profiles = prefs.getOrderedProfiles().filter {
                it.enabled &&
                it.host.isNotBlank() &&
                it.type != com.tvcs.fritzboxcallwidget.api.ConnectionType.INTERNET_MYFRITZ
            }

            if (profiles.isEmpty()) {
                Log.w(TAG, "No TR-064 profile for Click-to-Dial — falling back")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DialActivity,
                        getString(R.string.click_to_dial_no_profile),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialViaSystemApp(number)
                    finish()
                }
                return@launch
            }

            var success = false
            for (profile in profiles) {
                runCatching {
                    val client = FritzBoxClient(profile, prefs.fritzUsername, prefs.fritzPassword)
                    client.dialNumber(number, prefs.clickToDialExtension)
                    success = true
                    Log.d(TAG, "Click-to-Dial OK via ${profile.host}")
                }.onFailure {
                    Log.w(TAG, "Click-to-Dial failed on ${profile.host}: ${it.message}")
                }
                if (success) break
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(
                        this@DialActivity,
                        getString(R.string.click_to_dial_initiated, number),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@DialActivity,
                        getString(R.string.click_to_dial_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialViaSystemApp(number)
                }
                finish()
            }
        }
    }

    // ── Standard system phone app ─────────────────────────────────────────────

    private fun dialViaSystemApp(number: String) {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } else {
            Log.w(TAG, "No telephony feature available")
        }
    }
}
