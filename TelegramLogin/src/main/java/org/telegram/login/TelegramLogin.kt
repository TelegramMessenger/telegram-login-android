package org.telegram.login

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

object TelegramLogin {

    private const val BASE_URL = "https://oauth.telegram.org"

    private var clientId: String? = null
    private var redirectUri: String? = null
    private var scopes: List<String> = emptyList()
    private var codeVerifier: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @JvmStatic
    fun init(
        clientId: String,
        redirectUri: String,
        scopes: List<String> = emptyList()
    ) {
        this.clientId = clientId
        this.redirectUri = redirectUri
        this.scopes = scopes
    }

    @JvmStatic
    fun startLogin(context: Context) {
        val cId = requireNotNull(clientId) { "TelegramLogin.init() must be called before startLogin()" }
        val rUri = requireNotNull(redirectUri) { "TelegramLogin.init() must be called before startLogin()" }

        codeVerifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(codeVerifier!!)

        scope.launch {
            when (val result = fetchInAppUrl(cId, rUri, challenge)) {
                is InAppResult.Success -> {
                    val opened = tryOpenIntent(
                        context,
                        Intent(Intent.ACTION_VIEW, Uri.parse(result.tgUrl))
                    )
                    if (!opened) {
                        openWebAuth(context, cId, rUri, challenge)
                    }
                }
                is InAppResult.Error -> {
                    openWebAuth(context, cId, rUri, challenge)
                }
            }
        }
    }

    @JvmStatic
    fun handleLoginResponse(
        uri: Uri,
        onSuccess: (LoginData) -> Unit,
        onError: (LoginError) -> Unit
    ) {
        val error = uri.getQueryParameter("error")
        if (error != null) {
            val desc = uri.getQueryParameter("error_description") ?: error
            onError(LoginError(desc))
            return
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            onError(LoginError("No authorization code in response URI"))
            return
        }

        val cId = requireNotNull(clientId) { "TelegramLogin.init() must be called before handleLoginResponse()" }
        val rUri = requireNotNull(redirectUri) { "TelegramLogin.init() must be called before handleLoginResponse()" }
        val verifier = requireNotNull(codeVerifier) { "No active login session. Call startLogin() first." }

        scope.launch {
            when (val result = exchangeCode(code, cId, rUri, verifier)) {
                is TokenResult.Success -> {
                    codeVerifier = null
                    onSuccess(LoginData(idToken = result.idToken))
                }
                is TokenResult.Error -> onError(LoginError(result.message))
            }
        }
    }

    private sealed class InAppResult {
        data class Success(val tgUrl: String) : InAppResult()
        data class Error(val message: String) : InAppResult()
    }

    private sealed class TokenResult {
        data class Success(val idToken: String) : TokenResult()
        data class Error(val message: String) : TokenResult()
    }

    private suspend fun fetchInAppUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String
    ): InAppResult = withContext(Dispatchers.IO) {
        val scopeString = buildScopeString()
        val url = Uri.parse("${BASE_URL}/crossapp").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", scopeString)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("android_sdk", "1")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }

        try {
            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: ""
                return@withContext InAppResult.Error("HTTP $statusCode: $error")
            }

            val body = connection.inputStream.bufferedReader().readText()

            val tgUrl = parseTgUrl(body)
                ?: return@withContext InAppResult.Error("No tg_url in response")

            InAppResult.Success(tgUrl)
        } catch (e: Exception) {
            InAppResult.Error(e.message ?: "Network error")
        } finally {
            connection.disconnect()
        }
    }

    private fun openWebAuth(context: Context, clientId: String, redirectUri: String, codeChallenge: String) {
        val scopeString = buildScopeString()
        val authUri = Uri.parse("${BASE_URL}/auth").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", scopeString)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, authUri)
    }

    private suspend fun exchangeCode(
        code: String,
        clientId: String,
        redirectUri: String,
        codeVerifier: String
    ): TokenResult = withContext(Dispatchers.IO) {
        val connection =
            (URL("${BASE_URL}/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }

        try {
            val postBody = buildString {
                append("grant_type=authorization_code")
                append("&client_id=")
                append(URLEncoder.encode(clientId, "UTF-8"))
                append("&code=")
                append(URLEncoder.encode(code, "UTF-8"))
                append("&redirect_uri=")
                append(URLEncoder.encode(redirectUri, "UTF-8"))
                append("&code_verifier=")
                append(URLEncoder.encode(codeVerifier, "UTF-8"))
            }

            OutputStreamWriter(connection.outputStream).use { it.write(postBody) }

            val statusCode = connection.responseCode
            val responseBody = if (statusCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: ""
            }

            if (statusCode != HttpURLConnection.HTTP_OK) {
                return@withContext TokenResult.Error("HTTP $statusCode: $responseBody")
            }

            val idToken = parseIdToken(responseBody)
                ?: return@withContext TokenResult.Error("No id_token in response")

            TokenResult.Success(idToken)
        } catch (e: Exception) {
            TokenResult.Error(e.message ?: "Network error")
        } finally {
            connection.disconnect()
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun buildScopeString(): String {
        val all = mutableListOf("openid")
        scopes.forEach { s ->
            if (s != "openid") all.add(s)
        }
        return all.joinToString(" ")
    }

    private fun tryOpenIntent(context: Context, intent: Intent): Boolean {
        return try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun parseTgUrl(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optString("url").takeIf { it.isNotEmpty() }
            ?: json.optJSONObject("result")?.optString("url")?.takeIf { it.isNotEmpty() }
    }

    private fun parseIdToken(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optString("id_token").takeIf { it.isNotEmpty() }
            ?: json.optString("result").takeIf { it.isNotEmpty() }
    }
}
