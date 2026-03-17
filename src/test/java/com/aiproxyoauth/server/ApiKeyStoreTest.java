package com.aiproxyoauth.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyStoreTest {

    // --- core: lookup / isEnforcing ---

    @Test void lookup_findsInlineKey() {
        ApiKeyStore store = new ApiKeyStore(Map.of("sk-1", "alice"), null, null);
        assertEquals("alice", store.lookup("sk-1"));
    }

    @Test void lookup_returnsNullForUnknownKey() {
        ApiKeyStore store = new ApiKeyStore(Map.of("sk-1", "alice"), null, null);
        assertNull(store.lookup("sk-unknown"));
    }

    @Test void adminKey_returnsCliAdminKey() {
        ApiKeyStore store = new ApiKeyStore(Map.of(), null, "sk-admin");
        assertEquals("sk-admin", store.adminKey());
    }

    @Test void adminKey_notInLookupMap() {
        ApiKeyStore store = new ApiKeyStore(Map.of("sk-admin", "admin"), null, null);
        assertEquals("sk-admin", store.adminKey());
        assertNull(store.lookup("sk-admin"));
    }

    @Test void isEnforcing_trueWhenKeysPresent() {
        ApiKeyStore store = new ApiKeyStore(Map.of("sk-1", "alice"), null, null);
        assertTrue(store.isEnforcing());
    }

    @Test void isEnforcing_trueWhenOnlyAdminKey() {
        ApiKeyStore store = new ApiKeyStore(Map.of(), null, "sk-admin");
        assertTrue(store.isEnforcing());
    }

    @Test void isEnforcing_falseWhenEmpty() {
        ApiKeyStore store = new ApiKeyStore(Map.of(), null, null);
        assertFalse(store.isEnforcing());
    }

    // --- reload ---

    @Test void reload_picksUpNewFileKey(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "sk-2\n");
        ApiKeyStore store = new ApiKeyStore(Map.of("sk-1", "sk-1"), file.toString(), null);
        store.reload();
        assertEquals("sk-2", store.lookup("sk-2"));
    }

    @Test void reload_inlineKeys_surviveBadFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "sk-file\n");
        ApiKeyStore store = new ApiKeyStore(Map.of("sk-inline", "alice"), file.toString(), null);
        store.reload();

        Files.writeString(file, "");
        PrintStream saved = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            store.reload();
        } finally {
            System.setErr(saved);
        }

        assertEquals("alice", store.lookup("sk-inline"));
        assertEquals("sk-file", store.lookup("sk-file"));
    }

    @Test void reload_removesOldFileKey_whenFileUpdated(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "sk-old\n");
        ApiKeyStore store = new ApiKeyStore(Map.of(), file.toString(), null);
        store.reload();
        assertNotNull(store.lookup("sk-old"));

        Files.writeString(file, "sk-new\n");
        store.reload();
        assertNull(store.lookup("sk-old"));
        assertNotNull(store.lookup("sk-new"));
    }

    @Test void reload_missingFile_keepsExistingKeys(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "sk-1\n");
        ApiKeyStore store = new ApiKeyStore(Map.of(), file.toString(), null);
        store.reload();
        assertNotNull(store.lookup("sk-1"));

        Files.delete(file);
        PrintStream saved = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            store.reload();
        } finally {
            System.setErr(saved);
        }
        assertNotNull(store.lookup("sk-1"));
    }

    @Test void reload_extractsAdminFromFile_whenNoCliAdminKey(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "sk-a\nadmin:sk-admin\n");
        ApiKeyStore store = new ApiKeyStore(Map.of(), file.toString(), null);
        store.reload();
        assertEquals("sk-admin", store.adminKey());
        assertNull(store.lookup("sk-admin"));
    }

    @Test void reload_cliAdminKey_winsOverFileAdmin(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "admin:sk-file-admin\n");
        ApiKeyStore store = new ApiKeyStore(Map.of(), file.toString(), "sk-cli-admin");
        store.reload();
        assertEquals("sk-cli-admin", store.adminKey());
    }

    // --- reloadIfFileChanged ---

    @Test void reloadIfFileChanged_reloadsWhenFileIsNewer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "sk-1\n");
        ApiKeyStore store = new ApiKeyStore(Map.of(), file.toString(), null);
        store.reload();

        Thread.sleep(50);
        Files.writeString(file, "sk-2\n");
        java.nio.file.attribute.FileTime future =
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000);
        Files.setLastModifiedTime(file, future);

        store.reloadIfFileChanged();
        assertNotNull(store.lookup("sk-2"));
    }

    @Test void reloadIfFileChanged_noReloadWhenUnchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "sk-1\n");
        ApiKeyStore store = new ApiKeyStore(Map.of(), file.toString(), null);
        store.reload();

        Files.writeString(file, "sk-2\n");
        Files.setLastModifiedTime(file, store.lastModified());
        store.reloadIfFileChanged();

        assertNotNull(store.lookup("sk-1"));
        assertNull(store.lookup("sk-2"));
    }

    // --- WatchService ---

    @Test void startWatching_reloadsOnFileChange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys.txt");
        Files.writeString(file, "sk-1\n");
        ApiKeyStore store = new ApiKeyStore(Map.of(), file.toString(), null);
        store.reload();
        store.startWatching();

        Thread.sleep(200);
        Files.writeString(file, "sk-1\nsk-2\n");
        Thread.sleep(500);

        assertNotNull(store.lookup("sk-2"), "WatchService should have reloaded the new key");
        store.stopWatching();
    }

    @Test void stopWatching_doesNotThrow() {
        ApiKeyStore store = new ApiKeyStore(Map.of(), null, null);
        assertDoesNotThrow(store::stopWatching);
    }
}
