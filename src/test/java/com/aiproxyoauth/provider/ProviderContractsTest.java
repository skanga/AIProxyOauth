package com.aiproxyoauth.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderContractsTest {

    @Test
    void providerId_parsesStableWireNamesCaseInsensitively() {
        assertEquals(ProviderId.CODEX, ProviderId.parse("codex"));
        assertEquals(ProviderId.ANTHROPIC, ProviderId.parse(" ANTHROPIC "));
    }

    @Test
    void providerId_rejectsUnknownProvider() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProviderId.parse("other")
        );

        assertEquals("Unsupported provider: other", error.getMessage());
    }

    @Test
    void providerModel_isImmutableAndRequiresAUsableIdentity() {
        List<String> aliases = new java.util.ArrayList<>(List.of("anthropic/sonnet"));
        ProviderModel model = new ProviderModel(
                "claude-sonnet-4-5",
                "Claude Sonnet 4.5",
                ProviderId.ANTHROPIC,
                aliases,
                Optional.of(true),
                200_000
        );

        aliases.add("mutated");

        assertEquals(List.of("anthropic/sonnet"), model.aliases());
        assertThrows(UnsupportedOperationException.class, () -> model.aliases().add("other"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderModel(
                " ", "Claude", ProviderId.ANTHROPIC, List.of(), Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class, () -> new ProviderModel(
                "claude", "Claude", ProviderId.ANTHROPIC, List.of(), Optional.empty(), -1));
    }

    @Test
    void modelRoute_preservesRequestedAndUpstreamModels() {
        ModelRoute route = new ModelRoute(
                ProviderId.ANTHROPIC,
                "anthropic/sonnet",
                "claude-sonnet-4-5",
                "high"
        );

        assertEquals("anthropic/sonnet", route.requestedModel());
        assertEquals("claude-sonnet-4-5", route.upstreamModel());
        assertEquals("high", route.reasoningEffort());
    }

    @Test
    void providerErrorsHaveStableDefaultHttpStatuses() {
        assertEquals(401, ProviderError.of(
                ProviderError.Kind.AUTHENTICATION, "login required").httpStatus());
        assertEquals(429, ProviderError.of(
                ProviderError.Kind.RATE_LIMIT, "slow down").httpStatus());
        assertEquals(502, ProviderError.of(
                ProviderError.Kind.PROTOCOL, "truncated stream").httpStatus());
        assertEquals(529, ProviderError.of(
                ProviderError.Kind.OVERLOADED, "busy").httpStatus());
    }
}
