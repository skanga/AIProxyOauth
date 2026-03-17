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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.util.Map;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

public class ResponsesHandler implements Handler {

    private final CodexHttpClient client;
    private final ServerConfig config;
    private final UsageTracker usageTracker;
    private final ResponsesState state = new ResponsesState();

    public ResponsesHandler(CodexHttpClient client, ServerConfig config, UsageTracker usageTracker) {
        this.client = client;
        this.config = config;
        this.usageTracker = usageTracker;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String bodyStr = ctx.body();
        JsonNode body = MAPPER.readTree(bodyStr);

        if (body == null || !body.isObject()) {
            JsonHelper.toErrorResponse(ctx, "Request body must be a JSON object.");
            return;
        }

        boolean wantsStream = body.path("stream").asBoolean(false);

        // Expand previous_response_id and item_reference references before forwarding
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
            try (InputStream is = upstream.body();
                 OutputStream os = ctx.res().getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    os.flush();
                }
            }
        } else {
            // Collect completed response from SSE
            try (InputStream is = upstream.body()) {
                JsonNode completed = SseCollector.collectCompletedResponse(is);
                JsonNode usageNode = completed.get("usage");
                usageTracker.record(ctx.attribute("keyName"),
                        usageNode != null ? usageNode.path("input_tokens").asLong(0) : 0,
                        usageNode != null ? usageNode.path("output_tokens").asLong(0) : 0);
                // Cache response so subsequent requests can reference it via previous_response_id
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

}
