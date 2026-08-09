package com.aiproxyoauth.provider.anthropic.auth;

import java.nio.file.Path;
import java.util.Map;

public final class AnthropicCredentialPaths {
    private AnthropicCredentialPaths() {
    }

    public static Path defaultPath() {
        return forEnvironment(System.getenv(), Path.of(System.getProperty("user.home")));
    }

    static Path forEnvironment(Map<String, String> environment, Path userHome) {
        String localAppData = environment.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "AIProxyOauth", "anthropic-auth.json");
        }
        String xdgConfig = environment.get("XDG_CONFIG_HOME");
        Path configDirectory = xdgConfig == null || xdgConfig.isBlank()
                ? userHome.resolve(".config")
                : Path.of(xdgConfig);
        return configDirectory.resolve("AIProxyOauth").resolve("anthropic-auth.json");
    }
}
