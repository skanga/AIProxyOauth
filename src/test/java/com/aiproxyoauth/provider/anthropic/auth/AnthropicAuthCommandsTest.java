package com.aiproxyoauth.provider.anthropic.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicAuthCommandsTest {
    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void loginVerifiesFlowAndStoresTokensWithoutPrintingSecrets() throws Exception {
        Path path = temporary.resolve("anthropic.json");
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        OAuthLoginFlow flow = new OAuthLoginFlow() {
            @Override
            public AnthropicOAuthLogin.Attempt newAttempt() {
                return new AnthropicOAuthLogin.Attempt(
                        "verifier",
                        "state",
                        URI.create("https://example.test/authorize")
                );
            }

            @Override
            public OAuthTokenSet exchange(
                    String callback, AnthropicOAuthLogin.Attempt attempt) {
                assertEquals("code#state", callback);
                return new OAuthTokenSet("secret-access", "secret-refresh", 3600);
            }
        };
        AnthropicAuthCommands commands = commands(
                path, flow, new FixedInput("code#state", true), output, errors);

        assertEquals(0, commands.login(false));

        try (AnthropicCredentialStore store = AnthropicCredentialStore.open(path)) {
            AnthropicCredential saved = store.load().orElseThrow();
            assertEquals("secret-access", saved.accessToken());
            assertEquals("secret-refresh", saved.refreshToken());
            assertEquals(NOW.plusSeconds(3600), saved.expiresAt());
        }
        assertTrue(output.toString().contains("https://example.test/authorize"));
        assertFalse(output.toString().contains("secret-access"));
        assertFalse(output.toString().contains("secret-refresh"));
        assertEquals("", errors.toString());
    }

    @Test
    void loginInputFailureDoesNotCreateCredential() {
        Path path = temporary.resolve("anthropic.json");
        StringWriter errors = new StringWriter();
        AnthropicAuthCommands.SecureInput input = new AnthropicAuthCommands.SecureInput() {
            @Override
            public String readOAuthCallback(boolean allowStdin) throws IOException {
                assertFalse(allowStdin);
                throw new IOException("explicit stdin opt-in required");
            }

            @Override
            public boolean confirm(String prompt) {
                return false;
            }
        };
        AnthropicAuthCommands commands = commands(
                path,
                successfulFlow(),
                input,
                new StringWriter(),
                errors
        );

        assertEquals(1, commands.login(false));
        assertFalse(Files.exists(path));
        assertTrue(errors.toString().contains("explicit stdin opt-in required"));
    }

    @Test
    void logoutShowsExactPathAndRequiresConfirmationUnlessYes() throws Exception {
        Path path = temporary.resolve("anthropic.json").toAbsolutePath();
        save(path);
        StringWriter cancelledOutput = new StringWriter();
        AnthropicAuthCommands cancelled = commands(
                path,
                successfulFlow(),
                new FixedInput("", false),
                cancelledOutput,
                new StringWriter()
        );

        assertEquals(0, cancelled.logout(false));
        assertTrue(Files.exists(path));
        assertTrue(cancelledOutput.toString().contains(path.toString()));

        AnthropicAuthCommands confirmed = commands(
                path,
                successfulFlow(),
                new FixedInput("", false),
                new StringWriter(),
                new StringWriter()
        );
        assertEquals(0, confirmed.logout(true));
        assertFalse(Files.exists(path));
    }

    private AnthropicAuthCommands commands(
            Path path,
            OAuthLoginFlow flow,
            AnthropicAuthCommands.SecureInput input,
            StringWriter output,
            StringWriter errors) {
        return new AnthropicAuthCommands(
                path,
                flow,
                input,
                ignored -> {
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                new PrintWriter(output),
                new PrintWriter(errors)
        );
    }

    private static OAuthLoginFlow successfulFlow() {
        return new OAuthLoginFlow() {
            @Override
            public AnthropicOAuthLogin.Attempt newAttempt() {
                return new AnthropicOAuthLogin.Attempt(
                        "verifier", "state", URI.create("https://example.test"));
            }

            @Override
            public OAuthTokenSet exchange(
                    String callback, AnthropicOAuthLogin.Attempt attempt) {
                return new OAuthTokenSet("access", "refresh", 3600);
            }
        };
    }

    private static void save(Path path) throws Exception {
        try (AnthropicCredentialStore store = AnthropicCredentialStore.open(path)) {
            store.save(new AnthropicCredential(
                    "access", "refresh", NOW.plusSeconds(3600), NOW));
        }
    }

    private record FixedInput(String callback, boolean confirmation)
            implements AnthropicAuthCommands.SecureInput {
        @Override
        public String readOAuthCallback(boolean allowStdin) {
            return callback;
        }

        @Override
        public boolean confirm(String prompt) {
            return confirmation;
        }
    }
}
