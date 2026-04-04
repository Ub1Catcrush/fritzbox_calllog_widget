package com.tvcs.fritzboxcallwidget.api

import android.util.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * FritzBox TR-064 SOAP API Client
 * Implements digest authentication and SOAP calls to retrieve the call log.
 *
 * TR-064 Spec: https://avm.de/service/schnittstellen/
 * Relevant service: X_AVM-DE_OnTel:1 -> GetCallList
 */
class FritzBoxClient(
    private val host: String,       // e.g. "fritz.box" or "192.168.178.1"
    private val port: Int = 49000,  // TR-064 plain HTTP port (49443 for HTTPS)
    private val username: String,
    private val password: String,
    private val useHttps: Boolean = false
) {
    companion object {
        private const val TAG = "FritzBoxClient"
        private const val ONTEL_SERVICE = "urn:dslforum-org:service:X_AVM-DE_OnTel:1"
        private const val ONTEL_CONTROL_URL = "/upnp/control/x_contact"
        private const val SOAP_ACTION_GET_CALL_LIST = "GetCallList"
        private const val CALL_LIST_MAX_DAYS = 30
    }

    private val scheme = if (useHttps) "https" else "http"
    private val baseUrl = "$scheme://$host:$port"

    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)

        if (useHttps) {
            // Trust all certificates for local FritzBox (self-signed cert)
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

        builder.build()
    }

    /**
     * Fetches the call log URL via TR-064 GetCallList SOAP call,
     * then downloads and parses the XML call list.
     */
    @Throws(FritzBoxException::class)
    suspend fun getCallList(): List<FritzCallEntry> {
        val callListUrl = fetchCallListUrl()
        Log.d(TAG, "Call list URL: $callListUrl")
        return downloadAndParseCallList(callListUrl)
    }

    private fun fetchCallListUrl(): String {
        val soapBody = buildSoapEnvelope(
            serviceType = ONTEL_SERVICE,
            actionName = SOAP_ACTION_GET_CALL_LIST,
            arguments = mapOf("NewMaxDays" to CALL_LIST_MAX_DAYS.toString())
        )

        val url = "$baseUrl$ONTEL_CONTROL_URL"
        val soapAction = "\"$ONTEL_SERVICE#$SOAP_ACTION_GET_CALL_LIST\""

        val responseXml = performAuthenticatedSoapRequest(url, soapAction, soapBody)

        // Parse <NewCallListURL> from response
        val doc = parseXml(responseXml)
        val urlNodes: NodeList = doc.getElementsByTagName("NewCallListURL")
        if (urlNodes.length == 0) {
            throw FritzBoxException("No NewCallListURL in response")
        }
        return urlNodes.item(0).textContent.trim()
    }

    private fun downloadAndParseCallList(callListUrl: String): List<FritzCallEntry> {
        val request = Request.Builder()
            .url(callListUrl)
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body.string()

        if (!response.isSuccessful) {
            throw FritzBoxException("HTTP ${response.code} fetching call list")
        }

        return parseCallListXml(body)
    }

    private fun parseCallListXml(xml: String): List<FritzCallEntry> {
        val doc = parseXml(xml)
        val calls = doc.getElementsByTagName("Call")
        val result = mutableListOf<FritzCallEntry>()

        for (i in 0 until calls.length) {
            val call = calls.item(i) as Element
            val entry = FritzCallEntry(
                type = call.getChildText("Type").toIntOrNull() ?: 0,
                date = call.getChildText("Date"),
                name = call.getChildText("Name"),
                number = call.getChildText("Number"),
                duration = call.getChildText("Duration"),
                called = call.getChildText("Called")
            )
            result.add(entry)
        }

        return result
    }

    // -------------------------------------------------------------------------
    // SOAP / HTTP helpers
    // -------------------------------------------------------------------------

    /**
     * Performs a SOAP request with digest authentication.
     * First tries without auth; if 401, parses WWW-Authenticate header
     * and retries with proper digest credentials.
     */
    private fun performAuthenticatedSoapRequest(url: String, soapAction: String, body: String): String {
        val mediaType = "text/xml; charset=utf-8".toMediaType()
        val requestBody = body.toRequestBody(mediaType)

        // First attempt without auth
        var request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("SOAPAction", soapAction)
            .header("Content-Type", "text/xml; charset=utf-8")
            .build()

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
                .url(url)
                .post(body.toRequestBody(mediaType))
                .header("SOAPAction", soapAction)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("Authorization", authHeader)
                .build()

            response = httpClient.newCall(request).execute()
        }

        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            throw FritzBoxException("SOAP error ${response.code}: $responseBody")
        }
        return responseBody
    }

    private fun buildDigestAuthHeader(url: String, method: String, wwwAuth: String): String {
        val realm = wwwAuth.extractDigestParam("realm")
        val nonce = wwwAuth.extractDigestParam("nonce")
        val qop   = wwwAuth.extractDigestParam("qop")
        val opaque = wwwAuth.extractDigestParam("opaque")
        val uri   = java.net.URL(url).path

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val nc  = "00000001"
        val cnonce = System.currentTimeMillis().toString(16)

        val response = if (qop == "auth") {
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        return buildString {
            append("Digest username=\"$username\", ")
            append("realm=\"$realm\", ")
            append("nonce=\"$nonce\", ")
            append("uri=\"$uri\", ")
            if (qop.isNotEmpty()) {
                append("qop=$qop, nc=$nc, cnonce=\"$cnonce\", ")
            }
            append("response=\"$response\"")
            if (opaque.isNotEmpty()) append(", opaque=\"$opaque\"")
        }
    }

    private fun buildSoapEnvelope(serviceType: String, actionName: String, arguments: Map<String, String>): String {
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
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        return builder.parse(InputSource(StringReader(xml)))
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun String.extractDigestParam(param: String): String {
        val regex = Regex("""$param="([^"]*?)"""")
        return regex.find(this)?.groupValues?.get(1) ?: ""
    }

    private fun Element.getChildText(tag: String): String {
        val nodes = getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
    }
}

/**
 * Raw call entry as returned by the FritzBox XML call list.
 * Type codes:
 *   1 = incoming answered
 *   2 = missed
 *   3 = outgoing
 *   4 = incoming answered (active deflection)
 *   9 = active, ongoing call (ignore)
 *  10 = rejected
 */
data class FritzCallEntry(
    val type: Int,
    val date: String,   // Format: "DD.MM.YY HH:MM"
    val name: String,
    val number: String,
    val duration: String,
    val called: String
)

class FritzBoxException(message: String, cause: Throwable? = null) : Exception(message, cause)
