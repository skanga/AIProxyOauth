package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.provider.ModelRoute;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.Javalin;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnthropicChatCompletionsHandlerTest {
    private static final String TEXT_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","model":"claude-sonnet-4-5","usage":{"input_tokens":9,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello Claude"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
    private static final String TOOL_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_tool","model":"claude-sonnet-4-5","usage":{"input_tokens":6,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_1","name":"lookup","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\\"q\\\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\\"x\\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}

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
    void synchronousClaudeTextUsesOpenAiEnvelopeAndRecordsUsage() throws Exception {
        UsageTracker usage = new UsageTracker();
        HttpResponse<String> response = serveAndPost(usage, false);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello Claude"));
        assertTrue(response.body().contains("anthropic/sonnet"));
        assertEquals(9, usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).promptTokens());
        assertEquals(3, usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).completionTokens());
    }

    @Test
    void streamingClaudeTextTerminatesExactlyOnce() throws Exception {
        HttpResponse<String> response = serveAndPost(new UsageTracker(), true);

        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream", response.headers().firstValue("content-type")
                .orElseThrow().split(";")[0]);
        assertTrue(response.body().contains("chat.completion.chunk"));
        assertEquals(1, occurrences(response.body(), "data: [DONE]"));
    }

    @Test
    void synchronousClaudeToolCallUsesOpenAiToolShape() throws Exception {
        HttpResponse<String> response = serveAndPost(new UsageTracker(), false, TOOL_STREAM);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"finish_reason\":\"tool_calls\""));
        assertTrue(response.body().contains("\"id\":\"tool_1\""));
        assertTrue(response.body().contains("\\\"q\\\":\\\"x\\\""));
    }

    @Test
    void streamingClaudeToolCallEmitsStableIndexAndOneTerminator() throws Exception {
        HttpResponse<String> response = serveAndPost(new UsageTracker(), true, TOOL_STREAM);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"index\":0"));
        assertTrue(response.body().contains("\"finish_reason\":\"tool_calls\""));
        assertEquals(1, occurrences(response.body(), "data: [DONE]"));
    }

    private HttpResponse<String> serveAndPost(UsageTracker usage, boolean stream) throws Exception {
        return serveAndPost(usage, stream, TEXT_STREAM);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> serveAndPost(
            UsageTracker usage, boolean stream, String upstreamBody) throws Exception {
        AnthropicHttpClient client = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(200);
        when(upstream.body()).thenReturn(new ByteArrayInputStream(
                upstreamBody.getBytes(StandardCharsets.UTF_8)));
        when(client.request(any(URI.class), eq("POST"), anyString(), anyMap()))
                .thenReturn(upstream);
        AnthropicChatBackend backend = new AnthropicChatBackend(
                client,
                AnthropicCompatibilityProfile.claudeCodeOAuth(),
                usage,
                new RequestLogger(false, tempDir)
        );
        ModelRoute route = new ModelRoute(
                ProviderId.ANTHROPIC, "anthropic/sonnet", "claude-sonnet-4-5", null);
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.routes.post("/v1/chat/completions", context -> backend.handle(context, route));
        }).start("127.0.0.1", 0);
        http = HttpClient.newHttpClient();
        String body = """
                {"model":"anthropic/sonnet","stream":%s,
                 "messages":[{"role":"user","content":"hello"}]}
                """.formatted(stream);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
