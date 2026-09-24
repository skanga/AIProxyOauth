package com.aiproxyoauth.provider.anthropic.auth;

import java.io.IOException;
import java.util.Objects;

public final class AnthropicAuthException extends IOException {
    public enum Kind {
        MISSING_CREDENTIAL,
        REFRESH_FAILED
    }

    private final Kind kind;

    public AnthropicAuthException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public AnthropicAuthException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }

    /** Proxy-origin, actionable message for clients: names the cause and the fix. */
    public String userMessage() {
        String detail = getMessage() == null || getMessage().isBlank()
                ? "Anthropic OAuth credential is unavailable"
                : getMessage();
        return detail + ". Run `auth anthropic login` to re-authenticate.";
    }
}
