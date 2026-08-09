package com.aiproxyoauth.provider;

import java.util.List;
import java.util.Objects;

public final class ModelRoutingException extends IllegalArgumentException {

    public enum Kind {
        BLANK_MODEL,
        UNKNOWN_PROVIDER,
        AMBIGUOUS
    }

    private final Kind kind;
    private final List<String> alternatives;

    public ModelRoutingException(Kind kind, String message) {
        this(kind, message, List.of());
    }

    public ModelRoutingException(Kind kind, String message, List<String> alternatives) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
    }

    public Kind kind() {
        return kind;
    }

    public List<String> alternatives() {
        return alternatives;
    }
}
