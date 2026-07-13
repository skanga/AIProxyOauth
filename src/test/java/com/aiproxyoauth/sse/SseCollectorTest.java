package com.aiproxyoauth.sse;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class SseCollectorTest {
    @Test
    void collectCompletedResponse_success() throws Exception {
        String data = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-1\"}}\n\n";
        InputStream is = new ByteArrayInputStream(data.getBytes());
        JsonNode response = SseCollector.collectCompletedResponse(is);
        
        assertEquals("resp-1", response.get("id").asText());
    }

    @Test
    void collectCompletedResponse_noResponse_throwsException() {
        String data = "data: {\"type\":\"other\"}\n\n";
        InputStream is = new ByteArrayInputStream(data.getBytes());
        assertThrows(IOException.class, () -> SseCollector.collectCompletedResponse(is));
    }

    @Test
    void collectCompletedResponse_recoversFunctionCallFromStreamEvents() throws Exception {
        String data = """
                data: {"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"python-eval","arguments":""}}

                data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\\"code\\":"}

                data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\\"factorial(100)\\"}"}

                data: {"type":"response.completed","response":{"status":"completed","output":[],"usage":{"output_tokens":27}}}

                """;

        JsonNode response = SseCollector.collectCompletedResponse(
                new ByteArrayInputStream(data.getBytes()));

        JsonNode call = response.path("output").get(0);
        assertEquals("function_call", call.path("type").asText());
        assertEquals("call_1", call.path("call_id").asText());
        assertEquals("python-eval", call.path("name").asText());
        assertEquals("{\"code\":\"factorial(100)\"}", call.path("arguments").asText());
    }

    @Test
    void collectCompletedResponse_doesNotDuplicateFunctionCallAlreadyInCompletedOutput() throws Exception {
        String data = """
                data: {"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_1","name":"fn","arguments":""}}

                data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1","name":"fn","arguments":"{}"}]}}

                """;

        JsonNode response = SseCollector.collectCompletedResponse(
                new ByteArrayInputStream(data.getBytes()));

        assertEquals(1, response.path("output").size());
        assertEquals("{}", response.path("output").get(0).path("arguments").asText());
    }
}
