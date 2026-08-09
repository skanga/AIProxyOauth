package com.aiproxyoauth.provider;

import java.util.Objects;

public record ModelRoute(
        ProviderId provider,
        String requestedModel,
        String upstreamModel,
        String reasoningEffort
) {
    public ModelRoute {
        provider = Objects.requireNonNull(provider, "provider");
        requestedModel = requireNonBlank(requestedModel, "requestedModel");
        upstreamModel = requireNonBlank(upstreamModel, "upstreamModel");
        if (reasoningEffort != null && reasoningEffort.isBlank()) {
            reasoningEffort = null;
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
