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
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnthropicChatBackend implements ChatBackend {
    private static final int READ_BUFFER_BYTES = 16 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;

    private final AnthropicHttpClient client;
    private final AnthropicCompatibilityProfile profile;
    private final UsageTracker usageTracker;
    private final RequestLogger requestLogger;
    private final OpenAiChatRequestAdapter requestAdapter;
    private final Clock clock;

    public AnthropicChatBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            UsageTracker usageTracker,
            RequestLogger requestLogger
    ) {
        this(client, profile, usageTracker, requestLogger,
                new OpenAiChatRequestAdapter(), Clock.systemUTC());
    }

    AnthropicChatBackend(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            UsageTracker usageTracker,
            RequestLogger requestLogger,
            OpenAiChatRequestAdapter requestAdapter,
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
        String requestId = requestId(context);
        requestLogger.logInbound(requestId, context, bodyText);

        ChatRequest chatRequest;
        AnthropicWire.Request wire;
        try {
            JsonNode body = Json.MAPPER.readTree(bodyText);
            chatRequest = requestAdapter.adapt(body, route.upstreamModel());
            wire = AnthropicWire.build(chatRequest, profile);
        } catch (AnthropicTranslationException | IllegalArgumentException error) {
            JsonHelper.toErrorResponse(context, error.getMessage(), 400,
                    "invalid_request_error", null, "invalid_value");
            return;
        }

        AccessLogFields.mode(context, chatRequest.stream() ? "stream" : "sync");
        HttpResponse<InputStream> upstream = client.request(
                wire.uri(), "POST", wire.body(), Map.of("Content-Type", "application/json"));
        AccessLogFields.upstreamStatus(context, upstream.statusCode());
        try (InputStream input = upstream.body()) {
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                ProviderError error = AnthropicErrorParser.parse(
                        upstream.statusCode(), readBoundedError(input));
                writeError(context, error);
                return;
            }
            if (chatRequest.stream()) {
                stream(context, input, route.requestedModel());
            } else {
                collect(context, input, route.requestedModel());
            }
        }
    }

    private void collect(Context context, InputStream input, String requestedModel) throws IOException {
        AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(clock);
        OpenAiChatCompletionEncoder encoder = new OpenAiChatCompletionEncoder(requestedModel);
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
        recordUsage(context, encoder);
        JsonHelper.toJsonResponse(context, encoder.completion());
    }

    private void stream(Context context, InputStream input, String requestedModel) throws IOException {
        JsonHelper.setSseHeaders(context);
        OutputStream output = context.res().getOutputStream();
        AnthropicStreamDecoder decoder = new AnthropicStreamDecoder(clock);
        OpenAiChatCompletionEncoder encoder = new OpenAiChatCompletionEncoder(requestedModel);
        boolean done = false;
        try {
            ProviderError error = decode(input, decoder, encoder, output, context);
            if (error != null) writeSseError(context, output, error);
            if (encoder.isFinished()) recordUsage(context, encoder);
            writeDone(context, output);
            done = true;
        } finally {
            if (!done) {
                try {
                    writeDone(context, output);
                } catch (IOException ignored) {
                    // The downstream client may already have disconnected.
                }
            }
            output.flush();
        }
    }

    private ProviderError decode(
            InputStream input,
            AnthropicStreamDecoder decoder,
            OpenAiChatCompletionEncoder encoder,
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
            byte[] bytes = java.util.Arrays.copyOf(buffer, read);
            ProviderError error = consume(decoder.feed(bytes), encoder, output, context);
            if (error != null || encoder.isFinished()) return error;
        }
        return consume(decoder.end(), encoder, output, context);
    }

    private ProviderError consume(
            List<CompletionEvent> events,
            OpenAiChatCompletionEncoder encoder,
            OutputStream output,
            Context context
    ) throws IOException {
        for (CompletionEvent event : events) {
            if (event instanceof CompletionEvent.Error failure) return failure.error();
            List<ObjectNode> chunks = encoder.accept(event);
            if (output != null) {
                for (ObjectNode chunk : chunks) writeChunk(context, output, chunk);
            }
        }
        return null;
    }

    private void recordUsage(Context context, OpenAiChatCompletionEncoder encoder) {
        CompletionEvent.UsageSnapshot usage = encoder.usage();
        usageTracker.record(context.attribute("keyName"), usage.inputTokens(), usage.outputTokens());
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
        if (bytes.size() > MAX_ERROR_BODY_BYTES) return "{}";
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void writeError(Context context, ProviderError error) {
        JsonHelper.toErrorResponse(context, error.message(), error.httpStatus(),
                errorType(error.kind()), null, error.kind().name().toLowerCase(java.util.Locale.ROOT));
    }

    private void writeChunk(Context context, OutputStream output, ObjectNode chunk) throws IOException {
        writeBytes(context, output,
                "data: " + Json.MAPPER.writeValueAsString(chunk) + "\n\n");
    }

    private void writeSseError(Context context, OutputStream output, ProviderError error)
            throws IOException {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode body = root.putObject("error");
        body.put("message", error.message());
        body.put("type", errorType(error.kind()));
        body.put("code", error.kind().name().toLowerCase(java.util.Locale.ROOT));
        writeBytes(context, output,
                "event: error\ndata: " + Json.MAPPER.writeValueAsString(root) + "\n\n");
    }

    private void writeDone(Context context, OutputStream output) throws IOException {
        writeBytes(context, output, "data: [DONE]\n\n");
    }

    private void writeBytes(Context context, OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes);
        AccessLogFields.addResponseBytes(context, bytes.length);
        output.flush();
    }

    private String requestId(Context context) {
        String requestId = context.attribute(AccessLogFields.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = requestLogger.nextRequestId();
            context.attribute(AccessLogFields.REQUEST_ID, requestId);
        }
        return requestId;
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
}
