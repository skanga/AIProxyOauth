package com.aiproxyoauth.provider.anthropic.auth;

import java.time.Instant;
import java.util.Objects;

public record AnthropicCredential(
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        Instant updatedAt
) {
    public AnthropicCredential {
        accessToken = requireNonBlank(accessToken, "accessToken");
        refreshToken = requireNonBlank(refreshToken, "refreshToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
