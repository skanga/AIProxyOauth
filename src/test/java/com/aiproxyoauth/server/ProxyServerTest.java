package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ProxyServerTest {

    @Mock CodexHttpClient client;
    @Mock ModelResolver modelResolver;
    @Mock UsageTracker usageTracker;

    @Test
    void proxyServer_createsJavalinApp() {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of(), null
        );
        
        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null));
        assertNotNull(server.getApp());
        
        // We can't easily check all routes without starting, but we can verify it's configured.
    }

    @Test
    void proxyServer_corsEnabled() throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of(), null
        );
        
        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null));
        server.getApp().start(0);
        int port = server.getApp().port();

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "GET")
                .build();
        
        java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        
        server.getApp().stop();
    }

    @Test
    void proxyServer_authEnforced() throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of("key1", "user1"), null
        );
        
        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of("key1", "user1"), null, null));
        server.getApp().start(0);
        int port = server.getApp().port();

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        
        // No key
        java.net.http.HttpRequest req1 = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + "/v1/models"))
                .GET()
                .build();
        assertEquals(401, http.send(req1, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());

        // Valid key
        java.net.http.HttpRequest req2 = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + "/v1/models"))
                .header("Authorization", "Bearer key1")
                .GET()
                .build();
        // It will fail because modelResolver is not mocked to return anything but we just want to see it pass Auth
        int status = http.send(req2, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
        assertNotEquals(401, status);

        server.getApp().stop();
    }
}
