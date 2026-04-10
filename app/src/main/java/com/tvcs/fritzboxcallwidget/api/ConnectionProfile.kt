package com.tvcs.fritzboxcallwidget.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents one connection option to the FritzBox.
 *
 * Three types are supported:
 *   LAN_TR064      – local network, TR-064 SOAP
 *   INTERNET_TR064 – remote access, TR-064 SOAP
 *   INTERNET_MYFRITZ – remote access, MyFRITZ Session API (HTTP/HTTPS)
 */
enum class ConnectionType {
    LAN_TR064,
    INTERNET_TR064,
    INTERNET_MYFRITZ
}

data class ConnectionProfile(
    val type: ConnectionType,
    val host: String,
    val port: Int,
    val useHttps: Boolean,
    val enabled: Boolean = true
) {
    val displayName: String get() = when (type) {
        ConnectionType.LAN_TR064        -> "LAN TR-064"
        ConnectionType.INTERNET_TR064   -> "Internet TR-064"
        ConnectionType.INTERNET_MYFRITZ -> "Internet MyFRITZ"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type",     type.name)
        put("host",     host)
        put("port",     port)
        put("useHttps", useHttps)
        put("enabled",  enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = ConnectionProfile(
            type     = ConnectionType.valueOf(o.getString("type")),
            host     = o.getString("host"),
            port     = o.getInt("port"),
            useHttps = o.getBoolean("useHttps"),
            enabled  = o.optBoolean("enabled", true)
        )

        /** Default ordered list — sensible out-of-the-box defaults. */
        fun defaults() = listOf(
            ConnectionProfile(ConnectionType.LAN_TR064,        "fritz.box",   49000, false),
            ConnectionProfile(ConnectionType.INTERNET_TR064,   "",            49000, false, enabled = false),
            ConnectionProfile(ConnectionType.INTERNET_MYFRITZ, "",            80,    false, enabled = false)
        )
    }
}

// ── JSON list serialisation ────────────────────────────────────────────────

fun List<ConnectionProfile>.toJsonString(): String =
    JSONArray().also { arr -> forEach { arr.put(it.toJson()) } }.toString()

fun profilesFromJsonString(json: String): List<ConnectionProfile> = try {
    val arr = JSONArray(json)
    (0 until arr.length()).map { ConnectionProfile.fromJson(arr.getJSONObject(it)) }
} catch (_: Exception) {
    ConnectionProfile.defaults()
}
