package com.aiproxyoauth;

import com.aiproxyoauth.provider.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderStartupResolverTest {
    @Test
    void defaultsToEveryProviderWithCredentials() {
        assertEquals(
                Set.of(ProviderId.CODEX, ProviderId.ANTHROPIC),
                ProviderStartupResolver.resolve(null, true, true)
        );
        assertEquals(
                Set.of(ProviderId.ANTHROPIC),
                ProviderStartupResolver.resolve(null, false, true)
        );
    }

    @Test
    void explicitProviderMustHaveCredentials() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProviderStartupResolver.resolve("codex,anthropic", true, false)
        );
    }

    @Test
    void failsWhenNoProviderHasCredentialsOrNameIsUnknown() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProviderStartupResolver.resolve(null, false, false)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ProviderStartupResolver.resolve("other", true, true)
        );
    }

    @Test
    void resolvesAndValidatesDefaultProvider() {
        assertEquals(ProviderId.CODEX, ProviderStartupResolver.resolveDefault(
                null, Set.of(ProviderId.CODEX, ProviderId.ANTHROPIC)));
        assertEquals(ProviderId.ANTHROPIC, ProviderStartupResolver.resolveDefault(
                "anthropic", Set.of(ProviderId.CODEX, ProviderId.ANTHROPIC)));
        assertThrows(IllegalArgumentException.class, () ->
                ProviderStartupResolver.resolveDefault("anthropic", Set.of(ProviderId.CODEX)));
    }
}
