package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.anthropic.AnthropicRequestOptions;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnthropicMessagesHandlerTest {
    @TempDir Path temporary;
    private Javalin app;
    private HttpClient http;

    @AfterEach
    void close() {
        if (app != null) app.stop();
        if (http != null) http.close();
    }

    @Test
    void proxiesNativeSyncJsonAndRecordsUsageWithoutOpenAiTranslation() throws Exception {
        String upstreamBody = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                 "content":[{"type":"text","text":"native"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":7,"output_tokens":3}}
                """;
        UsageTracker usage = new UsageTracker();
        Fixture fixture = serve(upstreamBody, 200, "application/json", usage);

        HttpResponse<String> response = post(false, "2023-06-01", "future-beta-2026-01-01");

        assertEquals(200, response.statusCode());
        assertEquals(Json.MAPPER.readTree(upstreamBody), Json.MAPPER.readTree(response.body()));
        assertFalse(response.body().contains("chat.completion"));
        assertEquals(7, usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).promptTokens());
        assertEquals(3, usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).completionTokens());
        verify(fixture.client()).request(any(URI.class), eq("POST"),
                anyString(), any(AnthropicRequestOptions.class));
        assertTrue(fixture.sentBody().toString().contains("official CLI for Claude"));
    }

    @Test
    void proxiesNativeSseBytesWithoutDoneAndRecordsTerminalUsage() throws Exception {
        String upstreamBody = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","usage":{"input_tokens":8,"output_tokens":0}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"native"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        UsageTracker usage = new UsageTracker();
        serve(upstreamBody, 200, "text/event-stream", usage);

        HttpResponse<String> response = post(true, "2023-06-01", null);

        assertEquals(200, response.statusCode());
        assertEquals(upstreamBody, response.body());
        assertFalse(response.body().contains("[DONE]"));
        assertEquals(8, usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).promptTokens());
        assertEquals(4, usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).completionTokens());
    }

    @Test
    void rejectsMissingVersionWithAnthropicErrorShapeBeforeUpstream() throws Exception {
        Fixture fixture = serve("{}", 200, "application/json", new UsageTracker());

        HttpResponse<String> response = post(false, null, null);

        assertEquals(400, response.statusCode());
        assertEquals("error", Json.MAPPER.readTree(response.body()).path("type").asText());
        assertEquals("invalid_request_error", Json.MAPPER.readTree(response.body())
                .path("error").path("type").asText());
        org.mockito.Mockito.verifyNoInteractions(fixture.client());
    }

    @Test
    void mapsTransportFailureToAnthropicErrorShape() throws Exception {
        Fixture fixture = serve("{}", 200, "application/json", new UsageTracker());
        when(fixture.client().request(any(URI.class), eq("POST"), anyString(),
                any(AnthropicRequestOptions.class))).thenThrow(new IOException("offline"));

        HttpResponse<String> response = post(false, "2023-06-01", null);

        assertEquals(502, response.statusCode());
        JsonNode error = Json.MAPPER.readTree(response.body());
        assertEquals("error", error.path("type").asText());
        assertEquals("api_error", error.path("error").path("type").asText());
        assertFalse(response.body().contains("offline"));
    }

    @SuppressWarnings("unchecked")
    private Fixture serve(String body, int status, String contentType, UsageTracker usage)
            throws Exception {
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        HttpHeaders headers = HttpHeaders.of(
                Map.of("content-type", List.of(contentType), "request-id", List.of("req_upstream")),
                (a, b) -> true);
        when(upstream.statusCode()).thenReturn(status);
        when(upstream.headers()).thenReturn(headers);
        when(upstream.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        StringBuilder sentBody = new StringBuilder();
        when(client.request(any(URI.class), eq("POST"), anyString(),
                any(AnthropicRequestOptions.class))).thenAnswer(invocation -> {
                    sentBody.append(invocation.getArgument(2, String.class));
                    return upstream;
                });
        ModelCatalog catalog = () -> List.of(new ProviderModel(
                "claude-sonnet-4-5", "Claude Sonnet", ProviderId.ANTHROPIC,
                List.of("sonnet"), Optional.of(true), 200_000));
        AnthropicMessagesHandler handler = new AnthropicMessagesHandler(
                client, AnthropicCompatibilityProfile.claudeCodeOAuth(), catalog, usage,
                new RequestLogger(false, temporary.resolve("logs")));
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.routes.before(context -> context.attribute(
                    AccessLogFields.REQUEST_ID, "req_local"));
            config.routes.post("/v1/messages", handler);
        }).start("127.0.0.1", 0);
        http = HttpClient.newHttpClient();
        return new Fixture(client, sentBody);
    }

    private HttpResponse<String> post(boolean stream, String version, String betas)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/v1/messages"))
                .header("Content-Type", "application/json");
        if (version != null) request.header("anthropic-version", version);
        if (betas != null) request.header("anthropic-beta", betas);
        request.POST(HttpRequest.BodyPublishers.ofString("""
                {"model":"anthropic/sonnet","max_tokens":32,"stream":%s,
                 "system":"client","messages":[{"role":"user","content":"hello"}]}
                """.formatted(stream)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Fixture(AnthropicHttpClient client, StringBuilder sentBody) {}
}
