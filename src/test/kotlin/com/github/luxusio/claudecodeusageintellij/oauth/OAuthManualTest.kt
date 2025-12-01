package com.github.luxusio.claudecodeusageintellij.oauth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manual test for OAuth authentication flow.
 * Run this as a standalone application to test OAuth without IntelliJ.
 *
 * Usage: Run main() and follow the browser prompts.
 */
object OAuthManualTest {

    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val AUTHORIZATION_URL = "https://claude.ai/oauth/authorize"
    private const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"
    private const val USAGE_API_BASE_URL = "https://claude.ai/api"
    private const val CALLBACK_PORT = 19284
    private const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/callback"

    private val SCOPES = listOf("user:inference", "user:profile")

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    @JvmStatic
    fun main(args: Array<String>) {
        println("=" .repeat(60))
        println("Claude OAuth Manual Test")
        println("=" .repeat(60))
        println()

        // Generate PKCE codes
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        println("[1] Generated PKCE codes:")
        println("    Code Verifier: ${codeVerifier.take(20)}...")
        println("    Code Challenge: ${codeChallenge.take(20)}...")
        println("    State: $state")
        println()

        // Start callback server
        val latch = CountDownLatch(1)
        var authorizationCode: String? = null
        var authError: String? = null

        val server = startCallbackServer(state) { code, error ->
            authorizationCode = code
            authError = error
            latch.countDown()
        }

        println("[2] Started callback server on port $CALLBACK_PORT")
        println()

        // Build and open authorization URL
        val authUrl = buildAuthorizationUrl(codeChallenge, state)
        println("[3] Authorization URL:")
        println("    $authUrl")
        println()

        // Open browser
        println("[4] Opening browser for authentication...")
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI.create(authUrl))
        } else {
            println("    Desktop not supported. Please open the URL manually.")
        }
        println()

        println("[5] Waiting for callback (timeout: 5 minutes)...")
        println("    Please complete authentication in the browser.")
        println()

        // Wait for callback
        val received = latch.await(5, TimeUnit.MINUTES)
        server.stop(0)

        if (!received) {
            println("[ERROR] Timeout waiting for callback")
            return
        }

        if (authError != null) {
            println("[ERROR] Authentication failed: $authError")
            return
        }

        if (authorizationCode == null) {
            println("[ERROR] No authorization code received")
            return
        }

        println("[6] Received authorization code: ${authorizationCode!!.take(20)}...")
        println()

        // Exchange code for token
        println("[7] Exchanging code for token...")
        try {
            val token = exchangeCodeForToken(authorizationCode!!, codeVerifier)
            println("    SUCCESS!")
            println("    Access Token: ${token.accessToken.take(30)}...")
            println("    Refresh Token: ${token.refreshToken?.take(30) ?: "N/A"}...")
            println("    Expires In: ${token.expiresIn} seconds")
            println()

            // Test API call
            println("[8] Testing Usage API...")
            testUsageApi(token.accessToken)

        } catch (e: Exception) {
            println("[ERROR] Token exchange failed: ${e.message}")
            e.printStackTrace()
        }

        println()
        println("=" .repeat(60))
        println("Test completed!")
        println("=" .repeat(60))
    }

    private fun startCallbackServer(
        expectedState: String,
        callback: (code: String?, error: String?) -> Unit
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress(CALLBACK_PORT), 0)

        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.query ?: ""
            val params = parseQueryParams(query)

            val code = params["code"]
            val state = params["state"]
            val error = params["error"]

            val (responseHtml, resultCode, resultError) = when {
                error != null -> {
                    Triple(
                        createHtml("Authentication Failed", "Error: $error", false),
                        null,
                        error
                    )
                }
                state != expectedState -> {
                    Triple(
                        createHtml("Authentication Failed", "Invalid state parameter", false),
                        null,
                        "Invalid state"
                    )
                }
                code == null -> {
                    Triple(
                        createHtml("Authentication Failed", "No authorization code received", false),
                        null,
                        "No code"
                    )
                }
                else -> {
                    Triple(
                        createHtml("Authentication Successful", "You can close this window.", true),
                        code,
                        null
                    )
                }
            }

            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            val bytes = responseHtml.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }

            callback(resultCode, resultError)
        }

        server.start()
        return server
    }

    private fun buildAuthorizationUrl(codeChallenge: String, state: String): String {
        val params = mapOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to SCOPES.joinToString(" "),
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        )

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

        return "$AUTHORIZATION_URL?$queryString"
    }

    private fun exchangeCodeForToken(code: String, codeVerifier: String): TokenResponse {
        val formData = mapOf(
            "grant_type" to "authorization_code",
            "client_id" to CLIENT_ID,
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to codeVerifier
        )

        val body = formData.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

        println("    Token URL: $TOKEN_URL")
        println("    Request body: grant_type=authorization_code&client_id=...&code=...&redirect_uri=...&code_verifier=...")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        println("    Response status: ${response.statusCode()}")

        if (response.statusCode() != 200) {
            println("    Response body: ${response.body()}")
            throw RuntimeException("Token exchange failed: ${response.statusCode()} - ${response.body()}")
        }

        return objectMapper.readValue(response.body(), TokenResponse::class.java)
    }

    private fun testUsageApi(accessToken: String) {
        // First, get organization ID from session
        println("    Fetching session info...")

        val sessionRequest = HttpRequest.newBuilder()
            .uri(URI.create("$USAGE_API_BASE_URL/auth/session"))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .GET()
            .build()

        val sessionResponse = httpClient.send(sessionRequest, HttpResponse.BodyHandlers.ofString())
        println("    Session API status: ${sessionResponse.statusCode()}")

        if (sessionResponse.statusCode() == 200) {
            println("    Session response: ${sessionResponse.body().take(200)}...")

            // Try to parse organization ID
            try {
                val sessionData = objectMapper.readTree(sessionResponse.body())
                val orgId = sessionData.path("account").path("memberships")
                    .firstOrNull()?.path("organization")?.path("uuid")?.asText()
                    ?: sessionData.path("account").path("organization_id")?.asText()

                if (orgId != null) {
                    println("    Organization ID: $orgId")
                    println()
                    println("    Fetching usage data...")

                    val usageRequest = HttpRequest.newBuilder()
                        .uri(URI.create("$USAGE_API_BASE_URL/organizations/$orgId/usage"))
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Type", "application/json")
                        .GET()
                        .build()

                    val usageResponse = httpClient.send(usageRequest, HttpResponse.BodyHandlers.ofString())
                    println("    Usage API status: ${usageResponse.statusCode()}")
                    println("    Usage response: ${usageResponse.body()}")
                } else {
                    println("    Could not find organization ID in session response")
                    println("    Full response: ${sessionResponse.body()}")
                }
            } catch (e: Exception) {
                println("    Failed to parse session response: ${e.message}")
            }
        } else {
            println("    Session response: ${sessionResponse.body()}")
        }
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

    private fun createHtml(title: String, message: String, success: Boolean): String {
        val color = if (success) "#4ade80" else "#f87171"
        val icon = if (success) "✓" else "✗"
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>$title</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                           display: flex; justify-content: center; align-items: center;
                           height: 100vh; margin: 0; background: #1a1a2e; color: #fff; }
                    .container { text-align: center; }
                    .icon { font-size: 64px; margin-bottom: 20px; }
                    h1 { color: $color; }
                    p { color: #a1a1aa; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="icon">$icon</div>
                    <h1>$title</h1>
                    <p>$message</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("refresh_token") val refreshToken: String?,
        @JsonProperty("expires_in") val expiresIn: Long,
        @JsonProperty("token_type") val tokenType: String?
    )
}
