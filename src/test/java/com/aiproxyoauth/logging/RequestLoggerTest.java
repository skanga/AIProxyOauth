package com.aiproxyoauth.logging;

import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestLoggerTest {

    @Test
    void disabledLoggerDoesNotCreateLogFiles(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(false, tempDir.resolve("logs"));

        logger.logUpstreamRequest("req_1", "POST", "/responses", Map.of("Content-Type", "application/json"), "{}");

        assertFalse(Files.exists(tempDir.resolve("logs")));
    }

    @Test
    void createsLogDirectoryAndWritesInboundJson(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path logDir = tempDir.resolve("nested").resolve("requests");
        RequestLogger logger = new RequestLogger(true, logDir);
        Context ctx = mock(Context.class);
        when(ctx.method()).thenReturn(HandlerType.POST);
        when(ctx.path()).thenReturn("/v1/chat/completions");
        when(ctx.statusCode()).thenReturn(202);
        when(ctx.headerMap()).thenReturn(Map.of("User-Agent", "JUnit", "Content-Type", "application/json"));

        logger.logInbound("req_123", ctx, "{\"input\":\"hello\"}");

        JsonNode entry = readOnlyJsonFile(logDir);
        assertEquals("req_123", entry.path("request_id").asText());
        assertFalse(entry.path("timestamp").asText().isBlank());
        assertEquals("inbound", entry.path("stage").asText());
        assertEquals("POST", entry.path("method").asText());
        assertEquals("/v1/chat/completions", entry.path("path").asText());
        assertEquals(202, entry.path("status").asInt());
        assertEquals("JUnit", entry.path("headers").path("User-Agent").asText());
        assertEquals("{\"input\":\"hello\"}", entry.path("body").asText());
        assertFalse(entry.path("truncated").asBoolean());
    }

    @Test
    void redactsSensitiveHeadersCaseInsensitively(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(true, tempDir);

        logger.logUpstreamRequest("req_1", "POST", "/responses", Map.of(
                "authorization", "Bearer token",
                "Proxy-Authorization", "Bearer proxy",
                "X-Api-Key", "sk-test",
                "OpenAI-Api-Key", "sk-openai",
                "Cookie", "session=secret",
                "X-Session-Token", "abc",
                "X-Client-Secret", "def",
                "Content-Type", "application/json",
                "Accept", "application/json",
                "User-Agent", "JUnit"
        ), "{}");

        JsonNode headers = readOnlyJsonFile(tempDir).path("headers");
        assertEquals("[REDACTED]", headers.path("authorization").asText());
        assertEquals("[REDACTED]", headers.path("Proxy-Authorization").asText());
        assertEquals("[REDACTED]", headers.path("X-Api-Key").asText());
        assertEquals("[REDACTED]", headers.path("OpenAI-Api-Key").asText());
        assertEquals("[REDACTED]", headers.path("Cookie").asText());
        assertEquals("[REDACTED]", headers.path("X-Session-Token").asText());
        assertEquals("[REDACTED]", headers.path("X-Client-Secret").asText());
        assertEquals("application/json", headers.path("Content-Type").asText());
        assertEquals("application/json", headers.path("Accept").asText());
        assertEquals("JUnit", headers.path("User-Agent").asText());
    }

    @Test
    void writesResponseStatusAndListHeaders(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(true, tempDir);

        logger.logUpstreamResponse("req_1", 429, Map.of(
                "Set-Cookie", List.of("a=b", "c=d"),
                "Content-Type", List.of("application/json")
        ), "{\"error\":\"rate limited\"}");

        JsonNode entry = readOnlyJsonFile(tempDir);
        assertEquals("upstream_response", entry.path("stage").asText());
        assertEquals(429, entry.path("status").asInt());
        assertEquals("[REDACTED]", entry.path("headers").path("Set-Cookie").get(0).asText());
        assertEquals("[REDACTED]", entry.path("headers").path("Set-Cookie").get(1).asText());
        assertEquals("application/json", entry.path("headers").path("Content-Type").get(0).asText());
    }

    @Test
    void capsBodyAndMarksTruncated(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        RequestLogger logger = new RequestLogger(true, tempDir);
        String body = "x".repeat(300 * 1024);

        logger.logUpstreamRequest("req_1", "POST", "/responses", Map.of(), body);

        JsonNode entry = readOnlyJsonFile(tempDir);
        assertTrue(entry.path("truncated").asBoolean());
        assertTrue(entry.path("body").asText().length() < body.length());
    }

    private static JsonNode readOnlyJsonFile(Path logDir) throws Exception {
        try (var files = Files.list(logDir)) {
            List<Path> jsonFiles = files.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
            assertEquals(1, jsonFiles.size());
            return Json.MAPPER.readTree(Files.readString(jsonFiles.getFirst()));
        }
    }
}
