package com.aiproxyoauth.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.aiproxyoauth.util.Json;

import java.io.IOException;
import java.io.InputStream;

public final class SseCollector {

    private SseCollector() {}

    public static JsonNode collectCompletedResponse(InputStream input) throws IOException {
        JsonNode latestResponse = null;
        JsonNode latestError = null;

        for (ServerSentEvent event : SseParser.parse(input)) {
            if (event.data() == null || event.data().isEmpty()) {
                continue;
            }

            try {
                JsonNode parsed = Json.MAPPER.readTree(event.data());
                if (parsed == null || !parsed.isObject()) {
                    continue;
                }

                if ("error".equals(event.event())) {
                    latestError = parsed;
                    continue;
                }

                // Only accept the response object from a response.completed event to avoid
                // mistaking partial response objects in other event types for the final result.
                String eventType = parsed.path("type").asText(event.event() != null ? event.event() : "");
                if ("response.completed".equals(eventType)) {
                    JsonNode response = parsed.get("response");
                    if (response != null && response.isObject()) {
                        latestResponse = response;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (latestResponse != null) {
            return latestResponse;
        }

        String errorInfo = latestError != null ? " Last error: " + latestError : "";
        throw new IOException("No completed response found in SSE stream." + errorInfo);
    }
}
