package com.aiproxyoauth.server;

import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelsHandlerProviderTest {

    @Test
    void exposesProviderSpecificOwnershipForMergedModels() throws Exception {
        ModelCatalog catalog = new ModelCatalog() {
            @Override
            public List<ProviderModel> resolveModels() {
                return List.of(
                        model("gpt-5.5", ProviderId.CODEX),
                        model("claude-sonnet-4-5", ProviderId.ANTHROPIC)
                );
            }
        };
        Context context = mock(Context.class);

        new ModelsHandler(catalog).handle(context);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(context).status(200);
        verify(context).result(body.capture());
        JsonNode root = Json.MAPPER.readTree(body.getValue());
        assertEquals("codex-oauth", root.path("data").get(0).path("owned_by").asText());
        assertEquals("anthropic-oauth", root.path("data").get(1).path("owned_by").asText());
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
