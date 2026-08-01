package com.tvcs.fritzboxcallwidget.api

import android.content.Context
import android.util.Log
import com.tvcs.fritzboxcallwidget.model.CallEntry
import com.tvcs.fritzboxcallwidget.model.CallType
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import com.tvcs.fritzboxcallwidget.api.PhonebookRepository

/**
 * Fetches the call log using the configured connection profiles in priority order.
 *
 * Before attempting any network connection, the current [NetworkChecker.NetworkState]
 * is evaluated:
 *   - [NetworkChecker.NetworkState.NoNetwork]   → skip all profiles, return
 *     cached data if available, otherwise failure.
 *   - [NetworkChecker.NetworkState.Restricted]  → emit a warning via onProgress
 *     and continue with the fetch; the restriction *may* block the connection
 *     but we try anyway so the widget stays useful on a best-effort basis.
 *   - [NetworkChecker.NetworkState.Available]   → proceed normally.
 *
 * Per-profile TCP probe: before each HTTP fetch, [NetworkChecker.isHostReachable]
 * opens a short-lived TCP socket to the profile's host:port (1 s timeout).
 * If the probe fails the profile is skipped immediately without starting any
 * HTTP request. The next profile is tried automatically — this is the staged
 * connect mechanism (e.g. LAN fails → MyFRITZ is tried next).
 *
 * Thread safety: [cachedEntriesRef] uses AtomicReference for safe concurrent
 * read/write (@Volatile alone is insufficient for check-then-set patterns).
 */
class CallRepository(private val prefs: AppPreferences) {

    companion object {
        private const val TAG           = "CallRepository"
        private val FRITZ_DATE_FORMAT   = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
        private const val MAX_RETRIES   = 2
        private const val RETRY_BASE_MS = 500L

        /**
         * Hard ceiling for a single connection attempt (one profile, one retry
         * attempt). Bounds the worst case where a TCP connection is accepted
         * (so the pre-flight [NetworkChecker.isHostReachable] probe passes) but
         * then stalls mid-request — e.g. VPN drops or mobile data is interrupted
         * right after the socket opens. Without this, OkHttp's own timeouts
         * (connect+read+write, possibly doubled by a 401→digest retry) could
         * take the better part of a minute per attempt.
         */
        private const val PER_ATTEMPT_TIMEOUT_MS = 15_000L

        /**
         * Hard ceiling for the *entire* fetchCallLog() call across all
         * configured profiles and all their retries combined. This is the
         * safety net that guarantees the widget never appears to hang: no
         * matter how many profiles are configured, a refresh always resolves
         * (success, cached fallback, or error) within this window, which is
         * safely inside the time Android's goAsync()/WorkManager execution
         * window allows before the process can be reclaimed.
         */
        private const val TOTAL_FETCH_TIMEOUT_MS = 40_000L

        private val cachedEntriesRef = AtomicReference<List<CallEntry>?>(null)
    }

    fun getCachedEntries(): List<CallEntry>? = cachedEntriesRef.get()

    data class Progress(val message: String, val isError: Boolean = false)

    /**
     * Fetches the call log.
     *
     * @param context Required for network state checks via [NetworkChecker].
     * @param onProgress Called on the IO dispatcher with status updates.
     */
    suspend fun fetchCallLog(
        context: Context,
        onProgress: (Progress) -> Unit = {}
    ): Result<List<CallEntry>> = withContext(Dispatchers.IO) {

        // ── Network pre-check ─────────────────────────────────────────────────
        when (val netState = NetworkChecker.check(context)) {
            is NetworkChecker.NetworkState.NoNetwork -> {
                val msg = NetworkChecker.describeState(netState)
                onProgress(Progress(msg, isError = true))
                Log.w(TAG, "Fetch skipped: $msg")
                return@withContext cachedEntriesRef.get()
                    ?.let { Result.success(it) }
                    ?: Result.failure(Exception(msg))
            }
            is NetworkChecker.NetworkState.Restricted -> {
                // Warn but continue — the connection may still work even with
                // Battery Saver or Data Saver active (e.g. LAN connection,
                // app is whitelisted, or saver only affects metered networks).
                val msg = NetworkChecker.describeState(netState)
                onProgress(Progress("⚠ $msg — Verbindung wird trotzdem versucht…", isError = true))
                Log.w(TAG, "Network restricted: $msg — attempting fetch anyway")
            }
            is NetworkChecker.NetworkState.Available -> { /* normal path */ }
        }

        // ── Profile iteration ─────────────────────────────────────────────────
        val profiles = prefs.getOrderedProfiles().filter { it.enabled }

        if (profiles.isEmpty()) {
            val err = "Keine aktive Verbindungsoption konfiguriert"
            onProgress(Progress(err, isError = true))
            return@withContext cachedEntriesRef.get()?.let { Result.success(it) }
                ?: Result.failure(Exception(err))
        }

        var lastError: Throwable? = null
        var timedOut = false

        // ── Overall hard timeout ───────────────────────────────────────────────
        // Everything below (TCP probes, HTTP fetches, retries, across *all*
        // configured profiles) is bounded by TOTAL_FETCH_TIMEOUT_MS. If it's
        // exceeded we fall through exactly like "all profiles failed" below —
        // the widget shows cached data with an error overlay (or a plain error
        // if there's no cache yet) instead of hanging indefinitely. This is
        // what guarantees the widget can never appear frozen: a refresh always
        // finishes, one way or another, within a bounded time.
        val earlySuccess: Result<List<CallEntry>>? = try {
            withTimeout(TOTAL_FETCH_TIMEOUT_MS) {
            for (profile in profiles) {
                if (profile.host.isBlank()) {
                    onProgress(Progress(
                        "${profile.displayName}: Adresse nicht konfiguriert — übersprungen",
                        isError = true
                    ))
                    continue
                }

                // ── TCP reachability probe ─────────────────────────────────────────
                // Before making any HTTP request, verify that the host is actually
                // reachable at TCP level.  This catches the common case where Android
                // reports "Connected" (e.g. VPN active, or WiFi without LAN access)
                // but the FritzBox is not reachable on the current network — which
                // would otherwise cause the widget to hang for up to connectTimeout
                // seconds and trigger an Android ANR dialog.
                val reachable = NetworkChecker.isHostReachable(profile.host, profile.port)
                if (!reachable) {
                    val msg = "${profile.displayName}: Host ${profile.host}:${profile.port} " +
                              "nicht erreichbar (VPN oder kein LAN-Zugang?) — übersprungen"
                    onProgress(Progress(msg, isError = true))
                    Log.w(TAG, msg)
                    lastError = Exception(msg)
                    continue
                }

                onProgress(Progress("Verbinde mit ${profile.displayName} (${profile.host})…"))

                val result = fetchWithRetry(profile, onProgress)
                if (result.isSuccess) {
                    val entries = result.getOrThrow()
                    // Optionally enrich unknown entries with FritzBox phonebook names
                    val enriched = if (prefs.phonebookLookupEnabled) {
                        entries.map { e ->
                            if (e.name == null) {
                                val name = PhonebookRepository.lookupName(e.number, prefs)
                                if (name != null) e.copy(name = name) else e
                            } else e
                        }
                    } else entries

                    // Apply call type filter
                    val filtered = prefs.activeCallTypeFilter()
                        ?.let { allowed -> enriched.filter { it.type in allowed } }
                        ?: enriched

                    cachedEntriesRef.set(enriched)  // cache unfiltered for widget resize
                    onProgress(Progress("${filtered.size} Anrufe geladen von ${profile.displayName}"))
                    return@withTimeout Result.success(filtered)
                }

                lastError = result.exceptionOrNull()
                onProgress(Progress(
                    "${profile.displayName} fehlgeschlagen: ${lastError?.message}",
                    isError = true
                ))
            }
            null
            }
        } catch (e: TimeoutCancellationException) {
            timedOut = true
            null
        }

        if (earlySuccess != null) return@withContext earlySuccess

        if (timedOut) {
            val msg = "Zeitüberschreitung — Verbindungsversuch dauerte zu lange " +
                       "(VPN- oder Mobilfunkwechsel?)"
            onProgress(Progress(msg, isError = true))
            Log.w(TAG, "fetchCallLog exceeded ${TOTAL_FETCH_TIMEOUT_MS}ms budget")
            lastError = Exception(msg)
        }

        val cached = cachedEntriesRef.get()
        return@withContext if (cached != null) {
            onProgress(Progress(
                "Alle Verbindungen fehlgeschlagen — zeige zuletzt geladene Daten",
                isError = true
            ))
            Result.failure(lastError ?: Exception("Alle Verbindungsversuche fehlgeschlagen"))
        } else {
            Result.failure(lastError ?: Exception("Alle Verbindungsversuche fehlgeschlagen"))
        }
    }

    private suspend fun fetchWithRetry(
        profile: ConnectionProfile,
        onProgress: (Progress) -> Unit
    ): Result<List<CallEntry>> {
        var lastError: Throwable? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val client = FritzBoxClient(profile, prefs.fritzUsername, prefs.fritzPassword)
                // Bound this single attempt so a connection that stalls mid-request
                // (e.g. VPN drops right after the TCP probe succeeded, or DNS
                // resolution inside OkHttp hangs on a network with no working
                // DNS) fails fast and moves on to the next retry/profile.
                //
                // Plain `withTimeout { client.getCallList() }` is NOT reliable
                // here: getCallList() runs synchronous, non-suspending blocking
                // I/O under the hood (OkHttp's Call.execute(), raw DNS lookups),
                // and cancelling a coroutine that's synchronously executing
                // non-cooperative blocking code does not reliably unblock the
                // caller. BlockingIoTimeout runs the call on its own coroutine
                // and only ever awaits it under a timeout, which *does* reliably
                // return control — this is what actually prevents the Android
                // ANR dialog ("App reagiert nicht") on bad networks.
                val rawEntries = BlockingIoTimeout.runBounded(
                    PER_ATTEMPT_TIMEOUT_MS, "getCallList(${profile.host})"
                ) { client.getCallList() } ?: throw java.io.IOException(
                    "Zeitüberschreitung nach ${PER_ATTEMPT_TIMEOUT_MS / 1000}s " +
                    "bei ${profile.host} (Verbindung abgebrochen oder DNS hängt?)"
                )
                val entries = rawEntries.mapNotNull { raw ->
                    try { mapEntry(raw, prefs.phonePrefix, prefs.localAreaCode) }
                    catch (e: Exception) {
                        Log.w(TAG, "Skipping unparseable entry: $raw", e)
                        null
                    }
                }.sortedByDescending { it.date }
                return Result.success(entries)
            } catch (e: java.net.ConnectException) {
                // Host not reachable at TCP level — retrying won't help
                return Result.failure(e)
            } catch (e: Exception) {
                lastError = e
                val isLast = attempt == MAX_RETRIES - 1
                if (!isLast) {
                    val delayMs = RETRY_BASE_MS * (1L shl attempt)
                    Log.w(TAG, "Attempt ${attempt + 1}/$MAX_RETRIES on ${profile.host} failed: ${e.message}")
                    onProgress(Progress(
                        "${profile.displayName}: Versuch ${attempt + 1}/$MAX_RETRIES — " +
                        "Neuer Versuch in ${delayMs / 1000}s…",
                        isError = true
                    ))
                    delay(delayMs)
                } else {
                    Log.e(TAG, "All $MAX_RETRIES attempts failed on ${profile.host}: ${e.message}")
                }
            }
        }
        return Result.failure(lastError ?: Exception("Verbindung zu ${profile.host} fehlgeschlagen"))
    }

    // ── Entry mapping ─────────────────────────────────────────────────────────

    private fun mapEntry(raw: FritzBoxClient.FritzCallEntry, prefix: String, areaCode: String): CallEntry {
        // Determine CallType from FritzBox type code, port, and numbertype.
        //
        // Type codes:
        //   1  = Incoming answered
        //   2  = Missed
        //   3  = Outgoing
        //   4  = Incoming answered (active call deflection)
        //   9  = Active incoming call (still in progress — rarely in history)
        //  10  = Rejected / blocked by call-block rule
        //  11  = Active outgoing call (still in progress — rarely in history)
        //
        // Port ≥ 40: FritzBox internal answering machine recorded the call.
        // Numbertype "fax": fax transmission, not a voice call.
        val isFax = raw.numbertype == "fax"
        val isAB  = raw.port >= 40                 // AB port starts at 40 by default

        val type = when (raw.type) {
            1, 4 -> when {
                isFax -> CallType.FAX_RECEIVED
                isAB  -> CallType.VOICEMAIL        // caller left a message on AB
                else  -> CallType.INCOMING
            }
            2    -> CallType.MISSED
            3    -> if (isFax) CallType.FAX_SENT else CallType.OUTGOING
            9    -> CallType.ACTIVE_INCOMING
            10   -> CallType.BLOCKED
            11   -> CallType.ACTIVE_OUTGOING
            else -> CallType.INCOMING              // unknown — treat as incoming
        }
        val date = try {
            LocalDateTime.parse(raw.date, FRITZ_DATE_FORMAT)
        } catch (_: Exception) {
            Log.w(TAG, "Could not parse date '${raw.date}', using now")
            LocalDateTime.now()
        }
        val rawNumber = if (type == CallType.OUTGOING && raw.called.isNotBlank())
            raw.called else raw.caller
        return CallEntry(
                date   = date,
                type   = type,
                name   = raw.name.takeIf { it.isNotBlank() },
                number = applyPrefix(rawNumber, prefix, areaCode),
                duration = parseDurationToSeconds(raw.duration)
        )
    }

    /**
     * Parses a FritzBox duration string to total seconds.
     *
     * Both the TR-064 XML and the MyFRITZ CSV return duration in "MM:SS" format
     * (e.g. "3:42" = 3 minutes 42 seconds = 222 seconds).
     * The previous code used `toDoubleOrNull()` which always returned null for
     * this format, resulting in every call showing duration 0.
     */
    private fun parseDurationToSeconds(raw: String): Int {
        val trimmed = raw.trim()
        return if (trimmed.contains(':')) {
            val parts = trimmed.split(':')
            val minutes = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val seconds = parts.getOrNull(1)?.toIntOrNull() ?: 0
            minutes * 60 + seconds
        } else {
            // Fallback: plain number treated as seconds
            trimmed.toIntOrNull() ?: 0
        }
    }

    /**
     * Normalises a number using two optional prefixes:
     *  1. [areaCode] is prepended when the number has no leading 0, + or 00
     *     (i.e. it is a pure extension/local number like "12345").
     *     After prepending the area code the number starts with 0 so the
     *     country-prefix step below will handle it correctly.
     *  2. [prefix] (country prefix, e.g. "+49") replaces the leading 0.
     *     Skipped when blank or when the number already starts with + / 00.
     */
    private fun applyPrefix(number: String, prefix: String, areaCode: String): String {
        // Numbers already in international format are left untouched.
        if (number.startsWith("+") || number.startsWith("00")) return number

        // Pure local numbers (no leading 0): prepend area code first.
        val withArea = if (!number.startsWith("0") && areaCode.isNotBlank())
            areaCode + number
        else
            number

        // Now apply the country prefix (replaces leading 0).
        if (prefix.isBlank()) return withArea
        if (withArea.startsWith("0")) return prefix + withArea.removePrefix("0")
        return withArea
    }
}
