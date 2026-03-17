package com.aiproxyoauth.util;

import com.aiproxyoauth.config.ServerConfig;

import java.util.HexFormat;
import java.util.Map;

public final class ApiKeyUtils {

    private ApiKeyUtils() {}

    /** Parses "name:key" or bare "key" into the map (key → name). */
    public static void parseKeyEntry(String entry, Map<String, String> map) {
        int colon = entry.indexOf(':');
        if (colon >= 0) {
            String name = entry.substring(0, colon).trim();
            String key  = entry.substring(colon + 1).trim();
            if (!name.isEmpty() && !key.isEmpty()) {
                map.put(key, name);
            }
        } else {
            String key = entry.trim();
            if (!key.isEmpty()) {
                map.put(key, key); // no name — use key as its own name
            }
        }
    }

    public static String generateNewKey() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return ServerConfig.KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }
}
