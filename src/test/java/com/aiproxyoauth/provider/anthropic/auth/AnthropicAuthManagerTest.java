package com.aiproxyoauth.provider.anthropic.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicAuthManagerTest {
    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void refreshesOnceAcrossConcurrentCallersAndPersistsRotation() throws Exception {
        MemoryRepository repository = new MemoryRepository(credential("old", "refresh", 60));
        AtomicInteger refreshes = new AtomicInteger();
        TokenRefresher refresher = oldRefresh -> {
            refreshes.incrementAndGet();
            try {
                Thread.sleep(25);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", error);
            }
            return new OAuthTokenSet("new", "rotated", 3600);
        };
        AnthropicAuthManager manager =
                new AnthropicAuthManager(repository, refresher, CLOCK, null);

        List<Callable<String>> calls = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            calls.add(manager::accessToken);
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(calls);
            for (var result : results) {
                assertEquals("new", result.get());
            }
        }

        assertEquals(1, refreshes.get());
        assertEquals("rotated", repository.credential.refreshToken());
        assertEquals(NOW.plusSeconds(3600), repository.credential.expiresAt());
    }

    @Test
    void refreshesAtFiveMinuteBoundaryAndPreservesUnrotatedRefreshToken() throws Exception {
        MemoryRepository repository = new MemoryRepository(credential("old", "keep", 300));
        AnthropicAuthManager manager = new AnthropicAuthManager(
                repository,
                ignored -> new OAuthTokenSet("new", "", 600),
                CLOCK,
                null
        );

        assertEquals("new", manager.accessToken());
        assertEquals("keep", repository.credential.refreshToken());
    }

    @Test
    void publishesRotatedCredentialWhenPersistenceFailsAndRetriesLater() throws Exception {
        MemoryRepository repository = new MemoryRepository(credential("old", "refresh", 60));
        repository.failNextSave = true;
        AtomicInteger refreshes = new AtomicInteger();
        AnthropicAuthManager manager = new AnthropicAuthManager(
                repository,
                ignored -> {
                    refreshes.incrementAndGet();
                    return new OAuthTokenSet("new", "rotated", 3600);
                },
                CLOCK,
                null
        );

        assertEquals("new", manager.accessToken());
        assertTrue(manager.isDegraded());
        assertEquals("new", manager.accessToken());
        assertFalse(manager.isDegraded());
        assertEquals("rotated", repository.credential.refreshToken());
        assertEquals(1, refreshes.get());
    }

    @Test
    void usesStillValidOldTokenWhenRefreshFailsButFailsAfterExpiry() throws Exception {
        TokenRefresher failing = ignored -> {
            throw new IOException("safe refresh failure");
        };
        AnthropicAuthManager valid = new AnthropicAuthManager(
                new MemoryRepository(credential("old", "refresh", 60)),
                failing,
                CLOCK,
                null
        );
        assertEquals("old", valid.accessToken());

        AnthropicAuthManager expired = new AnthropicAuthManager(
                new MemoryRepository(credential("old", "refresh", -1)),
                failing,
                CLOCK,
                null
        );
        AnthropicAuthException error =
                assertThrows(AnthropicAuthException.class, expired::accessToken);
        assertEquals(AnthropicAuthException.Kind.REFRESH_FAILED, error.kind());
        assertFalse(error.getMessage().contains("safe refresh failure"));
    }

    @Test
    void environmentOverrideIsNonRefreshableAndDoesNotNeedStoredCredential() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AnthropicAuthManager manager = new AnthropicAuthManager(
                new MemoryRepository(null),
                ignored -> {
                    refreshes.incrementAndGet();
                    return new OAuthTokenSet("unexpected", "", 60);
                },
                CLOCK,
                "environment-token"
        );

        manager.invalidate();
        assertEquals("environment-token", manager.accessToken());
        assertEquals(0, refreshes.get());
    }

    @Test
    void missingCredentialHasTypedFailure() throws Exception {
        AnthropicAuthManager manager = new AnthropicAuthManager(
                new MemoryRepository(null),
                ignored -> new OAuthTokenSet("unexpected", "", 60),
                CLOCK,
                null
        );

        AnthropicAuthException error =
                assertThrows(AnthropicAuthException.class, manager::accessToken);
        assertEquals(AnthropicAuthException.Kind.MISSING_CREDENTIAL, error.kind());
    }

    private static AnthropicCredential credential(
            String access, String refresh, long expiresInSeconds) {
        return new AnthropicCredential(
                access,
                refresh,
                NOW.plusSeconds(expiresInSeconds),
                NOW.minusSeconds(60)
        );
    }

    private static final class MemoryRepository implements AnthropicCredentialRepository {
        private AnthropicCredential credential;
        private boolean failNextSave;

        private MemoryRepository(AnthropicCredential credential) {
            this.credential = credential;
        }

        @Override
        public Optional<AnthropicCredential> load() {
            return Optional.ofNullable(credential);
        }

        @Override
        public void save(AnthropicCredential credential) throws IOException {
            if (failNextSave) {
                failNextSave = false;
                throw new IOException("disk unavailable");
            }
            this.credential = credential;
        }
    }
}
