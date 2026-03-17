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
}
