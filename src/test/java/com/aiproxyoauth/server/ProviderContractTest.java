package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.CopilotModelCatalog;
import com.aiproxyoauth.provider.*;
import com.aiproxyoauth.provider.anthropic.*;
import com.aiproxyoauth.provider.copilot.CopilotClient;
import com.aiproxyoauth.sse.SseParser;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One client-visible contract, exercised through real HTTP handlers and each wire decoder. */
class ProviderContractTest {
    enum Backend {
        CODEX, ANTHROPIC, COPILOT_CHAT, COPILOT_RESPONSES, COPILOT_MESSAGES;
        ProviderId provider() { return this == CODEX ? ProviderId.CODEX : this == ANTHROPIC ? ProviderId.ANTHROPIC : ProviderId.COPILOT; }
        boolean messages() { return this == ANTHROPIC || this == COPILOT_MESSAGES; }
    }
    enum Outcome { COMPLETE, LENGTH, EMPTY_LENGTH, TRUNCATED, FAILED, REFUSAL, DISCONNECTED }

    @ParameterizedTest @EnumSource(Backend.class)
    void collectedAndStreamingChatAgreeOnTextUsageAndTermination(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.COMPLETE)) {
            JsonNode collected = json(fixture.post(false, false, null));
            assertEquals("Hello", collected.at("/choices/0/message/content").asString());
            assertEquals("stop", collected.at("/choices/0/finish_reason").asString());
            assertUsage(collected.path("usage"), false);
            HttpResponse<String> streamed = fixture.post(false, true, null);
            assertEquals(200, streamed.statusCode(), streamed.body());
            List<JsonNode> chunks = events(streamed.body());
            assertEquals("Hello", chunks.stream().map(n -> n.at("/choices/0/delta/content").asString("")).reduce("", String::concat));
            assertEquals(1, chunks.stream().filter(n -> n.at("/choices/0/finish_reason").asString().equals("stop")).count());
            assertEquals(1, occurrences(streamed.body(), "data: [DONE]"));
            assertUsage(chunks.stream().filter(n -> n.path("usage").isObject()).findFirst().orElseThrow().path("usage"), false);
            assertEquals(246, fixture.usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).promptTokens());
        }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void incompleteChatPreservesPartialOutputLengthAndUsageInBothModes(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.LENGTH)) {
            JsonNode collected = json(fixture.post(false, false, null));
            assertEquals("Hello", collected.at("/choices/0/message/content").asString());
            assertEquals("length", collected.at("/choices/0/finish_reason").asString());
            assertUsage(collected.path("usage"), false);
            var streamed = fixture.post(false, true, null);
            assertEquals(200, streamed.statusCode(), streamed.body());
            assertTrue(events(streamed.body()).stream().anyMatch(n -> n.at("/choices/0/finish_reason").asString().equals("length")), streamed.body());
            assertFalse(streamed.body().contains("\"finish_reason\":\"stop\""), streamed.body());
            assertEquals(1, occurrences(streamed.body(), "data: [DONE]"));
        }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void tokenLimitBeforeVisibleOutputIsStillAnIncompleteCompletion(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.EMPTY_LENGTH)) {
            JsonNode response = json(fixture.post(false, false, null));
            assertEquals("length", response.at("/choices/0/finish_reason").asString());
            assertTrue(response.at("/choices/0/message/content").isNull());
            assertUsage(response.path("usage"), false);
        }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void transportDisconnectAfterTextUsesOnlySseErrorAndOneDone(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.DISCONNECTED)) {
            var response = fixture.post(false, true, null);
            assertEquals(200, response.statusCode(), response.body());
            List<JsonNode> chunks = events(response.body());
            assertEquals(1, chunks.stream().filter(n -> n.path("error").isObject()).count(), response.body());
            assertFalse(chunks.stream().anyMatch(n -> n.at("/choices/0/finish_reason").isString()), response.body());
            assertEquals(1, occurrences(response.body(), "data: [DONE]"));
            assertTrue(response.body().stripTrailing().endsWith("data: [DONE]"), response.body());
        }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void truncatedChatIsAnErrorInBothModes(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.TRUNCATED)) {
            var collected = fixture.post(false, false, null);
            assertEquals(502, collected.statusCode(), collected.body());
            assertTrue(Json.MAPPER.readTree(collected.body()).path("error").isObject());
            var streamed = fixture.post(false, true, null);
            assertEquals(200, streamed.statusCode(), streamed.body());
            assertTrue(events(streamed.body()).stream().anyMatch(n -> n.path("error").isObject()), streamed.body());
            assertFalse(events(streamed.body()).stream().anyMatch(n -> n.at("/choices/0/finish_reason").isString()), streamed.body());
            assertEquals(1, occurrences(streamed.body(), "data: [DONE]"));
            assertTrue(fixture.usage.snapshot().isEmpty());
        }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void responsesAcceptImplicitMessagesAndReplayWithConsistentUsage(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.COMPLETE)) {
            var body = Json.MAPPER.createObjectNode();
            body.putArray("input").addObject().put("role", "user").put("content", "first");
            JsonNode response = json(fixture.post(true, false, body));
            assertEquals("completed", response.path("status").asString());
            assertEquals("Hello", response.at("/output/0/content/0/text").asString());
            assertUsage(response.path("usage"), true);
            JsonNode continued = json(fixture.post(true, false, Json.MAPPER.createObjectNode()
                    .put("previous_response_id", response.path("id").asString()).put("input", "second")));
            assertEquals("completed", continued.path("status").asString());
            assertTrue(fixture.sent.get().contains("first"));
            assertTrue(fixture.sent.get().contains("Hello"));
            assertTrue(fixture.sent.get().contains("second"));
        }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void incompleteResponsesPreserveStatusAndUsage(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.LENGTH)) {
            JsonNode response = json(fixture.post(true, false, null));
            assertEquals("incomplete", response.path("status").asString());
            assertEquals("max_output_tokens", response.at("/incomplete_details/reason").asString());
            assertUsage(response.path("usage"), true);
            var streamed = fixture.post(true, true, null);
            assertEquals(200, streamed.statusCode(), streamed.body());
            var terminal = events(streamed.body()).stream().filter(n -> n.path("type").asString().equals("response.incomplete")).toList();
            assertEquals(1, terminal.size(), streamed.body());
            assertUsage(terminal.getFirst().at("/response/usage"), true);
            assertFalse(streamed.body().contains("[DONE]"));
            assertEquals(246, fixture.usage.snapshot().get(UsageTracker.OPEN_MODE_KEY).promptTokens());
        }
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void responsesFailureHasTypedEnvelopeIdentityAndMonotonicSequence(Backend backend) throws Exception {
        // Codex Responses forwards upstream frames; the other backends translate the failure.
        List<Outcome> failuresToTest = backend == Backend.CODEX ? List.of(Outcome.FAILED)
                : List.of(Outcome.FAILED, Outcome.TRUNCATED, Outcome.DISCONNECTED);
        for (Outcome outcome : failuresToTest) {
        try (Fixture fixture = new Fixture(backend, outcome)) {
            var streamed = fixture.post(true, true, null);
            assertEquals(200, streamed.statusCode(), streamed.body());
            List<JsonNode> frames = events(streamed.body());
            List<JsonNode> failures = frames.stream().filter(n -> n.path("type").asString().equals("response.failed")).toList();
            assertEquals(1, failures.size(), streamed.body());
            JsonNode failure = failures.getFirst();
            assertEquals("failed", failure.at("/response/status").asString());
            assertTrue(failure.at("/response/error/message").isString());
            assertEquals(frames.getFirst().at("/response/id").asString(), failure.at("/response/id").asString());
            long previous = -1;
            for (JsonNode frame : frames) {
                assertTrue(frame.path("type").isString(), frame.toString());
                assertTrue(frame.path("sequence_number").isIntegralNumber(), frame.toString());
                assertTrue(frame.path("sequence_number").asLong() > previous, frame.toString());
                previous = frame.path("sequence_number").asLong();
            }
            assertFalse(streamed.body().contains("[DONE]"));
            assertTrue(fixture.usage.snapshot().isEmpty());
        }
        }
    }

    @ParameterizedTest @EnumSource(value = Backend.class, names = {"CODEX", "COPILOT_CHAT", "COPILOT_RESPONSES"})
    void refusalsRemainSeparateFromTextInBothChatModes(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.REFUSAL)) {
            JsonNode collected = json(fixture.post(false, false, null));
            assertEquals("Declined", collected.at("/choices/0/message/refusal").asString());
            assertTrue(collected.at("/choices/0/message/content").isNull());
            var streamed = fixture.post(false, true, null);
            assertEquals("Declined", events(streamed.body()).stream().map(n -> n.at("/choices/0/delta/refusal").asString("")).reduce("", String::concat));
        }
    }

    @ParameterizedTest @EnumSource(value = Backend.class, names = {"ANTHROPIC", "COPILOT_CHAT", "COPILOT_RESPONSES", "COPILOT_MESSAGES"})
    void encryptedReasoningIsRejectedBeforeUpstreamRatherThanDiscarded(Backend backend) throws Exception {
        try (Fixture fixture = new Fixture(backend, Outcome.COMPLETE)) {
            ObjectNode body = Json.MAPPER.createObjectNode();
            body.putArray("input").addObject().put("type", "reasoning").put("encrypted_content", "opaque-state").putArray("summary");
            var response = fixture.post(true, false, body);
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(Json.MAPPER.readTree(response.body()).at("/error/message").asString().contains("encrypted_content"), response.body());
            assertNull(fixture.sent.get());
        }
    }

    @Test
    void everyAdvertisedModelRoutesToItsOwnerEvenWithIdAndAliasCollisions() throws Exception {
        var models = List.of(model("same", ProviderId.CODEX, List.of()), model("same", ProviderId.COPILOT, List.of()),
                model("claude", ProviderId.ANTHROPIC, List.of()), model("other", ProviderId.COPILOT, List.of("claude")),
                model("unique", ProviderId.CODEX, List.of()));
        var context = mock(io.javalin.http.Context.class);
        new ModelsHandler(() -> models).handle(context);
        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(context).result(body.capture());
        var router = new ProviderRouter(models, ProviderId.COPILOT);
        var listed = Json.MAPPER.readTree(body.getValue()).path("data");
        for (int i = 0; i < models.size(); i++) {
            assertEquals(models.get(i).provider(), router.route(listed.get(i).path("id").asString()).provider());
        }
        assertEquals("unique", listed.get(4).path("id").asString());
    }

    @Test
    void failoverSkipsUnsupportedCopilotFieldsButKeepsImplicitResponsesMessages() throws Exception {
        try (Fixture fixture = new Fixture(Backend.COPILOT_CHAT, Outcome.COMPLETE)) {
            var body = Json.MAPPER.createObjectNode().put("parallel_tool_calls", false);
            body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            var context = mock(io.javalin.http.Context.class);
            when(context.body()).thenReturn(body.put("model", "same").toString());
            when(context.path()).thenReturn("/v1/chat/completions");
            var fallback = mock(ChatBackend.class);
            when(fallback.supports(any(), any(), anyBoolean())).thenReturn(true);
            var dispatch = new ProviderDispatch(() -> List.of(model("same", ProviderId.COPILOT, List.of()), model("same", ProviderId.CODEX, List.of())),
                    ProviderId.COPILOT, "same", Map.of(ProviderId.COPILOT, fixture.chat, ProviderId.CODEX, fallback), ProviderId.defaultOrder(), true);
            dispatch.handle(context);
            verify(fallback).handle(eq(context), any());
            assertNull(fixture.sent.get());
            ObjectNode implicit = Json.MAPPER.createObjectNode();
            implicit.putArray("input").addObject().put("role", "user").put("content", "hi");
            assertTrue(fixture.chat.supports(implicit, fixture.route, true));
        }
    }

    private static void assertUsage(JsonNode usage, boolean responses) {
        assertEquals(123, usage.path(responses ? "input_tokens" : "prompt_tokens").asInt(), usage.toString());
        assertEquals(14, usage.path(responses ? "output_tokens" : "completion_tokens").asInt(), usage.toString());
        assertEquals(137, usage.path("total_tokens").asInt(), usage.toString());
        assertEquals(100, usage.path(responses ? "input_tokens_details" : "prompt_tokens_details").path("cached_tokens").asInt(), usage.toString());
    }
    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Json.MAPPER.readTree(response.body());
    }
    private static List<JsonNode> events(String wire) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (var event : SseParser.parse(bytes(wire))) {
            if (event.data() != null && !event.data().equals("[DONE]")) result.add(Json.MAPPER.readTree(event.data()));
        }
        return result;
    }
    private static int occurrences(String text, String token) { return text.split(java.util.regex.Pattern.quote(token), -1).length - 1; }
    private static InputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private static String event(String type, String body) { return "event: " + type + "\ndata: " + body + "\n\n"; }
    private static ProviderModel model(String id, ProviderId provider, List<String> aliases) { return new ProviderModel(id, id, provider, aliases, Optional.of(true), 1000); }

    private static String wire(Backend backend, Outcome outcome) {
        if (outcome == Outcome.DISCONNECTED) return wire(backend, Outcome.TRUNCATED);
        if (outcome == Outcome.EMPTY_LENGTH) {
            String wire = wire(backend, Outcome.LENGTH).replace("Hello", "");
            // A reasoning/token-budget stop can legitimately have no visible output items.
            if (backend == Backend.CODEX || backend == Backend.COPILOT_RESPONSES) {
                wire = wire.replace("\"output\":[{\"id\":\"msg_one\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"\"}]}]", "\"output\":[]");
            }
            return wire;
        }
        if (backend.messages()) {
            String prefix = event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_one\",\"model\":\"same\",\"usage\":{\"input_tokens\":3,\"output_tokens\":0,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":20}}}")
                    + event("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"Hello\"}}")
                    + event("content_block_stop", "{\"index\":0}");
            if (outcome == Outcome.TRUNCATED) return prefix;
            if (outcome == Outcome.FAILED) return prefix + event("error", "{\"error\":{\"type\":\"api_error\",\"message\":\"failed\"}}");
            return prefix + event("message_delta", "{\"delta\":{\"stop_reason\":\"" + (outcome == Outcome.LENGTH ? "max_tokens" : "end_turn") + "\"},\"usage\":{\"output_tokens\":14}}") + event("message_stop", "{}");
        }
        if (backend == Backend.COPILOT_CHAT) {
            String prefix = event("message", "{\"id\":\"one\",\"model\":\"same\",\"choices\":[{\"index\":0,\"delta\":{\"" + (outcome == Outcome.REFUSAL ? "refusal\":\"Declined" : "content\":\"Hello") + "\"}}]}");
            if (outcome == Outcome.TRUNCATED) return prefix;
            if (outcome == Outcome.FAILED) return prefix + event("error", "{\"error\":{\"message\":\"failed\"}}");
            return prefix + event("message", "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + (outcome == Outcome.LENGTH ? "length" : "stop") + "\"}]}")
                    + event("message", "{\"choices\":[],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":14,\"prompt_tokens_details\":{\"cached_tokens\":100}}}") + "data: [DONE]\n\n";
        }
        String prefix = event("response.created", "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_one\",\"model\":\"same\",\"status\":\"in_progress\"}}")
                + event("delta", "{\"type\":\"" + (outcome == Outcome.REFUSAL ? "response.refusal.delta" : "response.output_text.delta") + "\",\"sequence_number\":1,\"output_index\":0,\"content_index\":0,\"delta\":\"" + (outcome == Outcome.REFUSAL ? "Declined" : "Hello") + "\"}");
        if (outcome == Outcome.TRUNCATED) return prefix;
        String status = outcome == Outcome.LENGTH ? "incomplete" : outcome == Outcome.FAILED ? "failed" : "completed";
        String part = outcome == Outcome.REFUSAL ? "{\"type\":\"refusal\",\"refusal\":\"Declined\"}" : "{\"type\":\"output_text\",\"text\":\"Hello\"}";
        return prefix + event("response." + status, "{\"type\":\"response." + status + "\",\"sequence_number\":2,\"response\":{\"id\":\"resp_one\",\"model\":\"same\",\"status\":\"" + status + "\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"error\":{\"message\":\"failed\"},\"output\":[{\"id\":\"msg_one\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[" + part + "]}],\"usage\":{\"input_tokens\":123,\"output_tokens\":14,\"total_tokens\":137,\"input_tokens_details\":{\"cached_tokens\":100}}}}");
    }

    private static final class Fixture implements AutoCloseable {
        final UsageTracker usage = new UsageTracker();
        final AtomicReference<String> sent = new AtomicReference<>();
        final ChatBackend chat;
        final ModelRoute route;
        final Javalin app;
        final HttpClient http = HttpClient.newHttpClient();

        @SuppressWarnings("unchecked") Fixture(Backend backend, Outcome outcome) throws Exception {
            route = new ModelRoute(backend.provider(), backend.provider().wireName() + "/same", "same", null);
            HttpResponse<InputStream> upstream = mock(HttpResponse.class);
            when(upstream.statusCode()).thenReturn(200);
            when(upstream.body()).thenAnswer(call -> {
                InputStream data = bytes(wire(backend, outcome));
                if (outcome != Outcome.DISCONNECTED) return data;
                return new java.io.FilterInputStream(data) {
                    @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                        int read = super.read(buffer, offset, length);
                        if (read == -1) throw new java.io.IOException("upstream reset");
                        return read;
                    }
                };
            });
            ResponsesBackend responses;
            var logger = new RequestLogger(false, Path.of("target/contract-logs"));
            if (backend == Backend.CODEX) {
                var client = mock(CodexHttpClient.class);
                when(client.request(anyString(), anyString(), anyString(), anyMap())).thenAnswer(call -> { sent.set(call.getArgument(2)); return upstream; });
                var config = new ServerConfig("127.0.0.1", 10531, null, "0.1", ServerConfig.DEFAULT_BASE_URL, null, null, null, "", false, Map.of(), null);
                chat = new ChatCompletionsHandler(client, config, usage);
                responses = new ResponsesHandler(client, config, usage);
            } else if (backend == Backend.ANTHROPIC) {
                var client = mock(AnthropicHttpClient.class);
                when(client.request(any(URI.class), anyString(), anyString(), anyMap())).thenAnswer(call -> { sent.set(call.getArgument(2)); return upstream; });
                var profile = AnthropicCompatibilityProfile.claudeCodeOAuth();
                chat = new AnthropicChatBackend(client, profile, usage, logger);
                responses = new AnthropicResponsesBackend(client, profile, usage, logger);
            } else {
                var client = mock(CopilotClient.class);
                when(client.identity()).thenReturn("account");
                when(client.request(anyString(), any())).thenAnswer(call -> { sent.set(call.getArgument(1).toString()); return upstream; });
                var catalog = mock(CopilotModelCatalog.class);
                when(catalog.endpoint(anyString(), anyBoolean())).thenReturn(backend == Backend.COPILOT_CHAT ? "/chat/completions" : backend == Backend.COPILOT_RESPONSES ? "/responses" : "/v1/messages");
                var copilot = new CopilotBackend(client, catalog, usage, logger);
                chat = copilot;
                responses = copilot;
            }
            app = Javalin.create(c -> {
                c.routes.post("/v1/chat/completions", ctx -> chat.handle(ctx, route));
                c.routes.post("/v1/responses", ctx -> responses.handle(ctx, route));
                c.routes.exception(Exception.class, (error, ctx) -> JsonHelper.toErrorResponse(ctx, "Unhandled test request failure: " + error.getMessage(), 500, "server_error"));
            }).start("127.0.0.1", 0);
        }
        HttpResponse<String> post(boolean responses, boolean stream, ObjectNode supplied) throws Exception {
            ObjectNode body = supplied == null ? Json.MAPPER.createObjectNode() : supplied.deepCopy();
            body.put("model", route.requestedModel()).put("stream", stream);
            if (responses && !body.has("input")) body.put("input", "hi");
            if (!responses && !body.has("messages")) body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + (responses ? "/v1/responses" : "/v1/chat/completions")))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        }
        public void close() { app.stop(); http.close(); }
    }
}
