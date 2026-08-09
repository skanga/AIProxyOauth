package com.aiproxyoauth.provider.anthropic.auth;

import java.io.IOException;
import java.util.Objects;

public final class AnthropicOAuthException extends IOException {

    public enum Kind {
        NETWORK,
        BAD_RESPONSE,
        RESPONSE_TOO_LARGE,
        API_ERROR,
        MISSING_TOKEN,
        STATE_MISMATCH,
        INVALID_CALLBACK
    }

    private final Kind kind;

    public AnthropicOAuthException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public AnthropicOAuthException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }
}
