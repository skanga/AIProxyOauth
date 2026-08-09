package com.aiproxyoauth.server;

import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.provider.ModelRoute;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class RoutingChatCompletionsHandlerTest {

    @Test
    void routesQualifiedClaudeAliasWithResolvedUpstreamModel() throws Exception {
        Context context = mock(Context.class);
        when(context.body()).thenReturn("""
                {"model":"anthropic/sonnet","messages":[{"role":"user","content":"hi"}]}
                """);
        ModelCatalog catalog = () -> List.of(new ProviderModel(
                "claude-sonnet-4-5", "Claude", ProviderId.ANTHROPIC,
                List.of("sonnet"), Optional.of(true), 200_000));
        AtomicReference<ModelRoute> captured = new AtomicReference<>();
        ChatBackend anthropic = (ctx, route) -> captured.set(route);
        ChatBackend codex = (ctx, route) -> { throw new AssertionError("wrong backend"); };

        new RoutingChatCompletionsHandler(catalog, ProviderId.CODEX, codex, anthropic)
                .handle(context);

        assertEquals(ProviderId.ANTHROPIC, captured.get().provider());
        assertEquals("claude-sonnet-4-5", captured.get().upstreamModel());
        assertEquals("anthropic/sonnet", captured.get().requestedModel());
    }
}
