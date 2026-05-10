package com.aiproxyoauth.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.aiproxyoauth.util.Json.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesRequestSanitizerTest {

    private final ResponsesRequestSanitizer sanitizer = new ResponsesRequestSanitizer();

    @Test
    void sanitize_statelessExplicitFalse_removesItemReferencesAndInputIds() throws Exception {
        ObjectNode body = object("""
                {
                  "store": false,
                  "input": [
                    {"id":"msg_1","type":"message","role":"user","content":"hi"},
                    {"type":"item_reference","id":"rs_123"}
                  ]
                }
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, true);

        assertEquals(1, sanitized.path("input").size());
        JsonNode item = sanitized.path("input").get(0);
        assertEquals("message", item.path("type").asText());
        assertEquals("user", item.path("role").asText());
        assertEquals("hi", item.path("content").asText());
        assertFalse(item.has("id"));
    }

    @Test
    void sanitize_statelessWhenStoreAbsent_removesItemReferencesAndInputIds() throws Exception {
        ObjectNode body = object("""
                {
                  "input": [
                    {"id":"msg_1","type":"message","role":"user","content":"hi"},
                    {"type":"item_reference","id":"rs_123"}
                  ]
                }
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, false);

        assertEquals(1, sanitized.path("input").size());
        assertFalse(sanitized.path("input").get(0).has("id"));
    }

    @Test
    void sanitize_storeTrue_leavesInputItemsUntouched() throws Exception {
        ObjectNode body = object("""
                {
                  "store": true,
                  "input": [
                    {"id":"msg_1","type":"message","role":"user","content":"hi"},
                    {"type":"item_reference","id":"rs_123"}
                  ]
                }
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, false);

        assertEquals(body, sanitized);
    }

    @Test
    void sanitize_missingInput_returnsCopyUnchanged() throws Exception {
        ObjectNode body = object("""
                {"store": false, "metadata": {"request": "abc"}}
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, true);

        assertEquals(body, sanitized);
    }

    @Test
    void sanitize_preservesContentMetadataAndOrdering() throws Exception {
        ObjectNode body = object("""
                {
                  "store": false,
                  "input": [
                    {"id":"msg_1","type":"message","role":"user","content":[{"type":"input_text","text":"hi"}],"metadata":{"a":1}},
                    {"id":"call_1_item","type":"function_call","call_id":"call_1","name":"read","arguments":"{}"},
                    {"id":"out_1","type":"function_call_output","call_id":"call_1","output":"ok"}
                  ]
                }
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, true);

        assertEquals(3, sanitized.path("input").size());
        assertEquals("message", sanitized.path("input").get(0).path("type").asText());
        assertEquals("function_call", sanitized.path("input").get(1).path("type").asText());
        assertEquals("function_call_output", sanitized.path("input").get(2).path("type").asText());
        assertEquals(1, sanitized.path("input").get(0).path("metadata").path("a").asInt());
        assertEquals("ok", sanitized.path("input").get(2).path("output").asText());
    }

    @Test
    void sanitize_unmatchedFunctionCallOutput_becomesAssistantMessage() throws Exception {
        ObjectNode body = object("""
                {"store": false, "input": [
                  {"type":"function_call_output","call_id":"call_1","output":"file contents"}
                ]}
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, true);

        JsonNode item = sanitized.path("input").get(0);
        assertEquals("message", item.path("type").asText());
        assertEquals("assistant", item.path("role").asText());
        assertTrue(item.path("content").asText().contains("[Previous read result; call_id=call_1]: file contents"));
    }

    @Test
    void sanitize_unmatchedCustomToolCallOutput_becomesAssistantMessage() throws Exception {
        ObjectNode body = object("""
                {"store": false, "input": [
                  {"type":"custom_tool_call_output","call_id":"call_1","output":"custom contents"}
                ]}
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, true);

        JsonNode item = sanitized.path("input").get(0);
        assertEquals("message", item.path("type").asText());
        assertEquals("assistant", item.path("role").asText());
        assertTrue(item.path("content").asText().contains("custom contents"));
    }

    @Test
    void sanitize_unmatchedLocalShellCallOutput_becomesAssistantMessage() throws Exception {
        ObjectNode body = object("""
                {"store": false, "input": [
                  {"type":"local_shell_call_output","call_id":"call_1","output":"shell contents"}
                ]}
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, true);

        JsonNode item = sanitized.path("input").get(0);
        assertEquals("message", item.path("type").asText());
        assertEquals("assistant", item.path("role").asText());
        assertTrue(item.path("content").asText().contains("shell contents"));
    }

    @Test
    void sanitize_matchedCallOutputPairsRemainUnchanged() throws Exception {
        ObjectNode body = object("""
                {"store": false, "input": [
                  {"type":"custom_tool_call","call_id":"call_1","name":"tool","input":"{}"},
                  {"type":"custom_tool_call_output","call_id":"call_1","output":"ok"}
                ]}
                """);

        ObjectNode sanitized = sanitizer.sanitize(body, true);

        assertEquals("custom_tool_call", sanitized.path("input").get(0).path("type").asText());
        assertEquals("custom_tool_call_output", sanitized.path("input").get(1).path("type").asText());
        assertEquals("ok", sanitized.path("input").get(1).path("output").asText());
    }

    @Test
    void sanitize_convertedOutputTextIsCappedAndMarked() throws Exception {
        String output = "x".repeat(17 * 1024);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("store", false);
        var input = MAPPER.createArrayNode();
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", "call_1");
        item.put("output", output);
        input.add(item);
        body.set("input", input);

        ObjectNode sanitized = sanitizer.sanitize(body, true);

        String content = sanitized.path("input").get(0).path("content").asText();
        assertTrue(content.length() < output.length());
        assertTrue(content.contains("[truncated]"));
    }

    private static ObjectNode object(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }
}
