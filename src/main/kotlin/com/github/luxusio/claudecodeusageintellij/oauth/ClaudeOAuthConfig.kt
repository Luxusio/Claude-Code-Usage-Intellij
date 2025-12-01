package com.github.luxusio.claudecodeusageintellij.oauth

object ClaudeOAuthConfig {
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val AUTHORIZATION_URL = "https://claude.ai/oauth/authorize"
    const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"
    const val USAGE_API_BASE_URL = "https://claude.ai/api"

    // Local callback server
    const val CALLBACK_PORT = 19284
    const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/callback"

    // Scopes needed for usage API
    val SCOPES = listOf(
        "user:inference",
        "user:profile"
    )
}
