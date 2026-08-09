package com.aiproxyoauth.provider.anthropic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated non-secret options which a native Anthropic client may influence. */
public record AnthropicRequestOptions(Map<String, String> headers, List<String> clientBetas) {
    private static final int MAX_BETAS = 32;
    private static final int MAX_HEADER_VALUE_CHARACTERS = 512;
    private static final Pattern BETA = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Map<String, String> SAFE_NATIVE_HEADERS = Map.of(
            "x-claude-code-session-id", "X-Claude-Code-Session-Id",
            "x-claude-code-agent-id", "X-Claude-Code-Agent-Id",
            "x-claude-code-parent-agent-id", "X-Claude-Code-Parent-Agent-Id",
            "anthropic-user-profile-id", "Anthropic-User-Profile-Id"
    );

    public AnthropicRequestOptions {
        headers = Map.copyOf(headers);
        clientBetas = List.copyOf(clientBetas);
    }

    public static AnthropicRequestOptions nativeRequest(
            String betaHeader,
            Map<String, String> requestedHeaders
    ) {
        LinkedHashSet<String> betas = new LinkedHashSet<>();
        if (betaHeader != null && !betaHeader.isBlank()) {
            for (String raw : betaHeader.split(",")) {
                String beta = raw.strip();
                if (!BETA.matcher(beta).matches()) {
                    throw new IllegalArgumentException("Invalid anthropic-beta value");
                }
                betas.add(beta);
                if (betas.size() > MAX_BETAS) {
                    throw new IllegalArgumentException("Too many anthropic-beta values");
                }
            }
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (requestedHeaders != null) {
            requestedHeaders.forEach((name, value) -> {
                String canonical = SAFE_NATIVE_HEADERS.get(name.toLowerCase(Locale.ROOT));
                if (canonical == null) {
                    throw new IllegalArgumentException("Unsupported native Anthropic header: " + name);
                }
                validateHeaderValue(value);
                headers.put(canonical, value);
            });
        }
        return new AnthropicRequestOptions(headers, new ArrayList<>(betas));
    }

    static AnthropicRequestOptions existing(Map<String, String> headers) {
        return new AnthropicRequestOptions(
                headers == null ? Map.of() : new LinkedHashMap<>(headers), List.of());
    }

    private static void validateHeaderValue(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_HEADER_VALUE_CHARACTERS
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid native Anthropic header value");
        }
    }
}
