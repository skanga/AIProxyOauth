package com.aiproxyoauth.provider.anthropic.auth;

import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnthropicOAuthClientTest {

    @Test
    void postsRefreshFormAndParsesRotatedToken() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            contentType.set(exchange.getRequestHeaders().getFirst("content-type"));
            respond(exchange, 200, """
                    {"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}
                    """);
        });
        try {
            AnthropicOAuthClient client = client(server);

            OAuthTokenSet token = client.refresh("old refresh");

            assertEquals(new OAuthTokenSet("new-access", "new-refresh", 3600), token);
            assertEquals("application/x-www-form-urlencoded", contentType.get());
            assertEquals(
                    "grant_type=refresh_token&client_id="
                            + AnthropicCompatibilityProfile.claudeCodeOAuth().clientId()
                            + "&refresh_token=old+refresh",
                    requestBody.get()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tokenEndpointErrorsAreTypedAndDoNotExposeSecretResponseText() throws Exception {
        String secret = "refresh-secret-that-must-not-leak";
        HttpServer server = server(exchange ->
                respond(exchange, 400, """
                        {"error":"invalid_grant","error_description":"%s"}
                        """.formatted(secret)));
        try {
            AnthropicOAuthException error = assertThrows(
                    AnthropicOAuthException.class,
                    () -> client(server).refresh(secret)
            );

            assertEquals(AnthropicOAuthException.Kind.API_ERROR, error.kind());
            assertFalse(error.getMessage().contains(secret));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedAndMissingTokenResponsesAreTyped() throws Exception {
        assertEquals(
                AnthropicOAuthException.Kind.BAD_RESPONSE,
                failureFor(200, "not-json").kind()
        );
        assertEquals(
                AnthropicOAuthException.Kind.MISSING_TOKEN,
                failureFor(200, "{}").kind()
        );
    }

    @Test
    void rejectsOversizedChunkedBodyWithoutReturningItsContents() throws Exception {
        String marker = "oversized-secret-marker";
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = (marker + "x".repeat(16 * 1024)).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 80; i++) {
                exchange.getResponseBody().write(chunk);
            }
            exchange.close();
        });
        try {
            AnthropicOAuthException error = assertThrows(
                    AnthropicOAuthException.class,
                    () -> client(server).refresh("refresh")
            );

            assertEquals(AnthropicOAuthException.Kind.RESPONSE_TOO_LARGE, error.kind());
            assertFalse(error.getMessage().contains(marker));
        } finally {
            server.stop(0);
        }
    }

    private static AnthropicOAuthException failureFor(int status, String body) throws Exception {
        HttpServer server = server(exchange -> respond(exchange, status, body));
        try {
            return assertThrows(
                    AnthropicOAuthException.class,
                    () -> client(server).refresh("refresh")
            );
        } finally {
            server.stop(0);
        }
    }

    private static AnthropicOAuthClient client(HttpServer server) {
        AnthropicCompatibilityProfile source =
                AnthropicCompatibilityProfile.claudeCodeOAuth();
        AnthropicCompatibilityProfile profile = new AnthropicCompatibilityProfile(
                source.name(),
                source.clientId(),
                source.authorizationUri(),
                tokenUri(server),
                source.redirectUri(),
                source.messagesUri(),
                source.modelsUri(),
                source.scopes(),
                source.anthropicVersion(),
                source.oauthBeta(),
                source.claudeCodeBeta(),
                source.oauthSystemPreamble()
        );
        return new AnthropicOAuthClient(profile, HttpClient.newHttpClient());
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

    private static void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
