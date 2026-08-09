package com.aiproxyoauth.provider.anthropic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnthropicRequestOptionsTest {
    @Test
    void validatesAndDeduplicatesClientBetasAndSafeHeaders() {
        AnthropicRequestOptions options = AnthropicRequestOptions.nativeRequest(
                "tools-2026-01-01, oauth-2025-04-20,tools-2026-01-01",
                Map.of(
                        "X-Claude-Code-Session-Id", "session-1",
                        "Anthropic-User-Profile-Id", "profile-1"
                )
        );

        assertEquals(List.of("tools-2026-01-01", "oauth-2025-04-20"),
                options.clientBetas());
        assertEquals("session-1", options.headers().get("X-Claude-Code-Session-Id"));
    }

    @Test
    void rejectsHeaderInjectionAndUnapprovedHeaders() {
        assertThrows(IllegalArgumentException.class,
                () -> AnthropicRequestOptions.nativeRequest("good-beta\r\nevil: yes", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> AnthropicRequestOptions.nativeRequest("good-beta", Map.of(
                        "Authorization", "Bearer attacker")));
        assertThrows(IllegalArgumentException.class,
                () -> AnthropicRequestOptions.nativeRequest("good-beta", Map.of(
                        "X-Arbitrary", "value")));
    }
}
