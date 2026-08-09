package com.aiproxyoauth.model;

import java.util.List;
import java.util.Objects;

public final class ModelCatalogException extends Exception {

    private final List<ModelCatalogFailure> failures;

    public ModelCatalogException(List<ModelCatalogFailure> failures) {
        super(message(failures));
        this.failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (this.failures.isEmpty()) {
            throw new IllegalArgumentException("failures cannot be empty");
        }
    }

    public List<ModelCatalogFailure> failures() {
        return failures;
    }

    private static String message(List<ModelCatalogFailure> failures) {
        Objects.requireNonNull(failures, "failures");
        if (failures.isEmpty()) {
            return "Model discovery failed.";
        }
        return failures.stream()
                .map(failure -> failure.provider().wireName() + ": " + failure.message())
                .collect(java.util.stream.Collectors.joining("; ", "Model discovery failed: ", ""));
    }
}
