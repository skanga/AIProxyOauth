package com.aiproxyoauth.provider.anthropic;

import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicAuthManager;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicCredential;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicCredentialStore;
import com.aiproxyoauth.provider.anthropic.auth.AnthropicOAuthClient;
import com.aiproxyoauth.transport.BoundedBodyReader;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnthropicHttpClientTest {
    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void addsExactOauthCompatibilityHeadersWithoutApiKey() throws Exception {
        AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/messages", exchange -> {
            headers.set(exchange.getRequestHeaders());
            respond(exchange, 200, "{}");
        });
        server.start();
        try (Fixture fixture = fixture(server, "override-token")) {
            var response = fixture.transport.request(
                    fixture.profile.messagesUri(), "POST", "{}", Map.of("content-type", "application/json"));
            BoundedBodyReader.read(response, 1024);

            assertEquals("Bearer override-token", first(headers.get(), "Authorization"));
            assertEquals("application/json", first(headers.get(), "Content-Type"));
            assertEquals("2023-06-01", first(headers.get(), "anthropic-version"));
            assertEquals(
                    "claude-code-20250219,oauth-2025-04-20",
                    first(headers.get(), "anthropic-beta")
            );
            assertEquals("true", first(headers.get(), "anthropic-dangerous-direct-browser-access"));
            assertEquals("AIProxyOauth", first(headers.get(), "x-app"));
            assertFalse(headers.get().containsKey("x-api-key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshesAndRetriesExactlyOnceOnPreBody401() throws Exception {
        AtomicInteger messageCalls = new AtomicInteger();
        List<String> authorizations = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/messages", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int call = messageCalls.incrementAndGet();
            respond(exchange, call == 1 ? 401 : 200, call == 1 ? """
                    {"type":"error","error":{"type":"authentication_error","message":"expired"}}
                    """ : "{}");
        });
        server.createContext("/token", exchange -> respond(exchange, 200, """
                {"access_token":"new-access","refresh_token":"rotated","expires_in":3600}
                """));
        server.start();

        AnthropicCompatibilityProfile profile = profile(server);
        try (AnthropicCredentialStore store =
                     AnthropicCredentialStore.open(temporary.resolve("credential.json"))) {
            store.save(new AnthropicCredential(
                    "old-access", "refresh", NOW.plusSeconds(3600), NOW));
            HttpClient http = HttpClient.newHttpClient();
            AnthropicAuthManager auth = new AnthropicAuthManager(
                    store,
                    new AnthropicOAuthClient(profile, http),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    (String) null
            );
            AnthropicHttpClient transport = new AnthropicHttpClient(
                    profile, http, auth, new RequestLogger(false, temporary.resolve("logs")));

            var response = transport.request(profile.messagesUri(), "POST", "{}", Map.of());
            BoundedBodyReader.read(response, 1024);

            assertEquals(200, response.statusCode());
            assertEquals(List.of("Bearer old-access", "Bearer new-access"), authorizations);
            assertEquals(2, messageCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCallerOverridesOfAuthenticationHeaders() throws Exception {
        HttpServer server = server();
        server.createContext("/messages", exchange -> respond(exchange, 200, "{}"));
        server.start();
        try (Fixture fixture = fixture(server, "override-token")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.transport.request(
                            fixture.profile.messagesUri(),
                            "GET",
                            null,
                            Map.of("Authorization", "Bearer attacker-controlled")
                    )
            );
        } finally {
            server.stop(0);
        }
    }

    private Fixture fixture(HttpServer server, String override) throws Exception {
        AnthropicCompatibilityProfile profile = profile(server);
        AnthropicCredentialStore store =
                AnthropicCredentialStore.open(temporary.resolve("override.json"));
        HttpClient http = HttpClient.newHttpClient();
        AnthropicAuthManager auth = new AnthropicAuthManager(
                store, new AnthropicOAuthClient(profile, http),
                Clock.fixed(NOW, ZoneOffset.UTC), override);
        return new Fixture(
                profile,
                new AnthropicHttpClient(
                        profile, http, auth, new RequestLogger(false, temporary.resolve("logs"))),
                store
        );
    }

    private static AnthropicCompatibilityProfile profile(HttpServer server) {
        AnthropicCompatibilityProfile source = AnthropicCompatibilityProfile.claudeCodeOAuth();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AnthropicCompatibilityProfile(
                source.name(), source.clientId(), source.authorizationUri(),
                URI.create(base + "/token"), source.redirectUri(),
                URI.create(base + "/messages"), URI.create(base + "/models?limit=100"),
                source.scopes(), source.anthropicVersion(), source.oauthBeta(),
                source.claudeCodeBeta(), source.oauthSystemPreamble()
        );
    }

    private static HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static String first(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst().orElseThrow().getValue().getFirst();
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
