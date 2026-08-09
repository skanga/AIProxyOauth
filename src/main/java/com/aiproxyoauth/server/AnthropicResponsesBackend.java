package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.provider.ModelRoute;
import com.aiproxyoauth.provider.ProviderError;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicErrorParser;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.anthropic.AnthropicStreamDecoder;
import com.aiproxyoauth.provider.anthropic.AnthropicTranslationException;
import com.aiproxyoauth.provider.anthropic.AnthropicWire;
import com.aiproxyoauth.provider.chat.ChatRequest;
import com.aiproxyoauth.provider.stream.CompletionEvent;
import com.aiproxyoauth.state.ResponsesState;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnthropicResponsesBackend implements ResponsesBackend {
    private static final int MAX_REPLAY_NAMESPACES = 512;
    private static final int READ_BUFFER_BYTES = 16 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;

    private final AnthropicHttpClient client;
    private final AnthropicCompatibilityProfile profile;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;
    private final ResponsesRequestAdapter requestAdapter;
    private final Clock clock;
    private final Map<String, ResponsesState> replayStates = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ResponsesState> eldest) {
                    return size() > MAX_REPLAY_NAMESPACES;
                }
            });

    public AnthropicResponsesBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            UsageTracker usageTracker,
            RequestLogger requestLogger
    ) {
        this(client, profile, usageTracker, requestLogger,
                new ResponsesRequestAdapter(), Clock.systemUTC());
    }

    AnthropicResponsesBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            UsageTracker usageTracker,
            RequestLogger requestLogger,
            ResponsesRequestAdapter requestAdapter,
            Clock clock
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker");
        this.requestLogger = Objects.requireNonNull(requestLogger, "requestLogger");
        this.requestAdapter = Objects.requireNonNull(requestAdapter, "requestAdapter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void handle(Context context, ModelRoute route) throws Exception {
        String bodyText = context.body();
        requestLogger.logInbound(requestId(context), context, bodyText);
        ObjectNode body;
        try {
            JsonNode parsed = Json.MAPPER.readTree(bodyText);
            if (parsed == null || !parsed.isObject()) {
                writeInvalid(context, "Request body must be a JSON object", null, "invalid_type");
                return;
            }
            body = normalizeStringInput((ObjectNode) parsed);
            validateStateFields(body);
        } catch (IllegalArgumentException error) {
            writeInvalid(context, error.getMessage(), null, "invalid_value");
            return;
        } catch (IOException error) {
            writeInvalid(context, "Request body must contain valid JSON", null, "invalid_json");
            return;
        }

        ResponsesState state = replayStateFor(context);
        ObjectNode expanded = state.expandRequestBody(body);
        if (state.requiresCachedState(expanded)) {
            String parameter = expanded.has("previous_response_id")
                    ? "previous_response_id" : "input";
            JsonHelper.toErrorResponse(context,
                    "Claude requires this state reference to be available in the local replay cache.",
                    400, "invalid_request_error", parameter, "unsupported_provider_feature");
            return;
        }
        expanded.remove("previous_response_id");

        ChatRequest request;
        AnthropicWire.Request wire;
        try {
            request = requestAdapter.adapt(expanded, route.upstreamModel());
            wire = AnthropicWire.build(request, profile);
        } catch (AnthropicTranslationException | IllegalArgumentException error) {
            writeInvalid(context, error.getMessage(), null, "invalid_value");
            return;
        }

        AccessLogFields.mode(context, request.stream() ? "stream" : "sync");
        HttpResponse<InputStream> upstream = client.request(
                wire.uri(), "POST", wire.body(), Map.of("Content-Type", "application/json"));
        AccessLogFields.upstreamStatus(context, upstream.statusCode());
        try (InputStream input = upstream.body()) {
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                writeError(context, AnthropicErrorParser.parse(
                        upstream.statusCode(), readBoundedError(input)));
                return;
            }
            if (request.stream()) {
                stream(context, input, route.requestedModel(), state, expanded);
            } else {
                collect(context, input, route.requestedModel(), state, expanded);
            }
        }
    }

    private void collect(
            Context context,
            InputStream input,
            String requestedModel,
            ResponsesState state,
            ObjectNode expanded
    ) throws IOException {
        AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(clock);
        ResponsesEventEncoder encoder = new ResponsesEventEncoder(requestedModel);
        ProviderError error = decode(input, decoder, encoder, null, context);
        if (error != null) {
            writeError(context, error);
            return;
        }
        if (!encoder.isFinished()) {
            writeError(context, ProviderError.of(
                    ProviderError.Kind.PROTOCOL, "Anthropic stream ended without a completion"));
            return;
        }
        ObjectNode response = encoder.response();
        recordUsage(context, encoder);
        state.rememberResponse(response, expanded);
        JsonHelper.toJsonResponse(context, response);
    }

    private void stream(
            Context context,
            InputStream input,
            String requestedModel,
            ResponsesState state,
            ObjectNode expanded
    ) throws IOException {
        JsonHelper.setSseHeaders(context);
        OutputStream output = context.res().getOutputStream();
        AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(clock);
        ResponsesEventEncoder encoder = new ResponsesEventEncoder(requestedModel);
        ProviderError error = decode(input, decoder, encoder, output, context);
        if (error != null) {
            writeStreamingError(context, output, error, encoder);
        } else if (encoder.isFinished()) {
            ObjectNode response = encoder.response();
            recordUsage(context, encoder);
            state.rememberResponse(response, expanded);
        }
        output.flush();
    }

    private ProviderError decode(
            InputStream input,
            AnthropicStreamDecoder decoder,
            ResponsesEventEncoder encoder,
            OutputStream output,
            Context context
    ) throws IOException {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                return ProviderError.of(ProviderError.Kind.PROTOCOL,
                        "Anthropic response exceeded the size limit");
            }
            ProviderError error = consume(
                    decoder.feed(Arrays.copyOf(buffer, read)), encoder, output, context);
            if (error != null || encoder.isFinished()) return error;
        }
        return consume(decoder.end(), encoder, output, context);
    }

    private ProviderError consume(
            List<CompletionEvent> events,
            ResponsesEventEncoder encoder,
            OutputStream output,
            Context context
    ) throws IOException {
        for (CompletionEvent event : events) {
            if (event instanceof CompletionEvent.Error failure) return failure.error();
            List<ResponsesEventEncoder.StreamEvent> encoded = encoder.accept(event);
            if (output != null) {
                for (ResponsesEventEncoder.StreamEvent streamEvent : encoded) {
                    writeStreamEvent(context, output, streamEvent.name(), streamEvent.data());
                }
            }
        }
        return null;
    }

    private void validateStateFields(ObjectNode body) {
        JsonNode previous = body.get("previous_response_id");
        if (previous != null && !previous.isNull() && !previous.isTextual()) {
            throw new IllegalArgumentException("`previous_response_id` must be a string");
        }
        JsonNode store = body.get("store");
        if (store != null && !store.isNull() && !store.isBoolean()) {
            throw new IllegalArgumentException("`store` must be a boolean");
        }
    }

    private ObjectNode normalizeStringInput(ObjectNode body) {
        ObjectNode normalized = body.deepCopy();
        JsonNode input = normalized.get("input");
        if (input == null || !input.isTextual()) return normalized;
        ObjectNode message = Json.MAPPER.createObjectNode();
        message.put("type", "message");
        message.put("role", "user");
        ObjectNode content = Json.MAPPER.createObjectNode();
        content.put("type", "input_text");
        content.put("text", input.asText());
        message.set("content", Json.MAPPER.createArrayNode().add(content));
        normalized.set("input", Json.MAPPER.createArrayNode().add(message));
        return normalized;
    }

    private String readBoundedError(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int remaining = MAX_ERROR_BODY_BYTES + 1;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) break;
            bytes.write(buffer, 0, read);
            remaining -= read;
        }
        return bytes.size() > MAX_ERROR_BODY_BYTES
                ? "{}" : bytes.toString(StandardCharsets.UTF_8);
    }

    private void recordUsage(Context context, ResponsesEventEncoder encoder) {
        CompletionEvent.UsageSnapshot usage = encoder.usage();
        usageTracker.record(context.attribute("keyName"), usage.inputTokens(), usage.outputTokens());
    }

    private void writeStreamingError(
            Context context,
            OutputStream output,
            ProviderError error,
            ResponsesEventEncoder encoder
    ) throws IOException {
        ObjectNode data = Json.MAPPER.createObjectNode();
        ObjectNode response = data.putObject("response");
        response.put("object", "response");
        response.put("status", "failed");
        if (encoder.isFinished()) response.setAll(encoder.response());
        ObjectNode errorNode = response.putObject("error");
        errorNode.put("type", errorType(error.kind()));
        errorNode.put("code", error.kind().name().toLowerCase(java.util.Locale.ROOT));
        errorNode.put("message", error.message());
        writeStreamEvent(context, output, "response.failed", data);
    }

    private void writeStreamEvent(
            Context context, OutputStream output, String eventName, ObjectNode data) throws IOException {
        String value = "event: " + eventName + "\ndata: "
                + Json.MAPPER.writeValueAsString(data) + "\n\n";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes);
        AccessLogFields.addResponseBytes(context, bytes.length);
        output.flush();
    }

    private void writeInvalid(Context context, String message, String parameter, String code) {
        JsonHelper.toErrorResponse(context, message, 400,
                "invalid_request_error", parameter, code);
    }

    private void writeError(Context context, ProviderError error) {
        JsonHelper.toErrorResponse(context, error.message(), error.httpStatus(),
                errorType(error.kind()), null,
                error.kind().name().toLowerCase(java.util.Locale.ROOT));
    }

    private String errorType(ProviderError.Kind kind) {
        return switch (kind) {
            case INVALID_REQUEST -> "invalid_request_error";
            case AUTHENTICATION -> "authentication_error";
            case PERMISSION -> "permission_error";
            case RATE_LIMIT -> "rate_limit_error";
            default -> "upstream_error";
        };
    }

    private String requestId(Context context) {
        String requestId = context.attribute(AccessLogFields.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = requestLogger.nextRequestId();
            context.attribute(AccessLogFields.REQUEST_ID, requestId);
        }
        return requestId;
    }

    private ResponsesState replayStateFor(Context context) {
        boolean admin = Boolean.TRUE.equals(context.attribute("isAdmin"));
        String keyFingerprint = context.attribute("keyFingerprint");
        String adminFingerprint = context.attribute("adminKeyFingerprint");
        String keyName = context.attribute("keyName");
        String namespace;
        if (admin && adminFingerprint != null) namespace = "admin-fp:" + adminFingerprint;
        else if (keyFingerprint != null) namespace = "key-fp:" + keyFingerprint;
        else if (keyName != null) namespace = "key:" + keyName;
        else namespace = admin ? "admin" : "open";
        synchronized (replayStates) {
            return replayStates.computeIfAbsent(namespace, ignored -> new ResponsesState());
        }
    }
}
