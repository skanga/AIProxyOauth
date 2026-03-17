package com.aiproxyoauth.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    private static ServerConfig minimal(Map<String, String> apiKeys) {
        return new ServerConfig(
                "127.0.0.1", 10531, null, null,
                ServerConfig.DEFAULT_BASE_URL, null, null, null,
                "", false, apiKeys, null
        );
    }

    @Test void keyPrefixConstant() {
        assertEquals("sk-proxy-", ServerConfig.KEY_PREFIX);
    }

    @Test void nullApiKeysDefaultsToEmpty() {
        ServerConfig config = minimal(null);
        assertEquals(Map.of(), config.apiKeys());
    }

    @Test void apiKeysIsUnmodifiable() {
        ServerConfig config = minimal(new HashMap<>(Map.of("sk-proxy-abc", "myapp")));
        assertThrows(UnsupportedOperationException.class,
                () -> config.apiKeys().put("sk-proxy-new", "other"));
    }

    @Test void apiKeysContainsConfiguredKey() {
        String key = "sk-proxy-a1b2c3";
        ServerConfig config = minimal(new HashMap<>(Map.of(key, "myapp")));
        assertTrue(config.apiKeys().containsKey(key));
    }

    @Test void apiKeysReturnsCorrectName() {
        String key = "sk-proxy-a1b2c3";
        ServerConfig config = minimal(new HashMap<>(Map.of(key, "cursor")));
        assertEquals("cursor", config.apiKeys().get(key));
    }

    @Test void adminKeyStoredAsIs() {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 10531, null, null,
                ServerConfig.DEFAULT_BASE_URL, null, null, null,
                "", false, Map.of(), "sk-proxy-adminkey12345678901234"
        );
        assertEquals("sk-proxy-adminkey12345678901234", config.adminKey());
    }

    @Test void nullAdminKeyIsAllowed() {
        ServerConfig config = minimal(Map.of());
        assertNull(config.adminKey());
    }

    @Test void emptyApiKeysIsOpenMode() {
        ServerConfig config = minimal(Map.of());
        assertTrue(config.apiKeys().isEmpty());
    }

    @Test void multipleKeysAllPresent() {
        Map<String, String> keys = Map.of(
                "sk-proxy-key1", "app1",
                "sk-proxy-key2", "app2",
                "sk-proxy-key3", "app3"
        );
        ServerConfig config = minimal(new HashMap<>(keys));
        assertEquals(3, config.apiKeys().size());
        keys.forEach((k, name) -> assertEquals(name, config.apiKeys().get(k)));
    }
}
