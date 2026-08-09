package com.aiproxyoauth.model;

import com.aiproxyoauth.provider.ProviderId;

import java.util.Objects;

public record ModelCatalogFailure(ProviderId provider, String message) {
    public ModelCatalogFailure {
        provider = Objects.requireNonNull(provider, "provider");
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            message = "Model discovery failed.";
        }
    }
}
