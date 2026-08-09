package com.aiproxyoauth.provider;

import java.util.Objects;

public record ProviderError(Kind kind, int httpStatus, String message) {

    public enum Kind {
        INVALID_REQUEST,
        AUTHENTICATION,
        PERMISSION,
        NOT_FOUND,
        RATE_LIMIT,
        REQUEST_TOO_LARGE,
        OVERLOADED,
        TIMEOUT,
        TRANSPORT,
        PROTOCOL
    }

    public ProviderError {
        kind = Objects.requireNonNull(kind, "kind");
        if (httpStatus < 400 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be between 400 and 599");
        }
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message cannot be blank");
        }
    }

    public static ProviderError of(Kind kind, String message) {
        return new ProviderError(kind, defaultHttpStatus(kind), message);
    }

    private static int defaultHttpStatus(Kind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case INVALID_REQUEST -> 400;
            case AUTHENTICATION -> 401;
            case PERMISSION -> 403;
            case NOT_FOUND -> 404;
            case RATE_LIMIT -> 429;
            case REQUEST_TOO_LARGE -> 413;
            case OVERLOADED -> 529;
            case TIMEOUT -> 504;
            case TRANSPORT, PROTOCOL -> 502;
        };
    }
}
