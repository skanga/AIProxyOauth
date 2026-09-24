package com.aiproxyoauth.server;
import com.aiproxyoauth.model.CopilotModelCatalog;
import com.aiproxyoauth.provider.copilot.CopilotClient;
import com.aiproxyoauth.provider.ModelRoute;
import com.aiproxyoauth.provider.ProviderError;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.logging.RequestLogger;
import io.javalin.http.Context;
import com.aiproxyoauth.provider.copilot.CopilotRequestEncoder;
import com.aiproxyoauth.provider.copilot.CopilotStreamDecoder;
import com.aiproxyoauth.provider.anthropic.AnthropicStreamDecoder;
import com.aiproxyoauth.provider.anthropic.AnthropicTranslationException;
import com.aiproxyoauth.provider.chat.ChatRequest;
import com.aiproxyoauth.provider.stream.*;
import com.aiproxyoauth.state.ResponsesState;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class CopilotBackend implements ChatBackend, ResponsesBackend {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 1024 * 1024;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            task -> { Thread thread = new Thread(task, "copilot-idle-timeout"); thread.setDaemon(true); return thread; });
    private final CopilotClient client;
    private final CopilotModelCatalog catalog;
    private final UsageTracker usage;
    private final RequestLogger logger;
    private final Map<String, ResponsesState> states = new LinkedHashMap<>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, ResponsesState> entry) { return size() > 512; }
    };

    public CopilotBackend(CopilotClient client, CopilotModelCatalog catalog, UsageTracker usage, RequestLogger logger) {
        this.client = client; this.catalog = catalog; this.usage = usage; this.logger = logger;
    }

    @Override public boolean supports(JsonNode body, ModelRoute route, boolean responses) throws Exception {
        try {
            if (body == null || !body.isObject()) return false;
            // Adapt and validate only; skip the wire encode, which handle() performs when the route is chosen.
            adapt((ObjectNode) body, route, responses);
            return true;
        } catch (IllegalArgumentException | AnthropicTranslationException error) { return false; }
    }

    private record Adapted(ChatRequest request, String endpoint) {}

    private record Prepared(ChatRequest request, String endpoint, ObjectNode wire) {}

    private Adapted adapt(ObjectNode body, ModelRoute route, boolean responses) throws Exception {
        validateFields(body, responses);
        ChatRequest request = responses ? new ResponsesRequestAdapter().adapt(body, route.upstreamModel())
                : new OpenAiChatRequestAdapter().adapt(body, route.upstreamModel());
        catalog.validate(request);
        String endpoint = catalog.endpoint(route.upstreamModel(), responses);
        return new Adapted(request, endpoint);
    }

    private Prepared prepare(ObjectNode body, ModelRoute route, boolean responses) throws Exception {
        Adapted adapted = adapt(body, route, responses);
        return new Prepared(adapted.request(), adapted.endpoint(),
                CopilotRequestEncoder.encode(adapted.request(), adapted.endpoint()));
    }

    public void handle(Context context, ModelRoute route) throws Exception {
        boolean responses = context.path().equals("/v1/responses");
        String requestId = context.attribute(AccessLogFields.REQUEST_ID);
        if (requestId == null) { requestId = logger.nextRequestId(); context.attribute(AccessLogFields.REQUEST_ID, requestId); }
        logger.logInbound(requestId, context, context.body());
        ChatRequest request;
        ObjectNode expanded;
        ObjectNode wire;
        String endpoint;
        ResponsesState state = null;
        try {
            JsonNode parsed = Json.MAPPER.readTree(context.body());
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("Request body must be a JSON object");
            expanded = (ObjectNode) parsed;
            validateFields(expanded, responses);
            if (responses) {
                state = stateFor(context);
                if (expanded.path("input").isTextual()) {
                    String text = expanded.path("input").asText();
                    expanded.putArray("input").addObject().put("type", "message").put("role", "user").put("content", text);
                }
                for (JsonNode item : expanded.path("input")) {
                    if (item.isObject() && !item.has("type") && item.has("role")) ((ObjectNode) item).put("type", "message");
                }
                expanded = state.expandRequestBody(expanded);
                if (state.requiresCachedState(expanded)) throw new IllegalArgumentException("State reference is unavailable in this client's Copilot replay cache");
                expanded.remove("previous_response_id");
            }
            Prepared prepared = prepare(expanded, route, responses);
            request = prepared.request();
            endpoint = prepared.endpoint();
            wire = prepared.wire();
        } catch (IllegalArgumentException | AnthropicTranslationException error) {
            JsonHelper.toErrorResponse(context, error.getMessage(), 400, "invalid_request_error", null, "unsupported_provider_feature");
            return;
        }
        AccessLogFields.mode(context, request.stream() ? "stream" : "sync");
        var upstream = client.request(endpoint, wire);
        AccessLogFields.upstreamStatus(context, upstream.statusCode());
        try (InputStream input = upstream.body()) {
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                if (Boolean.TRUE.equals(context.attribute("providerFailoverAttempt"))) throw new UpstreamFailure(upstream.statusCode());
                // Surface the upstream error body (Copilot is OpenAI-compatible), matching Codex/Anthropic.
                UpstreamErrorMapper.MappedUpstreamError mapped = new UpstreamErrorMapper()
                        .map(upstream.statusCode(), readBoundedError(input));
                context.status(mapped.statusCode());
                context.contentType(JsonHelper.JSON_CONTENT_TYPE);
                AccessLogFields.responseBytes(context, mapped.body().getBytes(StandardCharsets.UTF_8).length);
                context.result(mapped.body());
                return;
            }
            CompletionStreamDecoder decoder = endpoint.equals("/v1/messages")
                    ? new AnthropicStreamDecoder(Clock.systemUTC()) : new CopilotStreamDecoder(endpoint.equals("/responses"));
            Output output = new Output(context, responses, request.stream(), route.requestedModel());
            AtomicLong lastRead = new AtomicLong(System.nanoTime());
            ScheduledFuture<?> timeout = WATCHDOG.scheduleAtFixedRate(() -> {
                if (System.nanoTime() - lastRead.get() > TimeUnit.SECONDS.toNanos(60)) {
                    try { input.close(); } catch (IOException ignored) { }
                }
            }, 10, 10, TimeUnit.SECONDS);
            try {
                byte[] buffer = new byte[16384];
                long total = 0;
                while (!output.finished()) {
                    int count = input.read(buffer);
                    if (count < 0) { output.accept(decoder.end()); break; }
                    lastRead.set(System.nanoTime());
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("Copilot response exceeded the size limit");
                    output.accept(decoder.feed(Arrays.copyOf(buffer, count)));
                }
                if (!output.finished()) throw new IOException("Copilot response ended without a completion");
                ObjectNode result = output.result();
                CompletionEvent.UsageSnapshot tokens = output.tokens();
                usage.record(context.attribute("keyName"), tokens.inputTokens(), tokens.outputTokens());
                String status = result.path("status").asText();
                if (responses && ("completed".equals(status) || "incomplete".equals(status))) {
                    state.rememberResponse(result, expanded);
                    context.attribute("completedResponse", result);
                }
                if (request.stream()) { if (!responses) output.write(null, "[DONE]"); }
                else JsonHelper.toJsonResponse(context, result);
            } catch (IOException error) {
                if (!context.res().isCommitted()) {
                    context.contentType("application/json");
                    JsonHelper.toErrorResponse(context, "Copilot response was interrupted or invalid", 502, "upstream_error");
                } else {
                    output.fail("Copilot response was interrupted or invalid");
                }
            } finally { timeout.cancel(false); }
        }
    }

    private static String readBoundedError(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int remaining = MAX_ERROR_BODY_BYTES + 1;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) break;
            bytes.write(buffer, 0, read);
            remaining -= read;
        }
        return bytes.size() > MAX_ERROR_BODY_BYTES ? "" : bytes.toString(StandardCharsets.UTF_8);
    }

    private synchronized ResponsesState stateFor(Context context) throws IOException {
        return states.computeIfAbsent(ReplayNamespace.of(context) + ":" + client.identity(), unused -> new ResponsesState());
    }

    private static void validateFields(ObjectNode body, boolean responses) {
        Set<String> supported = responses
                ? Set.of("model", "input", "instructions", "tools", "tool_choice", "temperature", "top_p", "max_output_tokens", "stream", "reasoning", "stop", "store", "previous_response_id", "metadata")
                : Set.of("model", "messages", "tools", "tool_choice", "temperature", "top_p", "max_tokens", "max_completion_tokens", "stream", "stream_options", "reasoning_effort", "stop", "user", "metadata", "n");
        body.fieldNames().forEachRemaining(field -> {
            if (!supported.contains(field)) throw new IllegalArgumentException("Unsupported Copilot request field: " + field);
        });
        if (body.has("stream") && !body.path("stream").isBoolean()) throw new IllegalArgumentException("stream must be a boolean");
        if (body.has("store") && !body.path("store").isBoolean()) throw new IllegalArgumentException("store must be a boolean");
        if (body.hasNonNull("previous_response_id") && !body.path("previous_response_id").isTextual()) throw new IllegalArgumentException("previous_response_id must be a string");
        if (body.has("n") && (!body.path("n").isIntegralNumber() || body.path("n").asInt() != 1)) throw new IllegalArgumentException("Copilot supports n=1 only");
    }

    private static final class Output {
        private final Context context;
        private final boolean responses, streaming;
        private final ResponsesEventEncoder response;
        private final OpenAiChatCompletionEncoder chat;
        private final String id = "resp_copilot_" + UUID.randomUUID().toString().replace("-", "");
        Output(Context context, boolean responses, boolean streaming, String model) {
            this.context = context; this.responses = responses; this.streaming = streaming;
            response = new ResponsesEventEncoder(model); chat = new OpenAiChatCompletionEncoder(model);
        }
        void accept(List<CompletionEvent> events) throws IOException {
            for (CompletionEvent event : events) {
                if (event instanceof CompletionEvent.Error) throw new IOException("Invalid Copilot stream");
                if (responses) {
                    if (event instanceof CompletionEvent.Started start) event = new CompletionEvent.Started(id, start.model(), start.createdEpochSeconds());
                    for (var frame : response.accept(event)) if (streaming) write(frame.name(), frame.data().toString());
                } else for (var frame : chat.accept(event)) if (streaming) write(null, frame.toString());
            }
        }
        boolean finished() { return responses ? response.isFinished() : chat.isFinished(); }
        ObjectNode result() { return responses ? response.response() : chat.completion(); }
        CompletionEvent.UsageSnapshot tokens() { return responses ? response.usage() : chat.usage(); }
        void fail(String message) throws IOException {
            if (responses) {
                var error = new CompletionEvent.Error(ProviderError.of(ProviderError.Kind.PROTOCOL, message));
                for (var frame : response.accept(error)) write(frame.name(), frame.data().toString());
            } else {
                ObjectNode failure = Json.MAPPER.createObjectNode();
                failure.putObject("error").put("type", "upstream_error").put("message", message);
                write("error", failure.toString());
                write(null, "[DONE]");
            }
        }
        void write(String event, String data) throws IOException {
            JsonHelper.setSseHeaders(context);
            byte[] bytes = ((event == null ? "" : "event: " + event + "\n") + "data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
            context.res().getOutputStream().write(bytes);
            AccessLogFields.addResponseBytes(context, bytes.length);
            context.res().getOutputStream().flush();
        }
    }
}
