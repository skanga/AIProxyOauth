package com.aiproxyoauth.model;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.aiproxyoauth.provider.anthropic.AnthropicHttpClient;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicAuthManager;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicCredentialStore;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicOAuthClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicModelResolverTest {
    @TempDir
    Path temporary;

    @Test
    void discoversAndCachesClaudeModelsWithAliases() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, """
                    {"data":[
                      {"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"},
                      {"id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5"}
                    ]}
                    """);
        });
        try (Fixture fixture = fixture(server)) {
            AnthropicModelResolver resolver = new AnthropicModelResolver(
                    fixture.transport, fixture.profile, List.of(), Clock.systemUTC());

            var first = resolver.resolveModels();
            var second = resolver.resolveModels();

            assertEquals(2, first.size());
            assertEquals(ProviderId.ANTHROPIC, first.getFirst().provider());
            assertTrue(first.getFirst().aliases().contains("anthropic/sonnet"));
            assertEquals(first, second);
            assertEquals(1, calls.get());
            assertEquals(AnthropicModelResolver.Source.CACHE, resolver.source());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void familyAliasIsAssignedOnlyToPreferredDiscoveredVersion() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, """
                {"data":[
                  {"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"},
                  {"id":"claude-sonnet-4-0","display_name":"Claude Sonnet 4.0"}
                ]}
                """));
        try (Fixture fixture = fixture(server)) {
            AnthropicModelResolver resolver = new AnthropicModelResolver(
                    fixture.transport, fixture.profile, List.of(), Clock.systemUTC());

            var models = resolver.resolveModels();

            assertTrue(models.getFirst().aliases().contains("anthropic/sonnet"));
            assertTrue(models.get(1).aliases().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredModelsOverrideDiscovery() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 401, """
                {"type":"error","error":{"type":"authentication_error","message":"expired"}}
                """));
        try (Fixture fixture = fixture(server)) {
            AnthropicModelResolver resolver = new AnthropicModelResolver(
                    fixture.transport, fixture.profile, List.of("claude-configured"),
                    Clock.systemUTC());

            var models = resolver.resolveModels();

            assertEquals(List.of("claude-configured"), models.stream().map(m -> m.id()).toList());
            assertEquals(AnthropicModelResolver.Source.CONFIGURED_FALLBACK, resolver.source());
            assertTrue(resolver.lastFailure().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesLastGoodAfterRefreshFailureThenSeedWhenNoLastGood() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 200, """
                        {"data":[{"id":"claude-live","display_name":"Claude Live"}]}
                        """);
            } else {
                respond(exchange, 500, "{}");
            }
        });
        try (Fixture fixture = fixture(server)) {
            AnthropicModelResolver resolver = new AnthropicModelResolver(
                    fixture.transport, fixture.profile, List.of(), Clock.systemUTC(), Duration.ZERO);
            assertEquals("claude-live", resolver.resolveModels().getFirst().id());
            assertEquals("claude-live", resolver.resolveModels().getFirst().id());
            assertEquals(AnthropicModelResolver.Source.LAST_GOOD, resolver.source());
        } finally {
            server.stop(0);
        }

        HttpServer failing = server(exchange -> respond(exchange, 500, "{}"));
        try (Fixture fixture = fixture(failing)) {
            AnthropicModelResolver resolver = new AnthropicModelResolver(
                    fixture.transport, fixture.profile, List.of(), Clock.systemUTC());
            assertTrue(resolver.resolveModels().stream()
                    .allMatch(model -> model.provider() == ProviderId.ANTHROPIC));
            assertEquals(AnthropicModelResolver.Source.SEED_FALLBACK, resolver.source());
        } finally {
            failing.stop(0);
        }
    }

    @Test
    void oversizedCatalogUsesSeedFallback() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = "x".repeat(16 * 1024).getBytes(StandardCharsets.UTF_8);
            for (int index = 0; index < 70; index++) {
                exchange.getResponseBody().write(chunk);
            }
            exchange.close();
        });
        try (Fixture fixture = fixture(server)) {
            AnthropicModelResolver resolver = new AnthropicModelResolver(
                    fixture.transport, fixture.profile, List.of(), Clock.systemUTC());

            resolver.resolveModels();

            assertEquals(AnthropicModelResolver.Source.SEED_FALLBACK, resolver.source());
            assertEquals(
                    AnthropicModelResolver.FailureKind.INVALID_RESPONSE,
                    resolver.lastFailure().orElseThrow().kind()
            );
        } finally {
            server.stop(0);
        }
    }

    private Fixture fixture(HttpServer server) throws Exception {
        AnthropicCompatibilityProfile source = AnthropicCompatibilityProfile.claudeCodeOAuth();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AnthropicCompatibilityProfile profile = new AnthropicCompatibilityProfile(
                source.name(), source.clientId(), source.authorizationUri(),
                URI.create(base + "/token"), source.redirectUri(),
                URI.create(base + "/messages"), URI.create(base + "/models?limit=100"),
                source.scopes(), source.anthropicVersion(), source.oauthBeta(),
                source.claudeCodeBeta(), source.oauthSystemPreamble()
        );
        HttpClient http = HttpClient.newHttpClient();
        AnthropicCredentialStore store =
                AnthropicCredentialStore.open(temporary.resolve("credential-" + server.getAddress().getPort()));
        AnthropicAuthManager auth = new AnthropicAuthManager(
                store, new AnthropicOAuthClient(profile, http), Clock.systemUTC(), "token");
        return new Fixture(
                profile,
                new AnthropicHttpClient(
                        profile, http, auth, new RequestLogger(false, temporary.resolve("logs"))),
                store
        );
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", handler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Fixture(
            AnthropicCompatibilityProfile profile,
            AnthropicHttpClient transport,
            AnthropicCredentialStore store
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            store.close();
        }
    }
}
