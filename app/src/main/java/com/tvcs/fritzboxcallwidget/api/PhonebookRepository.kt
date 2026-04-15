package com.tvcs.fritzboxcallwidget.api

import android.util.Log
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Provides contact name lookup against the FritzBox phonebook.
 *
 * The phonebook is fetched once per process lifetime and cached in memory.
 * Call [invalidate] to force a refresh on the next lookup.
 *
 * Usage:
 *   val name = PhonebookRepository.lookupName(number, prefs)
 *   // Returns null if lookup is disabled, number not found, or fetch failed.
 */
object PhonebookRepository {

    private const val TAG = "PhonebookRepository"

    /** null = not yet loaded; empty map = loaded but empty / error */
    private val cache = AtomicReference<Map<String, String>?>(null)

    fun invalidate() { cache.set(null) }

    /**
     * Looks up [number] in the FritzBox phonebook.
     * Returns the contact name, or null if not found / lookup disabled.
     * Loads the phonebook on first call (cached for subsequent calls).
     */
    suspend fun lookupName(number: String, prefs: AppPreferences): String? {
        if (!prefs.phonebookLookupEnabled) return null
        if (number.isBlank()) return null

        val book = getOrLoad(prefs) ?: return null

        // Try exact match first, then suffix match (last 7 digits)
        val normalised = normalise(number)
        if (normalised.isBlank()) return null

        book[normalised]?.let { return it }

        // Suffix match: match on the last min(7, len) digits
        val suffix = normalised.takeLast(7)
        return book.entries.firstOrNull { it.key.endsWith(suffix) }?.value
    }

    private suspend fun getOrLoad(prefs: AppPreferences): Map<String, String>? {
        cache.get()?.let { return it }

        return withContext(Dispatchers.IO) {
            val profiles = prefs.getOrderedProfiles().filter {
                it.enabled && it.host.isNotBlank() &&
                it.type != ConnectionType.INTERNET_MYFRITZ
            }
            if (profiles.isEmpty()) {
                Log.w(TAG, "No TR-064 profile available for phonebook lookup")
                return@withContext null
            }
            for (profile in profiles) {
                runCatching {
                    val client = FritzBoxClient(profile, prefs.fritzUsername, prefs.fritzPassword)
                    val book   = client.getPhonebook()
                    cache.set(book)
                    Log.d(TAG, "Phonebook loaded: ${book.size} entries from ${profile.host}")
                    return@withContext book
                }.onFailure {
                    Log.w(TAG, "Phonebook fetch failed on ${profile.host}: ${it.message}")
                }
            }
            // All profiles failed — store empty map to avoid hammering
            cache.set(emptyMap())
            null
        }
    }

    private fun normalise(raw: String): String {
        var n = raw.replace(Regex("[\\s\\-()/.+]"), "")
        if (n.startsWith("0049")) n = n.removePrefix("0049")
        if (n.startsWith("49") && n.length > 10) n = n.removePrefix("49")
        return n.trimStart('0').ifEmpty { n }
    }
}
