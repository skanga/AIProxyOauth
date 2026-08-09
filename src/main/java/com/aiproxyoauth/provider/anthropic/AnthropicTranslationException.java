package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.provider.ProviderError;

import java.util.Objects;

public final class AnthropicTranslationException extends Exception {
    private final ProviderError error;

    public AnthropicTranslationException(String message) {
        super(message);
        this.error = ProviderError.of(
                ProviderError.Kind.INVALID_REQUEST,
                Objects.requireNonNull(message, "message")
        );
    }

    public ProviderError error() {
        return error;
    }
}
