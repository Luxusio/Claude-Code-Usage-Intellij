package com.github.luxusio.claudecodeusageintellij.oauth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class ClaudeOAuthService {

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private var cachedToken: OAuthToken? = null
    private var callbackServer: HttpServer? = null

    companion object {
        private const val CREDENTIAL_SERVICE = "ClaudeCodeUsagePlugin"
        private const val CREDENTIAL_KEY = "oauth_token"

        fun getInstance(): ClaudeOAuthService {
            return ApplicationManager.getApplication().getService(ClaudeOAuthService::class.java)
        }
    }

    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        val token = getStoredToken()
        return token != null && !token.isExpired()
    }

    /**
     * Get valid access token (refreshes if needed)
     */
    fun getAccessToken(): String? {
        val token = getStoredToken() ?: return null

        if (token.isExpired()) {
            return try {
                val refreshed = refreshToken(token.refreshToken)
                saveToken(refreshed)
                refreshed.accessToken
            } catch (e: Exception) {
                thisLogger().warn("Failed to refresh token: ${e.message}")
                clearToken()
                null
            }
        }

        return token.accessToken
    }

    /**
     * Start OAuth authentication flow
     */
    fun authenticate(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        try {
            // Generate PKCE codes
            val codeVerifier = generateCodeVerifier()
            val codeChallenge = generateCodeChallenge(codeVerifier)
            val state = generateState()

            // Start local callback server
            startCallbackServer(state, codeVerifier, future)

            // Build authorization URL
            val authUrl = buildAuthorizationUrl(codeChallenge, state)

            // Open browser
            BrowserUtil.browse(authUrl)

            // Timeout after 5 minutes
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    if (!future.isDone) {
                        future.get(5, TimeUnit.MINUTES)
                    }
                } catch (e: Exception) {
                    if (!future.isDone) {
                        future.complete(false)
                    }
                } finally {
                    stopCallbackServer()
                }
            }

        } catch (e: Exception) {
            thisLogger().error("OAuth authentication failed", e)
            future.complete(false)
        }

        return future
    }

    /**
     * Logout - clear stored token
     */
    fun logout() {
        clearToken()
        cachedToken = null
    }

    private fun buildAuthorizationUrl(codeChallenge: String, state: String): String {
        val params = mapOf(
            "client_id" to ClaudeOAuthConfig.CLIENT_ID,
            "redirect_uri" to ClaudeOAuthConfig.REDIRECT_URI,
            "response_type" to "code",
            "scope" to ClaudeOAuthConfig.SCOPES.joinToString(" "),
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        )

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

        return "${ClaudeOAuthConfig.AUTHORIZATION_URL}?$queryString"
    }

    private fun startCallbackServer(
        expectedState: String,
        codeVerifier: String,
        future: CompletableFuture<Boolean>
    ) {
        stopCallbackServer()

        callbackServer = HttpServer.create(InetSocketAddress(ClaudeOAuthConfig.CALLBACK_PORT), 0).apply {
            createContext("/callback") { exchange ->
                try {
                    val query = exchange.requestURI.query ?: ""
                    val params = parseQueryParams(query)

                    val code = params["code"]
                    val state = params["state"]
                    val error = params["error"]

                    val (responseHtml, success) = when {
                        error != null -> {
                            createErrorHtml("Authentication failed: $error") to false
                        }
                        state != expectedState -> {
                            createErrorHtml("Invalid state parameter") to false
                        }
                        code == null -> {
                            createErrorHtml("No authorization code received") to false
                        }
                        else -> {
                            try {
                                val token = exchangeCodeForToken(code, codeVerifier)
                                saveToken(token)
                                createSuccessHtml() to true
                            } catch (e: Exception) {
                                thisLogger().error("Token exchange failed", e)
                                createErrorHtml("Token exchange failed: ${e.message}") to false
                            }
                        }
                    }

                    exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                    val bytes = responseHtml.toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }

                    future.complete(success)

                } catch (e: Exception) {
                    thisLogger().error("Callback handling failed", e)
                    future.complete(false)
                }
            }
            start()
        }
    }

    private fun stopCallbackServer() {
        callbackServer?.stop(0)
        callbackServer = null
    }

    private fun exchangeCodeForToken(code: String, codeVerifier: String): OAuthToken {
        val formData = mapOf(
            "grant_type" to "authorization_code",
            "client_id" to ClaudeOAuthConfig.CLIENT_ID,
            "code" to code,
            "redirect_uri" to ClaudeOAuthConfig.REDIRECT_URI,
            "code_verifier" to codeVerifier
        )

        val body = formData.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(ClaudeOAuthConfig.TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RuntimeException("Token exchange failed: ${response.statusCode()} - ${response.body()}")
        }

        val tokenResponse = objectMapper.readValue(response.body(), TokenResponse::class.java)
        return OAuthToken(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken ?: "",
            expiresAt = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000)
        )
    }

    private fun refreshToken(refreshToken: String): OAuthToken {
        val formData = mapOf(
            "grant_type" to "refresh_token",
            "client_id" to ClaudeOAuthConfig.CLIENT_ID,
            "refresh_token" to refreshToken
        )

        val body = formData.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(ClaudeOAuthConfig.TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RuntimeException("Token refresh failed: ${response.statusCode()}")
        }

        val tokenResponse = objectMapper.readValue(response.body(), TokenResponse::class.java)
        return OAuthToken(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken ?: refreshToken,
            expiresAt = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000)
        )
    }

    private fun getStoredToken(): OAuthToken? {
        if (cachedToken != null) return cachedToken

        val credentialAttributes = createCredentialAttributes()
        val credentials = PasswordSafe.instance.get(credentialAttributes) ?: return null

        return try {
            val json = credentials.getPasswordAsString() ?: return null
            objectMapper.readValue(json, OAuthToken::class.java).also {
                cachedToken = it
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to parse stored token", e)
            null
        }
    }

    private fun saveToken(token: OAuthToken) {
        cachedToken = token
        val json = objectMapper.writeValueAsString(token)
        val credentialAttributes = createCredentialAttributes()
        PasswordSafe.instance.set(credentialAttributes, Credentials("", json))
    }

    private fun clearToken() {
        cachedToken = null
        val credentialAttributes = createCredentialAttributes()
        PasswordSafe.instance.set(credentialAttributes, null)
    }

    private fun createCredentialAttributes(): CredentialAttributes {
        return CredentialAttributes(generateServiceName(CREDENTIAL_SERVICE, CREDENTIAL_KEY))
    }

    // PKCE helpers
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(StandardCharsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
    }

    private fun createSuccessHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Authentication Successful</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                       display: flex; justify-content: center; align-items: center; height: 100vh;
                       margin: 0; background: #1a1a2e; color: #fff; }
                .container { text-align: center; }
                .icon { font-size: 64px; margin-bottom: 20px; }
                h1 { color: #4ade80; }
                p { color: #a1a1aa; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="icon">✓</div>
                <h1>Authentication Successful!</h1>
                <p>You can close this window and return to IntelliJ IDEA.</p>
            </div>
        </body>
        </html>
    """.trimIndent()

    private fun createErrorHtml(error: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Authentication Failed</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                       display: flex; justify-content: center; align-items: center; height: 100vh;
                       margin: 0; background: #1a1a2e; color: #fff; }
                .container { text-align: center; }
                .icon { font-size: 64px; margin-bottom: 20px; }
                h1 { color: #f87171; }
                p { color: #a1a1aa; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="icon">✗</div>
                <h1>Authentication Failed</h1>
                <p>$error</p>
            </div>
        </body>
        </html>
    """.trimIndent()
}

data class OAuthToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt - 60_000 // 1 min buffer
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String?,
    @JsonProperty("expires_in") val expiresIn: Long,
    @JsonProperty("token_type") val tokenType: String?
)
