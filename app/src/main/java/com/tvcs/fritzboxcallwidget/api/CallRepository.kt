package com.tvcs.fritzboxcallwidget.api

import android.content.Context
import android.util.Log
import com.tvcs.fritzboxcallwidget.model.CallEntry
import com.tvcs.fritzboxcallwidget.model.CallType
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * opens a short-lived TCP socket to the profile's host:port (3 s timeout).
 * If the probe fails the profile is skipped immediately without starting any
 * HTTP request.  This prevents the widget from hanging — and avoids the Android
 * ANR dialog — when the device is on a network that has no route to the FritzBox
 * (e.g. VPN active, public WiFi, or no LAN connection).
 *
 * Thread safety: [cachedEntriesRef] uses AtomicReference for safe concurrent
 * read/write (@Volatile alone is insufficient for check-then-set patterns).
 *
 * Retry strategy: exponential backoff (2 s / 4 s / 8 s) per profile.
 * Cache policy: errors never clear the cache — last successful result is
 * always returned as a fallback.
 */
class CallRepository(private val prefs: AppPreferences) {

    companion object {
        private const val TAG           = "CallRepository"
        private val FRITZ_DATE_FORMAT   = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
        private const val MAX_RETRIES   = 3
        private const val RETRY_BASE_MS = 2_000L

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

        // Respect the fallback-reset: start from profile index 0 if reset,
        // otherwise continue from where the last attempt left off.
        val startIdx = prefs.activeProfileFallbackIndex.coerceIn(0, profiles.lastIndex)
        val orderedProfiles = profiles.drop(startIdx) + profiles.take(startIdx)

        for (profile in orderedProfiles) {
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
                val currentIdx = profiles.indexOf(profile)
                if (currentIdx >= 0 && currentIdx + 1 < profiles.size) {
                    prefs.activeProfileFallbackIndex = currentIdx + 1
                }
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
                prefs.activeProfileFallbackIndex = 0  // reset for next fresh cycle
                onProgress(Progress("${filtered.size} Anrufe geladen von ${profile.displayName}"))
                return@withContext Result.success(filtered)
            }

            lastError = result.exceptionOrNull()
            // Advance fallback index so the next trigger skips this failed profile
            val currentIdx = profiles.indexOf(profile)
            if (currentIdx >= 0 && currentIdx + 1 < profiles.size) {
                prefs.activeProfileFallbackIndex = currentIdx + 1
            }
            onProgress(Progress(
                "${profile.displayName} fehlgeschlagen: ${lastError?.message}",
                isError = true
            ))
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
                val rawEntries = client.getCallList()
                val entries = rawEntries.mapNotNull { raw ->
                    try { mapEntry(raw, prefs.phonePrefix, prefs.localAreaCode) }
                    catch (e: Exception) {
                        Log.w(TAG, "Skipping unparseable entry: $raw", e)
                        null
                    }
                }.sortedByDescending { it.date }
                return Result.success(entries)
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
