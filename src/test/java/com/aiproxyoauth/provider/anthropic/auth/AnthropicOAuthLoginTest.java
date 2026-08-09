package com.aiproxyoauth.provider.anthropic.auth;

import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicOAuthLoginTest {

    @Test
    void buildsExactClaudePkceAuthorizationUrl() {
        String verifier = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_";
        URI uri = AnthropicOAuthLogin.authorizationUri(
                AnthropicCompatibilityProfile.claudeCodeOAuth(),
                verifier,
                "state with spaces"
        );

        assertEquals(
                "https://claude.ai/oauth/authorize?response_type=code"
                        + "&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e"
                        + "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"
                        + "&scope=user%3Aprofile%20user%3Ainference%20user%3Asessions%3Aclaude_code"
                        + "%20user%3Amcp_servers%20user%3Afile_upload"
                        + "&state=state%20with%20spaces"
                        + "&code_challenge=C3emvhFZpDKWQRqM3t1AsQggBeNFS-nQhKXS5GzAHcY"
                        + "&code_challenge_method=S256&code=true",
                uri.toString()
        );
    }

    @Test
    void newAttemptsUseIndependentVerifierAndStateValues() {
        AnthropicOAuthLogin login = new AnthropicOAuthLogin(
                AnthropicCompatibilityProfile.claudeCodeOAuth(),
                HttpClient.newHttpClient()
        );

        AnthropicOAuthLogin.Attempt first = login.newAttempt();
        AnthropicOAuthLogin.Attempt second = login.newAttempt();

        assertEquals(128, first.verifier().length());
        assertEquals(32, first.state().length());
        assertTrue(first.verifier().matches("[A-Za-z0-9_-]+"));
        assertTrue(first.state().matches("[A-Za-z0-9_-]+"));
        assertNotEquals(first.verifier(), second.verifier());
        assertNotEquals(first.state(), second.state());
    }

    @Test
    void exchangesCodeAndVerifiedReturnedStateUsingExactForm() throws Exception {
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            captured.set(form(exchange));
            respond(exchange, 200, """
                    {"access_token":"access","refresh_token":"refresh","expires_in":3600}
                    """);
        });
        try {
            AnthropicCompatibilityProfile profile = profileWithTokenUri(tokenUri(server));
            AnthropicOAuthLogin login = new AnthropicOAuthLogin(profile, HttpClient.newHttpClient());
            AnthropicOAuthLogin.Attempt attempt =
                    new AnthropicOAuthLogin.Attempt("verifier", "expected-state", URI.create("https://example.test"));

            OAuthTokenSet token = login.exchange("the-code#expected-state", attempt);

            assertEquals(new OAuthTokenSet("access", "refresh", 3600), token);
            assertEquals(Map.of(
                    "grant_type", "authorization_code",
                    "code", "the-code",
                    "client_id", profile.clientId(),
                    "redirect_uri", profile.redirectUri().toString(),
                    "code_verifier", "verifier",
                    "state", "expected-state"
            ), captured.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsStateMismatchBeforeCallingTokenEndpoint() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 500, "{}");
        });
        try {
            AnthropicOAuthLogin login = new AnthropicOAuthLogin(
                    profileWithTokenUri(tokenUri(server)),
                    HttpClient.newHttpClient()
            );
            AnthropicOAuthLogin.Attempt attempt =
                    new AnthropicOAuthLogin.Attempt("verifier", "expected", URI.create("https://example.test"));

            AnthropicOAuthException error = assertThrows(
                    AnthropicOAuthException.class,
                    () -> login.exchange("code#wrong", attempt)
            );

            assertEquals(AnthropicOAuthException.Kind.STATE_MISMATCH, error.kind());
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCallbackWithoutReturnedState() {
        AnthropicOAuthLogin login = new AnthropicOAuthLogin(
                AnthropicCompatibilityProfile.claudeCodeOAuth(),
                HttpClient.newHttpClient()
        );
        AnthropicOAuthLogin.Attempt attempt =
                new AnthropicOAuthLogin.Attempt("verifier", "expected", URI.create("https://example.test"));

        assertThrows(
                AnthropicOAuthException.class,
                () -> login.exchange("code-only", attempt)
        );
    }

    private static AnthropicCompatibilityProfile profileWithTokenUri(URI tokenUri) {
        AnthropicCompatibilityProfile source =
                AnthropicCompatibilityProfile.claudeCodeOAuth();
        return new AnthropicCompatibilityProfile(
                source.name(),
                source.clientId(),
                source.authorizationUri(),
                tokenUri,
                source.redirectUri(),
                source.messagesUri(),
                source.modelsUri(),
                source.scopes(),
                source.anthropicVersion(),
                source.oauthBeta(),
                source.claudeCodeBeta(),
                source.oauthSystemPreamble()
        );
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", handler);
        server.start();
        return server;
    }

    private static URI tokenUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    }

    private static Map<String, String> form(HttpExchange exchange) throws java.io.IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return Arrays.stream(body.split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                ));
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
