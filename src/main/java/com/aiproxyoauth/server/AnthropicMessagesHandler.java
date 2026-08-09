package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import com.aiproxyoauth.provider.ProviderRouter;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.anthropic.AnthropicNativeRequest;
import com.aiproxyoauth.provider.anthropic.AnthropicRequestOptions;
import com.aiproxyoauth.provider.anthropic.AnthropicTranslationException;
import com.aiproxyoauth.provider.anthropic.AnthropicUsageObserver;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native Anthropic Messages proxy; responses deliberately bypass OpenAI translation. */
public final class AnthropicMessagesHandler implements Handler {
    private static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;
    private static final List<String> SAFE_RESPONSE_HEADERS = List.of(
            "request-id", "retry-after", "x-should-retry",
            "anthropic-ratelimit-requests-limit", "anthropic-ratelimit-requests-remaining",
            "anthropic-ratelimit-requests-reset", "anthropic-ratelimit-tokens-limit",
            "anthropic-ratelimit-tokens-remaining", "anthropic-ratelimit-tokens-reset"
    );
    private static final List<String> SAFE_REQUEST_HEADERS = List.of(
            "X-Claude-Code-Session-Id", "X-Claude-Code-Agent-Id",
            "X-Claude-Code-Parent-Agent-Id", "Anthropic-User-Profile-Id"
    );

    private final AnthropicHttpClient client;
    private final AnthropicCompatibilityProfile profile;
    private final ModelCatalog modelCatalog;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;

    public AnthropicMessagesHandler(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            ModelCatalog modelCatalog,
            UsageTracker usageTracker,
            RequestLogger requestLogger
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker");
        this.requestLogger = Objects.requireNonNull(requestLogger, "requestLogger");
    }

    @Override
    public void handle(Context context) throws Exception {
        AccessLogFields.provider(context, ProviderId.ANTHROPIC.wireName());
        String version = context.header("anthropic-version");
        if (version == null || !profile.anthropicVersion().equals(version.strip())) {
            writeError(context, 400, "invalid_request_error",
                    "`anthropic-version` must be " + profile.anthropicVersion());
            return;
        }
        String contentLength = context.header("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > MAX_REQUEST_BYTES) {
                    writeError(context, 413, "request_too_large", "Request body is too large");
                    return;
                }
            } catch (NumberFormatException ignored) {
                // Jetty validates the framing; the decoded body is checked below.
            }
        }
        String bodyText = context.body();
        if (bodyText.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            writeError(context, 413, "request_too_large", "Request body is too large");
            return;
        }
        requestLogger.logInbound(requestId(context), context, bodyText);
        ObjectNode body;
        try {
            JsonNode parsed = Json.MAPPER.readTree(bodyText);
            if (parsed == null || !parsed.isObject()) {
                writeError(context, 400, "invalid_request_error",
                        "Request body must be a JSON object");
                return;
            }
            body = (ObjectNode) parsed;
        } catch (IOException error) {
            writeError(context, 400, "invalid_request_error",
                    "Request body must contain valid JSON");
            return;
        }

        List<ProviderModel> models;
        try {
            models = modelCatalog.resolveModels();
        } catch (Exception error) {
            writeError(context, 502, "api_error", "Anthropic model catalog is unavailable");
            return;
        }

        AnthropicNativeRequest.Prepared prepared;
        AnthropicRequestOptions options;
        try {
            ProviderRouter router = new ProviderRouter(models, ProviderId.ANTHROPIC);
            prepared = AnthropicNativeRequest.prepare(body, router, profile);
            options = AnthropicRequestOptions.nativeRequest(
                    context.header("anthropic-beta"), requestedHeaders(context));
        } catch (AnthropicTranslationException | IllegalArgumentException error) {
            writeError(context, 400, "invalid_request_error", error.getMessage());
            return;
        }
        AccessLogFields.mode(context, prepared.stream() ? "stream" : "sync");
        HttpResponse<InputStream> upstream;
        try {
            upstream = client.request(
                    profile.messagesUri(), "POST",
                    Json.MAPPER.writeValueAsString(prepared.body()), options);
        } catch (IOException error) {
            writeError(context, 502, "api_error", "Anthropic is temporarily unavailable");
            return;
        }
        AccessLogFields.upstreamStatus(context, upstream.statusCode());
        copyResponseHeaders(context, upstream);
        if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
            try (InputStream input = upstream.body()) {
                byte[] error = readBounded(input, MAX_ERROR_BYTES);
                context.status(upstream.statusCode());
                context.contentType(contentType(upstream, JsonHelper.JSON_CONTENT_TYPE));
                AccessLogFields.responseBytes(context, error.length);
                context.result(new String(error, StandardCharsets.UTF_8));
            } catch (BodyLimitException error) {
                writeError(context, 502, "api_error", "Anthropic error response was too large");
            }
            return;
        }
        if (prepared.stream()) stream(context, upstream);
        else collect(context, upstream);
    }

    private void collect(Context context, HttpResponse<InputStream> upstream) throws IOException {
        byte[] bytes;
        try (InputStream input = upstream.body()) {
            bytes = readBounded(input, Math.toIntExact(MAX_RESPONSE_BYTES));
        } catch (BodyLimitException error) {
            writeError(context, 502, "api_error", "Anthropic response was too large");
            return;
        }
        context.status(upstream.statusCode());
        context.contentType(contentType(upstream, JsonHelper.JSON_CONTENT_TYPE));
        AccessLogFields.responseBytes(context, bytes.length);
        String body = new String(bytes, StandardCharsets.UTF_8);
        context.result(body);
        recordSyncUsage(context, body);
    }

    private void stream(Context context, HttpResponse<InputStream> upstream) throws IOException {
        JsonHelper.setSseHeaders(context);
        context.status(upstream.statusCode());
        OutputStream output = context.res().getOutputStream();
        AnthropicUsageObserver usage = new AnthropicUsageObserver();
        long total = 0;
        try (InputStream input = upstream.body()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    return;
                }
                byte[] bytes = java.util.Arrays.copyOf(buffer, read);
                output.write(bytes);
                output.flush();
                usage.accept(bytes);
                AccessLogFields.addResponseBytes(context, read);
            }
        } catch (IOException clientOrUpstreamDisconnect) {
            return;
        }
        usageTracker.record(context.attribute("keyName"),
                usage.inputTokens(), usage.outputTokens());
    }

    private void recordSyncUsage(Context context, String body) {
        try {
            JsonNode usage = Json.MAPPER.readTree(body).path("usage");
            usageTracker.record(context.attribute("keyName"),
                    usage.path("input_tokens").asLong(), usage.path("output_tokens").asLong());
        } catch (Exception ignored) {
            // Native response delivery is not contingent on accounting.
        }
    }

    private static Map<String, String> requestedHeaders(Context context) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : SAFE_REQUEST_HEADERS) {
            String value = context.header(name);
            if (value != null) headers.put(name, value);
        }
        return headers;
    }

    private static void copyResponseHeaders(
            Context context, HttpResponse<InputStream> response) {
        for (String name : SAFE_RESPONSE_HEADERS) {
            response.headers().firstValue(name).ifPresent(value -> context.header(name, value));
        }
    }

    private static String contentType(
            HttpResponse<InputStream> response, String fallback) {
        return response.headers().firstValue("content-type").orElse(fallback);
    }

    private static byte[] readBounded(InputStream input, int maximumBytes)
            throws IOException, BodyLimitException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int remaining = maximumBytes + 1;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        if (output.size() > maximumBytes) throw new BodyLimitException();
        return output.toByteArray();
    }

    static void writeError(Context context, int status, String type, String message) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("type", "error");
        ObjectNode error = root.putObject("error");
        error.put("type", type);
        error.put("message", message == null || message.isBlank() ? "Request failed" : message);
        String requestId = context.attribute(AccessLogFields.REQUEST_ID);
        if (requestId != null) root.put("request_id", requestId);
        JsonHelper.toJsonResponse(context, root, status);
    }

    private String requestId(Context context) {
        String requestId = context.attribute(AccessLogFields.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = requestLogger.nextRequestId();
            context.attribute(AccessLogFields.REQUEST_ID, requestId);
        }
        return requestId;
    }

    private static final class BodyLimitException extends Exception {
    }
}
