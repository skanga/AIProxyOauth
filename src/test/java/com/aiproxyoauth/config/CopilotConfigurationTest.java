package com.aiproxyoauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CopilotConfigurationTest {
    @TempDir Path temporary;

    @Test void defaultPreferenceStartsWithCopilot() {
        var config = EffectiveConfigLoader.load(null, Map.of(), new ConfigOverrides());
        assertEquals("copilot", config.routing().defaultProvider().wireName());
    }

    @Test void explicitSelectionsAndOrderDetermineDefault() {
        var config = EffectiveConfigLoader.load(null, Map.of(
                "AIPROXY_PROVIDER", "anthropic,copilot", "AIPROXY_PROVIDER_ORDER", "anthropic,copilot"), new ConfigOverrides());
        assertEquals("anthropic", config.routing().defaultProvider().wireName());
    }

    @Test void rejectsDuplicateOrderAndDisabledDefault() {
        assertThrows(ConfigException.class, () -> EffectiveConfigLoader.load(null,
                Map.of("AIPROXY_PROVIDER_ORDER", "copilot,copilot"), new ConfigOverrides()));
        assertThrows(ConfigException.class, () -> EffectiveConfigLoader.load(null,
                Map.of("AIPROXY_PROVIDER", "both", "AIPROXY_DEFAULT_PROVIDER", "copilot"), new ConfigOverrides()));
    }

    @Test void loadsCopilotWithoutInlineSecrets() throws Exception {
        var yaml = temporary.resolve("proxy.yaml");
        Files.writeString(yaml, """
                routing:
                  provider: copilot
                  failover: true
                copilot:
                  github_host: github.com
                  oauth_file: ./copilot-auth.json
                  models: [gpt-4o-mini]
                """);
        var config = EffectiveConfigLoader.load(yaml, Map.of(), new ConfigOverrides());
        assertEquals("copilot", config.routing().defaultProvider().wireName());
        assertEquals("yaml", config.sources().get("copilot.oauth_file"));
        Files.writeString(yaml, "copilot:\n  token: secret\n");
        assertThrows(ConfigException.class, () -> EffectiveConfigLoader.load(yaml, Map.of(), new ConfigOverrides()));
    }
}
