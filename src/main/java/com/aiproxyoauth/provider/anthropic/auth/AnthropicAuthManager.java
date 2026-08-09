package com.aiproxyoauth.provider.anthropic.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class AnthropicAuthManager {
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final long MAX_EXPIRY_SECONDS = Duration.ofDays(365).toSeconds();

    private final AnthropicCredentialRepository repository;
    private final TokenRefresher refresher;
    private final Clock clock;
    private final String tokenOverride;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile AnthropicCredential current;
    private volatile AnthropicCredential pendingPersistence;
    private volatile boolean forceRefresh;

    public AnthropicAuthManager(AnthropicCredentialStore store,
                                AnthropicOAuthClient oauthClient,
                                Clock clock,
                                String tokenOverride) throws IOException {
        this(store, oauthClient::refresh, clock, tokenOverride);
    }

    public AnthropicAuthManager(AnthropicCredentialStore store,
                                AnthropicOAuthClient oauthClient,
                                Clock clock,
                                Map<String, String> environment) throws IOException {
        this(
                store,
                oauthClient::refresh,
                clock,
                Objects.requireNonNull(environment, "environment")
                        .get("CLAUDE_CODE_OAUTH_TOKEN")
        );
    }

    AnthropicAuthManager(AnthropicCredentialRepository repository,
                         TokenRefresher refresher,
                         Clock clock,
                         String tokenOverride) throws IOException {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenOverride = tokenOverride == null || tokenOverride.isBlank()
                ? null
                : tokenOverride;
        this.current = this.tokenOverride == null
                ? repository.load().orElse(null)
                : null;
    }

    public String accessToken() throws IOException {
        if (tokenOverride != null) {
            return tokenOverride;
        }

        AnthropicCredential snapshot = current;
        if (snapshot == null) {
            throw new AnthropicAuthException(
                    AnthropicAuthException.Kind.MISSING_CREDENTIAL,
                    "No Anthropic OAuth credential is configured"
            );
        }
        if (!forceRefresh && pendingPersistence == null && !needsRefresh(snapshot, clock.instant())) {
            return snapshot.accessToken();
        }
        return refreshOrPersist();
    }

    public void invalidate() {
        if (tokenOverride == null) {
            forceRefresh = true;
        }
    }

    public boolean isDegraded() {
        return pendingPersistence != null;
    }

    private String refreshOrPersist() throws IOException {
        refreshLock.lock();
        try {
            retryPendingPersistence();

            AnthropicCredential snapshot = current;
            if (snapshot == null) {
                throw new AnthropicAuthException(
                        AnthropicAuthException.Kind.MISSING_CREDENTIAL,
                        "No Anthropic OAuth credential is configured"
                );
            }
            Instant now = clock.instant();
            if (!forceRefresh && !needsRefresh(snapshot, now)) {
                return snapshot.accessToken();
            }

            OAuthTokenSet tokens;
            try {
                tokens = refresher.refresh(snapshot.refreshToken());
            } catch (IOException error) {
                forceRefresh = false;
                if (snapshot.expiresAt().isAfter(now)) {
                    return snapshot.accessToken();
                }
                throw new AnthropicAuthException(
                        AnthropicAuthException.Kind.REFRESH_FAILED,
                        "Anthropic OAuth token refresh failed",
                        error
                );
            }
            if (tokens.expiresInSeconds() > MAX_EXPIRY_SECONDS) {
                throw new AnthropicAuthException(
                        AnthropicAuthException.Kind.REFRESH_FAILED,
                        "Anthropic OAuth token expiration is invalid"
                );
            }

            String refreshToken = tokens.refreshToken().isBlank()
                    ? snapshot.refreshToken()
                    : tokens.refreshToken();
            AnthropicCredential refreshed = new AnthropicCredential(
                    tokens.accessToken(),
                    refreshToken,
                    now.plusSeconds(tokens.expiresInSeconds()),
                    now
            );
            current = refreshed;
            forceRefresh = false;
            try {
                repository.save(refreshed);
            } catch (IOException persistenceError) {
                pendingPersistence = refreshed;
            }
            return refreshed.accessToken();
        } finally {
            refreshLock.unlock();
        }
    }

    private void retryPendingPersistence() {
        AnthropicCredential pending = pendingPersistence;
        if (pending == null) {
            return;
        }
        try {
            repository.save(pending);
            pendingPersistence = null;
        } catch (IOException ignored) {
            // The rotated in-memory token remains authoritative; retry on the next request.
        }
    }

    private static boolean needsRefresh(AnthropicCredential credential, Instant now) {
        return !credential.expiresAt().isAfter(now.plus(REFRESH_MARGIN));
    }
}
