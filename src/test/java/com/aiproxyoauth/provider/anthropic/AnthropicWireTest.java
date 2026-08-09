package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.chat.ChatRequest;
import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicWireTest {
    @Test
    void buildsProfilePinnedMessageWireWithoutCredentials() throws Exception {
        ChatRequest request = new ChatRequest(
                "claude-haiku-4-5",
                List.of(new ChatRequest.Message(
                        ChatRequest.Role.USER,
                        List.of(new ChatRequest.Text("hello"))
                )),
                List.of(),
                new ChatRequest.ToolChoice.None(),
                8192,
                null,
                null,
                List.of(),
                null,
                false
        );

        AnthropicWire.Request wire = AnthropicWire.build(
                request, AnthropicCompatibilityProfile.claudeCodeOAuth());

        assertEquals(
                AnthropicCompatibilityProfile.claudeCodeOAuth().messagesUri(),
                wire.uri()
        );
        assertEquals("application/json", wire.headers().get("Content-Type"));
        assertEquals("2023-06-01", wire.headers().get("anthropic-version"));
        assertEquals(
                "claude-code-20250219,oauth-2025-04-20",
                wire.headers().get("anthropic-beta")
        );
        assertFalse(wire.headers().containsKey("Authorization"));
        assertFalse(wire.headers().containsKey("x-api-key"));
        assertTrue(Json.MAPPER.readTree(wire.body()).path("stream").asBoolean());
    }
}
