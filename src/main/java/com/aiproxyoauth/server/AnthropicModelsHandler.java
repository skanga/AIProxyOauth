package com.aiproxyoauth.server;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.anthropic.AnthropicRequestOptions;
import com.aiproxyoauth.transport.BoundedBodyReader;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Native Anthropic model discovery used by SDKs and Claude Code gateways. */
public final class AnthropicModelsHandler implements Handler {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CURSOR_CHARACTERS = 256;
    private static final Set<String> QUERY_PARAMETERS =
            Set.of("after_id", "before_id", "limit");

    private final AnthropicHttpClient client;
    private final AnthropicCompatibilityProfile profile;
    private final RequestLogger requestLogger;

    public AnthropicModelsHandler(
            AnthropicHttpClient client,
            AnthropicCompatibilityProfile profile,
            RequestLogger requestLogger
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.requestLogger = Objects.requireNonNull(requestLogger, "requestLogger");
    }

    @Override
    public void handle(Context context) throws Exception {
        AccessLogFields.provider(context, ProviderId.ANTHROPIC.wireName());
        AccessLogFields.mode(context, "sync");
        String version = context.header("anthropic-version");
        if (version == null || !profile.anthropicVersion().equals(version.strip())) {
            AnthropicMessagesHandler.writeError(context, 400, "invalid_request_error",
                    "`anthropic-version` must be " + profile.anthropicVersion());
            return;
        }
        URI uri;
        AnthropicRequestOptions options;
        try {
            uri = requestUri(context);
            options = AnthropicRequestOptions.nativeRequest(
                    context.header("anthropic-beta"), Map.of());
        } catch (IllegalArgumentException error) {
            AnthropicMessagesHandler.writeError(
                    context, 400, "invalid_request_error", error.getMessage());
            return;
        }
        requestLogger.logInbound(requestId(context), context, null);
        HttpResponse<java.io.InputStream> upstream;
        try {
            upstream = client.request(uri, "GET", null, options);
        } catch (IOException error) {
            AnthropicMessagesHandler.writeError(
                    context, 502, "api_error", "Anthropic is temporarily unavailable");
            return;
        }
        AccessLogFields.upstreamStatus(context, upstream.statusCode());
        upstream.headers().firstValue("request-id")
                .ifPresent(value -> context.header("request-id", value));
        upstream.headers().firstValue("retry-after")
                .ifPresent(value -> context.header("retry-after", value));
        byte[] body;
        try {
            body = BoundedBodyReader.read(upstream, MAX_RESPONSE_BYTES);
        } catch (BoundedBodyReader.BodyTooLargeException error) {
            AnthropicMessagesHandler.writeError(
                    context, 502, "api_error", "Anthropic model response was too large");
            return;
        }
        context.status(upstream.statusCode());
        context.contentType(upstream.headers().firstValue("content-type")
                .orElse(JsonHelper.JSON_CONTENT_TYPE));
        AccessLogFields.responseBytes(context, body.length);
        context.result(new String(body, StandardCharsets.UTF_8));
    }

    static boolean isNativeRequest(Context context) {
        return context.header("anthropic-version") != null
                || context.header("x-api-key") != null;
    }

    private URI requestUri(Context context) {
        for (String name : context.queryParamMap().keySet()) {
            if (!QUERY_PARAMETERS.contains(name)) {
                throw new IllegalArgumentException("Unsupported query parameter: " + name);
            }
        }
        String after = cursor(context, "after_id");
        String before = cursor(context, "before_id");
        if (after != null && before != null) {
            throw new IllegalArgumentException("`after_id` and `before_id` cannot be combined");
        }
        String limit = context.queryParam("limit");
        if (limit != null) {
            int value;
            try {
                value = Integer.parseInt(limit);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("`limit` must be an integer");
            }
            if (value < 1 || value > 1000) {
                throw new IllegalArgumentException("`limit` must be between 1 and 1000");
            }
        }
        List<String> parameters = new ArrayList<>();
        add(parameters, "after_id", after);
        add(parameters, "before_id", before);
        add(parameters, "limit", limit);
        String query = parameters.isEmpty() ? "limit=100" : String.join("&", parameters);
        URI base = profile.modelsUri();
        try {
            return new URI(base.getScheme(), base.getAuthority(), base.getPath(), query, null);
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("Invalid model discovery request", error);
        }
    }

    private static String cursor(Context context, String name) {
        String value = context.queryParam(name);
        if (value == null) return null;
        if (value.isBlank() || value.length() > MAX_CURSOR_CHARACTERS) {
            throw new IllegalArgumentException("`" + name + "` is invalid");
        }
        return value;
    }

    private static void add(List<String> parameters, String name, String value) {
        if (value == null) return;
        parameters.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private String requestId(Context context) {
        String requestId = context.attribute(AccessLogFields.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = requestLogger.nextRequestId();
            context.attribute(AccessLogFields.REQUEST_ID, requestId);
        }
        return requestId;
    }
}
