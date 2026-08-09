package com.aiproxyoauth.provider.anthropic.auth;

import com.aiproxyoauth.provider.anthropic.AnthropicCompatibilityProfile;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class AnthropicAuthCommands {
    private static final long MAX_EXPIRY_SECONDS = Duration.ofDays(365).toSeconds();

    private final Path credentialPath;
    private final OAuthLoginFlow loginFlow;
    private final SecureInput input;
    private final BrowserLauncher browser;
    private final Clock clock;
    private final PrintWriter out;
    private final PrintWriter err;

    AnthropicAuthCommands(Path credentialPath,
                          OAuthLoginFlow loginFlow,
                          SecureInput input,
                          BrowserLauncher browser,
                          Clock clock,
                          PrintWriter out,
                          PrintWriter err) {
        this.credentialPath = Objects.requireNonNull(credentialPath, "credentialPath")
                .toAbsolutePath()
                .normalize();
        this.loginFlow = Objects.requireNonNull(loginFlow, "loginFlow");
        this.input = Objects.requireNonNull(input, "input");
        this.browser = Objects.requireNonNull(browser, "browser");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    public static AnthropicAuthCommands system(
            Path credentialPath, PrintWriter out, PrintWriter err) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new AnthropicAuthCommands(
                credentialPath,
                new AnthropicOAuthLogin(
                        AnthropicCompatibilityProfile.claudeCodeOAuth(),
                        client
                ),
                new SystemSecureInput(),
                AnthropicAuthCommands::openBrowserIfSupported,
                Clock.systemUTC(),
                out,
                err
        );
    }

    public int login(boolean allowStdinOAuthCode) {
        try (AnthropicCredentialStore store = AnthropicCredentialStore.open(credentialPath)) {
            AnthropicOAuthLogin.Attempt attempt = loginFlow.newAttempt();
            out.println("Open this URL to authorize Claude:");
            out.println(attempt.authorizationUri());
            out.flush();
            browser.open(attempt.authorizationUri());

            String callback = input.readOAuthCallback(allowStdinOAuthCode);
            OAuthTokenSet tokens = loginFlow.exchange(callback, attempt);
            if (tokens.expiresInSeconds() > MAX_EXPIRY_SECONDS) {
                throw new IOException("OAuth response expiration is outside the supported range");
            }
            if (tokens.refreshToken().isBlank()) {
                throw new IOException("OAuth login response did not include a refresh token");
            }
            Instant now = clock.instant();
            store.save(new AnthropicCredential(
                    tokens.accessToken(),
                    tokens.refreshToken(),
                    now.plusSeconds(tokens.expiresInSeconds()),
                    now
            ));
            if (store.load().isEmpty()) {
                throw new IOException("Saved Anthropic credentials could not be reloaded");
            }
            out.println("Anthropic OAuth login saved to " + credentialPath);
            out.flush();
            return 0;
        } catch (IOException | RuntimeException error) {
            err.println("Anthropic OAuth login failed: " + safeMessage(error));
            err.flush();
            return 1;
        }
    }

    public int logout(boolean yes) {
        out.println("Anthropic credential file: " + credentialPath);
        out.flush();
        try (AnthropicCredentialStore store = AnthropicCredentialStore.open(credentialPath)) {
            if (!yes && !input.confirm("Delete this Anthropic credential file? [y/N] ")) {
                out.println("Anthropic logout cancelled.");
                out.flush();
                return 0;
            }
            store.clear();
            out.println("Anthropic OAuth credentials removed.");
            out.flush();
            return 0;
        } catch (IOException | RuntimeException error) {
            err.println("Anthropic logout failed: " + safeMessage(error));
            err.flush();
            return 1;
        }
    }

    private static String safeMessage(Exception error) {
        if (error instanceof AnthropicOAuthException oauthError) {
            return oauthError.getMessage();
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static void openBrowserIfSupported(URI uri) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (IOException | RuntimeException ignored) {
            // The printed URL remains the reliable fallback.
        }
    }

    interface SecureInput {
        String readOAuthCallback(boolean allowStdin) throws IOException;

        boolean confirm(String prompt) throws IOException;
    }

    @FunctionalInterface
    interface BrowserLauncher {
        void open(URI uri);
    }

    private static final class SystemSecureInput implements SecureInput {
        @Override
        public String readOAuthCallback(boolean allowStdin) throws IOException {
            Console console = System.console();
            if (console != null) {
                char[] value = console.readPassword("Paste the returned code#state: ");
                return value == null ? null : new String(value);
            }
            if (!allowStdin) {
                throw new IOException(
                        "No interactive console is available; pass --allow-stdin-oauth-code "
                                + "only if stdin is suitably protected"
                );
            }
            return new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8)
            ).readLine();
        }

        @Override
        public boolean confirm(String prompt) throws IOException {
            Console console = System.console();
            if (console == null) {
                throw new IOException("No interactive console is available; pass --yes to confirm");
            }
            String response = console.readLine("%s", prompt);
            return response != null
                    && ("y".equalsIgnoreCase(response.strip())
                    || "yes".equalsIgnoreCase(response.strip()));
        }
    }
}
