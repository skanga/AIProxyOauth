package com.aiproxyoauth.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelAliasResolverTest {

    private final ModelAliasResolver resolver = new ModelAliasResolver();

    @Test
    void resolve_exactCodexReasoningAliases() {
        assertResolved("gpt-5.3-codex-spark-low", "gpt-5.3-codex-spark", "low");
        assertResolved("gpt-5.3-codex-spark-medium", "gpt-5.3-codex-spark", "medium");
        assertResolved("gpt-5.3-codex-spark-high", "gpt-5.3-codex-spark", "high");
        assertResolved("gpt-5.3-codex-spark-xhigh", "gpt-5.3-codex-spark", "xhigh");
        assertResolved("gpt-5.4-mini-none", "gpt-5.4-mini", "none");
    }

    @Test
    void resolve_unknownModelIsPreserved() {
        ModelAliasResolver.ResolvedModel resolved = resolver.resolve("custom-model");

        assertEquals("custom-model", resolved.model());
        assertNull(resolved.reasoningEffort());
    }

    @Test
    void clampReasoningEffort_noneRemainsForCurrentNonCodexModels() {
        assertEquals("none", resolver.clampReasoningEffort("gpt-5.5", "none"));
        assertEquals("none", resolver.clampReasoningEffort("gpt-5.4-mini", "none"));
    }

    @Test
    void clampReasoningEffort_noneAndMinimalBecomeLowForCodexModels() {
        assertEquals("low", resolver.clampReasoningEffort("gpt-5.3-codex-spark", "none"));
        assertEquals("low", resolver.clampReasoningEffort("gpt-5.3-codex-spark", "minimal"));
    }

    @Test
    void clampReasoningEffort_xhighRemainsForSupportedModels() {
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.3-codex-spark", "xhigh"));
    }

    @Test
    void clampReasoningEffort_xhighBecomesHighForUnsupportedModels() {
        assertEquals("high", resolver.clampReasoningEffort("gpt-5.5", "xhigh"));
        assertEquals("high", resolver.clampReasoningEffort("custom-model", "xhigh"));
    }

    @Test
    void clampReasoningEffort_nullOrBlankRemainUnset() {
        assertNull(resolver.clampReasoningEffort("gpt-5.5", null));
        assertNull(resolver.clampReasoningEffort("gpt-5.5", " "));
    }

    private void assertResolved(String alias, String model, String reasoningEffort) {
        ModelAliasResolver.ResolvedModel resolved = resolver.resolve(alias);
        assertEquals(model, resolved.model());
        assertEquals(reasoningEffort, resolved.reasoningEffort());
    }
}
