package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.provider.ModelRoute;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnthropicResponsesHandlerTest {
    private static final String TEXT_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_resp","model":"claude-sonnet-4-5","usage":{"input_tokens":9,"output_tokens":0,"cache_read_input_tokens":2}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello Responses"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @TempDir Path tempDir;
    private Javalin app;
    private HttpClient http;

    @AfterEach
    void close() {
        if (app != null) app.stop();
        if (http != null) http.close();
    }

    @Test
    void synchronousClaudeResponseUsesResponsesEnvelopeAndUsage() throws Exception {
        UsageTracker usage = new UsageTracker();
        HttpResponse<String> response = serveAndPost(usage, false);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello Responses"));
        assertTrue(response.body().contains("\"object\":\"response\""));
        assertTrue(response.body().contains("\"model\":\"anthropic/sonnet\""));
        assertEquals(9, usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).promptTokens());
        assertEquals(3, usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).completionTokens());
    }

    @Test
    void streamingClaudeResponseEmitsLifecycleAndOneTerminalEvent() throws Exception {
        HttpResponse<String> response = serveAndPost(new UsageTracker(), true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("event: response.created"));
        assertTrue(response.body().contains("event: response.output_text.delta"));
        assertEquals(1, occurrences(response.body(), "event: response.completed"));
        assertFalse(response.body().contains("data: [DONE]"));
    }

    @Test
    void unresolvedPreviousResponseIsRejectedWithoutCallingAnthropic() throws Exception {
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        Context context = mock(Context.class);
        when(context.body()).thenReturn("""
                {"model":"anthropic/sonnet","previous_response_id":"resp_missing","input":"hi"}
                """);
        AnthropicResponsesBackend backend = backend(client, new UsageTracker());

        backend.handle(context, route());

        verify(context).status(400);
        verify(context).result(contains("unsupported_provider_feature"));
        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachedPreviousResponseIsExpandedBeforeSecondAnthropicRequest() throws Exception {
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> first = upstream(TEXT_STREAM);
        HttpResponse<InputStream> second = upstream(TEXT_STREAM.replace("msg_resp", "msg_resp_2"));
        when(client.request(any(URI.class), eq("POST"), anyString(), anyMap()))
                .thenReturn(first, second);
        AtomicReference<String> secondWireBody = new AtomicReference<>();
        doAnswer(invocation -> {
            secondWireBody.set(invocation.getArgument(2));
            return second;
        }).when(client).request(any(URI.class), eq("POST"), contains("follow up"), anyMap());
        Context context = mock(Context.class);
        when(context.body()).thenReturn(
                """
                {"model":"anthropic/sonnet","input":"first"}
                """,
                """
                {"model":"anthropic/sonnet","previous_response_id":"resp_msg_resp","input":"follow up"}
                """);
        AnthropicResponsesBackend backend = backend(client, new UsageTracker());

        backend.handle(context, route());
        backend.handle(context, route());

        assertNotNull(secondWireBody.get());
        assertTrue(secondWireBody.get().contains("Hello Responses"));
        assertFalse(secondWireBody.get().contains("previous_response_id"));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> serveAndPost(UsageTracker usage, boolean stream) throws Exception {
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> upstream = upstream(TEXT_STREAM);
        when(client.request(any(URI.class), eq("POST"), anyString(), anyMap()))
                .thenReturn(upstream);
        AnthropicResponsesBackend backend = backend(client, usage);
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.routes.post("/v1/responses", context -> backend.handle(context, route()));
        }).start("127.0.0.1", 0);
        http = HttpClient.newHttpClient();
        String body = """
                {"model":"anthropic/sonnet","stream":%s,"input":"hello"}
                """.formatted(stream);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/v1/responses"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private AnthropicResponsesBackend backend(AnthropicHttpClient client, UsageTracker usage) {
        return new AnthropicResponsesBackend(
                client, AnthropicCompatibilityProfile.claudeCodeOAuth(), usage,
                new RequestLogger(false, tempDir));
    }

    private ModelRoute route() {
        return new ModelRoute(
                ProviderId.ANTHROPIC, "anthropic/sonnet", "claude-sonnet-4-5", null);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> upstream(String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
