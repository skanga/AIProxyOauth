package com.aiproxyoauth.provider.copilot;
import com.aiproxyoauth.provider.stream.*;
import com.aiproxyoauth.server.*;
import com.aiproxyoauth.util.Json;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class CopilotProtocolTest {
    @Test void recoversFinalResponsesSnapshotWithoutDuplicatingStreamedText() {
        String start = "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_one\",\"model\":\"test\"}}\n\n";
        String done = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}}\n\n";
        for (boolean delta : new boolean[]{false, true}) {
            var encoder = new OpenAiChatCompletionEncoder("copilot/test");
            String wire = start + (delta ? "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"hello\"}\n\n" : "") + done;
            new CopilotStreamDecoder(true).feed(wire.getBytes(StandardCharsets.UTF_8)).forEach(encoder::accept);
            assertEquals("hello", encoder.completion().at("/choices/0/message/content").asText());
        }
    }
    @Test void preservesTextBeforeToolCallAndRejectsUndeclaredNamedTool() throws Exception {
        var request = new OpenAiChatRequestAdapter().adapt(Json.MAPPER.readTree("""
                {"messages":[{"role":"assistant","content":"I will look it up",
                "tool_calls":[{"id":"call1","function":{"name":"lookup","arguments":"{}"}}]}]}
                """), "test");
        var wire = CopilotRequestEncoder.encode(request, "/responses");
        assertEquals("message", wire.path("input").get(0).path("type").asText());
        assertEquals("function_call", wire.path("input").get(1).path("type").asText());
        var invalid = new OpenAiChatRequestAdapter().adapt(Json.MAPPER.readTree("""
                {"messages":[{"role":"user","content":"hi"}],"tool_choice":{"type":"function","function":{"name":"missing"}}}
                """), "test");
        assertThrows(IllegalArgumentException.class, () -> CopilotRequestEncoder.encode(invalid, "/chat/completions"));
    }
    @Test void ignoresLeadingPromptFilterFrameWithoutACompletionId() throws Exception {
        var decoder = new CopilotStreamDecoder(false);
        var events = decoder.feed(("data: {\"id\":\"\",\"model\":\"\",\"choices\":[],\"prompt_filter_results\":[]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"model\":\"test\",\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(events.stream().anyMatch(e -> e instanceof com.aiproxyoauth.provider.stream.CompletionEvent.Finished));
        assertFalse(events.stream().anyMatch(e -> e instanceof com.aiproxyoauth.provider.stream.CompletionEvent.Error));
    }
    @Test void translatesResponsesToolsToEveryProtocolWithoutClaudeIdentity() throws Exception {
        var request = new ResponsesRequestAdapter().adapt(Json.MAPPER.readTree("""
                {"instructions":"Be concise","input":[{"type":"function_call","call_id":"call1","name":"lookup","arguments":"{}"},
                {"type":"function_call_output","call_id":"call1","output":"result"}],
                "tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}]}
                """), "same-model");
        for (String endpoint : new String[]{"/chat/completions", "/responses", "/v1/messages"}) {
            var wire = CopilotRequestEncoder.encode(request, endpoint);
            assertEquals("same-model", wire.path("model").asText());
            assertTrue(wire.path("stream").asBoolean());
            assertTrue(wire.toString().contains("call1"));
            assertTrue(wire.toString().contains("result"));
            assertFalse(wire.toString().contains("You are Claude Code"));
        }
    }
    @Test void decodesFragmentedChatToolArgumentsAndLateUsage() {
        String sse = """
                data: {"id":"c1","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call1","function":{"name":"lookup","arguments":"{\\\"q\\\":"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":"tool_calls"}]}

                data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3}}

                data: [DONE]

                """;
        var decoder = new CopilotStreamDecoder(false);
        var encoder = new OpenAiChatCompletionEncoder("copilot/m");
        for (byte b : sse.getBytes(StandardCharsets.UTF_8)) decoder.feed(new byte[]{b}).forEach(encoder::accept);
        decoder.end().forEach(encoder::accept);
        assertTrue(encoder.isFinished());
        assertEquals(10, encoder.completion().path("usage").path("total_tokens").asInt());
        assertEquals("{\"q\":1}", encoder.completion().path("choices").get(0).path("message").path("tool_calls").get(0).path("function").path("arguments").asText());
    }
    @Test void translatesResponsesEventsAndRejectsTruncation() {
        var decoder = new CopilotStreamDecoder(true);
        var encoder = new OpenAiChatCompletionEncoder("copilot/m");
        String sse = """
                event: response.created
                data: {"type":"response.created","response":{"id":"resp_1","model":"m","created_at":1}}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"hello"}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":2,"output_tokens":1}}}

                """;
        decoder.feed(sse.getBytes(StandardCharsets.UTF_8)).forEach(encoder::accept);
        assertEquals("hello", encoder.completion().path("choices").get(0).path("message").path("content").asText());
        assertTrue(new CopilotStreamDecoder(false).end().stream().anyMatch(e -> e instanceof CompletionEvent.Error));
    }
}
