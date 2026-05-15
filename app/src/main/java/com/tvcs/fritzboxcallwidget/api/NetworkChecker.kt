package com.tvcs.fritzboxcallwidget.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

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
 * Additionally, [isHostReachable] performs a fast TCP-level probe so
 * callers can detect VPN or WiFi networks that have no route to the
 * FritzBox — before committing to the full HTTP fetch.  This prevents
 * the widget from hanging and avoids the Android ANR dialog when the
 * device is connected to a network but the FritzBox is unreachable
 * (e.g. corporate VPN, public WiFi without LAN access).
 *
 * Note: we cannot know for certain whether *this* app is whitelisted in
 * either saver mode, so we report RESTRICTED rather than NO_NETWORK and
 * let the user decide.  The fetch is still attempted; the warning is
 * informational to help diagnose unexpected failures.
 */
object NetworkChecker {

    private const val TAG = "NetworkChecker"

    /** Timeout for the TCP reachability probe in milliseconds. */
    private const val TCP_PROBE_TIMEOUT_MS = 1_000

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
        // minSdk=26 → Build.VERSION_CODES.M (23) ist immer erfüllt;
        // der deprecated activeNetworkInfo-Pfad (API<23) wird nie erreicht.
        val network = cm.activeNetwork
        val caps    = cm.getNetworkCapabilities(network)
        val isConnected = caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
             caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
             caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
             caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))

        if (!isConnected) return NetworkState.NoNetwork

        // ── 2. Check Battery Saver ────────────────────────────────────────────
        // minSdk=26 → isPowerSaveMode (API 21/LOLLIPOP) ist immer verfügbar.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batterySaverOn = pm.isPowerSaveMode

        // ── 3. Check Data Saver (restrict background data) ────────────────────
        // RESTRICT_BACKGROUND_STATUS_ENABLED means background data is blocked
        // for apps not on the whitelist.
        // minSdk=26 → restrictBackgroundStatus (API 24/N) ist immer verfügbar.
        val dataSaverOn = cm.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

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
     * Performs a fast TCP-level reachability probe against [host]:[port].
     *
     * This is intentionally a **blocking** call — always invoke it from a
     * background thread or inside `withContext(Dispatchers.IO)`.
     *
     * Why TCP and not ICMP ping?
     *   - Android requires ROOT or a special manifest permission for raw
     *     ICMP sockets.  A TCP connect to the target port is both
     *     permission-free and more meaningful: it confirms that the exact
     *     service endpoint (not just IP-layer routing) is available.
     *
     * Typical scenarios where Android reports "Connected" but this returns false:
     *   - VPN is active and routes traffic away from the local LAN
     *     (FritzBox at 192.168.x.x is no longer reachable)
     *   - Connected to a public / guest WiFi that has no route to the
     *     FritzBox
     *   - The FritzBox itself is offline or on a different subnet
     *
     * @param host  Hostname or IP address of the FritzBox.
     * @param port  Port to probe (e.g. 49000 for TR-064, 443 for HTTPS).
     * @param timeoutMs  Maximum wait in milliseconds (default [TCP_PROBE_TIMEOUT_MS]).
     * @return `true` if a TCP connection could be established, `false` otherwise.
     */
    fun isHostReachable(
        host: String,
        port: Int,
        timeoutMs: Int = TCP_PROBE_TIMEOUT_MS
    ): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "Host unreachable — $host:$port (${e.javaClass.simpleName}: ${e.message})")
            false
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
