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

class RoutingResponsesHandlerTest {
    @Test
    void routesClaudeAliasToResponsesBackend() throws Exception {
        Context context = mock(Context.class);
        when(context.body()).thenReturn("""
                {"model":"anthropic/sonnet","input":"hi"}
                """);
        ModelCatalog catalog = () -> List.of(new ProviderModel(
                "claude-sonnet-4-5", "Claude", ProviderId.ANTHROPIC,
                List.of("sonnet"), Optional.of(true), 200_000));
        AtomicReference<ModelRoute> route = new AtomicReference<>();
        ResponsesBackend anthropic = (ctx, resolved) -> route.set(resolved);

        new RoutingResponsesHandler(catalog, ProviderId.CODEX,
                (ctx, resolved) -> { throw new AssertionError("wrong backend"); },
                anthropic, "gpt-5.5").handle(context);

        assertEquals(ProviderId.ANTHROPIC, route.get().provider());
        assertEquals("claude-sonnet-4-5", route.get().upstreamModel());
    }
}
