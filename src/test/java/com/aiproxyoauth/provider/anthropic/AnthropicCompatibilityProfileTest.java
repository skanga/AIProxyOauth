package com.aiproxyoauth.provider.anthropic;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnthropicCompatibilityProfileTest {

    @Test
    void claudeCodeOAuthProfilePinsTheKnownPublicClientContract() {
        AnthropicCompatibilityProfile profile =
                AnthropicCompatibilityProfile.claudeCodeOAuth();

        assertEquals("claude-code-oauth-2026-07", profile.name());
        assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", profile.clientId());
        assertEquals(URI.create("https://claude.ai/oauth/authorize"), profile.authorizationUri());
        assertEquals(URI.create("https://platform.claude.com/v1/oauth/token"), profile.tokenUri());
        assertEquals(
                URI.create("https://platform.claude.com/oauth/code/callback"),
                profile.redirectUri()
        );
        assertEquals("2023-06-01", profile.anthropicVersion());
        assertEquals("oauth-2025-04-20", profile.oauthBeta());
        assertEquals("claude-code-20250219", profile.claudeCodeBeta());
        assertEquals(
                "You are Claude Code, Anthropic's official CLI for Claude.",
                profile.oauthSystemPreamble()
        );
        assertEquals(List.of(
                "user:profile",
                "user:inference",
                "user:sessions:claude_code",
                "user:mcp_servers",
                "user:file_upload"
        ), profile.scopes());
    }

    @Test
    void compatibilityCollectionsAreImmutableAndConstantsAreNotSecrets() {
        AnthropicCompatibilityProfile profile =
                AnthropicCompatibilityProfile.claudeCodeOAuth();

        assertThrowsUnsupported(() -> profile.scopes().add("other"));
        assertFalse(profile.clientId().startsWith("sk-"));
    }

    private static void assertThrowsUnsupported(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                action::run
        );
    }
}
