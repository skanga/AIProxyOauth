package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.anthropic.AnthropicRequestOptions;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnthropicModelsHandlerTest {
    @TempDir Path temporary;
    private Javalin app;
    private HttpClient http;

    @AfterEach
    void close() {
        if (app != null) app.stop();
        if (http != null) http.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void proxiesNativeCatalogAndValidatedPaginationUnchanged() throws Exception {
        String body = """
                {"data":[{"id":"claude-sonnet-4-5","type":"model",
                 "display_name":"Claude Sonnet 4.5","created_at":"2025-01-01T00:00:00Z",
                 "capabilities":{"thinking":{"supported":true}}}],
                 "first_id":"claude-sonnet-4-5","last_id":"claude-sonnet-4-5","has_more":false}
                """;
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(200);
        when(upstream.headers()).thenReturn(HttpHeaders.of(
                Map.of("content-type", List.of("application/json"),
                        "request-id", List.of("req_models")), (a, b) -> true));
        when(upstream.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(client.request(any(URI.class), eq("GET"), eq(null),
                any(AnthropicRequestOptions.class))).thenReturn(upstream);
        start(new AnthropicModelsHandler(
                client, AnthropicCompatibilityProfile.claudeCodeOAuth(),
                new RequestLogger(false, temporary.resolve("logs"))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port()
                        + "/v1/models?after_id=claude-old&limit=50"))
                .header("anthropic-version", "2023-06-01")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(body, response.body());
        assertEquals("req_models", response.headers().firstValue("request-id").orElseThrow());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(client).request(uri.capture(), eq("GET"), eq(null),
                any(AnthropicRequestOptions.class));
        assertTrue(uri.getValue().getQuery().contains("after_id=claude-old"));
        assertTrue(uri.getValue().getQuery().contains("limit=50"));
    }

    @Test
    void rejectsInvalidLimitWithoutCallingUpstream() throws Exception {
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        start(new AnthropicModelsHandler(
                client, AnthropicCompatibilityProfile.claudeCodeOAuth(),
                new RequestLogger(false, temporary.resolve("logs"))));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/v1/models?limit=1001"))
                .header("anthropic-version", "2023-06-01")
                .GET().build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("invalid_request_error"));
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void mapsTransportFailureToAnthropicErrorShape() throws Exception {
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        when(client.request(any(URI.class), eq("GET"), eq(null),
                any(AnthropicRequestOptions.class))).thenThrow(new IOException("offline"));
        start(new AnthropicModelsHandler(
                client, AnthropicCompatibilityProfile.claudeCodeOAuth(),
                new RequestLogger(false, temporary.resolve("logs"))));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/v1/models"))
                .header("anthropic-version", "2023-06-01")
                .GET().build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(502, response.statusCode());
        assertTrue(response.body().contains("\"type\":\"error\""));
        assertTrue(response.body().contains("\"type\":\"api_error\""));
        org.junit.jupiter.api.Assertions.assertFalse(response.body().contains("offline"));
    }

    private void start(AnthropicModelsHandler handler) {
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.routes.before(context -> context.attribute(
                    AccessLogFields.REQUEST_ID, "req_local"));
            config.routes.get("/v1/models", handler);
        }).start("127.0.0.1", 0);
        http = HttpClient.newHttpClient();
    }
}
