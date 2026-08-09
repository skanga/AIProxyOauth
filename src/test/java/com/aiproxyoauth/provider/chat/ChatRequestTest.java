package com.aiproxyoauth.provider.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatRequestTest {

    @Test
    void requestAndNestedValuesAreImmutable() {
        byte[] imageBytes = {1, 2, 3};
        List<ChatRequest.Content> content = new ArrayList<>();
        content.add(new ChatRequest.Text("hello"));
        content.add(new ChatRequest.Image("image/png", imageBytes));
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(new ChatRequest.Message(ChatRequest.Role.USER, content));
        List<String> stops = new ArrayList<>(List.of("END"));

        ChatRequest request = new ChatRequest(
                "claude-sonnet-4-5",
                messages,
                List.of(),
                new ChatRequest.ToolChoice.Auto(),
                8192,
                0.5,
                0.9,
                stops,
                "high",
                true
        );
        imageBytes[0] = 9;
        content.clear();
        messages.clear();
        stops.add("MUTATED");

        ChatRequest.Image image = (ChatRequest.Image) request.messages().getFirst().content().get(1);
        assertArrayEquals(new byte[]{1, 2, 3}, image.data());
        assertEquals(List.of("END"), request.stopSequences());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
    }

    @Test
    void contentContractRepresentsCodexTextToolsResultsAndReasoningWithoutLoss() {
        var redacted = JsonNodeFactory.instance.objectNode().put("data", "opaque");
        ChatRequest.Message assistant = new ChatRequest.Message(
                ChatRequest.Role.ASSISTANT,
                List.of(
                        new ChatRequest.Reasoning("plan", "signature", redacted),
                        new ChatRequest.Text("answer"),
                        new ChatRequest.ToolCall("call-1", "read", "{\"path\":\"README.md\"}")
                )
        );
        ChatRequest.Message tool = new ChatRequest.Message(
                ChatRequest.Role.TOOL,
                List.of(new ChatRequest.ToolResult("call-1", "contents", false))
        );

        redacted.put("data", "mutated");

        ChatRequest.Reasoning reasoning = (ChatRequest.Reasoning) assistant.content().getFirst();
        assertEquals("opaque", reasoning.redactedData().path("data").asText());
        assertEquals("call-1", ((ChatRequest.ToolCall) assistant.content().get(2)).id());
        assertEquals("contents", ((ChatRequest.ToolResult) tool.content().getFirst()).output());
    }

    @Test
    void requestRejectsInvalidNumericAndBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> request(" ", 1, null, null));
        assertThrows(IllegalArgumentException.class, () -> request("claude", 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> request("claude", 1, -0.1, null));
        assertThrows(IllegalArgumentException.class, () -> request("claude", 1, null, 1.1));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest.Image(
                "text/plain", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest.ToolCall(
                "", "read", "{}"));
    }

    private static ChatRequest request(
            String model,
            int maxOutputTokens,
            Double temperature,
            Double topP
    ) {
        return new ChatRequest(
                model,
                List.of(new ChatRequest.Message(
                        ChatRequest.Role.USER,
                        List.of(new ChatRequest.Text("hello"))
                )),
                List.of(),
                new ChatRequest.ToolChoice.Auto(),
                maxOutputTokens,
                temperature,
                topP,
                List.of(),
                null,
                false
        );
    }
}
