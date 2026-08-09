package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProviderStartupModesTest {

    @Test
    void startsCodexOnlyMode() throws Exception {
        verifyMode(List.of(model("gpt-5.5", ProviderId.CODEX)), null, ProviderId.CODEX,
                "gpt-5.5", false);
    }

    @Test
    void startsAnthropicOnlyMode() throws Exception {
        verifyMode(List.of(model("claude-sonnet-4-5", ProviderId.ANTHROPIC)),
                mock(AnthropicHttpClient.class), ProviderId.ANTHROPIC,
                "claude-sonnet-4-5", true);
    }

    @Test
    void startsDualProviderMode() throws Exception {
        verifyMode(List.of(
                        model("gpt-5.5", ProviderId.CODEX),
                        model("claude-sonnet-4-5", ProviderId.ANTHROPIC)),
                mock(AnthropicHttpClient.class), ProviderId.CODEX,
                "claude-sonnet-4-5", true);
    }

    private void verifyMode(
            List<ProviderModel> models,
            AnthropicHttpClient anthropic,
            ProviderId defaultProvider,
            String expectedModel,
            boolean expectAnthropicOwner
    ) throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531, null, "test", "http://base",
                null, null, null, "", false, Map.of(), null);
        ModelCatalog catalog = () -> models;
        ProxyServer server = new ProxyServer(
                config,
                mock(CodexHttpClient.class),
                catalog,
                new UsageTracker(),
                new ApiKeyStore(Map.of(), null, null),
                anthropic,
                anthropic == null ? null : AnthropicCompatibilityProfile.claudeCodeOAuth(),
                defaultProvider
        );
        server.getApp().start(0);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(uri(server, "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> listed = client.send(
                    HttpRequest.newBuilder(uri(server, "/v1/models")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals(200, listed.statusCode());
            assertTrue(listed.body().contains(expectedModel));
            assertEquals(expectAnthropicOwner,
                    listed.body().contains("anthropic-oauth"));
        } finally {
            server.getApp().stop();
        }
    }

    private ProviderModel model(String id, ProviderId provider) {
        return new ProviderModel(id, id, provider, List.of(), Optional.of(true), 200_000);
    }

    private URI uri(ProxyServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getApp().port() + path);
    }
}
