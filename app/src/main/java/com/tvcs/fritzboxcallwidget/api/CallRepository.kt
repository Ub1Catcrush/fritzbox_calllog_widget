package com.tvcs.fritzboxcallwidget.api

import android.util.Log
import com.tvcs.fritzboxcallwidget.model.CallEntry
import com.tvcs.fritzboxcallwidget.model.CallType
import com.tvcs.fritzboxcallwidget.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CallRepository(private val prefs: AppPreferences) {

    companion object {
        private const val TAG = "CallRepository"
        // FritzBox date format in call list: "dd.MM.yy HH:mm"
        private val FRITZ_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
    }

    suspend fun fetchCallLog(): Result<List<CallEntry>> = withContext(Dispatchers.IO) {
        try {
            val client = FritzBoxClient(
                host = prefs.fritzHost,
                port = prefs.fritzPort,
                username = prefs.fritzUsername,
                password = prefs.fritzPassword,
                useHttps = prefs.useHttps
            )

            val rawEntries = client.getCallList()
            val entries = rawEntries.mapNotNull { raw ->
                try {
                    mapEntry(raw, prefs.phonePrefix)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse entry: $raw", e)
                    null
                }
            }.sortedByDescending { it.date }

            Result.success(entries)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch call log", e)
            Result.failure(e)
        }
    }

    private fun mapEntry(raw: FritzCallEntry, prefix: String): CallEntry {
        val type = when (raw.type) {
            1, 4 -> CallType.INCOMING
            2, 10 -> CallType.MISSED
            3 -> CallType.OUTGOING
            else -> CallType.INCOMING
        }

        val date = try {
            LocalDateTime.parse(raw.date, FRITZ_DATE_FORMAT)
        } catch (e: Exception) {
            LocalDateTime.now()
        }

        // Determine the relevant phone number:
        // For outgoing calls, "Called" is the dialled number; "Number" is our own extension.
        // For incoming, "Number" is the caller's number.
        val rawNumber = if (type == CallType.OUTGOING && raw.called.isNotBlank()) {
            raw.called
        } else {
            raw.number
        }

        // Apply prefix if number doesn't already start with + or 0
        val number = applyPrefix(rawNumber, prefix)

        val name = raw.name.takeIf { it.isNotBlank() }

        return CallEntry(
            date = date,
            type = type,
            name = name,
            number = number
        )
    }

    private fun applyPrefix(number: String, prefix: String): String {
        if (prefix.isBlank()) return number
        // Already an international number — leave untouched
        if (number.startsWith("+") || number.startsWith("00")) return number
        // Number starts with 0 (national format, e.g. 0621123456):
        // replace the leading 0 with the prefix (e.g. +49 → +49621123456)
        if (number.startsWith("0")) return prefix + number.removePrefix("0")
        // Short internal number with no prefix at all — prepend directly
        return prefix + number
    }
}
