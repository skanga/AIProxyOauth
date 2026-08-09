package com.aiproxyoauth.model;

import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeModelCatalogTest {

    @Test
    void mergesProviderCatalogsAndDeduplicatesWithinAProvider() throws Exception {
        ProviderModelCatalog codex = catalog(
                ProviderId.CODEX,
                List.of(model("gpt-5.5", ProviderId.CODEX), model("gpt-5.5", ProviderId.CODEX))
        );
        ProviderModelCatalog anthropic = catalog(
                ProviderId.ANTHROPIC,
                List.of(model("claude-sonnet-4-5", ProviderId.ANTHROPIC))
        );

        CompositeModelCatalog composite = new CompositeModelCatalog(List.of(codex, anthropic));

        assertEquals(
                List.of(
                        model("gpt-5.5", ProviderId.CODEX),
                        model("claude-sonnet-4-5", ProviderId.ANTHROPIC)
                ),
                composite.resolveModels()
        );
        assertEquals(List.of(), composite.failures());
    }

    @Test
    void oneProviderFailureDoesNotEraseTheOtherCatalog() throws Exception {
        ProviderModelCatalog failedCodex = failingCatalog(ProviderId.CODEX, "codex unavailable");
        ProviderModelCatalog anthropic = catalog(
                ProviderId.ANTHROPIC,
                List.of(model("claude-sonnet-4-5", ProviderId.ANTHROPIC))
        );
        CompositeModelCatalog composite =
                new CompositeModelCatalog(List.of(failedCodex, anthropic));

        assertEquals(
                List.of(model("claude-sonnet-4-5", ProviderId.ANTHROPIC)),
                composite.resolveModels()
        );
        assertEquals(
                List.of(new ModelCatalogFailure(ProviderId.CODEX, "codex unavailable")),
                composite.failures()
        );
    }

    @Test
    void allProviderFailuresProduceOneCatalogException() {
        CompositeModelCatalog composite = new CompositeModelCatalog(List.of(
                failingCatalog(ProviderId.CODEX, "codex unavailable"),
                failingCatalog(ProviderId.ANTHROPIC, "anthropic unavailable")
        ));

        ModelCatalogException error = assertThrows(
                ModelCatalogException.class,
                composite::resolveModels
        );

        assertEquals(2, error.failures().size());
    }

    @Test
    void rejectsModelsClaimedByTheWrongCatalog() {
        CompositeModelCatalog composite = new CompositeModelCatalog(List.of(
                catalog(
                        ProviderId.CODEX,
                        List.of(model("claude-sonnet-4-5", ProviderId.ANTHROPIC))
                )
        ));

        assertThrows(ModelCatalogException.class, composite::resolveModels);
    }

    private static ProviderModelCatalog catalog(
            ProviderId provider,
            List<ProviderModel> models
    ) {
        return new ProviderModelCatalog() {
            @Override
            public ProviderId provider() {
                return provider;
            }

            @Override
            public List<ProviderModel> resolveModels() {
                return models;
            }
        };
    }

    private static ProviderModelCatalog failingCatalog(ProviderId provider, String message) {
        return new ProviderModelCatalog() {
            @Override
            public ProviderId provider() {
                return provider;
            }

            @Override
            public List<ProviderModel> resolveModels() {
                throw new IllegalStateException(message);
            }
        };
    }

    private static ProviderModel model(String id, ProviderId provider) {
        return new ProviderModel(
                id,
                id,
                provider,
                List.of(),
                Optional.empty(),
                0
        );
    }
}
