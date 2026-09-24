package com.aiproxyoauth.provider;

import java.util.Locale;

public enum ProviderId {
    CODEX("codex"),
    ANTHROPIC("anthropic"),
    COPILOT("copilot");

    public static java.util.List<ProviderId> defaultOrder() {
        return java.util.List.of(COPILOT, CODEX, ANTHROPIC);
    }

    private final String wireName;

    ProviderId(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ProviderId parse(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (ProviderId provider : values()) {
            if (provider.wireName.equals(normalized)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unsupported provider: " + value);
    }
}
