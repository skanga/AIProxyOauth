package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ProxyServerTest {

    @Mock CodexHttpClient client;
    @Mock ModelResolver modelResolver;
    @Mock UsageTracker usageTracker;

    private static ServerConfig minimalConfig() {
        return new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of(), null
        );
    }

    @Test
    void proxyServer_createsJavalinApp() {
        ServerConfig config = minimalConfig();
        
        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null));
        assertNotNull(server.getApp());
        
        // We can't easily check all routes without starting, but we can verify it's configured.
    }

    @Test
    void proxyServer_doesNotAllowArbitraryCorsOriginByDefault() throws Exception {
        ServerConfig config = minimalConfig();
        
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
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        
        server.getApp().stop();
    }

    @Test
    void proxyServer_allowsAnyCorsOriginWhenExplicitlyConfigured() throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of(), null,
                true, null
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
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

        server.getApp().stop();
    }

    @Test
    void proxyServer_allowsOnlyConfiguredCorsOrigin() throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of(), null,
                false, java.util.List.of("http://allowed.example")
        );

        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null));
        server.getApp().start(0);
        int port = server.getApp().port();

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest allowed = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://allowed.example")
                .header("Access-Control-Request-Method", "GET")
                .build();
        java.net.http.HttpRequest denied = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://denied.example")
                .header("Access-Control-Request-Method", "GET")
                .build();

        assertEquals("http://allowed.example",
                http.send(allowed, java.net.http.HttpResponse.BodyHandlers.ofString())
                        .headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(http.send(denied, java.net.http.HttpResponse.BodyHandlers.ofString())
                .headers().firstValue("Access-Control-Allow-Origin").isEmpty());

        server.getApp().stop();
    }

    @Test
    void proxyServer_allowsCorsPreflightWithoutApiKeyOnProtectedRoutes() throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of("key1", "user1"), null,
                false, java.util.List.of("http://allowed.example")
        );

        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker,
                new ApiKeyStore(Map.of("key1", "user1"), null, null));
        server.getApp().start(0);
        int port = server.getApp().port();

        HttpRequest preflight = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/responses"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://allowed.example")
                .header("Access-Control-Request-Method", "POST")
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(preflight, HttpResponse.BodyHandlers.ofString());

        assertNotEquals(401, response.statusCode());
        assertEquals("http://allowed.example",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

        server.getApp().stop();
    }

    @Test
    void proxyServer_globalExceptionHandlerHidesExceptionMessage() throws Exception {
        ServerConfig config = minimalConfig();
        when(usageTracker.snapshot()).thenThrow(new IllegalStateException("internal diagnostic marker"));

        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null));
        server.getApp().start(0);
        int port = server.getApp().port();

        java.io.PrintStream originalErr = System.err;
        HttpResponse<String> resp;
        try {
            System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            resp = HttpClient.newHttpClient().send(
                    req(port, "/v1/usage"),
                    HttpResponse.BodyHandlers.ofString()
            );
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(500, resp.statusCode());
        assertTrue(resp.body().contains("Unexpected server error."), resp.body());
        assertFalse(resp.body().contains("internal diagnostic marker"), resp.body());

        server.getApp().stop();
    }

    @Test
    void proxyServer_regularApiKeyAttributeUsesFingerprintNotRawKey() throws Exception {
        String rawKey = "sk-proxy-regular-secret";
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of(rawKey, "user1"), null
        );

        io.javalin.Javalin app = io.javalin.Javalin.create(cfg -> {
            ApiKeyStore store = new ApiKeyStore(Map.of(rawKey, "user1"), null, null);
            cfg.routes.beforeMatched(ctx -> ProxyServer.authenticateRequest(ctx, store));
            cfg.routes.get("/debug/fingerprint", ctx -> ctx.result(java.util.Objects.toString((Object) ctx.attribute("keyFingerprint"))));
        });
        app.start(0);
        int port = app.port();

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/debug/fingerprint"))
                .header("Authorization", "Bearer " + rawKey)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(fingerprint(rawKey), response.body());
        assertNotEquals(rawKey, response.body());

        app.stop();
    }

    @Test
    void proxyServer_adminApiKeyAttributeUsesFingerprintNotRawKey() throws Exception {
        String rawKey = "sk-proxy-admin-secret";
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of(), rawKey
        );

        io.javalin.Javalin app = io.javalin.Javalin.create(cfg -> {
            ApiKeyStore store = new ApiKeyStore(Map.of(), null, rawKey);
            cfg.routes.beforeMatched(ctx -> ProxyServer.authenticateRequest(ctx, store));
            cfg.routes.get("/debug/admin-fingerprint", ctx -> ctx.result(java.util.Objects.toString((Object) ctx.attribute("adminKeyFingerprint"))));
        });
        app.start(0);
        int port = app.port();

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/debug/admin-fingerprint"))
                .header("Authorization", "Bearer " + rawKey)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(fingerprint(rawKey), response.body());
        assertNotEquals(rawKey, response.body());

        app.stop();
    }

    @Test
    void responsesStatefulResourceRoutesAreNotRegistered() throws Exception {
        ProxyServer server = new ProxyServer(minimalConfig(), client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null));
        server.getApp().start(0);
        int port = server.getApp().port();

        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpResponse<String> retrieve = http.send(req(port, "/v1/responses/resp_1"), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, retrieve.statusCode());
            assertTrue(retrieve.body().contains("Route not found."));

            HttpResponse<String> inputItems = http.send(req(port, "/v1/responses/resp_1/input_items"), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, inputItems.statusCode());
            assertTrue(inputItems.body().contains("Route not found."));

            HttpResponse<String> conversations = http.send(post(port, "/v1/conversations", "{}"), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, conversations.statusCode());
            assertTrue(conversations.body().contains("Route not found."));
        } finally {
            server.getApp().stop();
        }
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

    @Test
    void proxyServer_rejectsNonLoopbackWhenLiveApiKeyStoreIsNotEnforcing() {
        ServerConfig config = new ServerConfig(
                "0.0.0.0", 10531,
                null, "0.1",
                "http://base", null, null, null,
                "", false,
                Map.of("configured-key", "user1"), null
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null)));
        assertTrue(ex.getMessage().contains("API key enforcement is required"));
    }

    private static HttpRequest req(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
    }

    private static HttpRequest post(int port, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
    }

    private static String fingerprint(String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
    }
}
