package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.chat.ChatRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AnthropicWire {
    private AnthropicWire() {
    }

    public static Request build(
            ChatRequest request,
            AnthropicCompatibilityProfile profile
    ) throws AnthropicTranslationException {
        Objects.requireNonNull(profile, "profile");
        String body = new AnthropicRequestTranslator(profile).translate(request).toString();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "AIProxyOauth/1.3.0");
        headers.put("x-app", "AIProxyOauth");
        headers.put("anthropic-version", profile.anthropicVersion());
        headers.put(
                "anthropic-beta",
                profile.claudeCodeBeta() + "," + profile.oauthBeta()
        );
        headers.put("anthropic-dangerous-direct-browser-access", "true");
        return new Request(profile.messagesUri(), body, headers);
    }

    public record Request(URI uri, String body, Map<String, String> headers) {
        public Request {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }
    }
}
