package com.aiproxyoauth.provider.anthropic.auth;

import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class AnthropicOAuthClient implements TokenRefresher {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final AnthropicCompatibilityProfile profile;
    private final HttpClient httpClient;

    public AnthropicOAuthClient(AnthropicCompatibilityProfile profile, HttpClient httpClient) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public OAuthTokenSet refresh(String refreshToken) throws IOException {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken must not be blank");
        }

        String form = formField("grant_type", "refresh_token")
                + "&" + formField("client_id", profile.clientId())
                + "&" + formField("refresh_token", refreshToken);
        return exchange(form);
    }

    OAuthTokenSet exchange(String form) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(profile.tokenUri())
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "AIProxyOauth/1.1.1")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return OAuthTokenParser.parse(BoundedOAuthResponseReader.send(httpClient, request));
    }

    static String formField(String name, String value) {
        return formEncode(name) + "=" + formEncode(value);
    }

    static String queryField(String name, String value) {
        return queryEncode(name) + "=" + queryEncode(value);
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String queryEncode(String value) {
        return formEncode(value).replace("+", "%20");
    }
}
