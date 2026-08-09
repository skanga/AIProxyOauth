package com.aiproxyoauth.provider;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ProviderModel(
        String id,
        String displayName,
        ProviderId provider,
        List<String> aliases,
        Optional<Boolean> supportsTools,
        int contextWindow
) {
    public ProviderModel {
        id = requireNonBlank(id, "id");
        displayName = requireNonBlank(displayName, "displayName");
        provider = Objects.requireNonNull(provider, "provider");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        supportsTools = Objects.requireNonNull(supportsTools, "supportsTools");
        if (contextWindow < 0) {
            throw new IllegalArgumentException("contextWindow cannot be negative");
        }
        for (String alias : aliases) {
            requireNonBlank(alias, "alias");
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
