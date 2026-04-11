package com.tvcs.fritzboxcallwidget.api

import android.annotation.SuppressLint
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okio.ByteString.Companion.decodeHex

/**
 * FritzBox connection client supporting:
 *   - TR-064 SOAP with HTTP Digest Auth  (LAN and Internet)
 *   - MyFRITZ Session API v2 (PBKDF2) with v1 (MD5) fallback
 *
 * All network I/O is dispatched on Dispatchers.IO via withContext.
 * Timeouts are set generously for internet access.
 */
class FritzBoxClient(
    private val profile: ConnectionProfile,
    private val username: String,
    private val password: String
) {
    companion object {
        private const val TAG                       = "FritzBoxClient"
        private const val ONTEL_SERVICE             = "urn:dslforum-org:service:X_AVM-DE_OnTel:1"
        private const val ONTEL_CONTROL_URL         = "/upnp/control/x_contact"
        private const val SOAP_ACTION_GET_CALL_LIST = "GetCallList"
        private const val CALL_LIST_MAX_DAYS        = 30
    }

    private val scheme  = if (profile.useHttps) "https" else "http"
    private val baseUrl = "$scheme://${profile.host}:${profile.port}"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (profile.useHttps) {
                    setupUnsafeSsl() // Oder besser: setupSafeSslWithCert()
                }
            }
            .build()
    }

    private fun OkHttpClient.Builder.setupUnsafeSsl() {
        val trustAllCerts = @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllCerts), java.security.SecureRandom())
        }

        sslSocketFactory(sslContext.socketFactory, trustAllCerts)
        // Nur nutzen, wenn absolut notwendig, da dies MITM-Attacken ermöglicht
        hostnameVerifier { _, _ -> true }

        // Best Practice: TLS-Versionen einschränken
        connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches the call list. Dispatches all blocking I/O on Dispatchers.IO
     * so it is safe to call from any coroutine context.
     */
    @Throws(FritzBoxException::class)
    suspend fun getCallList(): List<FritzCallEntry> = withContext(Dispatchers.IO) {
        when (profile.type) {
            ConnectionType.INTERNET_MYFRITZ -> getCallListMyFritz()
            else                            -> getCallListTR064()
        }
    }

    // ── MyFRITZ (Session API) ─────────────────────────────────────────────────

    private fun getCallListMyFritz(): List<FritzCallEntry> {
        val sid = loginMyFritz()

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("fon_num/foncalls_list.lua")
            .addQueryParameter("sid", sid)
            .addQueryParameter("csv", "")
            .build()

        return wrapIo("MyFRITZ call list fetch") {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful)
                    throw FritzBoxException("HTTP ${response.code} beim Abruf der Anrufliste (MyFRITZ)")
                val body = response.body?.string()
                    ?: throw FritzBoxException("Leere Antwort bei Anrufliste (MyFRITZ)")
                parseCallListCsv(body)
            }
        }
    }

    /**
     * MyFRITZ login. Tries Protocol v2 (PBKDF2-SHA256) first, falls back to v1 (MD5).
     * v2 reference: https://avm.de/service/schnittstellen/
     */
    private fun loginMyFritz(): String = wrapIo("MyFRITZ login") {
        val loginUrl = "$baseUrl/login_sid.lua?version=1"

        val challengeXml = httpClient.newCall(
            Request.Builder().url(loginUrl).build()
        ).execute().use { r ->
            if (!r.isSuccessful)
                throw FritzBoxException("HTTP ${r.code} beim Login-Challenge-Request")
            r.body?.string() ?: throw FritzBoxException("Leere Challenge-Antwort von ${profile.host}")
        }

        val currentSid = challengeXml.extractXmlTag("SID")
        if (currentSid != "0000000000000000") return@wrapIo currentSid

        val challenge = challengeXml.extractXmlTag("Challenge")
        if (challenge.isBlank())
            throw FritzBoxException("Ungültige Login-Challenge — falscher Port oder Protokoll?")

        val response = if (challenge.startsWith("2\$"))
            computePbkdf2Response(challenge)
        else
            computeMd5Response(challenge)

        // --- BLOCKTIME LOGIK ---
        val blockTime = challengeXml.substringAfter("<BlockTime>").substringBefore("</BlockTime>").trim().toIntOrNull() ?: 0
        if (blockTime > 0) {
            // Countdown-Schleife von blockTime bis 1
            Log.w(TAG, "FritzBox fordert eine Wartezeit von $blockTime Sekunden (Brute-Force-Schutz)")
            Thread.sleep(blockTime * 1000L)
        }
        // -----------------------

        val authUrl = loginUrl.toHttpUrl().newBuilder()
            .addQueryParameter("username", username)
            .addQueryParameter("response", response)
            .build()

        val authXml = httpClient.newCall(
            Request.Builder().url(authUrl).build()
        ).execute().use { r ->
            if (!r.isSuccessful)
                throw FritzBoxException("HTTP ${r.code} bei Login-Authentifizierung")
            r.body?.string() ?: throw FritzBoxException("Leere Auth-Antwort von ${profile.host}")
        }

        val sid = authXml.extractXmlTag("SID")
        if (sid == "0000000000000000" || sid.isBlank())
            throw FritzBoxException("Anmeldung fehlgeschlagen — Benutzername oder Passwort falsch")

        Log.d(TAG, "MyFRITZ login OK (${if (challenge.startsWith("2\$")) "v2/PBKDF2" else "v1/MD5"})")
        sid
    }

    /**
     * Protocol v1: MD5(challenge + "-" + password)
     * WICHTIG: Der String muss in UTF-16LE encodiert werden!
     */
    private fun computeMd5Response(challenge: String): String {
        val combined = "$challenge-$password".toByteArray(StandardCharsets.UTF_16LE)
        val hash = MessageDigest.getInstance("MD5").digest(combined)
            .joinToString("") { "%02x".format(it) }
        return "$challenge-$hash"
    }

    /** Protocol v2: challenge = "2$iter1$salt1$iter2$salt2" */
    private fun computePbkdf2Response(challenge: String): String {
        val parts = challenge.split("$")
        val iter1 = parts[1].toInt()
        val salt1 = parts[2].decodeHex()
        val iter2 = parts[3].toInt()
        val salt2 = parts[4].decodeHex()

        // 1. Hash: Klartext-Passwort -> hash1 (binär)
        val hash1 = pbkdf2(password.toCharArray(), salt1.toByteArray(), iter1)

        // 2. Hash: Binärer hash1 -> hash2
        // Wichtig: hash1 muss als "rohe Bytes" in Chars gemappt werden (ISO_8859_1)
        val hash1AsChars = String(hash1, Charsets.ISO_8859_1).toCharArray()
        val hash2 = pbkdf2(hash1AsChars, salt2.toByteArray(), iter2)

        // Das v2 Format verlangt oft nur den letzten Salt + Hash
        return "${parts[4]}$${hash2.joinToString("") { "%02x".format(it) }}"
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, iterations, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ── TR-064 SOAP ───────────────────────────────────────────────────────────

    private fun getCallListTR064(): List<FritzCallEntry> {
        val soapBody    = buildSoapEnvelope(ONTEL_SERVICE, SOAP_ACTION_GET_CALL_LIST,
                              mapOf("NewMaxDays" to CALL_LIST_MAX_DAYS.toString()))
        val url         = "$baseUrl$ONTEL_CONTROL_URL"
        val soapAction  = "\"$ONTEL_SERVICE#$SOAP_ACTION_GET_CALL_LIST\""
        val responseXml = performAuthenticatedSoapRequest(url, soapAction, soapBody)

        val doc      = parseXml(responseXml)
        val urlNodes: NodeList = doc.getElementsByTagName("NewCallListURL")
        if (urlNodes.length == 0)
            throw FritzBoxException("Keine NewCallListURL in TR-064-Antwort")
        val callListUrl = urlNodes.item(0).textContent.trim()

        return wrapIo("TR-064 call list download") {
            httpClient.newCall(Request.Builder().url(callListUrl).build()).execute().use { r ->
                val body = r.body?.string()
                    ?: throw FritzBoxException("Leere Antwort beim Abruf der Anrufliste")
                if (!r.isSuccessful) throw FritzBoxException("HTTP ${r.code} beim Abruf der Anrufliste")
                parseCallListXml(body)
            }
        }
    }

    private fun performAuthenticatedSoapRequest(url: String, soapAction: String, body: String): String {
        val mediaType   = "text/xml; charset=utf-8".toMediaType()
        val requestBody = body.toRequestBody(mediaType)

        return wrapIo("TR-064 SOAP request") {
            var request = Request.Builder()
                .url(url).post(requestBody)
                .header("SOAPAction", soapAction)
                .header("Content-Type", "text/xml; charset=utf-8")
                .build()

            var response = httpClient.newCall(request).execute()

            if (response.code == 401) {
                val wwwAuth = response.header("WWW-Authenticate") ?: ""
                response.close()
                val authHeader = if (wwwAuth.startsWith("Digest", ignoreCase = true))
                    buildDigestAuthHeader(url, "POST", wwwAuth)
                else
                    Credentials.basic(username, password)

                request = Request.Builder()
                    .url(url).post(body.toRequestBody(mediaType))
                    .header("SOAPAction", soapAction)
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("Authorization", authHeader)
                    .build()
                response = httpClient.newCall(request).execute()
            }

            val responseBody = response.body?.string()
                ?: throw FritzBoxException("Leere SOAP-Antwort von ${profile.host}")
            if (!response.isSuccessful)
                throw FritzBoxException("SOAP-Fehler ${response.code}: $responseBody")
            responseBody
        }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseCallListCsv(csv: String): List<FritzCallEntry> =
        csv.lines().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val c = line.split(";")
            if (c.size < 6) null
            else FritzCallEntry(
                type     = c.getOrNull(0)?.trim()?.toIntOrNull() ?: 0,
                date     = c.getOrNull(1)?.trim() ?: "",
                name     = c.getOrNull(2)?.trim() ?: "",
                caller   = c.getOrNull(3)?.trim() ?: "",
                called   = c.getOrNull(5)?.trim() ?: "",
                duration = c.getOrNull(6)?.trim() ?: ""
            )
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

    // ── XML / util helpers ────────────────────────────────────────────────────

    private fun buildSoapEnvelope(serviceType: String, actionName: String, args: Map<String, String>) =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$actionName xmlns:u="$serviceType">
      ${args.entries.joinToString("") { (k, v) -> "<$k>$v</$k>" }}
    </u:$actionName>
  </s:Body>
</s:Envelope>"""

    /**
     * Parses XML with XXE protection:
     * - DOCTYPE declarations disabled
     * - External general and parameter entities disabled
     * Prevents XML External Entity attacks from malicious FritzBox responses.
     */
    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().also { f ->
            f.isNamespaceAware = false
            // Android unterstützt setXIncludeAware nicht -> Entfernen oder in runCatching packen
            // f.isXIncludeAware = false

            runCatching {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                f.setFeature("http://xml.org/sax/features/external-general-entities", false)
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }

            f.isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }
    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).toHexString()

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val data = ByteArray(length / 2)
        for (i in data.indices)
            data[i] = ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
        return data
    }

    private fun String.extractDigestParam(param: String) =
        Regex("""$param="([^"]*?)"""").find(this)?.groupValues?.get(1) ?: ""

    private fun String.extractXmlTag(tag: String) =
        substringAfter("<$tag>", "").substringBefore("</$tag>", "").trim()

    private fun Element.getChildText(tag: String): String {
        val nodes = getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
    }

    private fun <T> wrapIo(operation: String, block: () -> T): T = try {
        block()
    } catch (e: FritzBoxException) {
        throw e
    } catch (e: java.net.ConnectException) {
        throw FritzBoxException("Keine Verbindung zu ${profile.host}:${profile.port} ($operation)", e)
    } catch (e: java.net.SocketTimeoutException) {
        throw FritzBoxException("Zeitüberschreitung bei ${profile.host}:${profile.port} ($operation)", e)
    } catch (e: java.net.UnknownHostException) {
        throw FritzBoxException("Host nicht gefunden: ${profile.host}", e)
    } catch (e: javax.net.ssl.SSLException) {
        throw FritzBoxException("SSL-Fehler bei ${profile.host} ($operation): ${e.message}", e)
    } catch (e: IOException) {
        throw FritzBoxException("Netzwerkfehler bei $operation: ${e.message}", e)
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
