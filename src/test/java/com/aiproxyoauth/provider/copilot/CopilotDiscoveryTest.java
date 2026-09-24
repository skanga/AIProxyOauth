package com.aiproxyoauth.provider.copilot;
import com.aiproxyoauth.config.EffectiveConfig;
import com.aiproxyoauth.model.CopilotModelCatalog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CopilotDiscoveryTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"https://api.other.ghe.com", "https://evilgithubcopilot.com", "http://api.githubcopilot.com", "https://api.githubcopilot.com@evil.example"})
    void rejectsUntrustedOrCrossTenantDiscoveredEndpoints(String endpoint) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user", ex -> {
            byte[] bytes = ("{\"endpoints\":{\"api\":\"" + endpoint + "\"}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try (HttpClient http = HttpClient.newHttpClient()) {
            var cfg = new EffectiveConfig.Copilot("acme.ghe.com", Path.of("unused"), "client", null, "test-token", List.of());
            var client = new CopilotClient(cfg, http, Clock.systemUTC(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/user"));
            assertThrows(java.io.IOException.class, client::validateCredentials);
        } finally { server.stop(0); }
    }
    @Test void discoversAccountEndpointFiltersEmbeddingsAndCachesCatalog() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AtomicInteger discovery = new AtomicInteger(), models = new AtomicInteger();
        server.createContext("/user", ex -> {
            discovery.incrementAndGet();
            assertEquals("Bearer test-token", ex.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = ("{\"endpoints\":{\"api\":\"" + base + "\"}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.createContext("/models", ex -> {
            models.incrementAndGet();
            byte[] bytes = """
                    {"data":[
                    {"id":"gpt-test","supported_endpoints":["/chat/completions","/responses"],"capabilities":{"type":"chat","supports":{"tool_calls":true}}},
                    {"id":"claude-test","supported_endpoints":["/v1/messages"],"capabilities":{"type":"chat"}},
                    {"id":"legacy","capabilities":{"type":"chat"}},
                    {"id":"embedding","capabilities":{"type":"embeddings"}}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try (HttpClient http = HttpClient.newHttpClient()) {
            var config = new EffectiveConfig.Copilot("github.com", Path.of("unused"), "client", null, "test-token", List.of());
            var client = new CopilotClient(config, http, Clock.systemUTC(), URI.create(base + "/user"));
            var catalog = new CopilotModelCatalog(client, List.of(), Clock.systemUTC());
            assertEquals(List.of("gpt-test", "claude-test", "legacy"), catalog.resolveModels().stream().map(m -> m.id()).toList());
            assertEquals("/responses", catalog.endpoint("gpt-test", true));
            assertEquals("/v1/messages", catalog.endpoint("claude-test", false));
            assertEquals("/chat/completions", catalog.endpoint("legacy", true));
            assertEquals(1, models.get()); assertEquals(1, discovery.get());
            assertThrows(IllegalArgumentException.class, () -> catalog.endpoint("missing", false));
        } finally { server.stop(0); }
    }
}
