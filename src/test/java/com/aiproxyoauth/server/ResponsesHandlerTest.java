package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.CodexInstructionsProvider;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import static com.aiproxyoauth.util.Json.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponsesHandlerTest {

    @Mock CodexHttpClient client;
    @Mock Context ctx;
    @Mock UsageTracker usageTracker;

    @TempDir Path tempDir;

    private static HttpResponse<InputStream> response(String sseData) {
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static ServletOutputStream outputStream(ByteArrayOutputStream sink) {
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int b) {
                sink.write(b);
            }
        };
    }

    private static void stubReplayAttributes(
            Context context, String keyFingerprint, String adminKeyFingerprint, Boolean isAdmin, String keyName) {
        lenient().when(context.attribute("keyFingerprint")).thenReturn(keyFingerprint);
        lenient().when(context.attribute("adminKeyFingerprint")).thenReturn(adminKeyFingerprint);
        lenient().when(context.attribute("isAdmin")).thenReturn(isAdmin);
        lenient().when(context.attribute("keyName")).thenReturn(keyName);
    }

    private static ServerConfig minimalConfig() {
        return new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.111.0",
                ServerConfig.DEFAULT_BASE_URL,
                null, null, null,
                "instr", false,
                Map.of(), null
        );
    }

    private static ServerConfig configWithRequestLogging(Path logDir) {
        return new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.111.0",
                ServerConfig.DEFAULT_BASE_URL,
                null, null, null,
                "instr", false,
                Map.of(), null,
                false, java.util.List.of(),
                true, logDir.toString(), false
        );
    }

    private static ServerConfig configWithPromptCacheForwarding() {
        return new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.111.0",
                ServerConfig.DEFAULT_BASE_URL,
                null, null, null,
                "instr", false,
                Map.of(), null,
                false, java.util.List.of(),
                false, null, true
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void handle_previousResponseId_accepted_andExpanded() throws Exception {
        // previous_response_id with no cached history: handler forwards the request as-is
        // (expansion is a no-op when nothing is cached for that id)
        when(ctx.body()).thenReturn("{\"previous_response_id\":\"unknown-id\",\"input\":[]}");

        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        String sseData = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n";
        when(mockResponse.body()).thenReturn(new ByteArrayInputStream(sseData.getBytes()));
        when(client.request(eq("/responses"), eq("POST"), anyString(), any())).thenReturn(mockResponse);

        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);

        // No 400 — request forwarded successfully
        verify(ctx).status(200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void handle_loggingEnabled_writesRedactedInboundLog() throws Exception {
        Path logDir = tempDir.resolve("request-logs");
        when(ctx.body()).thenReturn("{\"input\":[]}");
        when(ctx.method()).thenReturn(HandlerType.POST);
        when(ctx.path()).thenReturn("/v1/responses");
        when(ctx.statusCode()).thenReturn(200);
        when(ctx.headerMap()).thenReturn(Map.of(
                "Authorization", "Bearer sk-proxy-secret",
                "Content-Type", "application/json"
        ));

        HttpResponse<InputStream> mockResponse = response("""
                data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":1,"output_tokens":1}}}

                """);
        when(client.request(eq("/responses"), eq("POST"), anyString(), any(), anyString(), isNull()))
                .thenReturn(mockResponse);

        ServerConfig config = configWithRequestLogging(logDir);
        ResponsesHandler handler = new ResponsesHandler(
                client,
                config,
                usageTracker,
                new RequestLogger(true, logDir),
                new CodexInstructionsProvider(config.instructions())
        );
        handler.handle(ctx);

        String combinedLogs = Files.list(logDir)
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce("", String::concat);
        assertTrue(combinedLogs.contains("[REDACTED]"));
        assertFalse(combinedLogs.contains("sk-proxy-secret"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handle_promptCacheForwardingEnabled_passesPromptCacheKeyToClient() throws Exception {
        when(ctx.body()).thenReturn("{\"prompt_cache_key\":\"cache-abc\",\"input\":[]}");

        HttpResponse<InputStream> mockResponse = response("""
                data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":1,"output_tokens":1}}}

                """);
        when(client.request(eq("/responses"), eq("POST"), anyString(), any(), anyString(), eq("cache-abc")))
                .thenReturn(mockResponse);

        ResponsesHandler handler = new ResponsesHandler(client, configWithPromptCacheForwarding(), usageTracker);
        handler.handle(ctx);

        verify(client).request(eq("/responses"), eq("POST"), contains("\"prompt_cache_key\":\"cache-abc\""), any(),
                anyString(), eq("cache-abc"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handle_nonStreaming_success_withoutPersistentStore() throws Exception {
        when(ctx.body()).thenReturn("{\"store\":true,\"input\":[]}");
        
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        String sseData = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}\n\n";
        when(mockResponse.body()).thenReturn(new ByteArrayInputStream(sseData.getBytes()));
        
        when(client.request(eq("/responses"), eq("POST"), anyString(), any())).thenReturn(mockResponse);

        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        try {
            handler.handle(ctx);
        } finally {
            if (originalUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalUserHome);
            }
        }

        verify(ctx).status(200);
        verify(usageTracker).record(any(), eq(5L), eq(2L));
        assertFalse(Files.exists(tempDir.resolve(".aiproxyoauth").resolve("responses.sqlite")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonStreamingResponse_populatesSameProcessReplayCache() throws Exception {
        when(ctx.body()).thenReturn(
                """
                {"input":[{"id":"in_1","type":"message","role":"user"}]}
                """,
                """
                {"previous_response_id":"resp_1","input":[{"id":"in_2","type":"message","role":"user"}]}
                """
        );

        HttpResponse<InputStream> firstResponse = mock(HttpResponse.class);
        when(firstResponse.statusCode()).thenReturn(200);
        when(firstResponse.body()).thenReturn(new ByteArrayInputStream("""
                data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[{"id":"out_1","type":"message","role":"assistant"}],"usage":{"input_tokens":5,"output_tokens":2}}}

                """.getBytes()));

        HttpResponse<InputStream> secondResponse = mock(HttpResponse.class);
        when(secondResponse.statusCode()).thenReturn(200);
        when(secondResponse.body()).thenReturn(new ByteArrayInputStream("""
                data: {"type":"response.completed","response":{"id":"resp_2","status":"completed","output":[],"usage":{"input_tokens":7,"output_tokens":3}}}

                """.getBytes()));

        when(client.request(eq("/responses"), eq("POST"), anyString(), any()))
                .thenReturn(firstResponse, secondResponse);

        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);
        handler.handle(ctx);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).request(eq("/responses"), eq("POST"), bodyCaptor.capture(), any());

        JsonNode secondForwarded = MAPPER.readTree(bodyCaptor.getAllValues().get(1));
        assertFalse(secondForwarded.has("previous_response_id"));
        assertTrue(secondForwarded.path("input").isArray());
        assertEquals(3, secondForwarded.path("input").size());
        assertFalse(secondForwarded.path("input").get(0).has("id"));
        assertFalse(secondForwarded.path("input").get(1).has("id"));
        assertFalse(secondForwarded.path("input").get(2).has("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayCache_isScopedByApiKeyName() throws Exception {
        lenient().when(ctx.attribute("keyFingerprint")).thenReturn(null);
        lenient().when(ctx.attribute("adminKeyFingerprint")).thenReturn(null);
        lenient().when(ctx.attribute("isAdmin")).thenReturn(null);
        lenient().when(ctx.attribute("keyName")).thenReturn("alice", "alice", "bob", "bob");
        when(ctx.body()).thenReturn(
                """
                {"input":[{"id":"alice_in","type":"message","role":"user"}]}
                """,
                """
                {"previous_response_id":"resp_1","input":[{"id":"bob_in","type":"message","role":"user"}]}
                """
        );

        HttpResponse<InputStream> firstResponse = mock(HttpResponse.class);
        when(firstResponse.statusCode()).thenReturn(200);
        when(firstResponse.body()).thenReturn(new ByteArrayInputStream("""
                data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[{"id":"alice_out","type":"message","role":"assistant"}],"usage":{"input_tokens":5,"output_tokens":2}}}

                """.getBytes()));

        HttpResponse<InputStream> secondResponse = mock(HttpResponse.class);
        when(secondResponse.statusCode()).thenReturn(200);
        when(secondResponse.body()).thenReturn(new ByteArrayInputStream("""
                data: {"type":"response.completed","response":{"id":"resp_2","status":"completed","output":[],"usage":{"input_tokens":7,"output_tokens":3}}}

                """.getBytes()));

        when(client.request(eq("/responses"), eq("POST"), anyString(), any()))
                .thenReturn(firstResponse, secondResponse);

        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);
        handler.handle(ctx);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).request(eq("/responses"), eq("POST"), bodyCaptor.capture(), any());

        JsonNode secondForwarded = MAPPER.readTree(bodyCaptor.getAllValues().get(1));
        assertEquals("resp_1", secondForwarded.path("previous_response_id").asText());
        assertTrue(secondForwarded.path("input").isArray());
        assertEquals(1, secondForwarded.path("input").size());
        assertFalse(secondForwarded.path("input").get(0).has("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingResponse_recordsUsageFromCompletedEvent_andPreservesSseBytes() throws Exception {
        when(ctx.body()).thenReturn("{\"stream\":true,\"input\":[]}");

        String sseData = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"hi"}

                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp_stream","status":"completed","usage":{"input_tokens":11,"output_tokens":13}}}

                """;
        HttpResponse<InputStream> upstreamResponse = response(sseData);
        when(client.request(eq("/responses"), eq("POST"), anyString(), any()))
                .thenReturn(upstreamResponse);

        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        var servletResponse = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(servletResponse.getOutputStream()).thenReturn(outputStream(streamed));
        when(ctx.res()).thenReturn(servletResponse);

        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);

        assertEquals(sseData, streamed.toString(StandardCharsets.UTF_8));
        verify(ctx).attribute(AccessLogFields.MODE, "stream");
        verify(ctx).attribute(AccessLogFields.UPSTREAM_STATUS, 200);
        verify(ctx).attribute(AccessLogFields.RESPONSE_BYTES, (long) sseData.getBytes(StandardCharsets.UTF_8).length);
        verify(usageTracker).record(any(), eq(11L), eq(13L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingResponse_stopsBookkeepingAfterOversizedSseLine_butStillForwardsBytes() throws Exception {
        when(ctx.body()).thenReturn("{\"stream\":true,\"input\":[]}");

        String sseData = "data: " + "x".repeat(70_000) + "\n\n" + """
                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp_stream","status":"completed","usage":{"input_tokens":11,"output_tokens":13}}}

                """;
        HttpResponse<InputStream> upstreamResponse = response(sseData);
        when(client.request(eq("/responses"), eq("POST"), anyString(), any()))
                .thenReturn(upstreamResponse);

        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        var servletResponse = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(servletResponse.getOutputStream()).thenReturn(outputStream(streamed));
        when(ctx.res()).thenReturn(servletResponse);

        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);

        assertEquals(sseData, streamed.toString(StandardCharsets.UTF_8));
        verify(usageTracker, never()).record(any(), anyLong(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayCache_prefersKeyFingerprintOverKeyName() throws Exception {
        lenient().when(ctx.attribute("isAdmin")).thenReturn(null);
        lenient().when(ctx.attribute("keyFingerprint")).thenReturn("fp-alice", "fp-bob");
        lenient().when(ctx.attribute("adminKeyFingerprint")).thenReturn(null);
        lenient().when(ctx.attribute("keyName")).thenReturn("same-key-name", "same-key-name", "same-key-name", "same-key-name");
        when(ctx.body()).thenReturn(
                """
                {"input":[{"id":"alice_in","type":"message","role":"user"}]}
                """,
                """
                {"previous_response_id":"resp_1","input":[{"id":"bob_in","type":"message","role":"user"}]}
                """
        );

        HttpResponse<InputStream> firstResponse = response("""
                                data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[{"id":"alice_out","type":"message","role":"assistant"}],"usage":{"input_tokens":5,"output_tokens":2}}}

                                """);
        HttpResponse<InputStream> secondResponse = response("""
                                data: {"type":"response.completed","response":{"id":"resp_2","status":"completed","output":[],"usage":{"input_tokens":7,"output_tokens":3}}}

                                """);
        when(client.request(eq("/responses"), eq("POST"), anyString(), any()))
                .thenReturn(firstResponse, secondResponse);

        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);
        handler.handle(ctx);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).request(eq("/responses"), eq("POST"), bodyCaptor.capture(), any());

        JsonNode secondForwarded = MAPPER.readTree(bodyCaptor.getAllValues().get(1));
        assertEquals("resp_1", secondForwarded.path("previous_response_id").asText());
        assertEquals(1, secondForwarded.path("input").size());
        assertFalse(secondForwarded.path("input").get(0).has("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayCache_prefersAdminFingerprintForAdminRequests() throws Exception {
        lenient().when(ctx.attribute("keyFingerprint")).thenReturn(null);
        lenient().when(ctx.attribute("adminKeyFingerprint")).thenReturn("admin-fp", "admin-fp");
        lenient().when(ctx.attribute("isAdmin")).thenReturn(true, true);
        when(ctx.body()).thenReturn(
                """
                {"input":[{"id":"admin_in","type":"message","role":"user"}]}
                """,
                """
                {"previous_response_id":"resp_admin","input":[]}
                """
        );

        HttpResponse<InputStream> firstResponse = response("""
                                data: {"type":"response.completed","response":{"id":"resp_admin","status":"completed","output":[{"id":"admin_out","type":"message","role":"assistant"}],"usage":{"input_tokens":1,"output_tokens":1}}}

                                """);
        HttpResponse<InputStream> secondResponse = response("""
                                data: {"type":"response.completed","response":{"id":"resp_next","status":"completed","output":[],"usage":{"input_tokens":1,"output_tokens":1}}}

                                """);
        when(client.request(eq("/responses"), eq("POST"), anyString(), any()))
                .thenReturn(firstResponse, secondResponse);

        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        handler.handle(ctx);
        handler.handle(ctx);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).request(eq("/responses"), eq("POST"), bodyCaptor.capture(), any());

        JsonNode secondForwarded = MAPPER.readTree(bodyCaptor.getAllValues().get(1));
        assertFalse(secondForwarded.has("previous_response_id"));
        assertEquals(2, secondForwarded.path("input").size());
        assertFalse(secondForwarded.path("input").get(0).has("id"));
        assertFalse(secondForwarded.path("input").get(1).has("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayNamespaceCache_isBoundedTo512Entries() throws Exception {
        ResponsesHandler handler = new ResponsesHandler(client, minimalConfig(), usageTracker);
        when(client.request(eq("/responses"), eq("POST"), anyString(), any())).thenAnswer(invocation ->
                response("""
                        data: {"type":"response.completed","response":{"id":"resp_shared","status":"completed","output":[{"id":"out","type":"message","role":"assistant"}],"usage":{"input_tokens":1,"output_tokens":1}}}

                        """));

        for (int i = 0; i < 513; i++) {
            Context namespaceCtx = mock(Context.class);
            when(namespaceCtx.body()).thenReturn("{\"input\":[{\"id\":\"in-" + i + "\",\"type\":\"message\",\"role\":\"user\"}]}");
            stubReplayAttributes(namespaceCtx, "fp-" + i, null, null, null);
            handler.handle(namespaceCtx);
        }

        Context evictedCtx = mock(Context.class);
        when(evictedCtx.body()).thenReturn("{\"previous_response_id\":\"resp_shared\",\"input\":[]}");
        stubReplayAttributes(evictedCtx, "fp-0", null, null, null);
        handler.handle(evictedCtx);

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(514)).request(eq("/responses"), eq("POST"), bodyCaptor.capture(), any());

        JsonNode forwardedAfterEviction = MAPPER.readTree(bodyCaptor.getAllValues().get(513));
        assertEquals("resp_shared", forwardedAfterEviction.path("previous_response_id").asText());
        assertEquals(0, forwardedAfterEviction.path("input").size());
    }
}
