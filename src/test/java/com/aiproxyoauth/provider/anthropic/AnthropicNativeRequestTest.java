package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import com.aiproxyoauth.provider.ProviderRouter;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicNativeRequestTest {
    private final AnthropicCompatibilityProfile profile =
            AnthropicCompatibilityProfile.claudeCodeOAuth();
    private final ProviderRouter router = new ProviderRouter(List.of(
            new ProviderModel(
                    "claude-sonnet-4-5", "Sonnet", ProviderId.ANTHROPIC,
                    List.of("sonnet"), Optional.of(true), 200_000)
    ), ProviderId.ANTHROPIC);

    @Test
    void preservesNativeFieldsAndPrependsOauthPreamble() throws Exception {
        ObjectNode input = (ObjectNode) Json.MAPPER.readTree("""
                {"model":"anthropic/sonnet","max_tokens":99,"stream":false,
                 "system":"client system","service_tier":"priority",
                 "future_field":{"enabled":true},
                 "messages":[{"role":"user","content":"hello"}]}
                """);

        AnthropicNativeRequest.Prepared prepared =
                AnthropicNativeRequest.prepare(input, router, profile);

        assertEquals("claude-sonnet-4-5", prepared.body().path("model").asText());
        assertEquals(99, prepared.body().path("max_tokens").asInt());
        assertEquals("priority", prepared.body().path("service_tier").asText());
        assertTrue(prepared.body().path("future_field").path("enabled").asBoolean());
        assertEquals(profile.oauthSystemPreamble(),
                prepared.body().path("system").get(0).path("text").asText());
        assertEquals("client system",
                prepared.body().path("system").get(1).path("text").asText());
        assertEquals(false, prepared.stream());
    }

    @Test
    void preservesArraySystemBlocksAndDefaultsStreamToFalse() throws Exception {
        ObjectNode input = (ObjectNode) Json.MAPPER.readTree("""
                {"model":"claude-future","max_tokens":20,
                 "system":[{"type":"text","text":"cached","cache_control":{"type":"ephemeral"}}],
                 "messages":[{"role":"user","content":"hello"}]}
                """);

        AnthropicNativeRequest.Prepared prepared =
                AnthropicNativeRequest.prepare(input, router, profile);

        assertEquals("claude-future", prepared.body().path("model").asText());
        assertEquals(2, prepared.body().path("system").size());
        assertEquals("ephemeral", prepared.body().path("system").get(1)
                .path("cache_control").path("type").asText());
        assertEquals(false, prepared.stream());
    }

    @Test
    void rejectsCodexRouteAndInvalidSystemOrStream() throws Exception {
        ObjectNode codex = body("gpt-5.4");
        ObjectNode invalidSystem = body("claude-test");
        invalidSystem.put("system", 42);
        ObjectNode invalidStream = body("claude-test");
        invalidStream.put("stream", "yes");

        assertThrows(AnthropicTranslationException.class,
                () -> AnthropicNativeRequest.prepare(codex, router, profile));
        assertThrows(AnthropicTranslationException.class,
                () -> AnthropicNativeRequest.prepare(invalidSystem, router, profile));
        assertThrows(AnthropicTranslationException.class,
                () -> AnthropicNativeRequest.prepare(invalidStream, router, profile));
    }

    private static ObjectNode body(String model) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 20);
        root.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return root;
    }
}
