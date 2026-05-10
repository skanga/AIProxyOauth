package com.aiproxyoauth.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.sse.SseCollector;
import com.aiproxyoauth.state.ResponsesState;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

public class ResponsesHandler implements Handler {

    private static final int MAX_REPLAY_NAMESPACES = 512;
    private static final int MAX_SSE_BOOKKEEPING_LINE_BYTES = 64 * 1024;

    private final CodexHttpClient client;
    private final ServerConfig config;
    private final UsageTracker usageTracker;
    private final Map<String, ResponsesState> replayStates = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ResponsesState> eldest) {
                    return size() > MAX_REPLAY_NAMESPACES;
                }
            });

    public ResponsesHandler(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker) {
        this.client = client;
        this.config = config;
        this.usageTracker = usageTracker;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        create(ctx);
    }

    public void create(Context ctx) throws Exception {
        String bodyStr = ctx.body();
        JsonNode body = MAPPER.readTree(bodyStr);

        if (body == null || !body.isObject()) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.");
            return;
        }

        boolean wantsStream = body.path("stream").asBoolean(false);

        // Expand previous_response_id and item_reference references before forwarding
        ResponsesState state = replayStateFor(ctx);
        ObjectNode expanded = state.expandRequestBody((ObjectNode) body);

        // Normalize body
        ObjectNode normalized = normalizeBody(expanded);

        // Forward to upstream
        HttpResponse<InputStream> upstream = client.request(
                "/responses", "POST",
                MAPPER.writeValueAsString(normalized),
                Map.of("Content-Type", "application/json"));

        if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
            try (InputStream is = upstream.body()) {
                String rawBody = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                ctx.status(upstream.statusCode());
                ctx.contentType(JsonHelper.JSON_CONTENT_TYPE);
                ctx.result(JsonHelper.toUpstreamErrorBody(rawBody, upstream.statusCode()));
            }
            return;
        }

        if (wantsStream) {
            // Stream SSE directly to client
            JsonHelper.setSseHeaders(ctx);
            StreamingCompletionRecorder recorder = new StreamingCompletionRecorder(ctx, state, expanded);
            try (InputStream is = upstream.body();
                 OutputStream os = ctx.res().getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    recorder.accept(buffer, bytesRead);
                    os.flush();
                }
            }
            recorder.finish();
        } else {
            // Collect completed response from SSE
            try (InputStream is = upstream.body()) {
                JsonNode completed = SseCollector.collectCompletedResponse(is);
                recordUsage(ctx, completed.get("usage"));
                // Best-effort same-process replay cache only; nothing is persisted locally.
                state.rememberResponse(completed, expanded);
                JsonHelper.toJsonResponse(ctx, completed);
            }
        }
    }

    private ObjectNode normalizeBody(ObjectNode body) {
        ObjectNode normalized = body.deepCopy();
        normalized.put("stream", true);

        if (!normalized.has("instructions") || !normalized.get("instructions").isTextual()) {
            normalized.put("instructions", config.instructions());
        }

        if (!normalized.has("store")) {
            normalized.put("store", config.store());
        }

        return normalized;
    }

    private boolean recordStreamingCompletion(
            Context ctx, String eventType, String data, ResponsesState state, JsonNode expandedRequest) {
        try {
            if (data == null || data.isEmpty() || "[DONE]".equals(data)) {
                return false;
            }
            JsonNode parsed = MAPPER.readTree(data);
            if (parsed == null || !parsed.isObject()) {
                return false;
            }

            String parsedEventType = parsed.path("type").asText(eventType != null ? eventType : "");
            if (!"response.completed".equals(parsedEventType)) {
                return false;
            }

            JsonNode response = parsed.get("response");
            if (response != null && response.isObject()) {
                recordUsage(ctx, response.get("usage"));
                state.rememberResponse(response, expandedRequest);
                return true;
            }
        } catch (Exception ignored) {
            // Streaming payloads have already been forwarded; replay/usage bookkeeping is best-effort.
        }
        return false;
    }

    private void recordUsage(Context ctx, JsonNode usageNode) {
        usageTracker.record(ctx.attribute("keyName"),
                usageNode != null ? usageNode.path("input_tokens").asLong(0) : 0,
                usageNode != null ? usageNode.path("output_tokens").asLong(0) : 0);
    }

    private ResponsesState replayStateFor(Context ctx) {
        boolean isAdmin = Boolean.TRUE.equals(ctx.attribute("isAdmin"));
        String keyFingerprint = ctx.attribute("keyFingerprint");
        String adminKeyFingerprint = ctx.attribute("adminKeyFingerprint");
        String keyName = ctx.attribute("keyName");
        String namespace;
        if (isAdmin && adminKeyFingerprint != null) {
            namespace = "admin-fp:" + adminKeyFingerprint;
        } else if (keyFingerprint != null) {
            namespace = "key-fp:" + keyFingerprint;
        } else if (keyName != null) {
            namespace = "key:" + keyName;
        } else if (isAdmin) {
            namespace = "admin";
        } else {
            namespace = "open";
        }

        synchronized (replayStates) {
            return replayStates.computeIfAbsent(namespace, ignored -> new ResponsesState());
        }
    }

    private final class StreamingCompletionRecorder {
        private final Context ctx;
        private final ResponsesState state;
        private final JsonNode expandedRequest;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        private final List<String> dataLines = new ArrayList<>();
        private String eventType;
        private boolean recorded;
        private boolean bookkeepingDisabled;

        private StreamingCompletionRecorder(Context ctx, ResponsesState state, JsonNode expandedRequest) {
            this.ctx = ctx;
            this.state = state;
            this.expandedRequest = expandedRequest;
        }

        private void accept(byte[] buffer, int length) {
            if (bookkeepingDisabled) {
                return;
            }
            for (int i = 0; i < length; i++) {
                byte b = buffer[i];
                if (b == '\n') {
                    acceptLine(lineBuffer.toString(StandardCharsets.UTF_8));
                    lineBuffer.reset();
                } else {
                    lineBuffer.write(b);
                    if (lineBuffer.size() > MAX_SSE_BOOKKEEPING_LINE_BYTES) {
                        disableBookkeeping();
                        return;
                    }
                }
            }
        }

        private void finish() {
            if (bookkeepingDisabled) {
                return;
            }
            if (lineBuffer.size() > 0) {
                acceptLine(lineBuffer.toString(StandardCharsets.UTF_8));
                lineBuffer.reset();
            }
            if (eventType != null || !dataLines.isEmpty()) {
                dispatchEvent();
            }
        }

        private void acceptLine(String line) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                if (eventType != null || !dataLines.isEmpty()) {
                    dispatchEvent();
                }
                return;
            }
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                dataLines.add(value);
            }
        }

        private void dispatchEvent() {
            if (!recorded) {
                String data = dataLines.isEmpty() ? null : String.join("\n", dataLines);
                recorded = recordStreamingCompletion(ctx, eventType, data, state, expandedRequest);
            }
            eventType = null;
            dataLines.clear();
        }

        private void disableBookkeeping() {
            bookkeepingDisabled = true;
            recorded = true;
            lineBuffer.reset();
            dataLines.clear();
            eventType = null;
        }
    }
}
