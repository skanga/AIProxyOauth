package com.aiproxyoauth.provider.anthropic.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicCredentialStoreTest {
    private static final AnthropicCredential CREDENTIAL = new AnthropicCredential(
            "access",
            "refresh",
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-07-30T00:00:00Z")
    );

    @TempDir
    Path temporary;

    @Test
    void roundTripsVersionedCredentialAndAtomicallyOverwrites() throws Exception {
        Path path = temporary.resolve("nested").resolve("anthropic-oauth.json");
        try (AnthropicCredentialStore store = AnthropicCredentialStore.open(path)) {
            assertEquals(Optional.empty(), store.load());

            store.save(CREDENTIAL);
            assertEquals(Optional.of(CREDENTIAL), store.load());

            AnthropicCredential replacement = new AnthropicCredential(
                    "new-access",
                    "new-refresh",
                    CREDENTIAL.expiresAt().plusSeconds(60),
                    CREDENTIAL.updatedAt().plusSeconds(60)
            );
            store.save(replacement);
            assertEquals(Optional.of(replacement), store.load());
        }

        String json = Files.readString(path);
        assertTrue(json.contains("\"version\" : 1"));
        assertTrue(json.contains("\"provider\" : \"anthropic\""));
        assertFalse(json.contains("access_token.tmp"));
        try (var files = Files.list(path.getParent())) {
            assertFalse(files.anyMatch(candidate -> candidate.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsCorruptedOrWrongProviderFiles() throws Exception {
        Path path = temporary.resolve("credentials.json");
        try (AnthropicCredentialStore store = AnthropicCredentialStore.open(path)) {
            Files.writeString(path, "not-json");
            assertThrows(IOException.class, store::load);

            Files.writeString(path, """
                    {"version":1,"provider":"codex","access_token":"a","refresh_token":"r",
                     "expires_at":"2026-08-01T00:00:00Z","updated_at":"2026-07-30T00:00:00Z"}
                    """);
            assertThrows(IOException.class, store::load);
        }
    }

    @Test
    void secondStoreCannotLeaseSameCredentialUntilFirstCloses() throws Exception {
        Path path = temporary.resolve("credentials.json");
        AnthropicCredentialStore first = AnthropicCredentialStore.open(path);
        try {
            IOException error = assertThrows(
                    IOException.class,
                    () -> AnthropicCredentialStore.open(path)
            );
            assertTrue(error.getMessage().contains("already in use"));
        } finally {
            first.close();
        }

        try (AnthropicCredentialStore ignored = AnthropicCredentialStore.open(path)) {
            // Lease was released.
        }
    }

    @Test
    void clearDeletesOnlyCredentialAndKeepsLease() throws Exception {
        Path path = temporary.resolve("credentials.json");
        try (AnthropicCredentialStore store = AnthropicCredentialStore.open(path)) {
            store.save(CREDENTIAL);
            store.clear();
            assertFalse(Files.exists(path));
            assertEquals(Optional.empty(), store.load());
            assertThrows(IOException.class, () -> AnthropicCredentialStore.open(path));
        }
    }

    @Test
    void appliesOwnerOnlyPosixPermissionsWhereSupported() throws Exception {
        Path path = temporary.resolve("credentials.json");
        try (AnthropicCredentialStore store = AnthropicCredentialStore.open(path)) {
            store.save(CREDENTIAL);
        }
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path)
            );
        }
    }
}
