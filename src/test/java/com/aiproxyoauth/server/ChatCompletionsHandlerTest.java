package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatCompletionsHandlerTest {

    @Mock CodexHttpClient client;

    private Javalin app;
    private HttpClient http;
    private int port;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Canned SSE fixtures ---

    private static final String COMPLETED_TEXT_SSE =
            """
            data: {"type":"response.completed","response":{\
            "status":"completed",\
            "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello!"}]}],\
            "usage":{"input_tokens":10,"output_tokens":5}}}
            """;

    private static final String TOOL_CALL_SSE =
            """
            data: {"type":"response.completed","response":{\
            "status":"completed",\
            "output":[{"type":"function_call","call_id":"call_123","name":"myFunc","arguments":"{}"}],\
            "usage":{"input_tokens":10,"output_tokens":5}}}
            """;

    private static final String INCOMPLETE_SSE =
            """
            data: {"type":"response.completed","response":{\
            "status":"incomplete",\
            "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Truncated..."}]}],\
            "usage":{"input_tokens":10,"output_tokens":5}}}
            """;

    private static final String DELTA_SSE =
            """
            data: {"type":"response.output_text.delta","delta":"Hello World!"}

            data: {"type":"response.completed","response":{\
            "status":"completed","output":[],\
            "usage":{"input_tokens":5,"output_tokens":3}}}

            data: [DONE]
            """;

    // --- Helpers ---

    private static ServerConfig minimalConfig() {
        return new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.111.0",
                ServerConfig.DEFAULT_BASE_URL,
                null, null, null,
                "", false,
                Map.of(), null
        );
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> sseResponse(int status, String sseBody) {
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(
                new ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8)));
        return resp;
    }

    private HttpResponse<String> post(String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach void setUp() {
        ServerConfig config = minimalConfig();
        UsageTracker tracker = new UsageTracker();
        ChatCompletionsHandler handler = new ChatCompletionsHandler(client, config, tracker);
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.routes.post("/v1/chat/completions", handler);
            cfg.routes.exception(Exception.class, (e, ctx) -> ctx.status(500));
        });
        app.start("127.0.0.1", 0);
        port = app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach void tearDown() {
        if (app != null) app.stop();
        if (http != null) http.close();
    }

    // --- Request validation ---

    @Test void missingMessages_returns400() throws Exception {
        HttpResponse<String> resp = post("""
                {
                  "model": "gpt-5"
                }
                """);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("invalid_request_error"),
                "Body: " + resp.body());
    }

    @Test void truncatedJsonBody_returns500() throws Exception {
        // "{" is a truncated JSON object that Jackson's readTree() reliably throws JsonParseException on.
        // The handler has no try-catch around readTree(); Javalin maps the unhandled exception to 500.
        HttpResponse<String> resp = post("{");
        assertEquals(500, resp.statusCode());
    }

    // --- Non-streaming ---

    @Test void simpleUserMessage_returns200WithChatCompletion() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, COMPLETED_TEXT_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hello"
                    }
                  ]
                }
                """);

        assertEquals(200, resp.statusCode());
        JsonNode body = MAPPER.readTree(resp.body());
        assertEquals("chat.completion", body.path("object").asText());
        assertFalse(body.path("choices").get(0).path("message").path("content").isNull(),
                "content should not be null");
        assertEquals("Hello!", body.path("choices").get(0).path("message").path("content").asText());
    }

    @Test void systemMessage_becomesInstructions() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, COMPLETED_TEXT_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        post("""
                {
                  "messages": [
                    {
                      "role": "system",
                      "content": "You are helpful"
                    },
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ]
                }
                """);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).request(anyString(), anyString(), bodyCaptor.capture(), any());
        JsonNode upstream = MAPPER.readTree(bodyCaptor.getValue());
        assertEquals("You are helpful", upstream.path("instructions").asText());
    }

    @Test void maxTokens_mappedToMaxOutputTokens() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, COMPLETED_TEXT_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ],
                  "max_tokens": 100
                }
                """);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).request(anyString(), anyString(), bodyCaptor.capture(), any());
        JsonNode upstream = MAPPER.readTree(bodyCaptor.getValue());
        assertEquals(100, upstream.path("max_output_tokens").asInt());
    }

    @Test void maxCompletionTokens_takesPrecedenceOverMaxTokens() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, COMPLETED_TEXT_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ],
                  "max_tokens": 100,
                  "max_completion_tokens": 200
                }
                """);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).request(anyString(), anyString(), bodyCaptor.capture(), any());
        JsonNode upstream = MAPPER.readTree(bodyCaptor.getValue());
        assertEquals(200, upstream.path("max_output_tokens").asInt());
    }

    @Test void reasoningEffort_mappedToReasoningObject() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, COMPLETED_TEXT_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ],
                  "reasoning_effort": "high"
                }
                """);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).request(anyString(), anyString(), bodyCaptor.capture(), any());
        JsonNode upstream = MAPPER.readTree(bodyCaptor.getValue());
        assertEquals("high", upstream.path("reasoning").path("effort").asText());
    }

    @Test void functionCallInResponse_finishReasonIsToolCalls() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, TOOL_CALL_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Call a function"
                    }
                  ]
                }
                """);

        assertEquals(200, resp.statusCode());
        JsonNode body = MAPPER.readTree(resp.body());
        JsonNode choice = body.path("choices").get(0);
        assertEquals("tool_calls", choice.path("finish_reason").asText());
        assertTrue(choice.path("message").path("tool_calls").isArray());
        assertFalse(choice.path("message").path("tool_calls").isEmpty());
    }

    @Test void incompleteStatus_finishReasonIsLength() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, INCOMPLETE_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Long task"
                    }
                  ]
                }
                """);

        assertEquals(200, resp.statusCode());
        JsonNode body = MAPPER.readTree(resp.body());
        assertEquals("length", body.path("choices").get(0).path("finish_reason").asText());
    }

    // --- Streaming ---

    @Test void streamTrue_responseContentTypeIsEventStream() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, DELTA_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ],
                  "stream": true
                }
                """);

        assertTrue(resp.headers().firstValue("content-type")
                        .orElse("").startsWith("text/event-stream"),
                "Expected text/event-stream, got: " +
                        resp.headers().firstValue("content-type").orElse("none"));
    }

    @Test void streamTrue_deltaForwarded() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, DELTA_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ],
                  "stream": true
                }
                """);

        assertTrue(resp.body().contains("\"Hello World!\""),
                "Expected delta content in streaming body: " + resp.body());
    }

    @Test void streamTrue_endsWithDone() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, DELTA_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ],
                  "stream": true
                }
                """);

        assertTrue(resp.body().contains("data: [DONE]"),
                "Expected '[DONE]' in streaming body: " + resp.body());
    }

    // --- Tool role message translation ---

    @Test void toolRoleMessage_translatedToFunctionCallOutput() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, COMPLETED_TEXT_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Call it"
                    },
                    {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [
                        {
                          "id": "call_abc",
                          "type": "function",
                          "function": {
                            "name": "fn",
                            "arguments": "{}"
                          }
                        }
                      ]
                    },
                    {
                      "role": "tool",
                      "tool_call_id": "call_abc",
                      "content": "42"
                    }
                  ]
                }
                """);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).request(anyString(), anyString(), bodyCaptor.capture(), any());
        JsonNode upstream = MAPPER.readTree(bodyCaptor.getValue());

        boolean found = false;
        for (JsonNode item : upstream.path("input")) {
            if ("function_call_output".equals(item.path("type").asText())) {
                assertEquals("call_abc", item.path("call_id").asText());
                assertEquals("42", item.path("output").asText());
                found = true;
            }
        }
        assertTrue(found, "Expected function_call_output item in upstream input: " + upstream);
    }

    // --- Error passthrough ---

    @Test void upstream401_handlerReturns401() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(401, """
                {
                  "error": {
                    "message": "Unauthorized",
                    "type": "auth_error"
                  }
                }
                """);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ]
                }
                """);

        assertEquals(401, resp.statusCode());
    }

    @Test void streamTrue_upstreamErrorDuringStream_emittedToClient() throws Exception {
        String errorSse = """
                data: {"type":"response.failed","response":{"error":{"message":"Quota exceeded"}}}
                """;
        HttpResponse<InputStream> sseResp = sseResponse(200, errorSse);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ],
                  "stream": true
                }
                """);

        assertTrue(resp.body().contains("Quota exceeded"));
        assertTrue(resp.body().contains("event: error"));
    }

    @Test void streamTrue_unexpectedDone_terminatesCleanly() throws Exception {
        // Upstream sends [DONE] without response.completed
        String shortSse = "data: [DONE]\n\n";
        HttpResponse<InputStream> sseResp = sseResponse(200, shortSse);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        HttpResponse<String> resp = post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Hi"
                    }
                  ],
                  "stream": true
                }
                """);

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("finish_reason\":\"stop\""));
    }

    @Test void complexContentParts_translatedCorrectly() throws Exception {
        HttpResponse<InputStream> sseResp = sseResponse(200, COMPLETED_TEXT_SSE);
        when(client.request(anyString(), anyString(), anyString(), any())).thenReturn(sseResp);

        post("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": [
                        {
                          "type": "text",
                          "text": "describe this"
                        },
                        {
                          "type": "image_url",
                          "image_url": {
                            "url": "data:image/png;base64,abc"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).request(anyString(), anyString(), bodyCaptor.capture(), any());
        JsonNode upstream = MAPPER.readTree(bodyCaptor.getValue());
        JsonNode content = upstream.path("input").get(0).path("content");

        assertEquals(2, content.size());
        assertEquals("input_text", content.get(0).path("type").asText());
        assertEquals("input_image", content.get(1).path("type").asText());
    }
}
