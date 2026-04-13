package com.tvcs.fritzboxcallwidget.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager

/**
 * Checks the device's network and power state before attempting any
 * FritzBox connection.
 *
 * Returns a [NetworkState] that callers can inspect to decide whether
 * to proceed, warn the user, or skip the fetch entirely.
 *
 * Three distinct failure modes are covered:
 *   1. No connectivity at all (offline, airplane mode)
 *   2. Battery Saver active — Android may block background network access
 *      for apps that are not whitelisted, causing silent connection failures
 *   3. Data Saver (restrictBackground) active — metered background traffic
 *      is blocked; only whitelisted apps bypass this
 *
 * Note: we cannot know for certain whether *this* app is whitelisted in
 * either saver mode, so we report RESTRICTED rather than NO_NETWORK and
 * let the user decide.  The fetch is still attempted; the warning is
 * informational to help diagnose unexpected failures.
 */
object NetworkChecker {

    sealed class NetworkState {
        /** Network is available and not known to be restricted. */
        object Available : NetworkState()

        /** No active network interface at all (offline / airplane mode). */
        object NoNetwork : NetworkState()

        /**
         * A network interface exists but background traffic may be blocked.
         * [reason] describes which saver mode is active.
         */
        data class Restricted(val reason: Reason) : NetworkState() {
            enum class Reason { BATTERY_SAVER, DATA_SAVER, BATTERY_AND_DATA_SAVER }
        }
    }

    fun check(context: Context): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // ── 1. Check basic connectivity ───────────────────────────────────────
        val isConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val caps    = cm.getNetworkCapabilities(network)
            caps != null &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }

        if (!isConnected) return NetworkState.NoNetwork

        // ── 2. Check Battery Saver ────────────────────────────────────────────
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batterySaverOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pm.isPowerSaveMode
        } else false

        // ── 3. Check Data Saver (restrict background data) ────────────────────
        // RESTRICT_BACKGROUND_STATUS_ENABLED means background data is blocked
        // for apps not on the whitelist.
        val dataSaverOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } else false

        return when {
            batterySaverOn && dataSaverOn ->
                NetworkState.Restricted(NetworkState.Restricted.Reason.BATTERY_AND_DATA_SAVER)
            batterySaverOn ->
                NetworkState.Restricted(NetworkState.Restricted.Reason.BATTERY_SAVER)
            dataSaverOn ->
                NetworkState.Restricted(NetworkState.Restricted.Reason.DATA_SAVER)
            else ->
                NetworkState.Available
        }
    }

    /**
     * Returns a human-readable description of the current network state
     * suitable for display in the widget error overlay or a log entry.
     *
     * Messages are in German to match the app's primary audience; the
     * strings are also defined in strings.xml for full localisation.
     */
    fun describeState(state: NetworkState): String = when (state) {
        is NetworkState.NoNetwork ->
            "Kein Netzwerk — Gerät ist offline oder im Flugmodus"
        is NetworkState.Restricted -> when (state.reason) {
            NetworkState.Restricted.Reason.BATTERY_SAVER ->
                "Energiesparmodus aktiv — Hintergrundverbindungen können eingeschränkt sein"
            NetworkState.Restricted.Reason.DATA_SAVER ->
                "Datensparmodus aktiv — Hintergrunddaten können gesperrt sein"
            NetworkState.Restricted.Reason.BATTERY_AND_DATA_SAVER ->
                "Energie- und Datensparmodus aktiv — Hintergrundverbindungen eingeschränkt"
        }
        is NetworkState.Available -> "Netzwerk verfügbar"
    }
}
