package com.aiproxyoauth.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelAliasResolverTest {

    private final ModelAliasResolver resolver = new ModelAliasResolver();

    @Test
    void resolve_exactCodexReasoningAliases() {
        assertResolved("gpt-5.2-codex-low", "gpt-5.2-codex", "low");
        assertResolved("gpt-5.2-codex-medium", "gpt-5.2-codex", "medium");
        assertResolved("gpt-5.2-codex-high", "gpt-5.2-codex", "high");
        assertResolved("gpt-5.2-codex-xhigh", "gpt-5.2-codex", "xhigh");
        assertResolved("gpt-5.1-codex-max-xhigh", "gpt-5.1-codex-max", "xhigh");
        assertResolved("gpt-5.1-none", "gpt-5.1", "none");
    }

    @Test
    void resolve_unknownModelIsPreserved() {
        ModelAliasResolver.ResolvedModel resolved = resolver.resolve("custom-model");

        assertEquals("custom-model", resolved.model());
        assertNull(resolved.reasoningEffort());
    }

    @Test
    void clampReasoningEffort_noneRemainsForGptFiveOneAndFiveTwo() {
        assertEquals("none", resolver.clampReasoningEffort("gpt-5.1", "none"));
        assertEquals("none", resolver.clampReasoningEffort("gpt-5.2", "none"));
    }

    @Test
    void clampReasoningEffort_noneAndMinimalBecomeLowForCodexModels() {
        assertEquals("low", resolver.clampReasoningEffort("gpt-5.2-codex", "none"));
        assertEquals("low", resolver.clampReasoningEffort("gpt-5.2-codex", "minimal"));
    }

    @Test
    void clampReasoningEffort_xhighRemainsForSupportedModels() {
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.2", "xhigh"));
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.2-codex", "xhigh"));
        assertEquals("xhigh", resolver.clampReasoningEffort("gpt-5.1-codex-max", "xhigh"));
    }

    @Test
    void clampReasoningEffort_xhighBecomesHighForUnsupportedModels() {
        assertEquals("high", resolver.clampReasoningEffort("gpt-5.1", "xhigh"));
        assertEquals("high", resolver.clampReasoningEffort("custom-model", "xhigh"));
    }

    @Test
    void clampReasoningEffort_codexMiniAcceptsOnlyMediumOrHigh() {
        assertEquals("medium", resolver.clampReasoningEffort("gpt-5.1-codex-mini", "none"));
        assertEquals("medium", resolver.clampReasoningEffort("gpt-5.1-codex-mini", "low"));
        assertEquals("medium", resolver.clampReasoningEffort("gpt-5.1-codex-mini", "minimal"));
        assertEquals("medium", resolver.clampReasoningEffort("gpt-5.1-codex-mini", "medium"));
        assertEquals("high", resolver.clampReasoningEffort("gpt-5.1-codex-mini", "high"));
        assertEquals("high", resolver.clampReasoningEffort("gpt-5.1-codex-mini", "xhigh"));
    }

    @Test
    void clampReasoningEffort_nullOrBlankRemainUnset() {
        assertNull(resolver.clampReasoningEffort("gpt-5.2", null));
        assertNull(resolver.clampReasoningEffort("gpt-5.2", " "));
    }

    private void assertResolved(String alias, String model, String reasoningEffort) {
        ModelAliasResolver.ResolvedModel resolved = resolver.resolve(alias);
        assertEquals(model, resolved.model());
        assertEquals(reasoningEffort, resolved.reasoningEffort());
    }
}
