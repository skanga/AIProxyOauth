package com.aiproxyoauth.server;

import com.aiproxyoauth.provider.chat.ChatRequest;
import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponsesRequestAdapterTest {
    private final ResponsesRequestAdapter adapter = new ResponsesRequestAdapter();

    @Test
    void adaptsInstructionsMessagesToolsAndControls() throws Exception {
        ChatRequest request = adapter.adapt(Json.MAPPER.readTree("""
                {
                  "model":"anthropic/sonnet",
                  "instructions":"Be concise",
                  "input":[
                    {"type":"message","role":"user","content":[
                      {"type":"input_text","text":"Weather?"}]},
                    {"type":"function_call","call_id":"call_1","name":"weather",
                     "arguments":"{\\\"city\\\":\\\"LA\\\"}"},
                    {"type":"function_call_output","call_id":"call_1","output":"sunny"}
                  ],
                  "tools":[{"type":"function","name":"weather","description":"Lookup",
                    "parameters":{"type":"object"}}],
                  "tool_choice":{"type":"function","name":"weather"},
                  "max_output_tokens":321,
                  "reasoning":{"effort":"high"}
                }
                """), "claude-sonnet-4-5");

        assertEquals("claude-sonnet-4-5", request.model());
        assertEquals(4, request.messages().size());
        assertEquals(ChatRequest.Role.SYSTEM, request.messages().getFirst().role());
        assertInstanceOf(ChatRequest.ToolCall.class, request.messages().get(2).content().getFirst());
        assertInstanceOf(ChatRequest.ToolResult.class, request.messages().get(3).content().getFirst());
        assertInstanceOf(ChatRequest.ToolChoice.Named.class, request.toolChoice());
        assertEquals(321, request.maxOutputTokens());
        assertEquals("high", request.reasoningEffort());
    }

    @Test
    void adaptsStringInputAndInlineImage() throws Exception {
        ChatRequest text = adapter.adapt(Json.MAPPER.readTree("""
                {"input":"hello"}
                """), "claude-sonnet-4-5");
        assertEquals("hello", ((ChatRequest.Text)
                text.messages().getFirst().content().getFirst()).text());

        ChatRequest image = adapter.adapt(Json.MAPPER.readTree("""
                {"input":[{"type":"message","role":"user","content":[
                  {"type":"input_image","image_url":"data:image/png;base64,AQID"}]}]}
                """), "claude-sonnet-4-5");
        assertInstanceOf(ChatRequest.Image.class,
                image.messages().getFirst().content().getFirst());
    }

    @Test
    void rejectsUnsupportedItemsAndRemoteImages() throws Exception {
        IllegalArgumentException reference = assertThrows(IllegalArgumentException.class, () ->
                adapter.adapt(Json.MAPPER.readTree("""
                    {"input":[{"type":"item_reference","id":"item_1"}]}
                    """), "claude-sonnet-4-5"));
        assertTrue(reference.getMessage().contains("item_reference"));

        IllegalArgumentException image = assertThrows(IllegalArgumentException.class, () ->
                adapter.adapt(Json.MAPPER.readTree("""
                    {"input":[{"type":"message","role":"user","content":[
                      {"type":"input_image","image_url":"https://example.test/a.png"}]}]}
                    """), "claude-sonnet-4-5"));
        assertTrue(image.getMessage().contains("data URL"));
    }
}
