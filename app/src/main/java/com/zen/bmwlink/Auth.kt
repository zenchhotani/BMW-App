package com.zen.bmwlink

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

const val DEVICE_URL = "https://customer.bmwgroup.com/gcdm/oauth/device/code"
const val TOKEN_URL = "https://customer.bmwgroup.com/gcdm/oauth/token"
const val SCOPE = "authenticate_user openid cardata:streaming:read cardata:api:read"

/** Small SharedPreferences wrapper for credentials, tokens and the last known readings. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("bmwlink", Context.MODE_PRIVATE)
    private fun str(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    var clientId: String
        get() = str("clientId")
        set(v) = put("clientId", v)
    var vin: String
        get() = str("vin")
        set(v) = put("vin", v)
    var gcid: String
        get() = str("gcid")
        set(v) = put("gcid", v)
    var accessToken: String
        get() = str("access")
        set(v) = put("access", v)
    var idToken: String
        get() = str("id")
        set(v) = put("id", v)
    var refreshToken: String
        get() = str("refresh")
        set(v) = put("refresh", v)
    var expiresAt: Long
        get() = p.getLong("expiresAt", 0L)
        set(v) = p.edit().putLong("expiresAt", v).apply()

    fun clearSession() {
        accessToken = ""; idToken = ""; refreshToken = ""; gcid = ""; expiresAt = 0L
    }

    fun saveReadings(m: Map<String, Reading>) {
        val o = JSONObject()
        m.forEach { (k, r) ->
            o.put(k, JSONObject().put("v", r.value).put("u", r.unit ?: "").put("t", r.time ?: ""))
        }
        put("readings", o.toString())
    }

    fun loadReadings(): Map<String, Reading> {
        val out = mutableMapOf<String, Reading>()
        runCatching {
            val o = JSONObject(str("readings").ifEmpty { "{}" })
            for (k in o.keys()) {
                val j = o.getJSONObject(k)
                out[k] = Reading(k, j.optString("v"), j.optString("u").ifEmpty { null }, j.optString("t").ifEmpty { null })
            }
        }
        return out
    }
}

data class DeviceFlow(
    val deviceCode: String,
    val userCode: String,
    val url: String,
    val interval: Int,
    val expiresIn: Int,
    val verifier: String
)

/** OAuth2 device-code flow with PKCE against BMW's CarData (GCDM) endpoints. */
class Auth(private val store: Store, private val http: OkHttpClient) {

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun post(url: String, form: Map<String, String>): Pair<Int, JSONObject> {
        val body = FormBody.Builder()
        form.forEach { (k, v) -> body.add(k, v) }
        val req = Request.Builder().url(url).header("Accept", "application/json").post(body.build()).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            val j = try { JSONObject(txt) } catch (e: Exception) { JSONObject().put("error", txt.take(200)) }
            return r.code to j
        }
    }

    suspend fun start(): DeviceFlow = withContext(Dispatchers.IO) {
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val (code, j) = post(
            DEVICE_URL,
            mapOf(
                "client_id" to store.clientId,
                "response_type" to "device_code",
                "scope" to SCOPE,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256"
            )
        )
        if (code !in 200..299) {
            error("BMW rejected the request (${j.optString("error")}) ${j.optString("error_description")}".trim())
        }
        val url = j.optString("verification_uri_complete").ifEmpty {
            j.optString("verification_uri") + "?user_code=" + j.optString("user_code")
        }
        DeviceFlow(j.getString("device_code"), j.optString("user_code"), url, j.optInt("interval", 5), j.optInt("expires_in", 300), verifier)
    }

    /** Polls until the user approves the login in the browser. Throws on failure or timeout. */
    suspend fun poll(f: DeviceFlow) {
        var interval = f.interval
        val deadline = System.currentTimeMillis() + f.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val (code, j) = withContext(Dispatchers.IO) {
                post(
                    TOKEN_URL,
                    mapOf(
                        "client_id" to store.clientId,
                        "device_code" to f.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "code_verifier" to f.verifier
                    )
                )
            }
            if (code in 200..299 && j.has("access_token")) {
                save(j)
                return
            }
            when (j.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                else -> error("Login failed: ${j.optString("error")} ${j.optString("error_description")}".trim())
            }
        }
        error("Login timed out. Please try again.")
    }

    /** Refreshes tokens when the ID token is within 5 minutes of expiry. Returns false if the session is dead. */
    suspend fun ensureFresh(): Boolean = withContext(Dispatchers.IO) {
        if (store.idToken.isNotEmpty() && store.expiresAt - System.currentTimeMillis() > 5 * 60_000L) {
            return@withContext true
        }
        if (store.refreshToken.isEmpty()) return@withContext false
        val (code, j) = post(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to store.refreshToken,
                "client_id" to store.clientId
            )
        )
        if (code in 200..299 && j.has("access_token")) {
            save(j)
            true
        } else {
            false
        }
    }

    private fun save(j: JSONObject) {
        store.accessToken = j.optString("access_token")
        j.optString("id_token").takeIf { it.isNotEmpty() }?.let { store.idToken = it }
        j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.refreshToken = it }
        j.optString("gcid").takeIf { it.isNotEmpty() }?.let { store.gcid = it }
        store.expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L
    }
}
