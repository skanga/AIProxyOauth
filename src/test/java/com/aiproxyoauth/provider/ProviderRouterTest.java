package com.aiproxyoauth.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderRouterTest {

    private final ProviderRouter router = new ProviderRouter(List.of(
            model("gpt-5.5", ProviderId.CODEX, "codex/latest"),
            model("claude-sonnet-4-5", ProviderId.ANTHROPIC, "anthropic/sonnet"),
            model("shared-model", ProviderId.CODEX),
            model("shared-model", ProviderId.ANTHROPIC)
    ), ProviderId.CODEX);

    @Test
    void routesExactCatalogIdsAndAliases() {
        assertRoute(router.route("gpt-5.5"), ProviderId.CODEX, "gpt-5.5");
        assertRoute(
                router.route("anthropic/sonnet"),
                ProviderId.ANTHROPIC,
                "claude-sonnet-4-5"
        );
    }

    @Test
    void providerQualificationDisambiguatesAndIsRemovedUpstream() {
        ModelRoute route = router.route("anthropic/shared-model");

        assertEquals(ProviderId.ANTHROPIC, route.provider());
        assertEquals("anthropic/shared-model", route.requestedModel());
        assertEquals("shared-model", route.upstreamModel());
    }

    @Test
    void ambiguousUnqualifiedCatalogIdListsQualifiedAlternatives() {
        ModelRoutingException error = assertThrows(
                ModelRoutingException.class,
                () -> router.route("shared-model")
        );

        assertEquals(ModelRoutingException.Kind.AMBIGUOUS, error.kind());
        assertEquals(
                List.of("anthropic/shared-model", "codex/shared-model"),
                error.alternatives()
        );
    }

    @Test
    void routesKnownProviderPrefixesWithoutCatalogDiscovery() {
        assertRoute(
                router.route("claude-future-model"),
                ProviderId.ANTHROPIC,
                "claude-future-model"
        );
        assertRoute(router.route("gpt-future"), ProviderId.CODEX, "gpt-future");
        assertRoute(router.route("codex-future"), ProviderId.CODEX, "codex-future");
    }

    @Test
    void unknownUnqualifiedModelUsesEffectiveDefault() {
        ProviderRouter anthropicDefault = new ProviderRouter(
                List.of(),
                ProviderId.ANTHROPIC
        );

        assertRoute(
                anthropicDefault.route("organization-alias"),
                ProviderId.ANTHROPIC,
                "organization-alias"
        );
    }

    @Test
    void rejectsBlankAndUnknownProviderQualifiedModels() {
        assertThrows(ModelRoutingException.class, () -> router.route(" "));
        ModelRoutingException error = assertThrows(
                ModelRoutingException.class,
                () -> router.route("other/model")
        );

        assertEquals(ModelRoutingException.Kind.UNKNOWN_PROVIDER, error.kind());
    }

    private static ProviderModel model(
            String id,
            ProviderId provider,
            String... aliases
    ) {
        return new ProviderModel(
                id,
                id,
                provider,
                List.of(aliases),
                Optional.empty(),
                0
        );
    }

    private static void assertRoute(
            ModelRoute route,
            ProviderId provider,
            String upstreamModel
    ) {
        assertEquals(provider, route.provider());
        assertEquals(upstreamModel, route.upstreamModel());
    }
}
