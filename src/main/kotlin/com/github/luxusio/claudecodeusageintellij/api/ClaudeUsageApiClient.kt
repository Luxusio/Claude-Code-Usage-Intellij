package com.github.luxusio.claudecodeusageintellij.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.luxusio.claudecodeusageintellij.oauth.ClaudeOAuthConfig
import com.github.luxusio.claudecodeusageintellij.oauth.ClaudeOAuthService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.APP)
class ClaudeUsageApiClient {

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private var cachedUsage: UsageResponse? = null
    private var lastFetchTime: Long = 0
    private val cacheDurationMs: Long = 30_000 // 30 seconds

    companion object {
        fun getInstance(): ClaudeUsageApiClient {
            return ApplicationManager.getApplication().getService(ClaudeUsageApiClient::class.java)
        }
    }

    /**
     * Fetch usage data from Claude API
     */
    fun fetchUsage(organizationId: String, forceRefresh: Boolean = false): UsageResponse? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedUsage != null && (now - lastFetchTime) < cacheDurationMs) {
            return cachedUsage
        }

        val oauthService = ClaudeOAuthService.getInstance()
        val accessToken = oauthService.getAccessToken() ?: return null

        return try {
            val url = "${ClaudeOAuthConfig.USAGE_API_BASE_URL}/organizations/$organizationId/usage"

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("anthropic-client-platform", "claude-code")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val usage = objectMapper.readValue(response.body(), UsageResponse::class.java)
                cachedUsage = usage
                lastFetchTime = now
                usage
            } else {
                thisLogger().warn("Usage API returned ${response.statusCode()}: ${response.body()}")
                null
            }
        } catch (e: Exception) {
            thisLogger().error("Failed to fetch usage", e)
            null
        }
    }

    /**
     * Get organization ID from user profile
     */
    fun fetchOrganizationId(): String? {
        val oauthService = ClaudeOAuthService.getInstance()
        val accessToken = oauthService.getAccessToken() ?: return null

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${ClaudeOAuthConfig.USAGE_API_BASE_URL}/auth/session"))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val session = objectMapper.readValue(response.body(), SessionResponse::class.java)
                session.account?.organizationId
            } else {
                thisLogger().warn("Session API returned ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            thisLogger().error("Failed to fetch organization ID", e)
            null
        }
    }

    fun clearCache() {
        cachedUsage = null
        lastFetchTime = 0
    }
}

// API Response Models

@JsonIgnoreProperties(ignoreUnknown = true)
data class UsageResponse(
    @JsonProperty("session") val session: SessionUsage?,
    @JsonProperty("weekly") val weekly: WeeklyUsage?,
    @JsonProperty("weekly_sonnet") val weeklySonnet: WeeklyUsage?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionUsage(
    @JsonProperty("used_percent") val usedPercent: Double?,
    @JsonProperty("reset_at") val resetAt: String?,
    @JsonProperty("tokens_used") val tokensUsed: Long?,
    @JsonProperty("tokens_limit") val tokensLimit: Long?
) {
    fun getResetTime(): ZonedDateTime? {
        return resetAt?.let {
            try {
                Instant.parse(it).atZone(ZoneId.systemDefault())
            } catch (e: Exception) {
                null
            }
        }
    }

    fun getFormattedResetTime(): String {
        val resetTime = getResetTime() ?: return "Unknown"
        return resetTime.format(DateTimeFormatter.ofPattern("h:mma"))
    }

    fun getMinutesUntilReset(): Long {
        val resetTime = getResetTime() ?: return 0
        val now = ZonedDateTime.now()
        return Duration.between(now, resetTime).toMinutes().coerceAtLeast(0)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class WeeklyUsage(
    @JsonProperty("used_percent") val usedPercent: Double?,
    @JsonProperty("reset_at") val resetAt: String?,
    @JsonProperty("tokens_used") val tokensUsed: Long?,
    @JsonProperty("tokens_limit") val tokensLimit: Long?
) {
    fun getResetTime(): ZonedDateTime? {
        return resetAt?.let {
            try {
                Instant.parse(it).atZone(ZoneId.systemDefault())
            } catch (e: Exception) {
                null
            }
        }
    }

    fun getFormattedResetDate(): String {
        val resetTime = getResetTime() ?: return "Unknown"
        return resetTime.format(DateTimeFormatter.ofPattern("MMM d, h:mma"))
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionResponse(
    @JsonProperty("account") val account: AccountInfo?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountInfo(
    @JsonProperty("organization_id") val organizationId: String?,
    @JsonProperty("email") val email: String?
)
