package com.tvcs.fritzboxcallwidget.api

import android.util.Log
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * FritzBox TR-064 SOAP API Client + MyFRITZ Session API Client.
 *
 * Handles two connection modes:
 *   - TR-064 SOAP (default): port 49000/49443, Digest Auth
 *   - MyFRITZ Session API:   port 80/443, Challenge-Response MD5 Auth, CSV export
 *
 * Both modes include:
 *   - Longer timeouts suitable for remote (internet) access
 *   - retryOnConnectionFailure for transient network errors
 *   - Structured exception hierarchy for meaningful error messages
 */
class FritzBoxClient(
    private val host: String,
    private val port: Int = 49000,
    private val username: String,
    private val password: String,
    private val useHttps: Boolean = false,
    private val useMyFritz: Boolean = false
) {
    companion object {
        private const val TAG = "FritzBoxClient"
        private const val ONTEL_SERVICE    = "urn:dslforum-org:service:X_AVM-DE_OnTel:1"
        private const val ONTEL_CONTROL_URL = "/upnp/control/x_contact"
        private const val SOAP_ACTION_GET_CALL_LIST = "GetCallList"
        private const val CALL_LIST_MAX_DAYS = 30
    }

    private val scheme  = if (useHttps) "https" else "http"
    private val baseUrl = "$scheme://$host:$port"

    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)   // increased for internet access
            .readTimeout(30, TimeUnit.SECONDS)       // increased for slow connections
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)          // auto-retry on transient failures

        if (useHttps) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        builder.build()
    }

    // ── Public entry point ────────────────────────────────────────────────────

    @Throws(FritzBoxException::class)
    suspend fun getCallList(): List<FritzCallEntry> =
        if (useMyFritz) getCallListByMyFritz() else getCallListByTR064()

    // ── MyFRITZ (Session-ID + CSV) ────────────────────────────────────────────

    @Throws(FritzBoxException::class)
    suspend fun getCallListByMyFritz(): List<FritzCallEntry> {
        val sid = getSid()

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("fon_num/foncalls_list.lua")
            .addQueryParameter("sid", sid)
            .addQueryParameter("csv", "")
            .build()

        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw FritzBoxException(
                        "HTTP ${response.code} beim Abruf der Anrufliste (MyFRITZ)"
                    )
                }
                val body = response.body?.string()
                    ?: throw FritzBoxException("Leere Antwort beim Abruf der Anrufliste (MyFRITZ)")
                parseCallListCsv(body)
            }
        } catch (e: FritzBoxException) {
            throw e
        } catch (e: java.net.ConnectException) {
            throw FritzBoxException("Keine Verbindung zur FritzBox möglich (MyFRITZ): ${e.message}", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw FritzBoxException("Zeitüberschreitung (MyFRITZ) — Verbindung zu $host:$port", e)
        } catch (e: java.net.UnknownHostException) {
            throw FritzBoxException("Host nicht gefunden: $host", e)
        } catch (e: javax.net.ssl.SSLException) {
            throw FritzBoxException("SSL-Fehler (MyFRITZ): ${e.message}", e)
        } catch (e: IOException) {
            throw FritzBoxException("Netzwerkfehler (MyFRITZ): ${e.message}", e)
        }
    }

    private fun getSid(): String {
        try {
            // 1. Challenge holen
            val loginUrl = "$baseUrl/login_sid.lua"
            val xml = httpClient.newCall(
                Request.Builder().url(loginUrl).build()
            ).execute().use {
                it.body?.string()
                    ?: throw FritzBoxException("Keine Antwort auf Login-Challenge von $host")
            }

            val challenge   = xml.substringAfter("<Challenge>").substringBefore("</Challenge>")
            val currentSid  = xml.substringAfter("<SID>").substringBefore("</SID>")

            if (currentSid != "0000000000000000") return currentSid

            if (challenge.isBlank()) {
                throw FritzBoxException("Ungültige Login-Challenge von $host — evtl. falscher Port oder Protokoll")
            }

            // 2. Response berechnen (UTF-16LE Challenge-Password MD5)
            val combined = "$challenge-$password".toByteArray(StandardCharsets.UTF_16LE)
            val hash     = MessageDigest.getInstance("MD5").digest(combined)
                .joinToString("") { "%02x".format(it) }
            val response = "$challenge-$hash"

            // 3. Login durchführen
            val authUrl = loginUrl.toHttpUrl().newBuilder()
                .addQueryParameter("username", username)
                .addQueryParameter("response", response)
                .build()

            val authXml = httpClient.newCall(
                Request.Builder().url(authUrl).build()
            ).execute().use {
                it.body?.string()
                    ?: throw FritzBoxException("Keine Antwort auf Login-Request von $host")
            }

            val sid = authXml.substringAfter("<SID>").substringBefore("</SID>")
            if (sid == "0000000000000000" || sid.isBlank()) {
                throw FritzBoxException("Anmeldung fehlgeschlagen — Benutzername oder Passwort falsch")
            }
            return sid

        } catch (e: FritzBoxException) {
            throw e
        } catch (e: java.net.ConnectException) {
            throw FritzBoxException("Keine Verbindung zur FritzBox möglich (Login): ${e.message}", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw FritzBoxException("Zeitüberschreitung beim Login — Verbindung zu $host:$port", e)
        } catch (e: java.net.UnknownHostException) {
            throw FritzBoxException("Host nicht gefunden beim Login: $host", e)
        } catch (e: javax.net.ssl.SSLException) {
            throw FritzBoxException("SSL-Fehler beim Login: ${e.message}", e)
        } catch (e: IOException) {
            throw FritzBoxException("Netzwerkfehler beim Login: ${e.message}", e)
        }
    }

    // ── TR-064 SOAP ───────────────────────────────────────────────────────────

    @Throws(FritzBoxException::class)
    suspend fun getCallListByTR064(): List<FritzCallEntry> {
        val callListUrl = fetchCallListUrl()
        Log.d(TAG, "Call list URL: $callListUrl")
        return downloadAndParseCallList(callListUrl)
    }

    private fun fetchCallListUrl(): String {
        val soapBody = buildSoapEnvelope(
            serviceType = ONTEL_SERVICE,
            actionName  = SOAP_ACTION_GET_CALL_LIST,
            arguments   = mapOf("NewMaxDays" to CALL_LIST_MAX_DAYS.toString())
        )
        val url        = "$baseUrl$ONTEL_CONTROL_URL"
        val soapAction = "\"$ONTEL_SERVICE#$SOAP_ACTION_GET_CALL_LIST\""
        val responseXml = performAuthenticatedSoapRequest(url, soapAction, soapBody)

        val doc      = parseXml(responseXml)
        val urlNodes: NodeList = doc.getElementsByTagName("NewCallListURL")
        if (urlNodes.length == 0) throw FritzBoxException("Keine NewCallListURL in TR-064-Antwort")
        return urlNodes.item(0).textContent.trim()
    }

    private fun downloadAndParseCallList(callListUrl: String): List<FritzCallEntry> {
        val request = Request.Builder().url(callListUrl).build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                    ?: throw FritzBoxException("Leere Antwort beim Abruf der Anrufliste (TR-064)")
                if (!response.isSuccessful) {
                    throw FritzBoxException("HTTP ${response.code} beim Abruf der Anrufliste")
                }
                parseCallListXml(body)
            }
        } catch (e: FritzBoxException) {
            throw e
        } catch (e: IOException) {
            throw FritzBoxException("Netzwerkfehler beim Abruf der Anrufliste: ${e.message}", e)
        }
    }

    private fun performAuthenticatedSoapRequest(
        url: String, soapAction: String, body: String
    ): String {
        val mediaType   = "text/xml; charset=utf-8".toMediaType()
        val requestBody = body.toRequestBody(mediaType)

        var request = Request.Builder()
            .url(url).post(requestBody)
            .header("SOAPAction", soapAction)
            .header("Content-Type", "text/xml; charset=utf-8")
            .build()

        try {
            var response = httpClient.newCall(request).execute()

            if (response.code == 401) {
                val wwwAuth = response.header("WWW-Authenticate") ?: ""
                response.close()

                val authHeader = if (wwwAuth.startsWith("Digest", ignoreCase = true)) {
                    buildDigestAuthHeader(url, "POST", wwwAuth)
                } else {
                    Credentials.basic(username, password)
                }

                request = Request.Builder()
                    .url(url).post(body.toRequestBody(mediaType))
                    .header("SOAPAction", soapAction)
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("Authorization", authHeader)
                    .build()

                response = httpClient.newCall(request).execute()
            }

            val responseBody = response.body?.string()
                ?: throw FritzBoxException("Leere SOAP-Antwort von $host")
            if (!response.isSuccessful) {
                throw FritzBoxException("SOAP-Fehler ${response.code}: $responseBody")
            }
            return responseBody

        } catch (e: FritzBoxException) {
            throw e
        } catch (e: java.net.ConnectException) {
            throw FritzBoxException("Keine Verbindung zur FritzBox möglich: ${e.message}", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw FritzBoxException("Zeitüberschreitung — Verbindung zu $host:$port", e)
        } catch (e: java.net.UnknownHostException) {
            throw FritzBoxException("Host nicht gefunden: $host", e)
        } catch (e: javax.net.ssl.SSLException) {
            throw FritzBoxException("SSL-/Zertifikatsfehler: ${e.message}", e)
        } catch (e: IOException) {
            throw FritzBoxException("Netzwerkfehler: ${e.message}", e)
        }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseCallListCsv(csv: String): List<FritzCallEntry> {
        val lines = csv.lines()
        if (lines.size < 2) return emptyList()
        return lines.drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val c = line.split(";")
                if (c.size < 6) return@mapNotNull null
                FritzCallEntry(
                    type     = c.getOrNull(0)?.trim()?.toIntOrNull() ?: 0,
                    date     = c.getOrNull(1)?.trim() ?: "",
                    name     = c.getOrNull(2)?.trim() ?: "",
                    caller   = c.getOrNull(3)?.trim() ?: "",
                    called   = c.getOrNull(5)?.trim() ?: "",
                    duration = c.getOrNull(6)?.trim() ?: ""
                )
            }
    }

    private fun parseCallListXml(xml: String): List<FritzCallEntry> {
        val doc   = parseXml(xml)
        val calls = doc.getElementsByTagName("Call")
        return (0 until calls.length).map { i ->
            val call = calls.item(i) as Element
            FritzCallEntry(
                type     = call.getChildText("Type").toIntOrNull() ?: 0,
                date     = call.getChildText("Date"),
                name     = call.getChildText("Name"),
                duration = call.getChildText("Duration"),
                caller   = call.getChildText("Caller"),
                called   = call.getChildText("Called")
            )
        }
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    private fun buildDigestAuthHeader(url: String, method: String, wwwAuth: String): String {
        val realm  = wwwAuth.extractDigestParam("realm")
        val nonce  = wwwAuth.extractDigestParam("nonce")
        val qop    = wwwAuth.extractDigestParam("qop")
        val opaque = wwwAuth.extractDigestParam("opaque")
        val uri    = java.net.URL(url).path
        val ha1    = md5("$username:$realm:$password")
        val ha2    = md5("$method:$uri")
        val nc     = "00000001"
        val cnonce = System.currentTimeMillis().toString(16)
        val resp   = if (qop == "auth") md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
                     else               md5("$ha1:$nonce:$ha2")

        return buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", ")
            if (qop.isNotEmpty()) append("qop=$qop, nc=$nc, cnonce=\"$cnonce\", ")
            append("response=\"$resp\"")
            if (opaque.isNotEmpty()) append(", opaque=\"$opaque\"")
        }
    }

    private fun buildSoapEnvelope(
        serviceType: String, actionName: String, arguments: Map<String, String>
    ): String {
        val argsXml = arguments.entries.joinToString("") { (k, v) -> "<$k>$v</$k>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$actionName xmlns:u="$serviceType">
      $argsXml
    </u:$actionName>
  </s:Body>
</s:Envelope>"""
    }

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = false }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun md5(input: String): String {
        val hash = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun String.extractDigestParam(param: String): String =
        Regex("""$param="([^"]*?)"""").find(this)?.groupValues?.get(1) ?: ""

    private fun Element.getChildText(tag: String): String {
        val nodes = getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
    }
}

data class FritzCallEntry(
    val type: Int,
    val date: String,
    val name: String,
    val duration: String,
    val caller: String,
    val called: String
)

class FritzBoxException(message: String, cause: Throwable? = null) : Exception(message, cause)
