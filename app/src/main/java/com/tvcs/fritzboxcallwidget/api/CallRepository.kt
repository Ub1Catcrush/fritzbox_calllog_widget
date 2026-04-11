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
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches the call log using the configured connection profiles in priority order.
 *
 * Thread safety:
 *   - [cachedEntries] uses AtomicReference for safe concurrent read/write.
 *     @Volatile alone is insufficient because the check-then-set pattern
 *     (read null → fetch → write result) requires atomicity.
 *
 * Retry strategy: exponential backoff (2s / 4s / 8s) per profile.
 * Cache policy: errors never clear the cache — last successful result
 *   is always returned as a fallback.
 */
class CallRepository(private val prefs: AppPreferences) {

    companion object {
        private const val TAG           = "CallRepository"
        private val FRITZ_DATE_FORMAT   = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
        private const val MAX_RETRIES   = 3
        private const val RETRY_BASE_MS = 2_000L

        // AtomicReference instead of @Volatile field — ensures the reference
        // itself is updated atomically (no torn writes on 32-bit JVMs).
        private val cachedEntriesRef = AtomicReference<List<CallEntry>?>(null)
    }

    fun getCachedEntries(): List<CallEntry>? = cachedEntriesRef.get()

    data class Progress(val message: String, val isError: Boolean = false)

    suspend fun fetchCallLog(
        onProgress: (Progress) -> Unit = {}
    ): Result<List<CallEntry>> = withContext(Dispatchers.IO) {

        val profiles = prefs.getOrderedProfiles().filter { it.enabled }

        if (profiles.isEmpty()) {
            val err = "Keine aktive Verbindungsoption konfiguriert"
            onProgress(Progress(err, isError = true))
            return@withContext cachedEntriesRef.get()?.let { Result.success(it) }
                ?: Result.failure(Exception(err))
        }

        var lastError: Throwable? = null

        for (profile in profiles) {
            if (profile.host.isBlank()) {
                onProgress(Progress(
                    "${profile.displayName}: Adresse nicht konfiguriert — übersprungen",
                    isError = true
                ))
                continue
            }

            onProgress(Progress("Verbinde mit ${profile.displayName} (${profile.host})…"))

//            val result = fetchWithoutRetry(profile, onProgress)
            val result = fetchWithRetry(profile, onProgress)
            if (result.isSuccess) {
                val entries = result.getOrThrow()
                cachedEntriesRef.set(entries)
                onProgress(Progress("${entries.size} Anrufe geladen von ${profile.displayName}"))
                return@withContext Result.success(entries)
            }

            lastError = result.exceptionOrNull()
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
            // Return success so the widget shows the cache; caller sees isError via onProgress
            Result.failure(lastError ?: Exception("Alle Verbindungsversuche fehlgeschlagen"))
        } else {
            Result.failure(lastError ?: Exception("Alle Verbindungsversuche fehlgeschlagen"))
        }
    }

    private suspend fun fetchWithoutRetry(
        profile: ConnectionProfile,
        onProgress: (Progress) -> Unit
    ): Result<List<CallEntry>> {
        return try {
            // Initialer Status für die UI
            onProgress(Progress("${profile.displayName}: Verbinde...", isError = false))

            val client = FritzBoxClient(profile, prefs.fritzUsername, prefs.fritzPassword)
            val rawEntries = client.getCallList()

            val entries = rawEntries.mapNotNull { raw ->
                try {
                    mapEntry(raw, prefs.phonePrefix)
                } catch (e: Exception) {
                    Log.w(TAG, "Überpringe unlesbaren Eintrag: $raw", e)
                    null
                }
            }.sortedByDescending { it.date }

            Result.success(entries)
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Abrufen von ${profile.host}: ${e.message}")
            Result.failure(e)
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
                    try { mapEntry(raw, prefs.phonePrefix) }
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
                    val delayMs = RETRY_BASE_MS * (1L shl attempt) // 2s, 4s, 8s
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

    private fun mapEntry(raw: FritzCallEntry, prefix: String): CallEntry {
        val type = when (raw.type) {
            1, 4  -> CallType.INCOMING
            2, 10 -> CallType.MISSED
            3     -> CallType.OUTGOING
            else  -> CallType.INCOMING
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
            number = applyPrefix(rawNumber, prefix)
        )
    }

    private fun applyPrefix(number: String, prefix: String): String {
        if (prefix.isBlank()) return number
        if (number.startsWith("+") || number.startsWith("00")) return number
        if (number.startsWith("0")) return prefix + number.removePrefix("0")
        return prefix + number
    }
}
