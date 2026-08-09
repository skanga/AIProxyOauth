package com.aiproxyoauth.provider.anthropic;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, non-secret Claude Code OAuth and Anthropic wire constants.
 *
 * <p>These values are compatibility-sensitive and intentionally centralized so a future upstream
 * change does not require edits throughout authentication and request handlers.
 */
public record AnthropicCompatibilityProfile(
        String name,
        String clientId,
        URI authorizationUri,
        URI tokenUri,
        URI redirectUri,
        URI messagesUri,
        URI modelsUri,
        List<String> scopes,
        String anthropicVersion,
        String oauthBeta,
        String claudeCodeBeta,
        String oauthSystemPreamble
) {
    private static final AnthropicCompatibilityProfile CLAUDE_CODE_OAUTH =
            new AnthropicCompatibilityProfile(
                    "claude-code-oauth-2026-07",
                    "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
                    URI.create("https://claude.ai/oauth/authorize"),
                    URI.create("https://platform.claude.com/v1/oauth/token"),
                    URI.create("https://platform.claude.com/oauth/code/callback"),
                    URI.create("https://api.anthropic.com/v1/messages?beta=true"),
                    URI.create("https://api.anthropic.com/v1/models?limit=100"),
                    List.of(
                            "user:profile",
                            "user:inference",
                            "user:sessions:claude_code",
                            "user:mcp_servers",
                            "user:file_upload"
                    ),
                    "2023-06-01",
                    "oauth-2025-04-20",
                    "claude-code-20250219",
                    "You are Claude Code, Anthropic's official CLI for Claude."
            );

    public AnthropicCompatibilityProfile {
        name = requireNonBlank(name, "name");
        clientId = requireNonBlank(clientId, "clientId");
        authorizationUri = requireSecureHttpUri(authorizationUri, "authorizationUri");
        tokenUri = requireSecureHttpUri(tokenUri, "tokenUri");
        redirectUri = requireSecureHttpUri(redirectUri, "redirectUri");
        messagesUri = requireSecureHttpUri(messagesUri, "messagesUri");
        modelsUri = requireSecureHttpUri(modelsUri, "modelsUri");
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes cannot be empty");
        }
        for (String scope : scopes) {
            requireNonBlank(scope, "scope");
        }
        anthropicVersion = requireNonBlank(anthropicVersion, "anthropicVersion");
        oauthBeta = requireNonBlank(oauthBeta, "oauthBeta");
        claudeCodeBeta = requireNonBlank(claudeCodeBeta, "claudeCodeBeta");
        oauthSystemPreamble = requireNonBlank(oauthSystemPreamble, "oauthSystemPreamble");
    }

    public static AnthropicCompatibilityProfile claudeCodeOAuth() {
        return CLAUDE_CODE_OAUTH;
    }

    private static URI requireSecureHttpUri(URI value, String name) {
        Objects.requireNonNull(value, name);
        String scheme = value.getScheme();
        String host = value.getHost();
        boolean secure = "https".equalsIgnoreCase(scheme);
        boolean loopbackHttp = "http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(host) || isIpv4Loopback(host));
        if (host == null || (!secure && !loopbackHttp)) {
            throw new IllegalArgumentException(
                    name + " must use HTTPS, except for loopback test endpoints"
            );
        }
        return value;
    }

    private static boolean isIpv4Loopback(String host) {
        if (host == null || !host.startsWith("127.")) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            for (String part : parts) {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
