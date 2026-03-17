package com.aiproxyoauth.config;

import java.util.List;
import java.util.Map;

public record ServerConfig(
        String host,
        int port,
        List<String> models,
        String codexVersion,
        String baseUrl,
        String oauthClientId,
        String oauthTokenUrl,
        String oauthFilePath,
        String instructions,
        boolean store,
        Map<String, String> apiKeys,  // key → name
        String adminKey               // owner key — sees all stats, null = disabled
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 10531;
    public static final String DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex";
    public static final String DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String DEFAULT_ISSUER = "https://auth.openai.com";
    public static final String DEFAULT_INSTRUCTIONS = "";
    public static final String DEFAULT_MODEL = "gpt-5.2";
    public static final String KEY_PREFIX = "sk-proxy-";

    public ServerConfig {
        if (host == null) host = DEFAULT_HOST;
        if (baseUrl == null) baseUrl = DEFAULT_BASE_URL;
        if (oauthClientId == null) oauthClientId = DEFAULT_CLIENT_ID;
        if (instructions == null) instructions = DEFAULT_INSTRUCTIONS;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be in range 1-65535, got: " + port);
        }
        apiKeys = (apiKeys == null) ? Map.of() : Map.copyOf(apiKeys);
    }
}
