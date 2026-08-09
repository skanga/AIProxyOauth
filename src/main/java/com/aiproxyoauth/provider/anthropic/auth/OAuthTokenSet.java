package com.aiproxyoauth.provider.anthropic.auth;

import java.util.Objects;

public record OAuthTokenSet(
        String accessToken,
        String refreshToken,
        long expiresInSeconds
) {
    public OAuthTokenSet {
        accessToken = requireNonBlank(accessToken, "accessToken");
        refreshToken = refreshToken == null ? "" : refreshToken;
        if (expiresInSeconds <= 0) {
            throw new IllegalArgumentException("expiresInSeconds must be positive");
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
