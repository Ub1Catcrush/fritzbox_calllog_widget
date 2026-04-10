package com.tvcs.fritzboxcallwidget.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Step-by-step connectivity diagnostic for a single ConnectionProfile.
 *
 * All steps run on Dispatchers.IO. Each step is emitted via [onStep] as
 * it transitions from RUNNING → OK/WARN/FAIL so the UI updates live.
 *
 * Steps per profile type (6 each):
 *
 * LAN TR-064 / Internet TR-064:
 *   1. DNS / IP resolution
 *   2. TCP connect on port
 *   3. HTTP(S) basic reachability
 *   4. /tr64desc.xml — TR-064 service available
 *   5. Digest Auth + GetCallList SOAP — credentials & permissions
 *   6. Call list XML download
 *
 * Internet MyFRITZ:
 *   1. DNS / IP resolution
 *   2. TCP connect on port
 *   3. HTTP(S) basic reachability
 *   4. /login_sid.lua — login page + protocol version detection
 *   5. Authentication → SID  (PBKDF2 v2 or MD5 v1)
 *   6. CSV call list download
 */
object ConnectivityChecker {

    private const val TAG             = "ConnectivityChecker"
    private const val DNS_TIMEOUT_MS  = 5_000L
    private const val TCP_TIMEOUT_MS  = 5_000
    private const val HTTP_TIMEOUT_MS = 10_000L

    data class CheckStep(
        val label: String,
        val status: Status,
        val detail: String = ""
    ) {
        enum class Status { RUNNING, OK, WARN, FAIL }

        val icon: String get() = when (status) {
            Status.RUNNING -> "⏳"
            Status.OK      -> "✓"
            Status.WARN    -> "⚠"
            Status.FAIL    -> "✗"
        }
        override fun toString() =
            "$icon $label${if (detail.isNotBlank()) ": $detail" else ""}"
    }

    /**
     * Runs all diagnostic steps for [profile].
     * [onStep] is called on Dispatchers.IO — switch to Main for UI updates.
     */
    suspend fun check(
        profile: ConnectionProfile,
        username: String,
        password: String,
        onStep: suspend (CheckStep) -> Unit
    ): List<CheckStep> = withContext(Dispatchers.IO) {

        val steps = mutableListOf<CheckStep>()

        // Helpers — emit a step and add it to the list, updating in place if label matches
        suspend fun emit(step: CheckStep) {
            val idx = steps.indexOfFirst { it.label == step.label }
            if (idx >= 0) steps[idx] = step else steps += step
            onStep(step)
        }
        suspend fun running(label: String) =
            emit(CheckStep(label, CheckStep.Status.RUNNING))
        suspend fun ok(label: String, detail: String = "") =
            emit(CheckStep(label, CheckStep.Status.OK, detail))
        suspend fun warn(label: String, detail: String) =
            emit(CheckStep(label, CheckStep.Status.WARN, detail))
        suspend fun fail(label: String, detail: String) {
            emit(CheckStep(label, CheckStep.Status.FAIL, detail))
        }

        val client = buildHttpClient(profile.useHttps)
        val scheme  = if (profile.useHttps) "https" else "http"
        val baseUrl = "$scheme://${profile.host}:${profile.port}"

        // ── Step 1: DNS resolution ────────────────────────────────────────────
        val labelDns = "DNS / Hostname"
        running(labelDns)

        // Use withTimeoutOrNull + coroutine-safe dispatch — avoids blocking Thread.join()
        val resolved: InetAddress? = withTimeoutOrNull(DNS_TIMEOUT_MS) {
            runCatching { InetAddress.getByName(profile.host) }.getOrNull()
        }

        if (resolved == null) {
            fail(labelDns, "${profile.host} nicht auflösbar (Timeout oder kein DNS)")
            return@withContext steps
        }
        ok(labelDns, "${profile.host} → ${resolved.hostAddress}")

        // ── Step 2: TCP connect ───────────────────────────────────────────────
        val labelTcp = "TCP Verbindung Port ${profile.port}"
        running(labelTcp)
        val tcpOk = runCatching {
            Socket().use { s -> s.connect(InetSocketAddress(resolved, profile.port), TCP_TIMEOUT_MS) }
        }.isSuccess
        if (!tcpOk) {
            fail(labelTcp, "Port ${profile.port} nicht erreichbar")
            return@withContext steps
        }
        ok(labelTcp, "${resolved.hostAddress}:${profile.port} offen")

        // ── Step 3: HTTP(S) reachability ──────────────────────────────────────
        val labelHttp = if (profile.useHttps) "HTTPS Verbindung" else "HTTP Verbindung"
        running(labelHttp)
        val httpCode = runCatching {
            client.newCall(Request.Builder().url("$baseUrl/").head().build())
                .execute().also { it.close() }.code
        }.getOrElse { -1 }
        when {
            httpCode in 200..499 -> ok(labelHttp, "HTTP $httpCode")
            httpCode == -1       -> { fail(labelHttp, "Keine HTTP-Antwort"); return@withContext steps }
            else                 -> warn(labelHttp, "HTTP $httpCode")
        }

        // ── Steps 4–6: protocol-specific ─────────────────────────────────────
        when (profile.type) {
            ConnectionType.LAN_TR064,
            ConnectionType.INTERNET_TR064 ->
                checkTR064Steps(profile, baseUrl, client, username, password,
                    { l -> running(l) }, { l, d -> ok(l, d) }, { l, d -> warn(l, d) },
                    { l, d -> fail(l, d); false })

            ConnectionType.INTERNET_MYFRITZ ->
                checkMyFritzSteps(profile, baseUrl, client, username, password,
                    { l -> running(l) }, { l, d -> ok(l, d) }, { l, d -> warn(l, d) },
                    { l, d -> fail(l, d); false })
        }

        steps
    }

    // ── TR-064 steps 4–6 ─────────────────────────────────────────────────────

    private suspend fun checkTR064Steps(
        profile: ConnectionProfile, baseUrl: String, client: OkHttpClient,
        username: String, password: String,
        running: suspend (String) -> Unit,
        ok: suspend (String, String) -> Unit,
        warn: suspend (String, String) -> Unit,
        fail: suspend (String, String) -> Boolean
    ) {
        // Step 4: TR-064 service description
        val labelDesc = "TR-064 Service-Beschreibung"
        running(labelDesc)
        val descBody = runCatching {
            client.newCall(Request.Builder().url("$baseUrl/tr64desc.xml").build())
                .execute().use { r -> Pair(r.code, r.body?.string() ?: "") }
        }.getOrElse { return }
        when {
            descBody.first == 404             -> { fail(labelDesc, "tr64desc.xml nicht gefunden — falscher Port?"); return }
            !descBody.second.contains("TR-064") -> warn(labelDesc, "HTTP ${descBody.first} — kein TR-064 erkannt")
            else                              -> ok(labelDesc, "tr64desc.xml gefunden")
        }

        // Step 5: Auth + SOAP GetCallList
        val labelSoap = "TR-064 Authentifizierung"
        running(labelSoap)
        val callListUrl = runCatching {
            val soapBody = buildSoap(
                "urn:dslforum-org:service:X_AVM-DE_OnTel:1", "GetCallList",
                mapOf("NewMaxDays" to "1")
            )
            val soapUrl    = "$baseUrl/upnp/control/x_contact"
            val soapAction = "\"urn:dslforum-org:service:X_AVM-DE_OnTel:1#GetCallList\""
            val xml = performDigestSoap(client, soapUrl, soapAction, soapBody, username, password)
            parseXml(xml).getElementsByTagName("NewCallListURL")
                .item(0)?.textContent?.trim()
                ?: throw Exception("Keine NewCallListURL — Berechtigung fehlt?")
        }.getOrElse { e ->
            when {
                e.message?.contains("401") == true -> fail(labelSoap, "Falsches Passwort")
                e.message?.contains("403") == true -> fail(labelSoap, "Fehlende TR-064-Berechtigung")
                else                               -> fail(labelSoap, e.message ?: "SOAP-Fehler")
            }
            return
        }
        ok(labelSoap, "Anmeldung erfolgreich")

        // Step 6: Download call list
        val labelList = "Anrufliste herunterladen"
        running(labelList)
        runCatching {
            client.newCall(Request.Builder().url(callListUrl).build())
                .execute().use { r ->
                    val body = r.body?.string() ?: ""
                    val count = body.split("<Call>").size - 1
                    ok(labelList, "$count Anrufe gefunden")
                }
        }.onFailure { fail(labelList, it.message ?: "Download fehlgeschlagen") }
    }

    // ── MyFRITZ steps 4–6 ────────────────────────────────────────────────────

    private suspend fun checkMyFritzSteps(
        profile: ConnectionProfile, baseUrl: String, client: OkHttpClient,
        username: String, password: String,
        running: suspend (String) -> Unit,
        ok: suspend (String, String) -> Unit,
        warn: suspend (String, String) -> Unit,
        fail: suspend (String, String) -> Boolean
    ) {
        // Step 4: Login page + protocol version
        val labelLogin = "MyFRITZ Login-Seite"
        running(labelLogin)
        val challengeXml = runCatching {
            client.newCall(Request.Builder().url("$baseUrl/login_sid.lua?version=2").build())
                .execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    r.body?.string() ?: throw Exception("Leere Antwort")
                }
        }.getOrElse { e -> fail(labelLogin, e.message ?: "Nicht erreichbar"); return }

        if (!challengeXml.contains("<Challenge>")) {
            fail(labelLogin, "Keine Challenge — falscher Port oder Endpunkt")
            return
        }
        val challenge = challengeXml.substringAfter("<Challenge>").substringBefore("</Challenge>").trim()
        val proto = if (challenge.startsWith("2\$")) "v2 (PBKDF2)" else "v1 (MD5)"
        ok(labelLogin, "Challenge erhalten · Protokoll $proto")

        // Step 5: Authenticate → SID
        val labelSid = "MyFRITZ Anmeldung"
        running(labelSid)
        val existingSid = challengeXml.substringAfter("<SID>").substringBefore("</SID>").trim()
        val sid = if (existingSid != "0000000000000000") {
            ok(labelSid, "Bestehende Session aktiv")
            existingSid
        } else {
            val response = if (challenge.startsWith("2\$"))
                computePbkdf2Response(challenge, username, password)
            else
                computeMd5Response(challenge, password)

            val authUrl = "$baseUrl/login_sid.lua?version=2"
                .toHttpUrl()?.newBuilder() // Safe Call hier
                ?.addQueryParameter("username", username)
                ?.addQueryParameter("response", response)
                ?.build() ?: throw Exception("Ungültige Auth-URL") // Fallback, falls URL-Parsing fehlschlägt

            val authXml = runCatching {
                // Hier ist authUrl jetzt garantiert nicht null
                client.newCall(Request.Builder().url(authUrl).build())
                    .execute().use { r -> r.body?.string() ?: "" }
            }.getOrElse { e -> fail(labelSid, e.message ?: "Fehler"); return }

            val newSid = authXml.substringAfter("<SID>").substringBefore("</SID>").trim()
            if (newSid == "0000000000000000" || newSid.isBlank()) {
                fail(labelSid, "Anmeldung fehlgeschlagen — Benutzername oder Passwort falsch")
                return
            }
            ok(labelSid, "SID erhalten")
            newSid
        }

        // Step 6: Call list CSV
        val labelList = "Anrufliste abrufen (CSV)"
        running(labelList)
        val csvUrl = "$baseUrl/fon_num/foncalls_list.lua?version=2"
            .toHttpUrl().newBuilder()
            .addQueryParameter("sid", sid)
            .addQueryParameter("csv", "")
            .build()
        runCatching {
            client.newCall(Request.Builder().url(csvUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                val count = (r.body?.string() ?: "").lines()
                    .count { it.isNotBlank() } - 1 // minus header
                ok(labelList, "$count Anrufe gefunden")
            }
        }.onFailure { fail(labelList, it.message ?: "Abruf fehlgeschlagen") }
    }

    // ── HTTP client builder ───────────────────────────────────────────────────

    private fun buildHttpClient(useHttps: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
        if (useHttps) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            // Use TLS, not the deprecated SSL protocol identifier
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustAll, java.security.SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, trustAll[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private fun computePbkdf2Response(challenge: String, username: String, password: String): String {
        val parts = challenge.split("\$")
        if (parts.size != 5) throw Exception("Unbekanntes Challenge-Format: $challenge")
        val iter1 = parts[1].toInt(); val salt1 = parts[2].hexToBytes()
        val iter2 = parts[3].toInt(); val salt2 = parts[4].hexToBytes()
        val pw    = password.toByteArray(StandardCharsets.UTF_8)
        val hash1 = pbkdf2(pw, salt1, iter1, 32)
        val hash2 = pbkdf2(hash1, salt2, iter2, 32)
        return "$username:${hash2.toHexString()}"
    }

    private fun computeMd5Response(challenge: String, password: String): String {
        val combined = "$challenge-$password".toByteArray(StandardCharsets.UTF_16LE)
        return "$challenge-${MessageDigest.getInstance("MD5").digest(combined).toHexString()}"
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(
            password.map { it.toInt().toChar() }.toCharArray(), salt, iterations, keyLen * 8)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val data = ByteArray(length / 2)
        for (i in data.indices)
            data[i] = ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
        return data
    }

    // ── SOAP / XML helpers ────────────────────────────────────────────────────

    private fun performDigestSoap(
        client: OkHttpClient, url: String, soapAction: String, body: String,
        username: String, password: String
    ): String {
        val mt  = "text/xml; charset=utf-8".toMediaType()
        val rb  = body.toRequestBody(mt)
        var req = Request.Builder().url(url).post(rb)
            .header("SOAPAction", soapAction)
            .header("Content-Type", "text/xml; charset=utf-8")
            .build()
        var resp = client.newCall(req).execute()
        if (resp.code == 401) {
            val wwwAuth = resp.header("WWW-Authenticate") ?: ""
            resp.close()
            req = Request.Builder().url(url)
                .post(body.toRequestBody(mt))
                .header("SOAPAction", soapAction)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("Authorization", buildDigestHeader(url, "POST", wwwAuth, username, password))
                .build()
            resp = client.newCall(req).execute()
        }
        val result = resp.body?.string() ?: throw Exception("Leere SOAP-Antwort")
        if (!resp.isSuccessful) throw Exception("SOAP ${resp.code}: $result")
        return result
    }

    private fun buildDigestHeader(url: String, method: String, wwwAuth: String, u: String, p: String): String {
        fun ex(param: String) = Regex("""$param="([^"]*?)"""").find(wwwAuth)?.groupValues?.get(1) ?: ""
        val realm = ex("realm"); val nonce = ex("nonce")
        val qop   = ex("qop");   val opaque = ex("opaque")
        val uri   = java.net.URL(url).path
        val ha1   = md5("$u:$realm:$p"); val ha2 = md5("$method:$uri")
        val nc    = "00000001"; val cn = System.currentTimeMillis().toString(16)
        val r     = if (qop == "auth") md5("$ha1:$nonce:$nc:$cn:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$u\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", ")
            if (qop.isNotEmpty()) append("qop=$qop, nc=$nc, cnonce=\"$cn\", ")
            append("response=\"$r\"")
            if (opaque.isNotEmpty()) append(", opaque=\"$opaque\"")
        }
    }

    private fun buildSoap(serviceType: String, actionName: String, args: Map<String, String>) =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:$actionName xmlns:u="$serviceType">
    ${args.entries.joinToString("") { (k, v) -> "<$k>$v</$k>" }}
  </u:$actionName></s:Body>
</s:Envelope>"""

    /** XXE-safe XML parser (same protection as FritzBoxClient). */
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

    private fun md5(input: String) =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).toHexString()

}
