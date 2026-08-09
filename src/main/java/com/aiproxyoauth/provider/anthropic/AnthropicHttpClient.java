package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicAuthManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AnthropicHttpClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "authorization",
            "x-api-key",
            "anthropic-version",
            "anthropic-beta",
            "anthropic-dangerous-direct-browser-access",
            "user-agent",
            "x-app"
    );

    private final AnthropicCompatibilityProfile profile;
    private final HttpClient httpClient;
    private final AnthropicAuthManager authManager;
    private final RequestLogger requestLogger;

    public AnthropicHttpClient(AnthropicCompatibilityProfile profile,
                               HttpClient httpClient,
                               AnthropicAuthManager authManager,
                               RequestLogger requestLogger) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.authManager = Objects.requireNonNull(authManager, "authManager");
        this.requestLogger = Objects.requireNonNull(requestLogger, "requestLogger");
    }

    public HttpResponse<InputStream> request(
            URI uri, String method, String body, Map<String, String> extraHeaders)
            throws IOException {
        return request(uri, method, body, AnthropicRequestOptions.existing(extraHeaders));
    }

    public HttpResponse<InputStream> request(
            URI uri, String method, String body, AnthropicRequestOptions options)
            throws IOException {
        return request(uri, method, body, options, DEFAULT_TIMEOUT);
    }

    public HttpResponse<InputStream> request(
            URI uri,
            String method,
            String body,
            Map<String, String> extraHeaders,
            Duration timeout
    ) throws IOException {
        return request(uri, method, body, AnthropicRequestOptions.existing(extraHeaders), timeout);
    }

    public HttpResponse<InputStream> request(
            URI uri,
            String method,
            String body,
            AnthropicRequestOptions options,
            Duration timeout
    ) throws IOException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(timeout, "timeout");
        String requestId = requestLogger.nextRequestId();
        HttpResponse<InputStream> response = send(
                buildRequest(uri, method, body, options, timeout, requestId));
        if (response.statusCode() != 401) {
            logResponse(requestId, response);
            return response;
        }

        response.body().close();
        authManager.invalidate();
        HttpResponse<InputStream> retried = send(
                buildRequest(uri, method, body, options, timeout, requestId));
        logResponse(requestId, retried);
        return retried;
    }

    private HttpRequest buildRequest(
            URI uri,
            String method,
            String body,
            AnthropicRequestOptions options,
            Duration timeout,
            String requestId
    ) throws IOException {
        String effectiveMethod = method == null || method.isBlank() ? "GET" : method;
        Map<String, String> headers = new LinkedHashMap<>();
        if (options.headers() != null) {
            options.headers().forEach((name, value) -> {
                if (PROTECTED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "Cannot override protected Anthropic header: " + name);
                }
                headers.put(name, value);
            });
        }
        headers.put("Authorization", "Bearer " + authManager.accessToken());
        headers.put("Accept", "application/json");
        if (body != null) headers.put("Content-Type", "application/json");
        headers.put("anthropic-version", profile.anthropicVersion());
        LinkedHashSet<String> betas = new LinkedHashSet<>();
        if (isMessagesEndpoint(uri)) betas.add(profile.claudeCodeBeta());
        betas.add(profile.oauthBeta());
        betas.addAll(options.clientBetas());
        headers.put("anthropic-beta", String.join(",", betas));
        headers.put("anthropic-dangerous-direct-browser-access", "true");
        headers.put("User-Agent", "AIProxyOauth/1.3.0");
        headers.put("x-app", "AIProxyOauth");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(effectiveMethod, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(
                    effectiveMethod,
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            );
        }
        requestLogger.logUpstreamRequest(
                requestId, effectiveMethod, uri.getPath(), headers, body);
        return builder.build();
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Anthropic request was interrupted", error);
        }
    }

    private boolean isMessagesEndpoint(URI uri) {
        return uri.getPath().equals(profile.messagesUri().getPath());
    }

    private void logResponse(String requestId, HttpResponse<InputStream> response) {
        Map<String, List<String>> headers = response.headers() == null
                ? Map.of()
                : response.headers().map();
        requestLogger.logUpstreamResponse(
                requestId, response.statusCode(), headers, "[streaming body omitted]");
    }
}
