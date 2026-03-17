package com.aiproxyoauth.server;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for API key enforcement middleware.
 * Spins up a minimal Javalin server (port 0 = OS-assigned) with the same
 * beforeMatched logic used in ProxyServer, and verifies accept/reject behaviour.
 */
class ApiKeyEnforcementTest {

    private Javalin app;
    private HttpClient http;
    private int port;

    @BeforeEach
    void setUp() {
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    // -- helpers --

    /** Build a minimal app with two routes: GET /health and GET /v1/models. */
    private Javalin buildApp(Map<String, String> apiKeys, String adminKey) {
        return Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            if (!apiKeys.isEmpty() || adminKey != null) {
                cfg.routes.beforeMatched(ctx -> {
                    if ("/health".equals(ctx.path())) return;
                    String auth = ctx.header("Authorization");
                    String key = (auth != null && auth.startsWith("Bearer "))
                            ? auth.substring(7).strip() : null;
                    if (key != null && key.equals(adminKey)) {
                        ctx.attribute("isAdmin", true);
                        return;
                    }
                    String name = (key != null) ? apiKeys.get(key) : null;
                    if (name == null) {
                        JsonHelper.toErrorResponse(ctx, "Invalid or missing API key.", 401, "auth_error");
                        ctx.skipRemainingHandlers();
                    } else {
                        ctx.attribute("keyName", name);
                    }
                });
            }
            cfg.routes.get("/health",    ctx -> ctx.result("{\"ok\":true}"));
            cfg.routes.get("/v1/models", ctx -> ctx.result("{\"data\":[], \"keyName\":\"" + ctx.attribute("keyName") + "\"}"));
        });
    }

    private void start(Map<String, String> apiKeys) {
        app = buildApp(apiKeys, null);
        app.start("127.0.0.1", 0);
        port = app.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, null);
    }

    private HttpResponse<String> get(String path, String bearerKey) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (bearerKey != null) req.header("Authorization", "Bearer " + bearerKey);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    // -- open mode --

    @Test void openMode_noKeys_modelsPassesThrough() throws Exception {
        start(Map.of());
        assertEquals(200, get("/v1/models").statusCode());
    }

    @Test void openMode_noKeys_healthPassesThrough() throws Exception {
        start(Map.of());
        assertEquals(200, get("/health").statusCode());
    }

    // -- enforcement: rejected --

    @Test void enforcement_noAuthHeader_returns401() throws Exception {
        start(Map.of("sk-proxy-validkey12345678901234", "myapp"));
        assertEquals(401, get("/v1/models").statusCode());
    }

    @Test void enforcement_wrongKey_returns401() throws Exception {
        start(Map.of("sk-proxy-validkey12345678901234", "myapp"));
        assertEquals(401, get("/v1/models", "sk-proxy-wrongkey").statusCode());
    }

    @Test void enforcement_emptyKey_returns401() throws Exception {
        start(Map.of("sk-proxy-validkey12345678901234", "myapp"));
        assertEquals(401, get("/v1/models", "").statusCode());
    }

    @Test void enforcement_401ResponseBodyContainsAuthError() throws Exception {
        start(Map.of("sk-proxy-validkey12345678901234", "myapp"));
        HttpResponse<String> resp = get("/v1/models");
        assertEquals(401, resp.statusCode());
        assertTrue(resp.body().contains("auth_error"), "Body should contain 'auth_error': " + resp.body());
        assertTrue(resp.body().contains("Invalid or missing API key."), "Body should contain error message: " + resp.body());
    }

    // -- enforcement: accepted --

    @Test void enforcement_validKey_passesThrough() throws Exception {
        String key = "sk-proxy-validkey12345678901234";
        start(Map.of(key, "myapp"));
        assertEquals(200, get("/v1/models", key).statusCode());
    }

    @Test void enforcement_validKey_setsKeyNameAttribute() throws Exception {
        String key = "sk-proxy-validkey12345678901234";
        start(Map.of(key, "cursor"));
        HttpResponse<String> resp = get("/v1/models", key);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("cursor"), "Response should reflect keyName attribute: " + resp.body());
    }

    @Test void enforcement_oneOfMultipleKeys_passesThrough() throws Exception {
        String key1 = "sk-proxy-key1a2b3c4d5e6f7a8b9c0d";
        String key2 = "sk-proxy-key2a2b3c4d5e6f7a8b9c0d";
        start(Map.of(key1, "app1", key2, "app2"));
        assertEquals(200, get("/v1/models", key1).statusCode());
        assertEquals(200, get("/v1/models", key2).statusCode());
    }

    // -- health always open --

    @Test void enforcement_healthAlwaysOpen_noKey() throws Exception {
        start(Map.of("sk-proxy-validkey12345678901234", "myapp"));
        assertEquals(200, get("/health").statusCode());
    }

    @Test void enforcement_healthAlwaysOpen_wrongKey() throws Exception {
        start(Map.of("sk-proxy-validkey12345678901234", "myapp"));
        assertEquals(200, get("/health", "sk-proxy-wrongkey").statusCode());
    }

    // -- admin key --

    @Test void adminKey_passesNormalEndpoints() throws Exception {
        String adminKey = "sk-proxy-adminkey1234567890123456";
        app = buildApp(Map.of("sk-proxy-validkey12345678901234", "myapp"), adminKey);
        app.start("127.0.0.1", 0);
        port = app.port();
        assertEquals(200, get("/v1/models", adminKey).statusCode());
    }

    @Test void adminKey_setsIsAdminAttribute() throws Exception {
        String adminKey = "sk-proxy-adminkey1234567890123456";
        app = buildApp(Map.of("sk-proxy-validkey12345678901234", "myapp"), adminKey);
        app.start("127.0.0.1", 0);
        port = app.port();
        // keyName is not set for admin — response shows "null"
        HttpResponse<String> resp = get("/v1/models", adminKey);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("null"), "Admin keyName should be null: " + resp.body());
    }

    @Test void adminKey_regularKeyStillRejectedWithoutAuth() throws Exception {
        String adminKey = "sk-proxy-adminkey1234567890123456";
        app = buildApp(Map.of("sk-proxy-validkey12345678901234", "myapp"), adminKey);
        app.start("127.0.0.1", 0);
        port = app.port();
        assertEquals(401, get("/v1/models").statusCode());
    }
}
