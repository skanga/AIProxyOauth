package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.model.ModelCatalog;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.anthropic.AnthropicRequestOptions;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void proxyServer_acceptsCompositeReadyModelCatalog() throws Exception {
        ModelCatalog catalog = new ModelCatalog() {
            @Override
            public List<ProviderModel> resolveModels() {
                return List.of(new ProviderModel(
                        "claude-sonnet-4-5",
                        "Claude Sonnet 4.5",
                        ProviderId.ANTHROPIC,
                        List.of("anthropic/sonnet"),
                        Optional.of(true),
                        200_000
                ));
            }
        };
        ProxyServer server = new ProxyServer(
                minimalConfig(),
                client,
                catalog,
                usageTracker,
                new ApiKeyStore(Map.of(), null, null)
        );
        server.getApp().start(0);

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    req(server.getApp().port(), "/v1/models"),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"id\":\"claude-sonnet-4-5\""));
            assertTrue(response.body().contains("\"owned_by\":\"anthropic-oauth\""));
        } finally {
            server.getApp().stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void proxyServer_routesClaudeResponsesThroughAnthropicBackend() throws Exception {
        ModelCatalog catalog = () -> List.of(new ProviderModel(
                "claude-sonnet-4-5", "Claude Sonnet 4.5", ProviderId.ANTHROPIC,
                List.of("sonnet"), Optional.of(true), 200_000));
        AnthropicHttpClient anthropic = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(200);
        when(upstream.body()).thenReturn(new ByteArrayInputStream("""
                event: message_start
                data: {"message":{"id":"msg_proxy","model":"claude-sonnet-4-5","usage":{"input_tokens":2,"output_tokens":0}}}

                event: content_block_start
                data: {"index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"index":0,"delta":{"type":"text_delta","text":"routed"}}

                event: content_block_stop
                data: {"index":0}

                event: message_delta
                data: {"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {}

                """.getBytes(StandardCharsets.UTF_8)));
        when(anthropic.request(any(URI.class), eq("POST"), anyString(), anyMap()))
                .thenReturn(upstream);
        ProxyServer server = new ProxyServer(
                minimalConfig(), client, catalog, new UsageTracker(),
                new ApiKeyStore(Map.of(), null, null), anthropic,
                AnthropicCompatibilityProfile.claudeCodeOAuth(), ProviderId.ANTHROPIC);
        server.getApp().start(0);

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    post(server.getApp().port(), "/v1/responses", """
                            {"model":"claude-sonnet-4-5","input":"hello"}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("routed"));
            verify(anthropic).request(any(URI.class), eq("POST"),
                    contains("claude-sonnet-4-5"), anyMap());
        } finally {
            server.getApp().stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void codexOnlyChatStripsProviderQualifierBeforeUpstream() throws Exception {
        ModelCatalog catalog = codexCatalog("gpt-5.6-sol");
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(400);
        when(upstream.body()).thenReturn(new ByteArrayInputStream(
                "{\"detail\":\"probe response\"}".getBytes(StandardCharsets.UTF_8)));
        when(client.request(eq("/responses"), eq("POST"), anyString(), any()))
                .thenReturn(upstream);
        ProxyServer server = new ProxyServer(
                minimalConfig(), client, catalog, new UsageTracker(),
                new ApiKeyStore(Map.of(), null, null), null, null, ProviderId.CODEX);
        server.getApp().start(0);

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    post(server.getApp().port(), "/v1/chat/completions", """
                            {"model":"codex/gpt-5.6-sol","stream":true,
                             "messages":[{"role":"user","content":"hello"}]}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(client).request(eq("/responses"), eq("POST"), body.capture(), any());
            assertEquals("gpt-5.6-sol",
                    com.aiproxyoauth.util.Json.MAPPER.readTree(body.getValue()).path("model").asText());
            assertEquals(400, response.statusCode());
        } finally {
            server.getApp().stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void codexOnlyResponsesStripsProviderQualifierBeforeUpstream() throws Exception {
        ModelCatalog catalog = codexCatalog("gpt-5.6-sol");
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(400);
        when(upstream.body()).thenReturn(new ByteArrayInputStream(
                "{\"detail\":\"probe response\"}".getBytes(StandardCharsets.UTF_8)));
        when(client.request(eq("/responses"), eq("POST"), anyString(), any()))
                .thenReturn(upstream);
        ProxyServer server = new ProxyServer(
                minimalConfig(), client, catalog, new UsageTracker(),
                new ApiKeyStore(Map.of(), null, null), null, null, ProviderId.CODEX);
        server.getApp().start(0);

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    post(server.getApp().port(), "/v1/responses", """
                            {"model":"codex/gpt-5.6-sol","input":"hello"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(client).request(eq("/responses"), eq("POST"), body.capture(), any());
            assertEquals("gpt-5.6-sol",
                    com.aiproxyoauth.util.Json.MAPPER.readTree(body.getValue()).path("model").asText());
            assertEquals(400, response.statusCode());
        } finally {
            server.getApp().stop();
        }
    }

    @Test
    void codexOnlyRejectsQualifiedAnthropicModelAsDisabled() throws Exception {
        ProxyServer server = new ProxyServer(
                minimalConfig(), client, codexCatalog("gpt-5.6-sol"), new UsageTracker(),
                new ApiKeyStore(Map.of(), null, null), null, null, ProviderId.CODEX);
        server.getApp().start(0);

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    post(server.getApp().port(), "/v1/chat/completions", """
                            {"model":"anthropic/claude-sonnet-4-6",
                             "messages":[{"role":"user","content":"hello"}]}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("provider_not_enabled"), response.body());
            verifyNoInteractions(client);
        } finally {
            server.getApp().stop();
        }
    }

    private static ModelCatalog codexCatalog(String model) {
        return () -> List.of(new ProviderModel(
                model, model, ProviderId.CODEX, List.of(), Optional.empty(), 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void matchedAnthropic404PreservesUpstreamErrorInsteadOfBecomingRouteNotFound()
            throws Exception {
        ModelCatalog catalog = () -> List.of(new ProviderModel(
                "claude-opus-5", "Claude Opus 5", ProviderId.ANTHROPIC,
                List.of(), Optional.of(true), 200_000));
        AnthropicHttpClient anthropic = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(404);
        when(upstream.body()).thenReturn(new ByteArrayInputStream("""
                {"type":"error","error":{"type":"not_found_error",
                 "message":"Model is not available for this account"}}
                """.getBytes(StandardCharsets.UTF_8)));
        when(anthropic.request(any(URI.class), eq("POST"), anyString(), anyMap()))
                .thenReturn(upstream);
        ProxyServer server = new ProxyServer(
                minimalConfig(), client, catalog, new UsageTracker(),
                new ApiKeyStore(Map.of(), null, null), anthropic,
                AnthropicCompatibilityProfile.claudeCodeOAuth(), ProviderId.ANTHROPIC);
        server.getApp().start(0);

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    post(server.getApp().port(), "/v1/chat/completions", """
                            {"model":"claude-opus-5","stream":true,
                             "messages":[{"role":"user","content":"hello"}]}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(404, response.statusCode());
            assertTrue(response.body().contains("Model is not available for this account"));
            assertFalse(response.body().contains("Route not found."));
        } finally {
            server.getApp().stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeMessagesAcceptsXApiKeyAndNeverForwardsItUpstream() throws Exception {
        String proxyKey = "sk-proxy-native123456789012345678";
        ModelCatalog catalog = () -> List.of(new ProviderModel(
                "claude-sonnet-4-5", "Claude Sonnet", ProviderId.ANTHROPIC,
                List.of(), Optional.of(true), 200_000));
        AnthropicHttpClient anthropic = mock(AnthropicHttpClient.class);
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(200);
        when(upstream.headers()).thenReturn(java.net.http.HttpHeaders.of(
                Map.of("content-type", List.of("application/json")), (a, b) -> true));
        when(upstream.body()).thenReturn(new ByteArrayInputStream("""
                {"id":"msg_1","type":"message","content":[],
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """.getBytes(StandardCharsets.UTF_8)));
        when(anthropic.request(any(URI.class), eq("POST"), anyString(),
                any(AnthropicRequestOptions.class))).thenReturn(upstream);
        ProxyServer server = new ProxyServer(
                minimalConfig(), client, catalog, new UsageTracker(),
                new ApiKeyStore(Map.of(proxyKey, "native"), null, null), anthropic,
                AnthropicCompatibilityProfile.claudeCodeOAuth(), ProviderId.ANTHROPIC);
        server.getApp().start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getApp().port() + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .header("x-api-key", proxyKey)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"model":"claude-sonnet-4-5","max_tokens":10,
                             "messages":[{"role":"user","content":"hi"}]}
                            """))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            ArgumentCaptor<AnthropicRequestOptions> options =
                    ArgumentCaptor.forClass(AnthropicRequestOptions.class);
            verify(anthropic).request(any(URI.class), eq("POST"), anyString(), options.capture());
            assertFalse(options.getValue().headers().containsKey("x-api-key"));
        } finally {
            server.getApp().stop();
        }
    }

    @Test
    void nativeMessagesRejectsConflictingClientCredentialsWithNativeError() throws Exception {
        String first = "sk-proxy-first123456789012345678";
        String second = "sk-proxy-second12345678901234567";
        ProxyServer server = new ProxyServer(
                minimalConfig(), client, (ModelCatalog) () -> List.of(), new UsageTracker(),
                new ApiKeyStore(Map.of(first, "first", second, "second"), null, null));
        server.getApp().start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getApp().port() + "/v1/messages"))
                    .header("Authorization", "Bearer " + first)
                    .header("x-api-key", second)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
            assertTrue(response.body().contains("authentication_error"));
            assertTrue(response.body().contains("\"type\":\"error\""));
        } finally {
            server.getApp().stop();
        }
    }

    @Test
    void nativeMessagesReturnsNativeUnavailableWhenAnthropicIsDisabled() throws Exception {
        ProxyServer server = new ProxyServer(
                minimalConfig(), client, (ModelCatalog) () -> List.of(), new UsageTracker(),
                new ApiKeyStore(Map.of(), null, null));
        server.getApp().start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getApp().port() + "/v1/messages"))
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("api_error"));
        } finally {
            server.getApp().stop();
        }
    }

    @Test
    void proxyServer_accessLogOmitsHeadersAndQueryString() throws Exception {
        ServerConfig config = minimalConfig();
        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null));
        server.getApp().start(0);
        int port = server.getApp().port();

        java.io.PrintStream originalOut = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health?token=secret"))
                    .header("Authorization", "Bearer sk-proxy-secret")
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
        } finally {
            System.setOut(originalOut);
            server.getApp().stop();
        }

        String log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("GET /health 200"), log);
        assertTrue(log.matches("(?s).*id=req_[0-9a-f]{32}.*"), log);
        assertTrue(log.contains("mode=internal"), log);
        assertTrue(log.contains("req_bytes=0"), log);
        assertTrue(log.contains("status=200"), log);
        assertTrue(log.matches("(?s).*resp_bytes=\\d+.*"), log);
        assertFalse(log.contains("Authorization"), log);
        assertFalse(log.contains("sk-proxy-secret"), log);
        assertFalse(log.contains("token=secret"), log);
    }

    @Test
    void proxyServer_accessLogStatusPrefersUpstreamStatus() throws Exception {
        ServerConfig config = minimalConfig();
        ProxyServer server = new ProxyServer(config, client, modelResolver, usageTracker, new ApiKeyStore(Map.of(), null, null));
        String upstreamBody = """
                {"error":{"message":"usage limit","type":"not_found"}}
                """;
        @SuppressWarnings("unchecked")
        HttpResponse<java.io.InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(404);
        when(upstream.body()).thenReturn(new java.io.ByteArrayInputStream(upstreamBody.getBytes(StandardCharsets.UTF_8)));
        when(client.request(eq("/responses"), eq("POST"), anyString(), any())).thenReturn(upstream);

        server.getApp().start(0);
        int port = server.getApp().port();

        java.io.PrintStream originalOut = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/responses"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"input\":[]}"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, response.statusCode());
        } finally {
            System.setOut(originalOut);
            server.getApp().stop();
        }

        String log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("POST /v1/responses 429"), log);
        assertTrue(log.contains("status=404"), log);
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
