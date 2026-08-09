package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.chat.ChatRequest;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestTranslatorTest {
    @Test
    void translatesEverySupportedFieldAndPreservesToolChronology() throws Exception {
        ChatRequest request = new ChatRequest(
                "claude-sonnet-4-5",
                List.of(
                        message(ChatRequest.Role.SYSTEM, new ChatRequest.Text("system")),
                        message(ChatRequest.Role.DEVELOPER, new ChatRequest.Text("developer")),
                        message(
                                ChatRequest.Role.USER,
                                new ChatRequest.Text("look"),
                                new ChatRequest.Image(
                                        "image/png", "png".getBytes(StandardCharsets.UTF_8))
                        ),
                        message(
                                ChatRequest.Role.ASSISTANT,
                                new ChatRequest.Reasoning("plan", "signed", null),
                                new ChatRequest.Text("checking"),
                                new ChatRequest.ToolCall("call-1", "read", "{\"path\":\"README.md\"}")
                        ),
                        message(
                                ChatRequest.Role.TOOL,
                                new ChatRequest.ToolResult("call-1", "contents", false)
                        ),
                        message(ChatRequest.Role.USER, new ChatRequest.Text("continue"))
                ),
                List.of(new ChatRequest.ToolDefinition(
                        "read",
                        "Read a file",
                        JsonNodeFactory.instance.objectNode().put("type", "object")
                )),
                new ChatRequest.ToolChoice.Named("read"),
                4096,
                0.25,
                0.9,
                List.of("STOP"),
                "high",
                false
        );

        JsonNode body = new AnthropicRequestTranslator(
                AnthropicCompatibilityProfile.claudeCodeOAuth()).translate(request);

        assertEquals("claude-sonnet-4-5", body.path("model").asText());
        assertEquals(4096, body.path("max_tokens").asInt());
        assertTrue(body.path("stream").asBoolean());
        assertEquals(0.25, body.path("temperature").asDouble());
        assertEquals(0.9, body.path("top_p").asDouble());
        assertEquals("STOP", body.path("stop_sequences").get(0).asText());
        assertEquals("adaptive", body.path("thinking").path("type").asText());
        assertEquals("high", body.path("output_config").path("effort").asText());

        assertEquals(3, body.path("system").size());
        assertEquals(
                AnthropicCompatibilityProfile.claudeCodeOAuth().oauthSystemPreamble(),
                body.path("system").get(0).path("text").asText()
        );
        assertEquals("system", body.path("system").get(1).path("text").asText());
        assertEquals("developer", body.path("system").get(2).path("text").asText());

        JsonNode messages = body.path("messages");
        assertEquals(List.of("user", "assistant", "user"), List.of(
                messages.get(0).path("role").asText(),
                messages.get(1).path("role").asText(),
                messages.get(2).path("role").asText()
        ));
        assertEquals("base64", messages.get(0).path("content").get(1)
                .path("source").path("type").asText());
        assertEquals("thinking", messages.get(1).path("content").get(0).path("type").asText());
        assertEquals("tool_use", messages.get(1).path("content").get(2).path("type").asText());
        assertEquals("README.md", messages.get(1).path("content").get(2)
                .path("input").path("path").asText());
        assertEquals("tool_result", messages.get(2).path("content").get(0).path("type").asText());
        assertEquals("continue", messages.get(2).path("content").get(1).path("text").asText());

        assertEquals("read", body.path("tools").get(0).path("name").asText());
        assertEquals("tool", body.path("tool_choice").path("type").asText());
        assertEquals("read", body.path("tool_choice").path("name").asText());
    }

    @Test
    void mergesAdjacentEffectiveRoles() throws Exception {
        ChatRequest request = request(List.of(
                message(ChatRequest.Role.USER, new ChatRequest.Text("one")),
                message(ChatRequest.Role.USER, new ChatRequest.Text("two")),
                message(ChatRequest.Role.ASSISTANT, new ChatRequest.Text("three"))
        ));

        JsonNode messages = new AnthropicRequestTranslator(
                AnthropicCompatibilityProfile.claudeCodeOAuth())
                .translate(request).path("messages");

        assertEquals(2, messages.size());
        assertEquals(2, messages.get(0).path("content").size());
    }

    @Test
    void rejectsDuplicateOrOrphanToolIdsAndInvalidArguments() {
        AnthropicRequestTranslator translator = new AnthropicRequestTranslator(
                AnthropicCompatibilityProfile.claudeCodeOAuth());

        assertThrows(AnthropicTranslationException.class, () -> translator.translate(request(List.of(
                message(
                        ChatRequest.Role.ASSISTANT,
                        new ChatRequest.ToolCall("same", "a", "{}"),
                        new ChatRequest.ToolCall("same", "b", "{}")
                )
        ))));
        assertThrows(AnthropicTranslationException.class, () -> translator.translate(
                new ChatRequest(
                        "claude",
                        List.of(message(ChatRequest.Role.USER, new ChatRequest.Text("hello"))),
                        List.of(),
                        new ChatRequest.ToolChoice.Named("missing"),
                        100,
                        null,
                        null,
                        List.of(),
                        null,
                        true
                )
        ));
        assertThrows(AnthropicTranslationException.class, () -> translator.translate(request(List.of(
                message(
                        ChatRequest.Role.TOOL,
                        new ChatRequest.ToolResult("missing", "output", false)
                )
        ))));
        assertThrows(AnthropicTranslationException.class, () -> translator.translate(request(List.of(
                message(
                        ChatRequest.Role.ASSISTANT,
                        new ChatRequest.ToolCall("call", "read", "not-json")
                )
        ))));
    }

    @Test
    void rejectsUnsupportedImageMediaTypes() {
        ChatRequest request = request(List.of(message(
                ChatRequest.Role.USER,
                new ChatRequest.Image("image/svg+xml", new byte[]{1})
        )));

        AnthropicTranslationException error = assertThrows(
                AnthropicTranslationException.class,
                () -> new AnthropicRequestTranslator(
                        AnthropicCompatibilityProfile.claudeCodeOAuth()).translate(request)
        );

        assertEquals(400, error.error().httpStatus());
        assertFalse(error.getMessage().contains("data"));
    }

    @Test
    void mapsRequiredAndAutoToolChoicesAndRejectsRequiredWithoutTools() throws Exception {
        ChatRequest.ToolDefinition tool = new ChatRequest.ToolDefinition(
                "read", "", JsonNodeFactory.instance.objectNode());
        ChatRequest required = new ChatRequest(
                "claude",
                List.of(message(ChatRequest.Role.USER, new ChatRequest.Text("hello"))),
                List.of(tool),
                new ChatRequest.ToolChoice.Required(),
                100,
                null,
                null,
                List.of(),
                null,
                true
        );
        JsonNode requiredBody = new AnthropicRequestTranslator(
                AnthropicCompatibilityProfile.claudeCodeOAuth()).translate(required);
        assertEquals("any", requiredBody.path("tool_choice").path("type").asText());

        ChatRequest invalid = new ChatRequest(
                "claude",
                required.messages(),
                List.of(),
                new ChatRequest.ToolChoice.Required(),
                100,
                null,
                null,
                List.of(),
                null,
                true
        );
        assertThrows(
                AnthropicTranslationException.class,
                () -> new AnthropicRequestTranslator(
                        AnthropicCompatibilityProfile.claudeCodeOAuth()).translate(invalid)
        );
    }

    private static ChatRequest request(List<ChatRequest.Message> messages) {
        return new ChatRequest(
                "claude-sonnet-4-5",
                messages,
                List.of(),
                new ChatRequest.ToolChoice.Auto(),
                1024,
                null,
                null,
                List.of(),
                null,
                true
        );
    }

    private static ChatRequest.Message message(
            ChatRequest.Role role, ChatRequest.Content... content) {
        return new ChatRequest.Message(role, List.of(content));
    }
}
