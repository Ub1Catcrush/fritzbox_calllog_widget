package com.tvcs.fritzboxcallwidget.api

import android.util.Log
import com.tvcs.fritzboxcallwidget.model.CallEntry
import com.tvcs.fritzboxcallwidget.model.CallType
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CallRepository(private val prefs: AppPreferences) {

    companion object {
        private const val TAG = "CallRepository"
        private val FRITZ_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")

        /** Max attempts per host before giving up or trying the next host. */
        private const val MAX_RETRIES = 3

        /** Base delay between retries in ms — doubles each attempt (exponential backoff). */
        private const val RETRY_BASE_DELAY_MS = 2_000L
    }

    /**
     * Fetches the call log.
     *
     * Strategy when lanFirstFallback is enabled:
     *   1. Try LAN address (fast, low-latency in home network)
     *   2. If that fails, fall back to internet address
     *
     * Each host attempt is retried up to MAX_RETRIES times with
     * exponential backoff to handle transient connection errors
     * (brief LTE dropout, DNS hiccup, NAT re-association, etc.).
     */
    suspend fun fetchCallLog(): Result<List<CallEntry>> = withContext(Dispatchers.IO) {

        val lanHost      = prefs.fritzLanHost
        val lanPort      = prefs.fritzLanPort
        val internetHost = prefs.fritzInternetHost
        val internetPort = prefs.fritzInternetPort
        val hasInternet  = internetHost.isNotBlank()
        val useFallback  = prefs.lanFirstFallback && hasInternet

        // Build the list of (host, port) pairs to try, in order
        val candidates: List<Pair<String, Int>> = when {
            useFallback  -> listOf(lanHost to lanPort, internetHost to internetPort)
            hasInternet  -> listOf(internetHost to internetPort)
            else         -> listOf(lanHost to lanPort)
        }

        var lastError: Throwable? = null

        for ((host, port) in candidates) {
            Log.d(TAG, "Trying $host:$port (useMyFritz=${prefs.useMyFritz})")

            val result = fetchWithRetry(host, port)
            if (result.isSuccess) return@withContext result

            lastError = result.exceptionOrNull()
            Log.w(TAG, "Failed on $host:$port — ${lastError?.message}")
            // If there's another candidate to try, log the fallback
            if (candidates.last() != host to port) {
                Log.i(TAG, "Falling back to next host…")
            }
        }

        Result.failure(lastError ?: Exception("Alle Verbindungsversuche fehlgeschlagen"))
    }

    /**
     * Attempts to fetch the call list from a specific host, retrying up to
     * MAX_RETRIES times with exponential backoff on transient failures.
     */
    private suspend fun fetchWithRetry(host: String, port: Int): Result<List<CallEntry>> {
        var lastError: Throwable? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val client = FritzBoxClient(
                    host       = host,
                    port       = port,
                    username   = prefs.fritzUsername,
                    password   = prefs.fritzPassword,
                    useHttps   = prefs.useHttps,
                    useMyFritz = prefs.useMyFritz
                )
                val rawEntries = client.getCallList()
                val entries = rawEntries.mapNotNull { raw ->
                    try { mapEntry(raw, prefs.phonePrefix) }
                    catch (e: Exception) { Log.w(TAG, "Parse error: $raw", e); null }
                }.sortedByDescending { it.date }

                return Result.success(entries)

            } catch (e: Exception) {
                lastError = e
                val isLastAttempt = attempt == MAX_RETRIES - 1
                if (!isLastAttempt) {
                    val delayMs = RETRY_BASE_DELAY_MS * (1L shl attempt) // 2s, 4s, 8s
                    Log.w(TAG, "Attempt ${attempt + 1}/$MAX_RETRIES failed on $host: ${e.message}. " +
                               "Retrying in ${delayMs}ms…")
                    delay(delayMs)
                } else {
                    Log.e(TAG, "All $MAX_RETRIES attempts failed on $host: ${e.message}")
                }
            }
        }

        return Result.failure(lastError ?: Exception("Verbindung zu $host fehlgeschlagen"))
    }

    // ── Entry mapping ─────────────────────────────────────────────────────────

    private fun mapEntry(raw: FritzCallEntry, prefix: String): CallEntry {
        val type = when (raw.type) {
            1, 4  -> CallType.INCOMING
            2, 10 -> CallType.MISSED
            3     -> CallType.OUTGOING
            else  -> CallType.INCOMING
        }
        val date = try {
            LocalDateTime.parse(raw.date, FRITZ_DATE_FORMAT)
        } catch (e: Exception) {
            LocalDateTime.now()
        }
        val rawNumber = if (type == CallType.OUTGOING && raw.called.isNotBlank()) raw.called
                        else raw.caller
        val number = applyPrefix(rawNumber, prefix)
        val name   = raw.name.takeIf { it.isNotBlank() }

        return CallEntry(date = date, type = type, name = name, number = number)
    }

    private fun applyPrefix(number: String, prefix: String): String {
        if (prefix.isBlank()) return number
        if (number.startsWith("+") || number.startsWith("00")) return number
        if (number.startsWith("0")) return prefix + number.removePrefix("0")
        return prefix + number
    }
}
